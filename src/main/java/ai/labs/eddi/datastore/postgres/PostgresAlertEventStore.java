/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.alerts.AlertJson;
import ai.labs.eddi.engine.alerts.IAlertEventStore;
import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertEvent.Delivery;
import ai.labs.eddi.engine.alerts.model.AlertEventFilter;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IAlertEventStore}: events in
 * {@code alert_events}, throttle counters in {@code alert_windows}.
 * <p>
 * The dedupe is {@code INSERT … ON CONFLICT (dedupe_key) DO NOTHING}: a second
 * insert with the same key affects no row on whichever replica runs it, and is
 * turned into an increment of {@code duplicates}. The throttle counter is one
 * {@code INSERT … ON CONFLICT DO UPDATE … RETURNING}. Delivery updates and
 * claims are conditional on {@code version}.
 *
 * @since 6.6.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresAlertEventStore implements IAlertEventStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresAlertEventStore.class);

    private static final TypeReference<List<Delivery>> DELIVERIES = new TypeReference<>() {
    };

    private static final String CREATE_EVENTS = """
            CREATE TABLE IF NOT EXISTS alert_events (
                id VARCHAR(64) PRIMARY KEY,
                rule_id VARCHAR(64),
                rule_name VARCHAR(255),
                source VARCHAR(32),
                kind VARCHAR(16) NOT NULL,
                agent_id VARCHAR(255),
                agent_version INTEGER,
                conversation_id VARCHAR(255),
                fired_at TIMESTAMPTZ NOT NULL,
                summary TEXT,
                match JSONB,
                rating_id VARCHAR(64),
                deliveries JSONB NOT NULL,
                state VARCHAR(16) NOT NULL,
                next_attempt_at TIMESTAMPTZ,
                dedupe_key VARCHAR(512) UNIQUE,
                duplicates INTEGER NOT NULL DEFAULT 0,
                digest_count INTEGER,
                digest_items TEXT[],
                acknowledged_by VARCHAR(255),
                acknowledged_at TIMESTAMPTZ,
                version BIGINT NOT NULL DEFAULT 0,
                subject_user_id VARCHAR(255),
                digest_event_ids TEXT[],
                send_claim_token VARCHAR(64),
                send_claim_until TIMESTAMPTZ
            )
            """;

    private static final String CREATE_WINDOWS = """
            CREATE TABLE IF NOT EXISTS alert_windows (
                window_key VARCHAR(255) PRIMARY KEY,
                count BIGINT NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL
            )
            """;

    private static final String[] CREATE_INDEXES = {
            "CREATE INDEX IF NOT EXISTS idx_alert_events_agent_fired ON alert_events (agent_id, fired_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_alert_events_fired ON alert_events (fired_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_alert_events_next_attempt ON alert_events (next_attempt_at) WHERE next_attempt_at IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS idx_alert_events_rule_state ON alert_events (rule_id, state, fired_at)",
            "CREATE INDEX IF NOT EXISTS idx_alert_events_unacknowledged ON alert_events (agent_id) WHERE acknowledged_at IS NULL",
            "CREATE INDEX IF NOT EXISTS idx_alert_events_rating ON alert_events (rating_id) WHERE rating_id IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS idx_alert_events_subject ON alert_events (subject_user_id) WHERE subject_user_id IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS idx_alert_windows_expiry ON alert_windows (expires_at)"};

    /** No send claim, or an expired one (one parameter: now). */
    private static final String UNCLAIMED = " AND (send_claim_until IS NULL OR send_claim_until <= ?)";

    private static final String COLUMNS = "id, rule_id, rule_name, source, kind, agent_id, agent_version, conversation_id, fired_at, summary, "
            + "match::text, deliveries::text, state, next_attempt_at, dedupe_key, duplicates, digest_count, digest_items, acknowledged_by, "
            + "acknowledged_at, version, digest_event_ids";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresAlertEventStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_EVENTS);
            // Columns added after the table first shipped on development branches.
            stmt.execute("ALTER TABLE alert_events ADD COLUMN IF NOT EXISTS subject_user_id VARCHAR(255)");
            stmt.execute("ALTER TABLE alert_events ADD COLUMN IF NOT EXISTS digest_event_ids TEXT[]");
            stmt.execute("ALTER TABLE alert_events ADD COLUMN IF NOT EXISTS send_claim_token VARCHAR(64)");
            stmt.execute("ALTER TABLE alert_events ADD COLUMN IF NOT EXISTS send_claim_until TIMESTAMPTZ");
            stmt.execute(CREATE_WINDOWS);
            for (String index : CREATE_INDEXES) {
                stmt.execute(index);
            }
            schemaInitialized = true;
            LOGGER.info("PostgresAlertEventStore schema initialized");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize alert_events table", e);
        }
    }

    @Override
    public Optional<AlertEvent> insert(AlertEvent event) {
        ensureSchema();
        AlertEvent stored = event.withId(new ObjectId().toHexString()).withVersion(0);
        String sql = "INSERT INTO alert_events (id, rule_id, rule_name, source, kind, agent_id, agent_version, conversation_id, fired_at, "
                + "summary, match, rating_id, deliveries, state, next_attempt_at, dedupe_key, duplicates, digest_count, digest_items, "
                + "acknowledged_by, acknowledged_at, version, subject_user_id, digest_event_ids) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?) ON CONFLICT (dedupe_key) DO NOTHING";
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            int inserted;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, stored.id());
                ps.setString(2, stored.ruleId());
                ps.setString(3, stored.ruleName());
                ps.setString(4, stored.source() == null ? null : stored.source().name());
                ps.setString(5, stored.kind().name());
                ps.setString(6, stored.agentId());
                setNullableInt(ps, 7, stored.agentVersion());
                ps.setString(8, stored.conversationId());
                ps.setObject(9, timestamp(stored.firedAt()));
                ps.setString(10, stored.summary());
                ps.setString(11, AlertJson.write(stored.match()));
                ps.setString(12, stored.match() == null ? null : stored.match().ratingId());
                ps.setString(13, AlertJson.write(stored.deliveries()));
                ps.setString(14, stored.state().name());
                ps.setObject(15, timestamp(stored.nextAttemptAt()));
                ps.setString(16, stored.dedupeKey());
                ps.setInt(17, stored.duplicates());
                setNullableInt(ps, 18, stored.digestCount());
                ps.setArray(19, stored.digestItems() == null ? null : conn.createArrayOf("text", stored.digestItems().toArray(new String[0])));
                ps.setString(20, stored.acknowledgedBy());
                ps.setObject(21, timestamp(stored.acknowledgedAt()));
                ps.setString(22, stored.subjectUserId());
                ps.setArray(23,
                        stored.digestEventIds() == null ? null : conn.createArrayOf("text", stored.digestEventIds().toArray(new String[0])));
                inserted = ps.executeUpdate();
            }
            if (inserted > 0) {
                return Optional.of(stored);
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE alert_events SET duplicates = duplicates + 1 WHERE dedupe_key = ?")) {
                ps.setString(1, event.dedupeKey());
                ps.executeUpdate();
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store alert event", e);
        }
    }

    @Override
    public long incrementWindow(String ruleId, Instant windowStart, Instant expiresAt) {
        ensureSchema();
        String sql = "INSERT INTO alert_windows (window_key, count, expires_at) VALUES (?, 1, ?) "
                + "ON CONFLICT (window_key) DO UPDATE SET count = alert_windows.count + 1 RETURNING count";
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            long count;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ruleId + "|" + windowStart.toEpochMilli());
                ps.setObject(2, timestamp(expiresAt));
                try (ResultSet rs = ps.executeQuery()) {
                    count = rs.next() ? rs.getLong(1) : 1L;
                }
            }
            // No TTL index in PostgreSQL: expired counters go on the way.
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM alert_windows WHERE expires_at < ?")) {
                ps.setObject(1, timestamp(windowStart));
                ps.executeUpdate();
            }
            return count;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count alert window", e);
        }
    }

    @Override
    public Optional<AlertEvent> read(String id) {
        ensureSchema();
        List<AlertEvent> found = select("SELECT " + COLUMNS + " FROM alert_events WHERE id = ?", List.of(id));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public Optional<AlertEvent> updateDelivery(AlertEvent event, long expectedVersion) {
        ensureSchema();
        String sql = "UPDATE alert_events SET deliveries = ?::jsonb, state = ?, next_attempt_at = ?, version = version + 1 "
                + "WHERE id = ? AND version = ? RETURNING " + COLUMNS;
        List<AlertEvent> updated = select(sql,
                Arrays.asList(AlertJson.write(event.deliveries()), event.state().name(), timestamp(event.nextAttemptAt()), event.id(),
                        expectedVersion));
        return updated.isEmpty() ? Optional.empty() : Optional.of(updated.get(0));
    }

    @Override
    public List<AlertEvent> claimDue(Instant now, Instant leaseUntil, int limit) {
        ensureSchema();
        // One statement: FOR UPDATE SKIP LOCKED keeps two runners off the same rows.
        String sql = "UPDATE alert_events SET next_attempt_at = ?, version = version + 1 WHERE id IN ("
                + "SELECT id FROM alert_events WHERE next_attempt_at <= ? ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED) "
                + "RETURNING " + COLUMNS;
        return select(sql, Arrays.asList(timestamp(leaseUntil), timestamp(now), limit));
    }

    @Override
    public List<AlertEvent> list(AlertEventFilter filter, int offset, int limit) {
        ensureSchema();
        List<Object> params = new ArrayList<>();
        String where = where(filter, params);
        params.add(limit);
        params.add(Math.max(0, offset));
        return select("SELECT " + COLUMNS + " FROM alert_events" + where + " ORDER BY fired_at DESC, id DESC LIMIT ? OFFSET ?", params);
    }

    @Override
    public long count(AlertEventFilter filter) {
        ensureSchema();
        List<Object> params = new ArrayList<>();
        return update("SELECT COUNT(*) FROM alert_events" + where(filter, params), params, true);
    }

    @Override
    public boolean acknowledge(String id, String by, Instant at) {
        ensureSchema();
        return update("UPDATE alert_events SET acknowledged_by = ?, acknowledged_at = ? WHERE id = ? AND acknowledged_at IS NULL",
                Arrays.asList(by, timestamp(at), id), false) > 0;
    }

    @Override
    public long acknowledgeMatching(AlertEventFilter filter, String by, Instant at) {
        ensureSchema();
        List<Object> params = new ArrayList<>(Arrays.asList(by, timestamp(at)));
        String where = where(filter, params);
        String sql = "UPDATE alert_events SET acknowledged_by = ?, acknowledged_at = ?" + where
                + (where.isEmpty() ? " WHERE" : " AND") + " acknowledged_at IS NULL";
        return update(sql, params, false);
    }

    @Override
    public long acknowledgeIds(Collection<String> ids, String by, Instant at) {
        ensureSchema();
        if (ids.isEmpty()) {
            return 0;
        }
        return update("UPDATE alert_events SET acknowledged_by = ?, acknowledged_at = ? WHERE id = ANY(?) AND acknowledged_at IS NULL",
                Arrays.asList(by, timestamp(at), ids.toArray(new String[0])), false);
    }

    @Override
    public List<AlertEvent> findDigestPending(String ruleId, int limit) {
        ensureSchema();
        return select("SELECT " + COLUMNS + " FROM alert_events WHERE rule_id = ? AND state = 'DIGEST_PENDING' ORDER BY fired_at LIMIT ?",
                Arrays.asList(ruleId, limit));
    }

    @Override
    public long markDigested(Collection<String> ids) {
        ensureSchema();
        if (ids.isEmpty()) {
            return 0;
        }
        return update("UPDATE alert_events SET state = 'DIGESTED', version = version + 1 WHERE id = ANY(?) AND state = 'DIGEST_PENDING'",
                List.of((Object) ids.toArray(new String[0])), false);
    }

    @Override
    public long deleteFiredBefore(Instant cutoff) {
        ensureSchema();
        return update("DELETE FROM alert_events WHERE fired_at < ?", List.of(timestamp(cutoff)), false);
    }

    @Override
    public long redactRatingComments(Collection<String> ratingIds, Instant now) {
        ensureSchema();
        if (ratingIds.isEmpty()) {
            return 0;
        }
        return update("UPDATE alert_events SET match = match - 'commentExcerpt' WHERE rating_id = ANY(?) AND match->>'commentExcerpt' IS NOT NULL"
                + UNCLAIMED, Arrays.asList((Object) ratingIds.toArray(new String[0]), timestamp(now)), false);
    }

    @Override
    public long countRatingComments(Collection<String> ratingIds) {
        ensureSchema();
        if (ratingIds.isEmpty()) {
            return 0;
        }
        return update("SELECT COUNT(*) FROM alert_events WHERE rating_id = ANY(?) AND match->>'commentExcerpt' IS NOT NULL",
                List.of((Object) ratingIds.toArray(new String[0])), true);
    }

    @Override
    public boolean claimSend(String eventId, String token, Instant now, Instant until) {
        ensureSchema();
        return update("UPDATE alert_events SET send_claim_token = ?, send_claim_until = ? WHERE id = ?" + UNCLAIMED,
                Arrays.asList(token, timestamp(until), eventId, timestamp(now)), false) == 1;
    }

    @Override
    public boolean releaseSend(String eventId, String token) {
        ensureSchema();
        return update("UPDATE alert_events SET send_claim_token = NULL, send_claim_until = NULL WHERE id = ? AND send_claim_token = ?",
                Arrays.asList(eventId, token), false) == 1;
    }

    @Override
    public long clearAcknowledger(String userId) {
        ensureSchema();
        return update("UPDATE alert_events SET acknowledged_by = NULL WHERE acknowledged_by = ?", List.of(userId), false);
    }

    @Override
    public List<AlertEvent> findAcknowledgedBy(String userId, int limit) {
        ensureSchema();
        return select("SELECT " + COLUMNS + " FROM alert_events WHERE acknowledged_by = ? ORDER BY acknowledged_at DESC NULLS LAST, id DESC LIMIT ?",
                Arrays.asList(userId, limit));
    }

    @Override
    public Optional<AlertEvent> findByDedupeKey(String dedupeKey) {
        ensureSchema();
        List<AlertEvent> found = select("SELECT " + COLUMNS + " FROM alert_events WHERE dedupe_key = ?", List.of(dedupeKey));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public long redactOutputValues(String userId, Instant now) {
        ensureSchema();
        return update("UPDATE alert_events SET match = match - 'value', subject_user_id = NULL WHERE subject_user_id = ?" + UNCLAIMED,
                Arrays.asList(userId, timestamp(now)), false);
    }

    @Override
    public long countOutputValues(String userId) {
        ensureSchema();
        return update("SELECT COUNT(*) FROM alert_events WHERE subject_user_id = ?", List.of(userId), true);
    }

    @Override
    public List<AlertEvent> findOutputValuesBySubject(String userId, int limit) {
        ensureSchema();
        return select("SELECT " + COLUMNS + " FROM alert_events WHERE subject_user_id = ? ORDER BY fired_at DESC, id DESC LIMIT ?",
                Arrays.asList(userId, limit));
    }

    @Override
    public long redactRatingComment(String eventId, Instant now) {
        ensureSchema();
        return update("UPDATE alert_events SET match = match - 'commentExcerpt' WHERE id = ? AND match->>'commentExcerpt' IS NOT NULL" + UNCLAIMED,
                Arrays.asList(eventId, timestamp(now)), false);
    }

    static String where(AlertEventFilter f, List<Object> params) {
        List<String> clauses = new ArrayList<>();
        if (f != null) {
            if (f.agentId() != null) {
                clauses.add("agent_id = ?");
                params.add(f.agentId());
            }
            if (f.ruleId() != null) {
                clauses.add("rule_id = ?");
                params.add(f.ruleId());
            }
            if (f.source() != null) {
                clauses.add("source = ?");
                params.add(f.source().name());
            }
            if (f.acknowledged() != null) {
                clauses.add(f.acknowledged() ? "acknowledged_at IS NOT NULL" : "acknowledged_at IS NULL");
            }
            if (f.state() != null) {
                clauses.add("state = ?");
                params.add(f.state().name());
            }
            if (f.conversationId() != null) {
                clauses.add("conversation_id = ?");
                params.add(f.conversationId());
            }
            if (f.from() != null) {
                clauses.add("fired_at >= ?");
                params.add(timestamp(f.from()));
            }
            if (f.to() != null) {
                clauses.add("fired_at < ?");
                params.add(timestamp(f.to()));
            }
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private long update(String sql, List<Object> params, boolean query) {
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(conn, ps, params);
            if (query) {
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Alert event statement failed", e);
        }
    }

    private List<AlertEvent> select(String sql, List<Object> params) {
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(conn, ps, params);
            List<AlertEvent> events = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(toEvent(rs));
                }
            }
            return events;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read alert events", e);
        }
    }

    private static void bind(Connection conn, PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value instanceof String[] array) {
                ps.setArray(i + 1, conn.createArrayOf("text", array));
            } else if (value == null) {
                ps.setNull(i + 1, Types.NULL);
            } else {
                ps.setObject(i + 1, value);
            }
        }
    }

    private static AlertEvent toEvent(ResultSet rs) throws SQLException {
        String source = rs.getString("source");
        int agentVersion = rs.getInt("agent_version");
        boolean noVersion = rs.wasNull();
        int digestCount = rs.getInt("digest_count");
        boolean noDigestCount = rs.wasNull();
        Array items = rs.getArray("digest_items");
        Array covered = rs.getArray("digest_event_ids");
        return new AlertEvent(rs.getString("id"), rs.getString("rule_id"), rs.getString("rule_name"),
                source == null ? null : AlertSource.valueOf(source), AlertEvent.Kind.valueOf(rs.getString("kind")), rs.getString("agent_id"),
                noVersion ? null : agentVersion, rs.getString("conversation_id"), instant(rs.getObject("fired_at", OffsetDateTime.class)),
                rs.getString("summary"), AlertJson.read(rs.getString("match"), AlertMatch.class),
                AlertJson.read(rs.getString("deliveries"), DELIVERIES), AlertEvent.State.valueOf(rs.getString("state")),
                instant(rs.getObject("next_attempt_at", OffsetDateTime.class)), rs.getString("dedupe_key"), rs.getInt("duplicates"),
                noDigestCount ? null : digestCount, items == null ? null : Arrays.asList((String[]) items.getArray()),
                rs.getString("acknowledged_by"), instant(rs.getObject("acknowledged_at", OffsetDateTime.class)), rs.getLong("version"), null,
                covered == null ? null : Arrays.asList((String[]) covered.getArray()));
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
