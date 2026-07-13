/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * PostgreSQL implementation of {@link IHitlToolJournalStore}. Annotated
 * {@code @DefaultBean} so {@code DataStoreProducers} can select it when
 * {@code eddi.datastore.type=postgres}.
 * <p>
 * {@code tryClaim} relies on the unique constraint on
 * {@code (conversation_id, pause_epoch, call_id)} for atomicity: an
 * {@code INSERT ... ON CONFLICT DO NOTHING} either inserts (this call won the
 * claim, {@code executeUpdate() > 0}) or is a no-op because a prior attempt
 * already claimed or completed this execution ({@code executeUpdate() == 0}).
 * Only the zero-rows case returns {@code false}. Any genuine
 * {@link SQLException} (connectivity/timeout) is NOT a duplicate and must not
 * be conflated with one — it is propagated as an unchecked
 * {@link RuntimeException} so the caller fails cleanly (and can retry) instead
 * of silently skipping a human-approved tool. This mirrors the Mongo
 * implementation's contract.
 *
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresHitlToolJournalStore implements IHitlToolJournalStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresHitlToolJournalStore.class);

    private static final int RESULT_CAPPED_MAX_BYTES = 32 * 1024;

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS hitl_tool_execution_journal (
                conversation_id VARCHAR(255) NOT NULL,
                pause_epoch VARCHAR(64) NOT NULL,
                call_id VARCHAR(255) NOT NULL,
                tool_name VARCHAR(255),
                status VARCHAR(32) NOT NULL,
                result_capped TEXT,
                executed_at BIGINT,
                decided_by VARCHAR(255),
                claimed_at BIGINT NOT NULL,
                CONSTRAINT uq_journal_key UNIQUE (conversation_id, pause_epoch, call_id)
            )
            """;

    private final Instance<DataSource> dataSourceInstance;
    private final long retentionMillis;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresHitlToolJournalStore(Instance<DataSource> dataSourceInstance,
            @ConfigProperty(name = "eddi.hitl.tool.journal-retention", defaultValue = "30d") Duration journalRetention) {
        this.dataSourceInstance = dataSourceInstance;
        this.retentionMillis = journalRetention == null || journalRetention.isNegative() || journalRetention.isZero()
                ? Duration.ofDays(30).toMillis()
                : journalRetention.toMillis();
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            schemaInitialized = true;
            LOGGER.info("PostgresHitlToolJournalStore schema initialized");
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize hitl_tool_execution_journal table", e);
            return;
        }
        // Postgres has no TTL index. Run a one-shot, best-effort cleanup at startup
        // to bound growth (a continuous sweep is out of scope). Anchored on
        // claimed_at so both completed entries and crash-orphaned EXECUTING claims
        // are purged after the retention window.
        cleanupExpired();
    }

    private void cleanupExpired() {
        long cutoff = Instant.now().toEpochMilli() - retentionMillis;
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM hitl_tool_execution_journal WHERE claimed_at < ?")) {
            ps.setLong(1, cutoff);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                LOGGER.infof("HITL tool journal: startup cleanup removed %d entries older than retention", deleted);
            }
        } catch (SQLException e) {
            // Best-effort only — cleanup failure must not break startup.
            LOGGER.warnf(e, "HITL tool journal: startup retention cleanup failed");
        }
    }

    @Override
    public boolean tryClaim(String conversationId, String pauseEpoch, String callId, String toolName, String decidedBy) {
        ensureSchema();
        String sql = """
                INSERT INTO hitl_tool_execution_journal
                    (conversation_id, pause_epoch, call_id, tool_name, status, claimed_at, decided_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (conversation_id, pause_epoch, call_id) DO NOTHING
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.setString(2, pauseEpoch);
            ps.setString(3, callId);
            ps.setString(4, toolName);
            ps.setString(5, Status.EXECUTING.name());
            ps.setLong(6, Instant.now().toEpochMilli());
            // Persist the approver identity at claim time (parity with the Mongo store):
            // without this, find() reads decidedBy back as null on Postgres.
            ps.setString(7, decidedBy);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                LOGGER.infof("HITL tool journal: claim already exists for conversationId=%s pauseEpoch=%s callId=%s — not re-executing",
                        sanitize(conversationId), sanitize(pauseEpoch), sanitize(callId));
                return false;
            }
            return true;
        } catch (SQLException e) {
            // A connectivity/timeout failure is NOT a duplicate and must never be
            // conflated with "already claimed". Propagate so the caller fails cleanly
            // and can retry, rather than silently skipping a human-approved tool.
            LOGGER.errorf(e, "HITL tool journal: failed to claim conversationId=%s pauseEpoch=%s callId=%s",
                    sanitize(conversationId), sanitize(pauseEpoch), sanitize(callId));
            throw new RuntimeException("Failed to claim HITL tool execution", e);
        }
    }

    @Override
    public void markExecuted(String conversationId, String pauseEpoch, String callId, String resultCapped) {
        ensureSchema();
        String capped = capUtf8(resultCapped, RESULT_CAPPED_MAX_BYTES);
        String sql = """
                UPDATE hitl_tool_execution_journal
                SET status = ?, result_capped = ?, executed_at = ?
                WHERE conversation_id = ? AND pause_epoch = ? AND call_id = ?
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Status.EXECUTED.name());
            ps.setString(2, capped);
            ps.setLong(3, Instant.now().toEpochMilli());
            ps.setString(4, conversationId);
            ps.setString(5, pauseEpoch);
            ps.setString(6, callId);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                LOGGER.warnf("HITL tool journal: markExecuted found no claimed entry for conversationId=%s pauseEpoch=%s callId=%s",
                        sanitize(conversationId), sanitize(pauseEpoch), sanitize(callId));
            }
        } catch (SQLException e) {
            LOGGER.errorf(e, "HITL tool journal: failed to markExecuted conversationId=%s pauseEpoch=%s callId=%s",
                    sanitize(conversationId), sanitize(pauseEpoch), sanitize(callId));
            throw new RuntimeException("Failed to mark HITL tool execution", e);
        }
    }

    @Override
    public Optional<JournalEntry> find(String conversationId, String pauseEpoch, String callId) {
        ensureSchema();
        String sql = """
                SELECT conversation_id, pause_epoch, call_id, tool_name, status, result_capped, executed_at, decided_by
                FROM hitl_tool_execution_journal
                WHERE conversation_id = ? AND pause_epoch = ? AND call_id = ?
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.setString(2, pauseEpoch);
            ps.setString(3, callId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String statusRaw = rs.getString("status");
                Status status = statusRaw != null ? Status.valueOf(statusRaw) : Status.EXECUTING;
                long executedAtMillis = rs.getLong("executed_at");
                Instant executedAt = rs.wasNull() ? null : Instant.ofEpochMilli(executedAtMillis);
                return Optional.of(new JournalEntry(
                        rs.getString("conversation_id"),
                        rs.getString("pause_epoch"),
                        rs.getString("call_id"),
                        rs.getString("tool_name"),
                        status,
                        rs.getString("result_capped"),
                        executedAt,
                        rs.getString("decided_by")));
            }
        } catch (SQLException e) {
            LOGGER.errorf(e, "HITL tool journal: failed to find conversationId=%s pauseEpoch=%s callId=%s",
                    sanitize(conversationId), sanitize(pauseEpoch), sanitize(callId));
            throw new RuntimeException("Failed to read HITL tool execution", e);
        }
    }

    @Override
    public long deleteByConversationId(String conversationId) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM hitl_tool_execution_journal WHERE conversation_id = ?")) {
            ps.setString(1, conversationId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.errorf(e, "HITL tool journal: failed to delete entries for conversationId=%s", sanitize(conversationId));
            throw new RuntimeException("Failed to delete HITL tool journal entries", e);
        }
    }

    private static String capUtf8(String value, int maxBytes) {
        if (value == null) {
            return null;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        // Truncate on a byte boundary, then trim any trailing partial UTF-8
        // sequence so the result decodes cleanly.
        int end = maxBytes;
        // UTF-8 continuation bytes have the high bits 10xxxxxx (0x80-0xBF).
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
