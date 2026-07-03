/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.memory.model.ConversationState;
import io.quarkus.arc.DefaultBean;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static ai.labs.eddi.engine.model.Context.ContextType.valueOf;
import static ai.labs.eddi.engine.memory.model.ConversationState.ENDED;

/**
 * PostgreSQL implementation of {@link IConversationMemoryStore}.
 * <p>
 * Stores conversation snapshots as JSONB in a dedicated
 * {@code conversation_memories} table with extracted indexed columns for
 * efficient querying.
 */
@ApplicationScoped
@DefaultBean
public class PostgresConversationMemoryStore implements IConversationMemoryStore, IResourceStore<ConversationMemorySnapshot> {

    private static final Logger LOGGER = Logger.getLogger(PostgresConversationMemoryStore.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS conversation_memories (
                id UUID PRIMARY KEY,
                AGENT_ID TEXT NOT NULL,
                AGENT_VERSION INTEGER NOT NULL,
                conversation_state TEXT NOT NULL DEFAULT 'IN_PROGRESS',
                data JSONB NOT NULL
            )
            """;

    private static final String CREATE_INDEX_STATE = "CREATE INDEX IF NOT EXISTS idx_conv_state ON conversation_memories (conversation_state)";
    private static final String CREATE_INDEX_AGENT = "CREATE INDEX IF NOT EXISTS idx_conv_agent ON conversation_memories (AGENT_ID, AGENT_VERSION)";

    private final Instance<DataSource> dataSourceInstance;
    private final IJsonSerialization jsonSerialization;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresConversationMemoryStore(Instance<DataSource> dataSourceInstance, IJsonSerialization jsonSerialization) {
        this.dataSourceInstance = dataSourceInstance;
        this.jsonSerialization = jsonSerialization;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            stmt.execute(CREATE_INDEX_STATE);
            stmt.execute(CREATE_INDEX_AGENT);
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize conversation_memories table", e);
        }
    }

    @Override
    public String storeConversationMemorySnapshot(ConversationMemorySnapshot snapshot) {
        ensureSchema();
        try {
            String json = jsonSerialization.serialize(snapshot);
            String conversationId = snapshot.getConversationId();

            if (conversationId != null) {
                // Update existing
                String sql = """
                        UPDATE conversation_memories
                        SET AGENT_ID = ?, AGENT_VERSION = ?, conversation_state = ?, data = ?::jsonb
                        WHERE id = ?::uuid
                        """;
                try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, snapshot.getAgentId());
                    ps.setInt(2, snapshot.getAgentVersion());
                    ps.setString(3, snapshot.getConversationState().name());
                    ps.setString(4, json);
                    ps.setString(5, conversationId);
                    ps.executeUpdate();
                }
            } else {
                // Insert new
                conversationId = UUID.randomUUID().toString();
                snapshot.setId(conversationId);
                String json2 = jsonSerialization.serialize(snapshot); // re-serialize with ID
                String sql = """
                        INSERT INTO conversation_memories (id, AGENT_ID, AGENT_VERSION, conversation_state, data)
                        VALUES (?::uuid, ?, ?, ?, ?::jsonb)
                        """;
                try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, conversationId);
                    ps.setString(2, snapshot.getAgentId());
                    ps.setInt(3, snapshot.getAgentVersion());
                    ps.setString(4, snapshot.getConversationState() != null ? snapshot.getConversationState().name() : "IN_PROGRESS");
                    ps.setString(5, json2);
                    ps.executeUpdate();
                }
            }
            return conversationId;
        } catch (IOException | SQLException e) {
            throw new RuntimeException("Failed to store conversation memory", e);
        }
    }

    @Override
    public boolean storeConversationMemorySnapshotIfState(ConversationMemorySnapshot snapshot, ConversationState expectedState)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String conversationId = snapshot.getConversationId();
        if (conversationId == null) {
            // A conditional store only makes sense against an existing row.
            return false;
        }
        try {
            String json = jsonSerialization.serialize(snapshot);
            // Atomic compare-and-store: the WHERE guards the state column (the CAS
            // arbiter, see compareAndSetState), so a concurrent terminal writer that
            // moved the row off expectedState is not overwritten.
            String sql = """
                    UPDATE conversation_memories
                    SET AGENT_ID = ?, AGENT_VERSION = ?, conversation_state = ?, data = ?::jsonb
                    WHERE id = ?::uuid AND conversation_state = ?
                    """;
            try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, snapshot.getAgentId());
                ps.setInt(2, snapshot.getAgentVersion());
                ps.setString(3, snapshot.getConversationState() != null ? snapshot.getConversationState().name() : "IN_PROGRESS");
                ps.setString(4, json);
                ps.setString(5, conversationId);
                ps.setString(6, expectedState.name());
                return ps.executeUpdate() > 0;
            }
        } catch (IOException | SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to conditionally store conversation memory", e);
        }
    }

    @Override
    public ConversationMemorySnapshot loadConversationMemorySnapshot(String conversationId) {
        String sql = "SELECT conversation_state, data FROM conversation_memories WHERE id = ?::uuid";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ConversationMemorySnapshot snapshot = jsonSerialization.deserialize(rs.getString("data"), ConversationMemorySnapshot.class);
                    fixContextTypes(snapshot);
                    snapshot.setConversationId(conversationId);
                    applyStateColumn(snapshot, rs.getString("conversation_state"));
                    return snapshot;
                }
                return null;
            }
        } catch (IOException | SQLException e) {
            throw new RuntimeException("Failed to load conversation memory", e);
        }
    }

    @Override
    public List<ConversationMemorySnapshot> loadActiveConversationMemorySnapshot(String agentId, Integer agentVersion)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT conversation_state, data FROM conversation_memories "
                + "WHERE AGENT_ID = ? AND AGENT_VERSION = ? AND conversation_state != ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setInt(2, agentVersion);
            ps.setString(3, ENDED.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<ConversationMemorySnapshot> results = new ArrayList<>();
                while (rs.next()) {
                    ConversationMemorySnapshot snapshot = jsonSerialization.deserialize(rs.getString("data"), ConversationMemorySnapshot.class);
                    applyStateColumn(snapshot, rs.getString("conversation_state"));
                    results.add(snapshot);
                }
                return results;
            }
        } catch (IOException | SQLException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    /**
     * The indexed {@code conversation_state} column is the single source of truth
     * for the state: CAS transitions ({@link #compareAndSetState}) and
     * {@link #setConversationState} update the column, while the JSONB document
     * still carries the state it had when the full snapshot was last stored.
     * Loading MUST reconcile the two, or a cancelled/timed-out pause keeps
     * reporting AWAITING_HUMAN from the stale document — wedging say() and
     * resurrecting terminated approvals (parity with MongoDB, where the state lives
     * once in the document the codec reads).
     */
    private static void applyStateColumn(ConversationMemorySnapshot snapshot, String stateColumn) {
        if (stateColumn != null) {
            snapshot.setConversationState(ConversationState.valueOf(stateColumn));
        }
    }

    @Override
    public void setConversationState(String conversationId, ConversationState conversationState) {
        ensureSchema();
        // Patch the JSONB copy of the state along with the column so direct
        // document readers can never observe the pre-transition state.
        String sql = "UPDATE conversation_memories SET conversation_state = ?, "
                + "data = jsonb_set(data, '{conversationState}', to_jsonb(?::text)) WHERE id = ?::uuid";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationState.name());
            ps.setString(2, conversationState.name());
            ps.setString(3, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set conversation state", e);
        }
    }

    @Override
    public void deleteConversationMemorySnapshot(String conversationId) {
        ensureSchema();
        String sql = "DELETE FROM conversation_memories WHERE id = ?::uuid";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete conversation memory", e);
        }
    }

    @Override
    public ConversationState getConversationState(String conversationId) {
        ensureSchema();
        String sql = "SELECT conversation_state FROM conversation_memories WHERE id = ?::uuid";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return ConversationState.valueOf(rs.getString("conversation_state"));
                }
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get conversation state", e);
        }
    }

    @Override
    public Long getActiveConversationCount(String agentId, Integer agentVersion) {
        ensureSchema();
        // Plan §10(a): AWAITING_HUMAN does not count as active (mirrors the Mongo
        // store) — otherwise a forgotten approval blocks undeploy/GC forever.
        String sql = "SELECT COUNT(*) FROM conversation_memories "
                + "WHERE AGENT_ID = ? AND AGENT_VERSION = ? AND conversation_state NOT IN (?, ?)";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setInt(2, agentVersion);
            ps.setString(3, ENDED.toString());
            ps.setString(4, ConversationState.AWAITING_HUMAN.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count active conversations", e);
        }
    }

    @Override
    public List<String> getEndedConversationIds() {
        ensureSchema();
        String sql = "SELECT id FROM conversation_memories WHERE conversation_state = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ENDED.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getString("id"));
                }
                return ids;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list ended conversations", e);
        }
    }

    @Override
    public boolean compareAndSetState(String conversationId, ConversationState expected, ConversationState target)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        // Column is the CAS arbiter; the JSONB copy is patched in the same
        // statement so document and column can never diverge on this transition.
        String sql = "UPDATE conversation_memories SET conversation_state = ?, "
                + "data = jsonb_set(data, '{conversationState}', to_jsonb(?::text)) "
                + "WHERE id = ?::uuid AND conversation_state = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, target.name());
            ps.setString(2, target.name());
            ps.setString(3, conversationId);
            ps.setString(4, expected.name());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to compare-and-set conversation state", e);
        }
    }

    @Override
    public List<String> findConversationIdsByState(ConversationState state) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT id FROM conversation_memories WHERE conversation_state = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getString("id"));
                }
                return ids;
            }
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to find conversations by state", e);
        }
    }

    @Override
    public void clearHitlBookmark(String conversationId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "UPDATE conversation_memories SET data = data "
                + "- 'hitlPausedWorkflowId' - 'hitlPausedAbsoluteTaskIndex' - 'hitlPausedAt' "
                + "- 'hitlPauseReason' - 'hitlTimeoutPolicy' - 'hitlApprovalTimeout' "
                + "WHERE id = ?::uuid";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to clear HITL bookmark", e);
        }
    }

    /**
     * Projected columns for pending-approval summaries — never the full document.
     */
    private static final String PENDING_SUMMARY_SELECT = "SELECT id, AGENT_ID, data->>'userId' AS user_id, data->'hitlPausedAt' AS paused_at_json, "
            + "data->>'hitlPauseReason' AS pause_reason, data->>'hitlTimeoutPolicy' AS timeout_policy, "
            + "data->>'hitlApprovalTimeout' AS approval_timeout "
            + "FROM conversation_memories WHERE conversation_state = ?";

    @Override
    public List<ai.labs.eddi.engine.model.PendingApprovalSummary> findPendingApprovalSummaries(int limit)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        // Single bounded query with JSONB field extraction — this listing is
        // polled and backs the crash-recovery sweep; deserializing full multi-MB
        // documents here violates the interface's projection contract.
        String sql = PENDING_SUMMARY_SELECT + " LIMIT ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ConversationState.AWAITING_HUMAN.name());
            ps.setInt(2, limit);
            return readPendingSummaries(ps);
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to list pending approvals", e);
        }
    }

    @Override
    public List<ai.labs.eddi.engine.model.PendingApprovalSummary> findPendingApprovalSummaries(String ownerUserId, int limit)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        // Owner filter INSIDE the query: the limit applies after the restriction,
        // so a user's inbox is complete even behind a large global backlog.
        String sql = PENDING_SUMMARY_SELECT + " AND data->>'userId' = ? LIMIT ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ConversationState.AWAITING_HUMAN.name());
            ps.setString(2, ownerUserId);
            ps.setInt(3, limit);
            return readPendingSummaries(ps);
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to list pending approvals for owner", e);
        }
    }

    private List<ai.labs.eddi.engine.model.PendingApprovalSummary> readPendingSummaries(PreparedStatement ps)
            throws SQLException {
        List<ai.labs.eddi.engine.model.PendingApprovalSummary> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("id");
                var summary = new ai.labs.eddi.engine.model.PendingApprovalSummary(
                        id, rs.getString("AGENT_ID"), rs.getString("user_id"),
                        parseInstantJson(id, rs.getString("paused_at_json")),
                        rs.getString("pause_reason"), rs.getString("timeout_policy"));
                summary.setApprovalTimeout(rs.getString("approval_timeout"));
                out.add(summary);
            }
        }
        return out;
    }

    /**
     * Deserializes the raw JSON value of {@code hitlPausedAt} ({@code data->},
     * keeping the JSON representation) through the SAME mapper that serialized the
     * snapshot — correct for both ISO-string and numeric-timestamp configurations.
     */
    private java.time.Instant parseInstantJson(String conversationId, String rawJson) {
        if (rawJson == null || rawJson.isBlank() || "null".equals(rawJson)) {
            return null;
        }
        try {
            return jsonSerialization.deserialize(rawJson, java.time.Instant.class);
        } catch (Exception e) {
            LOGGER.warnf("Unparseable hitlPausedAt for conversation %s: %s", conversationId, e.getMessage());
            return null;
        }
    }

    // -- IResourceStore<ConversationMemorySnapshot> methods --

    @Override
    public ConversationMemorySnapshot readIncludingDeleted(String id, Integer version) {
        return loadConversationMemorySnapshot(id);
    }

    @Override
    public IResourceStore.IResourceId create(ConversationMemorySnapshot content) {
        String id = storeConversationMemorySnapshot(content);
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return 0;
            }
        };
    }

    @Override
    public ConversationMemorySnapshot read(String id, Integer version) {
        return loadConversationMemorySnapshot(id);
    }

    @Override
    public Integer update(String id, Integer version, ConversationMemorySnapshot content) {
        storeConversationMemorySnapshot(content);
        return 0;
    }

    @Override
    public void delete(String id, Integer version) {
        deleteConversationMemorySnapshot(id);
    }

    @Override
    public void deleteAllPermanently(String id) {
        deleteConversationMemorySnapshot(id);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return 0;
            }
        };
    }

    // === GDPR ===

    @Override
    public List<String> getConversationIdsByUserId(String userId) {
        ensureSchema();
        String sql = "SELECT id FROM conversation_memories WHERE data->>'userId' = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getString("id"));
                }
                return ids;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find conversations by userId", e);
        }
    }

    @Override
    public long deleteConversationsByUserId(String userId) {
        ensureSchema();
        String sql = "DELETE FROM conversation_memories WHERE data->>'userId' = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete conversations by userId", e);
        }
    }

    /**
     * Fix deserialized context types — same logic as the MongoDB implementation.
     * When deserialized from JSON, Context objects may be represented as
     * LinkedHashMap.
     */
    @SuppressWarnings("unchecked")
    private void fixContextTypes(ConversationMemorySnapshot snapshot) {
        for (var conversationStep : snapshot.getConversationSteps()) {
            for (var aWorkflow : conversationStep.getWorkflows()) {
                for (var lifecycleTask : aWorkflow.getLifecycleTasks()) {
                    if (lifecycleTask.getKey().startsWith("context")) {
                        var result = lifecycleTask.getResult();
                        if (result instanceof LinkedHashMap) {
                            var map = (LinkedHashMap<String, Object>) result;
                            var context = new Context(valueOf(map.get("type").toString()), map.get("value"));
                            lifecycleTask.setResult(context);
                        }
                    }
                }
            }
        }
    }
}
