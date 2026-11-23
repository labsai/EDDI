/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams.refs;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL {@link ITeamsConversationReferenceStore}: table
 * {@code teams_conversation_references}, primary key (app_id, conversation_id).
 *
 * @since 6.7.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresTeamsConversationReferenceStore implements ITeamsConversationReferenceStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresTeamsConversationReferenceStore.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS teams_conversation_references (
                app_id VARCHAR(64) NOT NULL,
                conversation_id VARCHAR(512) NOT NULL,
                conversation_type VARCHAR(32),
                service_url VARCHAR(1024) NOT NULL,
                tenant_id VARCHAR(64),
                bot_id VARCHAR(256),
                user_id VARCHAR(256),
                updated_at TIMESTAMP NOT NULL,
                PRIMARY KEY (app_id, conversation_id)
            )
            """;

    private static final String CREATE_USER_INDEX = "CREATE INDEX IF NOT EXISTS idx_teams_ref_user ON teams_conversation_references (user_id)";

    private static final String UPSERT = """
            INSERT INTO teams_conversation_references
                (app_id, conversation_id, conversation_type, service_url, tenant_id, bot_id, user_id, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (app_id, conversation_id) DO UPDATE SET
                conversation_type = EXCLUDED.conversation_type,
                service_url = EXCLUDED.service_url,
                tenant_id = EXCLUDED.tenant_id,
                bot_id = EXCLUDED.bot_id,
                user_id = EXCLUDED.user_id,
                updated_at = EXCLUDED.updated_at
            """;

    private static final String COLUMNS = "app_id, conversation_id, conversation_type, service_url, tenant_id, bot_id, user_id, updated_at";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresTeamsConversationReferenceStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void createSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection connection = dataSourceInstance.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            statement.execute(CREATE_USER_INDEX);
            schemaInitialized = true;
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to create the teams_conversation_references schema");
        }
    }

    @Override
    public void upsert(TeamsConversationReference reference) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, reference.appId());
            statement.setString(2, reference.conversationId());
            statement.setString(3, reference.conversationType());
            statement.setString(4, reference.serviceUrl());
            statement.setString(5, reference.tenantId());
            statement.setString(6, reference.botId());
            statement.setString(7, reference.userId());
            statement.setTimestamp(8, Timestamp.from(reference.updatedAt() != null ? reference.updatedAt() : Instant.now()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store the Teams conversation reference", e);
        }
    }

    @Override
    public Optional<TeamsConversationReference> find(String appId, String conversationId) {
        createSchema();
        String sql = "SELECT " + COLUMNS + " FROM teams_conversation_references WHERE app_id = ? AND conversation_id = ?";
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, appId);
            statement.setString(2, conversationId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(toReference(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the Teams conversation reference", e);
        }
    }

    @Override
    public void delete(String appId, String conversationId) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection
                        .prepareStatement("DELETE FROM teams_conversation_references WHERE app_id = ? AND conversation_id = ?")) {
            statement.setString(1, appId);
            statement.setString(2, conversationId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete the Teams conversation reference", e);
        }
    }

    @Override
    public List<TeamsConversationReference> findByUser(String userId) {
        List<TeamsConversationReference> result = new ArrayList<>();
        if (userId == null) {
            return result;
        }
        createSchema();
        String sql = "SELECT " + COLUMNS + " FROM teams_conversation_references WHERE user_id = ?";
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(toReference(rows));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the Teams conversation references", e);
        }
    }

    @Override
    public long deleteByUser(String userId) {
        if (userId == null) {
            return 0;
        }
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM teams_conversation_references WHERE user_id = ?")) {
            statement.setString(1, userId);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete the Teams conversation references", e);
        }
    }

    private static TeamsConversationReference toReference(ResultSet rows) throws SQLException {
        Timestamp updated = rows.getTimestamp("updated_at");
        return new TeamsConversationReference(rows.getString("app_id"), rows.getString("conversation_id"), rows.getString("conversation_type"),
                rows.getString("service_url"), rows.getString("tenant_id"), rows.getString("bot_id"), rows.getString("user_id"),
                updated != null ? updated.toInstant() : null);
    }
}
