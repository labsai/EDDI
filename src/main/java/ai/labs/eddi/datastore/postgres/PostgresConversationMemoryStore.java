package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.memory.model.ConversationState;
import io.quarkus.arc.DefaultBean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    private final DataSource dataSource;
    private final IJsonSerialization jsonSerialization;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresConversationMemoryStore(DataSource dataSource, IJsonSerialization jsonSerialization) {
        this.dataSource = dataSource;
        this.jsonSerialization = jsonSerialization;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
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
                try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
                try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
    public ConversationMemorySnapshot loadConversationMemorySnapshot(String conversationId) {
        String sql = "SELECT data FROM conversation_memories WHERE id = ?::uuid";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ConversationMemorySnapshot snapshot = jsonSerialization.deserialize(rs.getString("data"), ConversationMemorySnapshot.class);
                    fixContextTypes(snapshot);
                    snapshot.setConversationId(conversationId);
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
        String sql = "SELECT data FROM conversation_memories " + "WHERE AGENT_ID = ? AND AGENT_VERSION = ? AND conversation_state != ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setInt(2, agentVersion);
            ps.setString(3, ENDED.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<ConversationMemorySnapshot> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(jsonSerialization.deserialize(rs.getString("data"), ConversationMemorySnapshot.class));
                }
                return results;
            }
        } catch (IOException | SQLException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void setConversationState(String conversationId, ConversationState conversationState) {
        ensureSchema();
        String sql = "UPDATE conversation_memories SET conversation_state = ? WHERE id = ?::uuid";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationState.name());
            ps.setString(2, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set conversation state", e);
        }
    }

    @Override
    public void deleteConversationMemorySnapshot(String conversationId) {
        ensureSchema();
        String sql = "DELETE FROM conversation_memories WHERE id = ?::uuid";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
        String sql = "SELECT COUNT(*) FROM conversation_memories " + "WHERE AGENT_ID = ? AND AGENT_VERSION = ? AND conversation_state != ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setInt(2, agentVersion);
            ps.setString(3, ENDED.toString());
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
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
