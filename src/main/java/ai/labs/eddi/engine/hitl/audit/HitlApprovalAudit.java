/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.audit;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit.UnitKind;
import ai.labs.eddi.engine.hitl.review.ApprovalCategories;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.utils.LogSanitizer;
import io.micrometer.core.instrument.Metrics;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The HITL decision audit contract: writes {@code hitl.approval} entries
 * (schema 2) and {@code hitl.edit.refused} entries, parses any
 * {@code hitl.approval} entry (schema 1 or 2) into {@link HitlDecisionUnit}s,
 * and fires {@link HitlDecisionRecorded} after each write.
 * <p>
 * One entry per decision event (a resume, a group approval, a cancellation):
 * every legacy field is kept for existing readers, and the decided units — the
 * answer, each tool call, the phase, each task — are listed in {@code units[]}.
 * The full field list is in {@code docs/hitl-review-and-audit.md}. Consumers
 * read entries through {@link #parse(AuditEntry)}, never the raw map.
 * <p>
 * A plain class constructed from the {@link AuditLedgerService} its owner
 * already holds; it never throws into the decision path.
 *
 * @since 6.7.0
 */
public final class HitlApprovalAudit {

    private static final Logger LOGGER = Logger.getLogger(HitlApprovalAudit.class);

    /** {@code taskId} of a decision entry (unchanged since 6.0). */
    public static final String TASK_ID = "hitl.approval";
    /**
     * {@code taskId} of an entry recording that an approved edit was not applied.
     */
    public static final String EDIT_REFUSED_TASK_ID = "hitl.edit.refused";
    /** {@code taskType} of both. */
    public static final String TASK_TYPE = "hitl";
    /** The current schema. Entries without a {@code schema} field are schema 1. */
    public static final int SCHEMA = 2;
    /** Counter: decisions per {@code surface}, {@code outcome}, {@code channel}. */
    public static final String DECISION_METER = "eddi_hitl_decision_total";
    /** Distribution of {@code charsChanged} of edits. */
    public static final String EDIT_CHARS_METER = "eddi_hitl_edit_chars";

    /** {@code reason} of a refused edit: an output guardrail blocked it. */
    public static final String REFUSED_SCREENED_OUT = "SCREENED_OUT";
    /**
     * {@code reason} of a refused edit: the configuration changed since the pause.
     */
    public static final String REFUSED_CONFIG_DRIFT = "CONFIG_DRIFT";
    /**
     * {@code reason} of a refused edit: the draft holds content the approver was
     * shown redacted, and the submitted text carries the redaction placeholder, so
     * applying it would replace the concealed content with the placeholder.
     */
    public static final String REFUSED_REDACTED_CONTENT = "REDACTED_CONTENT";

    private final AuditLedgerService ledger;
    private final Consumer<HitlDecisionRecorded> publisher;

    public HitlApprovalAudit(AuditLedgerService ledger) {
        this(ledger, HitlDecisionEvents.CDI_ASYNC);
    }

    public HitlApprovalAudit(AuditLedgerService ledger, Consumer<HitlDecisionRecorded> publisher) {
        this.ledger = ledger;
        this.publisher = publisher != null ? publisher : event -> {
        };
    }

    /**
     * A fresh decision id, for a caller that needs it before the entry is written.
     */
    public static String newDecisionId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Writes the decision's entry and fires {@link HitlDecisionRecorded}. Never
     * throws.
     *
     * @param decisionId
     *            the entry id to use (see {@link #newDecisionId()}), or null for a
     *            fresh one
     * @return the entry id, or null when the ledger is off or the write failed
     */
    public String write(HitlDecisionRecord record, String decisionId) {
        if (ledger == null || !ledger.isEnabled() || record == null) {
            return null;
        }
        try {
            String id = decisionId != null ? decisionId : newDecisionId();
            AuditEntry entry = toEntry(record, id);
            ledger.submit(entry);
            List<HitlDecisionUnit> units = parse(entry);
            recordMetrics(record, units);
            if (!units.isEmpty()) {
                publisher.accept(new HitlDecisionRecorded(id, units));
            }
            return id;
        } catch (RuntimeException e) {
            LOGGER.warnf("Failed to submit HITL decision audit entry for %s: %s", LogSanitizer.sanitize(record.conversationId),
                    LogSanitizer.sanitize(e.getMessage()));
            return null;
        }
    }

    /** {@link #write(HitlDecisionRecord, String)} with a fresh id. */
    public String write(HitlDecisionRecord record) {
        return write(record, null);
    }

    /** The audit entry a record is stored as. */
    static AuditEntry toEntry(HitlDecisionRecord r, String id) {
        boolean group = HitlDecisionRecord.SURFACE_GROUP.equals(r.surface);
        // The agentId column of a group entry has always carried the groupId.
        String agentColumn = group ? r.groupId : r.agentId;
        return new AuditEntry(id, r.conversationId, agentColumn, group ? null : r.agentVersion, r.userId, r.environment, -1, TASK_ID,
                TASK_TYPE, -1, 0L, Map.of(), toOutput(r, id), null, null, List.of(), 0.0, r.decidedAt, null, null);
    }

    static Map<String, Object> toOutput(HitlDecisionRecord r, String id) {
        var out = new LinkedHashMap<String, Object>();
        // Legacy fields first, exactly as the pre-6.7 writers named them.
        out.put("verdict", r.verdict != null ? r.verdict : "UNKNOWN");
        out.put("decidedBy", r.decidedBy != null ? r.decidedBy : "unknown");
        boolean automated = r.decidedBy != null && r.decidedBy.startsWith("system:");
        out.put("automated", automated);
        if (r.note != null) {
            out.put("note", r.note);
        }
        out.putAll(r.legacy);
        out.put("surface", r.surface);
        // Schema 2.
        out.put("schema", SCHEMA);
        out.put("decisionId", id);
        if (r.pauseType != null) {
            out.put("pauseType", r.pauseType);
        }
        if (r.groupId != null) {
            out.put("groupId", r.groupId);
        }
        String pauseId = HitlDecision.pauseIdOf(r.pausedAt);
        if (pauseId != null) {
            out.put("pauseId", pauseId);
            out.put("pausedAt", r.pausedAt.toString());
        }
        out.put("decidedAt", r.decidedAt.toString());
        Long latency = latencyMs(r.pausedAt, r.decidedAt);
        if (latency != null) {
            out.put("latencyMs", latency);
        }
        String aggregate = aggregateOutcome(r.units);
        if (aggregate != null) {
            out.put("decisionOutcome", aggregate);
        }
        out.put("channel", HitlChannel.of(r.decidedBy, r.declaredChannel).name());
        String automationReason = automationReason(r.decidedBy);
        if (automationReason != null) {
            out.put("automationReason", automationReason);
        }
        if (r.timeoutPolicy != null) {
            out.put("timeoutPolicy", r.timeoutPolicy);
        }
        if (r.category != null) {
            out.put("approvalCategory", r.category.category());
            out.put("approvalCategorySource", r.category.source().name());
        }
        if (r.reasonCode != null) {
            out.put("reasonCode", r.reasonCode);
        }
        List<Map<String, Object>> units = new ArrayList<>();
        for (HitlDecisionRecord.Unit unit : r.units) {
            var u = new LinkedHashMap<String, Object>();
            u.put("unitKind", unit.kind().name());
            u.put("unitId", unit.unitId());
            u.put("decisionOutcome", unit.outcome().name());
            ApprovalCategories.ApprovalCategory category = unit.category() != null ? unit.category() : r.category;
            if (category != null) {
                u.put("approvalCategory", category.category());
                u.put("approvalCategorySource", category.source().name());
            }
            // the decision-level code explains only what changed or was rejected - a unit
            // approved as it stood takes none
            boolean unchanged = unit.outcome() == DecisionOutcome.APPROVED || unit.outcome() == DecisionOutcome.AUTO_APPROVED;
            String reasonCode = unit.reasonCode() != null ? unit.reasonCode() : unchanged ? null : r.reasonCode;
            if (reasonCode != null) {
                u.put("reasonCode", reasonCode);
            }
            if (unit.toolName() != null) {
                u.put("toolName", unit.toolName());
            }
            if (unit.argsDigest() != null) {
                u.put("argsDigest", unit.argsDigest());
            }
            u.put("edit", unit.edit() != null ? unit.edit().toMap() : null);
            units.add(u);
        }
        out.put("units", units);
        return out;
    }

    /**
     * The common outcome of the units, {@link DecisionOutcome#MIXED} when they
     * disagree, null when there are none.
     */
    static String aggregateOutcome(List<HitlDecisionRecord.Unit> units) {
        Set<DecisionOutcome> outcomes = new LinkedHashSet<>();
        for (HitlDecisionRecord.Unit unit : units) {
            outcomes.add(unit.outcome());
        }
        if (outcomes.isEmpty()) {
            return null;
        }
        return outcomes.size() == 1 ? outcomes.iterator().next().name() : DecisionOutcome.MIXED;
    }

    static Long latencyMs(Instant pausedAt, Instant decidedAt) {
        if (pausedAt == null || decidedAt == null) {
            return null;
        }
        return Math.max(0L, Duration.between(pausedAt, decidedAt).toMillis());
    }

    /**
     * Why the system decided, from its {@code system:*} decider id; null for a
     * human.
     */
    public static String automationReason(String decidedBy) {
        if (decidedBy == null || !decidedBy.startsWith("system:")) {
            return null;
        }
        String reason = decidedBy.substring("system:".length());
        return switch (reason) {
            case "timeout" -> "TIMEOUT_POLICY";
            case "no-progress" -> "NO_PROGRESS";
            case "retention" -> "PENDING_MAX_AGE";
            case "group" -> "MEMBER_PAUSE_IN_GROUP";
            default -> reason.isBlank() ? "SYSTEM" : reason.toUpperCase(Locale.ROOT).replace('-', '_');
        };
    }

    private static void recordMetrics(HitlDecisionRecord r, List<HitlDecisionUnit> units) {
        try {
            String channel = HitlChannel.of(r.decidedBy, r.declaredChannel).name();
            for (HitlDecisionUnit unit : units) {
                Metrics.counter(DECISION_METER, "surface", r.surface, "outcome", unit.decisionOutcome().name(), "channel", channel)
                        .increment();
                if (unit.edit() != null) {
                    Metrics.summary(EDIT_CHARS_METER, "kind", unit.edit().kind().name()).record(unit.edit().charsChanged());
                }
            }
        } catch (RuntimeException e) {
            LOGGER.debugf("HITL decision metrics not recorded: %s", e.getMessage());
        }
    }

    // === hitl.edit.refused ===

    /**
     * The entry recording that an approved edit was not applied — screened out by
     * an output guardrail, or the configuration changed since the pause. The same
     * pause is then followed by a new pause. Joins its decision by
     * {@code decisionId}.
     */
    public static AuditEntry editRefusedEntry(String decisionId, String conversationId, String agentId, Integer agentVersion, String userId,
                                              String environment, UnitKind unitKind, String reason, String guardrail) {
        var out = new LinkedHashMap<String, Object>();
        out.put("schema", SCHEMA);
        out.put("decisionId", decisionId);
        out.put("unitKind", unitKind != null ? unitKind.name() : null);
        out.put("reason", reason);
        if (guardrail != null) {
            out.put("guardrail", guardrail);
        }
        return new AuditEntry(newDecisionId(), conversationId, agentId, agentVersion, userId, environment, -1, EDIT_REFUSED_TASK_ID, TASK_TYPE,
                -1, 0L, Map.of(), out, null, null, List.of(), 0.0, Instant.now(), null, null);
    }

    // === Parsing ===

    /**
     * The units of a {@code hitl.approval} entry, schema 1 or 2. Anything else —
     * another task id, a redacted entry, a HUMAN_TURN resolution — yields an empty
     * list.
     * <p>
     * Schema 1 (before 6.7) is mapped best-effort: {@code toolDecisions[]} become
     * TOOL_CALL units ({@code amended} → {@code APPROVED_EDITED} with
     * {@code edit = null}); otherwise one ANSWER (regular) or PHASE (group) unit;
     * {@code CANCELLED} → {@code ABORTED}; category, latency and pause id are null;
     * the channel comes from the {@code decidedBy} prefix (REST when there is
     * none).
     */
    public static List<HitlDecisionUnit> parse(AuditEntry entry) {
        if (entry == null || !TASK_ID.equals(entry.taskId()) || entry.output() == null) {
            return List.of();
        }
        try {
            return parseOutput(entry);
        } catch (RuntimeException e) {
            LOGGER.debugf("Unparseable hitl.approval entry %s: %s", LogSanitizer.sanitize(entry.id()), LogSanitizer.sanitize(e.getMessage()));
            return List.of();
        }
    }

    /** Whether the entry is a schema-2 {@code hitl.approval} entry. */
    public static boolean isSchema2(AuditEntry entry) {
        return entry != null && entry.output() != null && entry.output().get("schema") instanceof Number n && n.intValue() >= SCHEMA;
    }

    private static List<HitlDecisionUnit> parseOutput(AuditEntry entry) {
        Map<String, Object> out = entry.output();
        int schema = isSchema2(entry) ? SCHEMA : 1;
        String verdict = str(out.get("verdict"));
        if (verdict == null || verdict.startsWith("HUMAN_")) {
            return List.of();
        }
        String surface = str(out.get("surface"));
        surface = surface != null ? surface.toLowerCase(Locale.ROOT) : HitlDecisionRecord.SURFACE_REGULAR;
        boolean group = HitlDecisionRecord.SURFACE_GROUP.equals(surface);
        String groupId = str(out.get("groupId"));
        if (group && groupId == null) {
            groupId = entry.agentId();
        }
        String decidedBy = str(out.get("decidedBy"));
        boolean automated = decidedBy != null && decidedBy.startsWith("system:");
        HitlChannel channel = parseChannel(str(out.get("channel")), decidedBy);
        Instant decidedAt = instant(out.get("decidedAt"));
        if (decidedAt == null) {
            decidedAt = entry.timestamp();
        }
        Instant pausedAt = instant(out.get("pausedAt"));
        Long latency = out.get("latencyMs") instanceof Number n ? n.longValue() : latencyMs(pausedAt, decidedAt);
        String automationReason = str(out.get("automationReason"));
        if (automationReason == null) {
            automationReason = automationReason(decidedBy);
        }
        var context = new Context(entry.id(), schema, surface, str(out.get("pauseType")), entry.conversationId(), group ? null : entry.agentId(),
                group ? null : entry.agentVersion(), group ? groupId : null, entry.userId(), entry.environment(), str(out.get("pauseId")),
                pausedAt, decidedAt, latency, decidedBy, automated, channel, automationReason, str(out.get("timeoutPolicy")));

        List<HitlDecisionUnit> units = new ArrayList<>();
        if (schema >= SCHEMA) {
            if (out.get("units") instanceof List<?> stored) {
                for (Object item : stored) {
                    if (item instanceof Map<?, ?> unit) {
                        HitlDecisionUnit parsed = context.unit(unit);
                        if (parsed != null) {
                            units.add(parsed);
                        }
                    }
                }
            }
            return units;
        }

        // Schema 1.
        if (HitlDecisionRecord.VERDICT_CANCELLED.equals(verdict)) {
            units.add(context.legacyUnit(group ? UnitKind.PHASE : UnitKind.ANSWER, group ? null : "answer", DecisionOutcome.ABORTED, null, null));
            return units;
        }
        HitlVerdict topVerdict = HitlVerdict.fromString(verdict);
        if (topVerdict == null) {
            return units;
        }
        if (out.get("toolDecisions") instanceof List<?> calls && !calls.isEmpty()) {
            for (Object item : calls) {
                if (!(item instanceof Map<?, ?> call)) {
                    continue;
                }
                HitlVerdict callVerdict = HitlVerdict.fromString(str(call.get("verdict")));
                boolean amended = Boolean.TRUE.equals(call.get("amended"));
                DecisionOutcome outcome = DecisionOutcome.of(callVerdict != null ? callVerdict : topVerdict, decidedBy, amended, false);
                units.add(context.legacyUnit(UnitKind.TOOL_CALL, str(call.get("callId")), outcome, str(call.get("toolName")),
                        str(call.get("argsDigest"))));
            }
            return units;
        }
        units.add(context.legacyUnit(group ? UnitKind.PHASE : UnitKind.ANSWER, group ? null : "answer",
                DecisionOutcome.of(topVerdict, decidedBy, false, false), null, null));
        return units;
    }

    /** The decision context every unit repeats. */
    private record Context(String decisionId, int schema, String surface, String pauseType, String conversationId, String agentId,
            Integer agentVersion, String groupId, String userId, String environment, String pauseId, Instant pausedAt, Instant decidedAt,
            Long latencyMs, String decidedBy, boolean automated, HitlChannel channel, String automationReason, String timeoutPolicy) {

        HitlDecisionUnit unit(Map<?, ?> unit) {
            UnitKind kind = enumOf(UnitKind.class, str(unit.get("unitKind")));
            DecisionOutcome outcome = enumOf(DecisionOutcome.class, str(unit.get("decisionOutcome")));
            if (kind == null || outcome == null) {
                return null;
            }
            return new HitlDecisionUnit(decisionId, schema, surface, pauseType, conversationId, agentId, agentVersion, groupId, userId,
                    environment, pauseId, pausedAt, decidedAt, latencyMs, decidedBy, automated, channel, automationReason, timeoutPolicy, kind,
                    str(unit.get("unitId")), outcome, str(unit.get("approvalCategory")),
                    enumOf(ApprovalCategories.Source.class, str(unit.get("approvalCategorySource"))), str(unit.get("reasonCode")),
                    str(unit.get("toolName")), str(unit.get("argsDigest")), EditSummary.fromMap(unit.get("edit")));
        }

        HitlDecisionUnit legacyUnit(UnitKind kind, String unitId, DecisionOutcome outcome, String toolName, String argsDigest) {
            return new HitlDecisionUnit(decisionId, schema, surface, pauseType, conversationId, agentId, agentVersion, groupId, userId,
                    environment, pauseId, pausedAt, decidedAt, latencyMs, decidedBy, automated, channel, automationReason, timeoutPolicy, kind,
                    unitId, outcome, null, null, null, toolName, argsDigest, null);
        }
    }

    private static HitlChannel parseChannel(String stored, String decidedBy) {
        HitlChannel channel = enumOf(HitlChannel.class, stored);
        return channel != null ? channel : HitlChannel.of(decidedBy, null);
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static String str(Object value) {
        return value instanceof String text ? text : null;
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof String text) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }
}
