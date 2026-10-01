/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.settings;

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
import java.sql.Types;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IWorkspaceSettingsStore}: one row per
 * tenant in {@code workspace_settings}, every setting nullable because null is
 * how the model says "not set". Schema is created on first access, following
 * {@code PostgresConnectionSettingsStore}.
 */
@ApplicationScoped
@DefaultBean
public class PostgresWorkspaceSettingsStore implements IWorkspaceSettingsStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresWorkspaceSettingsStore.class);

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS workspace_settings (
                tenant_id VARCHAR(255) PRIMARY KEY,
                default_space TEXT,
                legacy_visibility TEXT,
                updated_at TIMESTAMPTZ,
                updated_by TEXT
            )
            """;

    static final String UPSERT = """
            INSERT INTO workspace_settings (tenant_id, default_space, legacy_visibility, updated_at, updated_by)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id) DO UPDATE SET
                default_space = EXCLUDED.default_space,
                legacy_visibility = EXCLUDED.legacy_visibility,
                updated_at = EXCLUDED.updated_at,
                updated_by = EXCLUDED.updated_by
            """;

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresWorkspaceSettingsStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        synchronized (this) {
            if (schemaInitialized) {
                return;
            }
            try (Connection connection = dataSourceInstance.get().getConnection(); Statement statement = connection.createStatement()) {
                statement.execute(CREATE_TABLE);
                schemaInitialized = true;
                LOGGER.info("PostgresWorkspaceSettingsStore initialized (table=workspace_settings)");
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to initialize the workspace_settings table", e);
            }
        }
    }

    @Override
    public Optional<StoredWorkspaceSettings> read(String tenantId) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT default_space, legacy_visibility, updated_at, updated_by FROM workspace_settings WHERE tenant_id = ?")) {
            statement.setString(1, tenantId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                Timestamp updatedAt = row.getTimestamp("updated_at");
                return Optional.of(new StoredWorkspaceSettings(row.getString("default_space"), row.getString("legacy_visibility"),
                        updatedAt == null ? null : updatedAt.toInstant(), row.getString("updated_by")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read workspace settings for tenant " + tenantId, e);
        }
    }

    @Override
    public void write(String tenantId, StoredWorkspaceSettings settings) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, tenantId);
            statement.setString(2, settings.defaultSpace());
            statement.setString(3, settings.legacyVisibility());
            if (settings.updatedAt() == null) {
                statement.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                statement.setTimestamp(4, Timestamp.from(settings.updatedAt()));
            }
            statement.setString(5, settings.updatedBy());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write workspace settings for tenant " + tenantId, e);
        }
    }
}
