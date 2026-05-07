/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationCheckpointStore;
import ai.labs.eddi.engine.memory.model.MemoryCheckpoint;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL implementation of {@link IConversationCheckpointStore}.
 * <p>
 * Stores checkpoints as JSONB with extracted indexed columns for efficient
 * queries. Pruning removes oldest checkpoints beyond the retention limit.
 *
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresConversationCheckpointStore implements IConversationCheckpointStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresConversationCheckpointStore.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS conversation_checkpoints (
                checkpoint_id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                parent_conversation_id TEXT,
                step_index INTEGER NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                triggered_by TEXT,
                triggered_by_class TEXT,
                data JSONB NOT NULL
            )
            """;

    private static final String CREATE_INDEX_CONV = "CREATE INDEX IF NOT EXISTS idx_ckpt_conv ON conversation_checkpoints (conversation_id, created_at DESC)";

    private final Instance<DataSource> dataSourceInstance;
    private final IJsonSerialization jsonSerialization;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresConversationCheckpointStore(Instance<DataSource> dataSourceInstance, IJsonSerialization jsonSerialization) {
        this.dataSourceInstance = dataSourceInstance;
        this.jsonSerialization = jsonSerialization;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            stmt.execute(CREATE_INDEX_CONV);
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize conversation_checkpoints table", e);
        }
    }

    @Override
    public void create(MemoryCheckpoint checkpoint) {
        ensureSchema();
        String sql = """
                INSERT INTO conversation_checkpoints
                (checkpoint_id, conversation_id, parent_conversation_id, step_index, created_at, triggered_by, triggered_by_class, data)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, checkpoint.checkpointId());
            ps.setString(2, checkpoint.conversationId());
            ps.setString(3, checkpoint.parentConversationId());
            ps.setInt(4, checkpoint.stepIndex());
            ps.setTimestamp(5, Timestamp.from(checkpoint.createdAt()));
            ps.setString(6, checkpoint.triggeredBy());
            ps.setString(7, checkpoint.triggeredByClass());
            ps.setString(8, jsonSerialization.serialize(checkpoint));
            ps.executeUpdate();
        } catch (IOException | SQLException e) {
            throw new RuntimeException("Failed to create checkpoint", e);
        }
    }

    @Override
    public List<MemoryCheckpoint> findByConversationId(String conversationId, int limit) {
        ensureSchema();
        String sql = "SELECT data FROM conversation_checkpoints WHERE conversation_id = ? ORDER BY created_at DESC LIMIT ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<MemoryCheckpoint> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(jsonSerialization.deserialize(rs.getString("data"), MemoryCheckpoint.class));
                }
                return results;
            }
        } catch (IOException | SQLException e) {
            throw new RuntimeException("Failed to find checkpoints by conversationId", e);
        }
    }

    @Override
    public MemoryCheckpoint findById(String checkpointId) {
        ensureSchema();
        String sql = "SELECT data FROM conversation_checkpoints WHERE checkpoint_id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, checkpointId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return jsonSerialization.deserialize(rs.getString("data"), MemoryCheckpoint.class);
                }
                return null;
            }
        } catch (IOException | SQLException e) {
            throw new RuntimeException("Failed to find checkpoint by id", e);
        }
    }

    @Override
    public void deleteById(String checkpointId) {
        ensureSchema();
        String sql = "DELETE FROM conversation_checkpoints WHERE checkpoint_id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, checkpointId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete checkpoint", e);
        }
    }

    @Override
    public int pruneOldest(String conversationId, int keepCount) {
        ensureSchema();
        // Delete all checkpoints except the keepCount newest ones
        String sql = """
                DELETE FROM conversation_checkpoints
                WHERE conversation_id = ?
                AND checkpoint_id NOT IN (
                    SELECT checkpoint_id FROM conversation_checkpoints
                    WHERE conversation_id = ?
                    ORDER BY created_at DESC
                    LIMIT ?
                )
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.setString(2, conversationId);
            ps.setInt(3, keepCount);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                LOGGER.debugf("Pruned %d checkpoints for conversation '%s' (keeping %d)", (Object) deleted, conversationId, keepCount);
            }
            return deleted;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to prune checkpoints", e);
        }
    }

    @Override
    public long deleteByConversationId(String conversationId) {
        ensureSchema();
        String sql = "DELETE FROM conversation_checkpoints WHERE conversation_id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete checkpoints by conversationId", e);
        }
    }
}
