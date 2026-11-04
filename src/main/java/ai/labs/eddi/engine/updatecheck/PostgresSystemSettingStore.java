/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.updatecheck;

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
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link ISystemSettingStore}: one row per key in
 * {@code system_settings}. Schema is created on first access, following
 * {@code PostgresWorkspaceSettingsStore}.
 */
@ApplicationScoped
@DefaultBean
public class PostgresSystemSettingStore implements ISystemSettingStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresSystemSettingStore.class);

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS system_settings (
                setting_key VARCHAR(255) PRIMARY KEY,
                setting_value TEXT,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_by TEXT
            )
            """;

    static final String UPSERT = """
            INSERT INTO system_settings (setting_key, setting_value, updated_at, updated_by)
            VALUES (?, ?, now(), ?)
            ON CONFLICT (setting_key) DO UPDATE SET
                setting_value = EXCLUDED.setting_value,
                updated_at = EXCLUDED.updated_at,
                updated_by = EXCLUDED.updated_by
            """;

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresSystemSettingStore(Instance<DataSource> dataSourceInstance) {
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
                LOGGER.info("PostgresSystemSettingStore initialized (table=system_settings)");
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to initialize the system_settings table", e);
            }
        }
    }

    @Override
    public Optional<String> read(String key) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT setting_value FROM system_settings WHERE setting_key = ?")) {
            statement.setString(1, key);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.ofNullable(row.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read system setting " + key, e);
        }
    }

    @Override
    public void write(String key, String value, String updatedBy) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.setString(3, updatedBy);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write system setting " + key, e);
        }
    }
}
