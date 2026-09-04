/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import io.quarkus.arc.DefaultBean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import jakarta.enterprise.inject.Instance;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * PostgreSQL implementation of {@link IAuditStore}.
 * <p>
 * Uses a dedicated {@code audit_ledger} table with INSERT-only semantics. No
 * UPDATE or DELETE operations — enforces the write-once contract.
 * <p>
 * Activated via {@code @DefaultBean}.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresAuditStore implements IAuditStore {

    /**
     * {@code conversation_id}, {@code AGENT_ID} and {@code AGENT_VERSION} are
     * deliberately nullable.
     * <p>
     * They used to be {@code NOT NULL}, which made this backend unable to store
     * entries the rest of the system legitimately produces: GDPR compliance events
     * have no conversation and no agent, and the HITL/group oversight entries know
     * the conversation but not the agent version. {@link AuditEntry#agentVersion()}
     * is an {@code Integer} precisely because "unknown" is a value, and MongoDB
     * stores and returns it as null. On PostgreSQL those entries failed on write —
     * and because {@code AuditLedgerService.flush} re-queued the whole batch, a
     * single one of them discarded three flush windows of unrelated conversations'
     * audit data every time a pending approval was cancelled or a facilitator
     * emitted a checkpoint.
     */
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS audit_ledger (
                id UUID PRIMARY KEY,
                conversation_id TEXT,
                AGENT_ID TEXT,
                AGENT_VERSION INTEGER,
                user_id TEXT,
                environment TEXT,
                step_index INTEGER NOT NULL,
                task_id TEXT NOT NULL,
                task_type TEXT,
                task_index INTEGER NOT NULL,
                duration_ms BIGINT NOT NULL,
                cost DOUBLE PRECISION NOT NULL DEFAULT 0,
                hmac TEXT,
                agent_signature TEXT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                sequence BIGINT NOT NULL DEFAULT -1,
                data JSONB NOT NULL
            )
            """;

    /**
     * Adds {@code sequence} to ledgers created before it existed. Without it this
     * backend cannot persist the per-conversation sequence, so
     * {@link #supportsSequence()} would have to stay false and deletion/reordering
     * of audit rows would be undetectable on PostgreSQL while MongoDB deployments
     * were protected. Defaults to the UNSEQUENCED sentinel so pre-existing rows
     * keep reporting the chain as UNAVAILABLE rather than BROKEN.
     */
    private static final String ADD_SEQUENCE_COLUMN = "ALTER TABLE audit_ledger ADD COLUMN IF NOT EXISTS sequence BIGINT NOT NULL DEFAULT -1";

    /**
     * Adds {@code agent_signature} to ledgers created before it existed.
     * <p>
     * {@code eddi.audit.agent-signing-enabled} defaults to true and the Ed25519
     * signature was duly computed for every entry — but this backend had no column
     * to put it in and its row-mapper hard-coded {@code null}, so the signature was
     * discarded on write and read back absent. The second, non-repudiation half of
     * the ledger's integrity story was silently inert on PostgreSQL while MongoDB
     * deployments had it. Nullable, because rows written before this column existed
     * genuinely have no signature to record.
     */
    private static final String ADD_AGENT_SIGNATURE_COLUMN = "ALTER TABLE audit_ledger ADD COLUMN IF NOT EXISTS agent_signature TEXT";

    /**
     * Relaxes the three {@code NOT NULL} constraints on ledgers created before this
     * fix. Dropping a constraint a column does not have is a no-op in PostgreSQL,
     * so these are idempotent and safe to run on every start.
     */
    private static final String[] DROP_NOT_NULL_CONSTRAINTS = {
            "ALTER TABLE audit_ledger ALTER COLUMN conversation_id DROP NOT NULL",
            "ALTER TABLE audit_ledger ALTER COLUMN AGENT_ID DROP NOT NULL",
            "ALTER TABLE audit_ledger ALTER COLUMN AGENT_VERSION DROP NOT NULL"};

    private static final String CREATE_INDEX_CONV = "CREATE INDEX IF NOT EXISTS idx_audit_conv ON audit_ledger (conversation_id)";
    /** Chain verification reads a conversation's entries in sequence order. */
    private static final String CREATE_INDEX_CONV_SEQ = "CREATE INDEX IF NOT EXISTS idx_audit_conv_seq ON audit_ledger (conversation_id, sequence)";

    private static final String CREATE_INDEX_AGENT = "CREATE INDEX IF NOT EXISTS idx_audit_agent ON audit_ledger (AGENT_ID, AGENT_VERSION)";
    private static final String CREATE_INDEX_TS = "CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit_ledger (created_at DESC)";

    /**
     * GDPR export and erasure both scan the ledger by {@code user_id} — it is the
     * largest, append-only, never-pruned table in the system, and both operations
     * are legally deadline-bound. Without this index they are sequential scans.
     * <p>
     * <strong>Upgrade note.</strong> On the first start after this change the index
     * is built on an existing ledger, and a plain (non-{@code CONCURRENTLY})
     * {@code CREATE INDEX} takes a {@code SHARE} lock: audit inserts block for the
     * duration of the build, which on a multi-million-row table is minutes.
     * {@code CREATE INDEX CONCURRENTLY} is not an option here because it cannot run
     * inside {@code ensureSchema}'s statement batch (PostgreSQL forbids it in a
     * transaction block) and it can leave an INVALID index behind on failure, which
     * nothing in this class would notice or repair. Operators of large existing
     * ledgers who cannot take that pause should build {@code idx_audit_user} with
     * {@code CREATE INDEX CONCURRENTLY} out of band before deploying;
     * {@code IF NOT EXISTS} then makes this statement a no-op.
     */
    private static final String CREATE_INDEX_USER = "CREATE INDEX IF NOT EXISTS idx_audit_user ON audit_ledger (user_id)";

    /**
     * {@code ON CONFLICT (id) DO NOTHING} makes the insert idempotent, which is
     * what lets {@code AuditLedgerService} retry a failed batch entry by entry: a
     * JDBC batch in autocommit stops at the first failing statement and commits the
     * ones before it, so a plain retry of the whole batch would collide with rows
     * that already landed. Re-inserting an entry with a different body under the
     * same id is not a scenario the ledger has — entry ids are per-submission
     * UUIDs.
     */
    private static final String INSERT_SQL = """
            INSERT INTO audit_ledger
                (id, conversation_id, AGENT_ID, AGENT_VERSION, user_id, environment,
                 step_index, task_id, task_type, task_index, duration_ms, cost, hmac, created_at, data, sequence,
                 agent_signature)
            VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;

    private static final String SELECT_ALL = """
            id, conversation_id, AGENT_ID, AGENT_VERSION, user_id, environment,
            step_index, task_id, task_type, task_index, duration_ms, cost, hmac, created_at, data,
            sequence, agent_signature
            """;

    private final Instance<DataSource> dataSourceInstance;
    private final IJsonSerialization jsonSerialization;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresAuditStore(Instance<DataSource> dataSourceInstance, IJsonSerialization jsonSerialization) {
        this.dataSourceInstance = dataSourceInstance;
        this.jsonSerialization = jsonSerialization;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            stmt.execute(ADD_SEQUENCE_COLUMN); // idempotent upgrades for pre-existing ledgers
            stmt.execute(ADD_AGENT_SIGNATURE_COLUMN);
            for (String alter : DROP_NOT_NULL_CONSTRAINTS) {
                stmt.execute(alter);
            }
            stmt.execute(CREATE_INDEX_CONV);
            stmt.execute(CREATE_INDEX_CONV_SEQ);
            stmt.execute(CREATE_INDEX_AGENT);
            stmt.execute(CREATE_INDEX_TS);
            stmt.execute(CREATE_INDEX_USER);
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize audit_ledger table", e);
        }
    }

    @Override
    public void appendEntry(AuditEntry entry) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            setEntryParams(ps, entry);
            ps.executeUpdate();
        } catch (SQLException | IOException e) {
            throw new RuntimeException("Failed to insert audit entry", e);
        }
    }

    @Override
    public void appendBatch(List<AuditEntry> entries) {
        if (entries == null || entries.isEmpty())
            return;
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
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
        String sql = "SELECT " + SELECT_ALL + " FROM audit_ledger" + " WHERE conversation_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return queryEntries(sql, conversationId, limit, skip);
    }

    @Override
    public List<AuditEntry> getEntriesByAgent(String agentId, Integer agentVersion, int skip, int limit) {
        ensureSchema();
        if (agentVersion != null) {
            String sql = "SELECT " + SELECT_ALL + " FROM audit_ledger"
                    + " WHERE AGENT_ID = ? AND AGENT_VERSION = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
            try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, agentId);
                ps.setInt(2, agentVersion);
                ps.setInt(3, limit);
                ps.setInt(4, skip);
                return readEntries(ps);
            } catch (SQLException | IOException e) {
                throw new RuntimeException("Failed to query audit entries by agent", e);
            }
        } else {
            String sql = "SELECT " + SELECT_ALL + " FROM audit_ledger" + " WHERE AGENT_ID = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
            return queryEntries(sql, agentId, limit, skip);
        }
    }

    @Override
    public long countByConversation(String conversationId) {
        ensureSchema();
        String sql = "SELECT COUNT(*) FROM audit_ledger WHERE conversation_id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count audit entries", e);
        }
    }

    @Override
    public long maxSequence(String conversationId) {
        ensureSchema();
        String sql = "SELECT MAX(sequence) FROM audit_ledger WHERE conversation_id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long max = rs.getLong(1);
                    // MAX over an empty set is SQL NULL, which getLong reports as 0 —
                    // indistinguishable from a real position 0 without wasNull().
                    return rs.wasNull() ? AuditEntry.UNSEQUENCED : max;
                }
            }
            return AuditEntry.UNSEQUENCED;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read max audit sequence", e);
        }
    }

    @Override
    public List<AuditEntry> getEntriesByUserId(String userId, int skip, int limit) {
        ensureSchema();
        String sql = "SELECT " + SELECT_ALL + " FROM audit_ledger"
                + " WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return queryEntries(sql, userId, limit, skip);
    }

    // -- Internal helpers --

    private void setEntryParams(PreparedStatement ps, AuditEntry entry) throws SQLException, IOException {
        // Build JSONB with variable-length map fields
        var data = new LinkedHashMap<String, Object>();
        if (entry.input() != null)
            data.put("input", entry.input());
        if (entry.output() != null)
            data.put("output", entry.output());
        if (entry.llmDetail() != null)
            data.put("llmDetail", entry.llmDetail());
        if (entry.toolCalls() != null)
            data.put("toolCalls", entry.toolCalls());
        if (entry.actions() != null)
            data.put("actions", entry.actions());

        ps.setString(1, entry.id() != null ? entry.id() : UUID.randomUUID().toString());
        ps.setString(2, entry.conversationId());
        ps.setString(3, entry.agentId());
        // setObject, not setInt: agentVersion is a nullable Integer and unboxing a
        // null here threw an NPE that escaped appendBatch's SQLException|IOException
        // catch entirely, taking the whole flush batch down with it.
        ps.setObject(4, entry.agentVersion(), Types.INTEGER);
        ps.setString(5, entry.userId());
        ps.setString(6, entry.environment());
        ps.setInt(7, entry.stepIndex());
        ps.setString(8, entry.taskId());
        ps.setString(9, entry.taskType());
        ps.setInt(10, entry.taskIndex());
        ps.setLong(11, entry.durationMs());
        ps.setDouble(12, entry.cost());
        ps.setString(13, entry.hmac());
        ps.setTimestamp(14, entry.timestamp() != null ? Timestamp.from(entry.timestamp()) : Timestamp.from(Instant.now()));
        ps.setString(15, jsonSerialization.serialize(data));
        ps.setLong(16, entry.sequence());
        ps.setString(17, entry.agentSignature());
    }

    private List<AuditEntry> queryEntries(String sql, String param, int limit, int skip) {
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
                Map<String, Object> data = jsonSerialization.deserialize(rs.getString("data"), Map.class);
                results.add(fromRow(rs, data));
            }
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private AuditEntry fromRow(ResultSet rs, Map<String, Object> data) throws SQLException {
        Timestamp ts = rs.getTimestamp("created_at");
        // getObject, not getInt: getInt maps SQL NULL to 0, and the HMAC canonical
        // form renders null and 0 differently — so a null-versioned entry would read
        // back as version 0 and report as tampered forever.
        Integer agentVersion = rs.getObject("AGENT_VERSION", Integer.class);
        return new AuditEntry(rs.getString("id"), rs.getString("conversation_id"), rs.getString("AGENT_ID"), agentVersion,
                rs.getString("user_id"), rs.getString("environment"), rs.getInt("step_index"), rs.getString("task_id"), rs.getString("task_type"),
                rs.getInt("task_index"), rs.getLong("duration_ms"), (Map<String, Object>) data.get("input"), (Map<String, Object>) data.get("output"),
                (Map<String, Object>) data.get("llmDetail"), (Map<String, Object>) data.get("toolCalls"),
                data.get("actions") instanceof List<?> list ? (List<String>) list : null, rs.getDouble("cost"), ts != null ? ts.toInstant() : null,
                rs.getString("hmac"), rs.getString("agent_signature"), rs.getLong("sequence"));
    }
    /**
     * PostgreSQL persists the per-conversation sequence, so the chain continuity
     * check applies here exactly as it does on MongoDB. Without this the ledger
     * silently degraded to HMAC-only on one of the two supported backends, and a
     * deleted audit row would have gone unnoticed.
     */
    @Override
    public boolean supportsSequence() {
        return true;
    }

    // === GDPR ===

    @Override
    public long pseudonymizeByUserId(String userId, String pseudonym) {
        ensureSchema();
        String sql = "UPDATE audit_ledger SET user_id = ? WHERE user_id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pseudonym);
            ps.setString(2, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to pseudonymize audit entries for userId", e);
        }
    }
}
