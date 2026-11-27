/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard;
import ai.labs.eddi.engine.health.HealthMeasures.OutcomeStats;
import ai.labs.eddi.engine.health.HealthPeriods.Window;
import ai.labs.eddi.engine.health.model.HealthEventCount;
import ai.labs.eddi.engine.health.model.HealthOutcome;
import ai.labs.eddi.engine.health.model.HealthOutcomeCount;
import ai.labs.eddi.engine.health.model.HealthSessionQuery;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code GET /agents/{agentId}/health/export} (contract §8, H5): an agent's
 * health as data, for a BI tool or a spreadsheet — never conversation content
 * and never a user id.
 * <ul>
 * <li>{@code kind=daily} — one row per UTC day of the period: turns, failures,
 * cost and tokens (from the daily rollup), the sessions that ended that day and
 * how (the resolution split in its four parts), and per event type the agent's
 * current version declares, the matched business events and the sum of their
 * values. Needs {@code VIEW}, like the report.</li>
 * <li>{@code kind=sessions} — one row per session that ended in the period (the
 * drill-down's rows, plus the business references they carried in the form they
 * are stored — hashed when the agent hashes them), newest first, at most
 * {@link #MAX_SESSIONS}. Follows the drill-down rule (the caller checks it):
 * {@code EDIT} on the agent and conversation review enabled.</li>
 * </ul>
 * CSV has a header row and a fixed column order ({@link #DAILY_COLUMNS},
 * {@link #SESSION_COLUMNS}; the daily event columns follow the declared types),
 * is written row by row, and prefixes a value that a spreadsheet would read as
 * a formula ({@code = + - @}) with {@code '}. JSON carries the same rows as
 * objects, streamed.
 * <p>
 * <b>GDPR.</b> A session row names its conversation and references, and is read
 * a page at a time before it is written out — so a user erasure (or a permanent
 * conversation delete) that starts while an export runs could delete a row the
 * export already holds. Every row is therefore checked against the erasure
 * tombstones right before it is written ({@link ErasureGuard#erasedForReading},
 * captured at the session's start, the matcher's rule): a row of an erased user
 * or a deleted conversation is withheld, also when the tombstones cannot be
 * read (fail closed). What is guaranteed: a row is handed to the response
 * stream only if its tombstone check passed at most {@link #VERDICT_MAX_AGE}
 * before — a verdict is reused for the conversation's further rows of the same
 * page only while it is that fresh (two point reads of the tombstone store per
 * check). Tombstones are written before anything is deleted, so a row handed on
 * within that time after an erasure began can slip through, none later; bytes
 * the response stream still buffers are not recalled. The daily kind holds
 * counts only, which the rollup re-rolls without an erased user's rows.
 */
@ApplicationScoped
public class HealthExport {

    /** The most sessions one export carries ({@code truncated} says so). */
    public static final int MAX_SESSIONS = 10_000;

    /**
     * How old a tombstone verdict may be when a row is handed on; an older one is
     * taken again.
     */
    static final Duration VERDICT_MAX_AGE = Duration.ofSeconds(1);

    /** {@link #VERDICT_MAX_AGE} in nanos, lowered by tests. */
    long verdictMaxAgeNanos = VERDICT_MAX_AGE.toNanos();

    /** A tombstone verdict and when it was taken ({@link System#nanoTime}). */
    private record Verdict(boolean erased, long takenAt) {
    }
    static final int PAGE = 1_000;

    public static final List<String> DAILY_COLUMNS = List.of("day", "turns", "conversations", "failedTurns", "agentFailedTurns",
            "platformFailedTurns", "costUsd", "totalTokens", "sessions", "engagedSessions", "closedSessions", "resolved", "resolvedConfirmed",
            "resolvedSelfReported", "resolvedAgentClosed", "resolvedSilence", "escalated", "abandoned", "deflected", "failed", "endedQuietly");

    public static final List<String> SESSION_COLUMNS = List.of("conversationId", "sessionNo", "startedAt", "endedAt", "agentVersion", "origin",
            "engaged", "outcome", "evidence", "provisional", "turns", "costUsd", "failures", "refs");

    private static final JsonFactory JSON = new JsonFactory();

    private final IHealthDailyStore dailyStore;
    private final IHealthOutcomeStore outcomeStore;
    private final Instance<IHealthEventStore> eventStore;
    private final HealthConfigPolicy configPolicy;
    private final HealthSettings settings;

    /**
     * Withholds the rows of erased users and deleted conversations from a sessions
     * export. Field-injected and null-checked: tests build this class by hand.
     */
    @Inject
    ErasureGuard erasureGuard;

    @Inject
    public HealthExport(IHealthDailyStore dailyStore, IHealthOutcomeStore outcomeStore, Instance<IHealthEventStore> eventStore,
            HealthConfigPolicy configPolicy, HealthSettings settings) {
        this.dailyStore = dailyStore;
        this.outcomeStore = outcomeStore;
        this.eventStore = eventStore;
        this.configPolicy = configPolicy;
        this.settings = settings;
    }

    /** What an export writes. */
    @FunctionalInterface
    public interface Body {
        void write(OutputStream out) throws IOException;
    }

    // ------------------------------------------------------------------ daily

    /**
     * The daily rows, column → value, oldest day first — every day of the window, a
     * day without data with zeros.
     */
    public List<Map<String, Object>> dailyRows(String agentId, Window window, Integer version, List<String> origins) {
        Set<String> wanted = Set.copyOf(origins);
        Map<LocalDate, long[]> turns = new TreeMap<>();
        Map<LocalDate, double[]> money = new HashMap<>();
        for (HealthTurnAggregate row : dailyStore.find(agentId, window.from(), window.to())) {
            if (!wanted.contains(row.origin()) || version != null && !version.equals(row.agentVersion()) || !window.contains(row.day())) {
                continue;
            }
            long[] t = turns.computeIfAbsent(row.day(), d -> new long[6]);
            t[0] += row.turns();
            t[1] += row.conversations();
            t[2] += row.failedTurns();
            t[3] += row.agentFailedTurns();
            t[4] += row.platformFailedTurns();
            t[5] += row.totalTokens();
            money.computeIfAbsent(row.day(), d -> new double[1])[0] += row.costUsd();
        }
        HealthUsageQuery query = new HealthUsageQuery(agentId, window.from(), window.to(), version, origins);
        Map<LocalDate, List<HealthOutcomeCount>> outcomes = new HashMap<>();
        if (!window.from().isBefore(oldestKept())) {
            for (HealthOutcomeCount c : outcomeStore.counts(query, true, "none")) {
                if (c.day() != null) {
                    outcomes.computeIfAbsent(c.day(), d -> new ArrayList<>()).add(c);
                }
            }
        }
        Map<String, String> declared = configPolicy.current(agentId).outcomeEvents();
        Map<LocalDate, List<HealthEventCount>> events = new HashMap<>();
        if (!declared.isEmpty() && eventStore != null && eventStore.isResolvable() && !window.from().isBefore(oldestKept())) {
            for (HealthEventCount c : eventStore.get().counts(query, true)) {
                if (c.day() != null) {
                    events.computeIfAbsent(c.day(), d -> new ArrayList<>()).add(c);
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LocalDate day = window.from(); !day.isAfter(window.to()); day = day.plusDays(1)) {
            long[] t = turns.getOrDefault(day, new long[6]);
            OutcomeStats o = OutcomeStats.of(outcomes.getOrDefault(day, List.of()), settings.minOutcomeCoverage());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", day.toString());
            row.put("turns", t[0]);
            row.put("conversations", t[1]);
            row.put("failedTurns", t[2]);
            row.put("agentFailedTurns", t[3]);
            row.put("platformFailedTurns", t[4]);
            row.put("costUsd", money.getOrDefault(day, new double[1])[0]);
            row.put("totalTokens", t[5]);
            row.put("sessions", o.sessions());
            row.put("engagedSessions", o.engagedSessions());
            row.put("closedSessions", o.closed());
            row.put("resolved", o.resolved());
            row.put("resolvedConfirmed", o.resolvedConfirmed());
            row.put("resolvedSelfReported", o.resolvedSelfReported());
            row.put("resolvedAgentClosed", o.resolvedAgentClosed());
            row.put("resolvedSilence", o.resolvedSilence());
            row.put("escalated", o.escalated());
            row.put("abandoned", o.abandoned());
            row.put("deflected", o.deflected());
            row.put("failed", o.failed());
            row.put("endedQuietly", o.endedQuietly());
            List<HealthEventCount> dayEvents = events.getOrDefault(day, List.of());
            for (String type : declared.keySet()) {
                long n = 0;
                double sum = 0;
                for (HealthEventCount c : dayEvents) {
                    if (type.equals(c.eventType())) {
                        n += c.events();
                        sum += c.valueSum();
                    }
                }
                row.put(HealthBusinessMeasures.PREFIX + type, n);
                row.put(HealthBusinessMeasures.PREFIX + type + ":value", sum);
            }
            rows.add(row);
        }
        return rows;
    }

    /** The daily export, in {@code format}. */
    public Body daily(String agentId, Window window, Integer version, List<String> origins, String format) {
        List<Map<String, Object>> rows = dailyRows(agentId, window, version, origins);
        List<String> columns = new ArrayList<>(DAILY_COLUMNS);
        for (String type : configPolicy.current(agentId).outcomeEvents().keySet()) {
            columns.add(HealthBusinessMeasures.PREFIX + type);
            columns.add(HealthBusinessMeasures.PREFIX + type + ":value");
        }
        if ("csv".equals(format)) {
            return out -> {
                Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                csvRow(w, columns);
                for (Map<String, Object> row : rows) {
                    csvRow(w, columns.stream().map(c -> row.get(c)).toList());
                }
                w.flush();
            };
        }
        return out -> {
            try (JsonGenerator g = JSON.createGenerator(out, JsonEncoding.UTF8)) {
                g.writeStartObject();
                header(g, agentId, "daily", window, version, origins);
                g.writeArrayFieldStart("columns");
                for (String c : columns) {
                    g.writeString(c);
                }
                g.writeEndArray();
                g.writeArrayFieldStart("items");
                for (Map<String, Object> row : rows) {
                    g.writeStartObject();
                    for (String c : columns) {
                        g.writeFieldName(c);
                        value(g, row.get(c));
                    }
                    g.writeEndObject();
                }
                g.writeEndArray();
                g.writeEndObject();
            }
        };
    }

    // ------------------------------------------------------------------ sessions

    /**
     * The sessions export, in {@code format}: written page by page, at most
     * {@link #MAX_SESSIONS} rows.
     *
     * @param versions
     *            the versions the caller may list (drill-down rule)
     */
    public Body sessions(String agentId, Window window, Set<Integer> versions, List<String> origins, String format) {
        if ("csv".equals(format)) {
            return out -> {
                Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                csvRow(w, SESSION_COLUMNS);
                forEachSession(agentId, window, versions, origins, o -> csvRow(w, sessionValues(o)));
                w.flush();
            };
        }
        return out -> {
            try (JsonGenerator g = JSON.createGenerator(out, JsonEncoding.UTF8)) {
                g.writeStartObject();
                header(g, agentId, "sessions", window, null, origins);
                g.writeArrayFieldStart("items");
                boolean truncated = forEachSession(agentId, window, versions, origins, o -> {
                    List<Object> values = sessionValues(o);
                    g.writeStartObject();
                    for (int i = 0; i < SESSION_COLUMNS.size(); i++) {
                        g.writeFieldName(SESSION_COLUMNS.get(i));
                        value(g, "refs".equals(SESSION_COLUMNS.get(i)) ? o.refs() : values.get(i));
                    }
                    g.writeEndObject();
                });
                g.writeEndArray();
                g.writeBooleanField("truncated", truncated);
                g.writeEndObject();
            }
        };
    }

    @FunctionalInterface
    interface RowWriter {
        void write(HealthOutcome session) throws IOException;
    }

    /** @return whether {@link #MAX_SESSIONS} cut the export short */
    boolean forEachSession(String agentId, Window window, Set<Integer> versions, List<String> origins, RowWriter writer) throws IOException {
        if (versions.isEmpty() || origins.isEmpty()) {
            return false;
        }
        int written = 0;
        int offset = 0;
        while (written < MAX_SESSIONS) {
            int size = Math.min(PAGE, MAX_SESSIONS - written);
            List<HealthOutcome> page = outcomeStore.sessions(new HealthSessionQuery(agentId, window.from(), window.to(), versions, origins, List.of(),
                    List.of(), offset, size + 1));
            int consumed = Math.min(size, page.size());
            // Per page, and per verdict at most VERDICT_MAX_AGE old.
            Map<String, Verdict> withheld = new HashMap<>();
            for (int i = 0; i < consumed; i++) {
                HealthOutcome session = page.get(i);
                if (withheld(session, page, withheld)) {
                    continue;
                }
                writer.write(session);
                written++;
            }
            offset += consumed;
            if (page.size() <= size) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code session} belongs to an erasure and must not be written. A
     * verdict per conversation of the page, taken when its first row is reached and
     * again once it is older than {@link #verdictMaxAgeNanos}, at the earliest
     * start of the page's sessions of that conversation and user — which withholds
     * rather than writes when they differ.
     */
    private boolean withheld(HealthOutcome session, List<HealthOutcome> page, Map<String, Verdict> verdicts) {
        if (erasureGuard == null) {
            return false;
        }
        String key = session.userId() + "\n" + session.conversationId();
        Verdict verdict = verdicts.get(key);
        if (verdict == null || System.nanoTime() - verdict.takenAt() > verdictMaxAgeNanos) {
            // Null is "before any erasure", the strictest: it is never replaced.
            Instant capturedAt = session.startedAt();
            for (int i = 0; capturedAt != null && i < page.size(); i++) {
                HealthOutcome other = page.get(i);
                if (Objects.equals(other.conversationId(), session.conversationId()) && Objects.equals(other.userId(), session.userId())
                        && (other.startedAt() == null || other.startedAt().isBefore(capturedAt))) {
                    capturedAt = other.startedAt();
                }
            }
            verdict = new Verdict(erasureGuard.erasedForReading(session.userId(), session.conversationId(), capturedAt), System.nanoTime());
            verdicts.put(key, verdict);
        }
        return verdict.erased();
    }

    static List<Object> sessionValues(HealthOutcome o) {
        List<Object> values = new ArrayList<>(SESSION_COLUMNS.size());
        values.add(o.conversationId());
        values.add(o.sessionNo());
        values.add(o.startedAt() == null ? null : o.startedAt().toString());
        values.add(o.endedAt() == null ? null : o.endedAt().toString());
        values.add(o.agentVersion());
        values.add(o.origin());
        values.add(o.engaged());
        values.add(o.outcome());
        values.add(o.evidence());
        values.add(o.provisional());
        values.add(o.turns());
        values.add(o.costUsd());
        values.add(o.failures());
        values.add(String.join(" ", o.refs()));
        return values;
    }

    // ------------------------------------------------------------------ writing

    private static void header(JsonGenerator g, String agentId, String kind, Window window, Integer version, List<String> origins)
            throws IOException {
        g.writeStringField("agentId", agentId);
        g.writeStringField("kind", kind);
        g.writeObjectFieldStart("period");
        g.writeStringField("from", window.from().toString());
        g.writeStringField("to", window.to().toString());
        g.writeNumberField("days", window.days());
        g.writeEndObject();
        if (version != null) {
            g.writeNumberField("version", version);
        }
        g.writeArrayFieldStart("origins");
        for (String origin : origins) {
            g.writeString(origin);
        }
        g.writeEndArray();
    }

    private static void value(JsonGenerator g, Object value) throws IOException {
        if (value == null) {
            g.writeNull();
        } else if (value instanceof Boolean b) {
            g.writeBoolean(b);
        } else if (value instanceof Integer i) {
            g.writeNumber(i);
        } else if (value instanceof Long l) {
            g.writeNumber(l);
        } else if (value instanceof Double d) {
            g.writeNumber(d);
        } else if (value instanceof List<?> list) {
            g.writeStartArray();
            for (Object entry : list) {
                g.writeString(String.valueOf(entry));
            }
            g.writeEndArray();
        } else {
            g.writeString(String.valueOf(value));
        }
    }

    static void csvRow(Writer w, List<?> values) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(csvCell(values.get(i)));
        }
        line.append("\r\n");
        w.write(line.toString());
    }

    /**
     * One CSV cell (RFC 4180): quoted when it holds a comma, quote or line break; a
     * text that a spreadsheet would read as a formula gets a leading {@code '}.
     */
    static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (!(value instanceof Number) && !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private LocalDate oldestKept() {
        return LocalDate.now(ZoneOffset.UTC).minusDays(settings.factsRetentionDays());
    }
}
