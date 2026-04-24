/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import jakarta.enterprise.inject.Instance;
import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL implementation of {@link IUserConversationStore}.
 */
@ApplicationScoped
@DefaultBean
public class PostgresUserConversationStore implements IUserConversationStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresUserConversationStore.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS user_conversations (
                intent VARCHAR(255) NOT NULL,
                user_id VARCHAR(255) NOT NULL,
                data JSONB NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (intent, user_id)
            )
            """;

    private final Instance<DataSource> dataSourceInstance;
    private final IJsonSerialization jsonSerialization;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresUserConversationStore(Instance<DataSource> dataSourceInstance, IJsonSerialization jsonSerialization) {
        this.dataSourceInstance = dataSourceInstance;
        this.jsonSerialization = jsonSerialization;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            schemaInitialized = true;
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize user_conversations table", e);
        }
    }

    @Override
    public UserConversation readUserConversation(String intent, String userId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT data FROM user_conversations WHERE intent = ? AND user_id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, intent);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return jsonSerialization.deserialize(rs.getString("data"), UserConversation.class);
                }
            }
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
        return null;
    }

    @Override
    public void createUserConversation(UserConversation userConversation)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceAlreadyExistsException {
        ensureSchema();

        // Check if already exists
        UserConversation existing = readUserConversation(userConversation.getIntent(), userConversation.getUserId());
        if (existing != null) {
            throw new IResourceStore.ResourceAlreadyExistsException(
                    String.format("UserConversation with intent=%s does already exist", userConversation.getIntent()));
        }

        String sql = "INSERT INTO user_conversations (intent, user_id, data) VALUES (?, ?, ?::jsonb)";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userConversation.getIntent());
            ps.setString(2, userConversation.getUserId());
            ps.setString(3, jsonSerialization.serialize(userConversation));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void deleteUserConversation(String intent, String userId) {
        ensureSchema();
        String sql = "DELETE FROM user_conversations WHERE intent = ? AND user_id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, intent);
            ps.setString(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to delete user conversation intent=" + intent, e);
        }
    }
    // === GDPR ===

    @Override
    public long deleteAllForUser(String userId) {
        ensureSchema();
        String sql = "DELETE FROM user_conversations WHERE user_id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to delete all user conversations", e);
            return 0;
        }
    }

    @Override
    public List<UserConversation> getAllForUser(String userId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT data FROM user_conversations WHERE user_id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                var results = new ArrayList<UserConversation>();
                while (rs.next()) {
                    results.add(jsonSerialization.deserialize(rs.getString("data"), UserConversation.class));
                }
                return results;
            }
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }
}
