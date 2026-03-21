package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import io.quarkus.arc.profile.IfBuildProfile;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * PostgreSQL implementation of {@link IAuditStore}.
 * <p>
 * Uses a dedicated {@code audit_ledger} table with INSERT-only semantics.
 * No UPDATE or DELETE operations — enforces the write-once contract.
 * <p>
 * Activated via {@code @IfBuildProfile("postgres")}.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
@IfBuildProfile("postgres")
public class PostgresAuditStore implements IAuditStore {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS audit_ledger (
                id UUID PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                AGENT_ID TEXT NOT NULL,
                AGENT_VERSION INTEGER NOT NULL,
                user_id TEXT,
                environment TEXT,
                step_index INTEGER NOT NULL,
                task_id TEXT NOT NULL,
                task_type TEXT,
                task_index INTEGER NOT NULL,
                duration_ms BIGINT NOT NULL,
                cost DOUBLE PRECISION NOT NULL DEFAULT 0,
                hmac TEXT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                data JSONB NOT NULL
            )
            """;

    private static final String CREATE_INDEX_CONV =
            "CREATE INDEX IF NOT EXISTS idx_audit_conv ON audit_ledger (conversation_id)";
    private static final String CREATE_INDEX_BOT =
            "CREATE INDEX IF NOT EXISTS idx_audit_bot ON audit_ledger (AGENT_ID, AGENT_VERSION)";
    private static final String CREATE_INDEX_TS =
            "CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit_ledger (created_at DESC)";

    private static final String INSERT_SQL = """
            INSERT INTO audit_ledger
                (id, conversation_id, AGENT_ID, AGENT_VERSION, user_id, environment,
                 step_index, task_id, task_type, task_index, duration_ms, cost, hmac, created_at, data)
            VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """;

    private static final String SELECT_ALL = """
            id, conversation_id, AGENT_ID, AGENT_VERSION, user_id, environment,
            step_index, task_id, task_type, task_index, duration_ms, cost, hmac, created_at, data
            """;

    private final DataSource dataSource;
    private final IJsonSerialization jsonSerialization;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresAuditStore(DataSource dataSource, IJsonSerialization jsonSerialization) {
        this.dataSource = dataSource;
        this.jsonSerialization = jsonSerialization;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) return;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            stmt.execute(CREATE_INDEX_CONV);
            stmt.execute(CREATE_INDEX_BOT);
            stmt.execute(CREATE_INDEX_TS);
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize audit_ledger table", e);
        }
    }

    @Override
    public void appendEntry(AuditEntry entry) {
        ensureSchema();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            setEntryParams(ps, entry);
            ps.executeUpdate();
        } catch (SQLException | IOException e) {
            throw new RuntimeException("Failed to insert audit entry", e);
        }
    }

    @Override
    public void appendBatch(List<AuditEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        ensureSchema();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            for (AuditEntry entry : entries) {
                setEntryParams(ps, entry);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException | IOException e) {
            throw new RuntimeException("Failed to batch-insert audit entries", e);
        }
    }

    @Override
    public List<AuditEntry> getEntries(String conversationId, int skip, int limit) {
        ensureSchema();
        String sql = "SELECT " + SELECT_ALL + " FROM audit_ledger" +
                " WHERE conversation_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return queryEntries(sql, conversationId, limit, skip);
    }

    @Override
    public List<AuditEntry> getEntriesByBot(String agentId, Integer agentVersion, int skip, int limit) {
        ensureSchema();
        if (agentVersion != null) {
            String sql = "SELECT " + SELECT_ALL + " FROM audit_ledger" +
                    " WHERE AGENT_ID = ? AND AGENT_VERSION = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, agentId);
                ps.setInt(2, agentVersion);
                ps.setInt(3, limit);
                ps.setInt(4, skip);
                return readEntries(ps);
            } catch (SQLException | IOException e) {
                throw new RuntimeException("Failed to query audit entries by bot", e);
            }
        } else {
            String sql = "SELECT " + SELECT_ALL + " FROM audit_ledger" +
                    " WHERE AGENT_ID = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
            return queryEntries(sql, agentId, limit, skip);
        }
    }

    @Override
    public long countByConversation(String conversationId) {
        ensureSchema();
        String sql = "SELECT COUNT(*) FROM audit_ledger WHERE conversation_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count audit entries", e);
        }
    }

    // -- Internal helpers --

    private void setEntryParams(PreparedStatement ps, AuditEntry entry) throws SQLException, IOException {
        // Build JSONB with variable-length map fields
        var data = new LinkedHashMap<String, Object>();
        if (entry.input() != null) data.put("input", entry.input());
        if (entry.output() != null) data.put("output", entry.output());
        if (entry.llmDetail() != null) data.put("llmDetail", entry.llmDetail());
        if (entry.toolCalls() != null) data.put("toolCalls", entry.toolCalls());
        if (entry.actions() != null) data.put("actions", entry.actions());

        ps.setString(1, entry.id() != null ? entry.id() : UUID.randomUUID().toString());
        ps.setString(2, entry.conversationId());
        ps.setString(3, entry.agentId());
        ps.setInt(4, entry.agentVersion());
        ps.setString(5, entry.userId());
        ps.setString(6, entry.environment());
        ps.setInt(7, entry.stepIndex());
        ps.setString(8, entry.taskId());
        ps.setString(9, entry.taskType());
        ps.setInt(10, entry.taskIndex());
        ps.setLong(11, entry.durationMs());
        ps.setDouble(12, entry.cost());
        ps.setString(13, entry.hmac());
        ps.setTimestamp(14, entry.timestamp() != null
                ? Timestamp.from(entry.timestamp()) : Timestamp.from(Instant.now()));
        ps.setString(15, jsonSerialization.serialize(data));
    }

    private List<AuditEntry> queryEntries(String sql, String param, int limit, int skip) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            ps.setInt(2, limit);
            ps.setInt(3, skip);
            return readEntries(ps);
        } catch (SQLException | IOException e) {
            throw new RuntimeException("Failed to query audit entries", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<AuditEntry> readEntries(PreparedStatement ps) throws SQLException, IOException {
        List<AuditEntry> results = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> data = jsonSerialization.deserialize(
                        rs.getString("data"), Map.class);
                results.add(fromRow(rs, data));
            }
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private AuditEntry fromRow(ResultSet rs, Map<String, Object> data) throws SQLException {
        Timestamp ts = rs.getTimestamp("created_at");
        return new AuditEntry(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getString("AGENT_ID"),
                rs.getInt("AGENT_VERSION"),
                rs.getString("user_id"),
                rs.getString("environment"),
                rs.getInt("step_index"),
                rs.getString("task_id"),
                rs.getString("task_type"),
                rs.getInt("task_index"),
                rs.getLong("duration_ms"),
                (Map<String, Object>) data.get("input"),
                (Map<String, Object>) data.get("output"),
                (Map<String, Object>) data.get("llmDetail"),
                (Map<String, Object>) data.get("toolCalls"),
                data.get("actions") instanceof List<?> list ? (List<String>) list : null,
                rs.getDouble("cost"),
                ts != null ? ts.toInstant() : null,
                rs.getString("hmac")
        );
    }
}
