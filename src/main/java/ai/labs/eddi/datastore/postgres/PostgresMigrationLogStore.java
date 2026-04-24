/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.migration.IMigrationLogStore;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import jakarta.enterprise.inject.Instance;
import javax.sql.DataSource;
import jakarta.inject.Inject;
import java.sql.*;

/**
 * PostgreSQL implementation of {@link IMigrationLogStore}. Tracks migration
 * status in a simple table.
 */
@ApplicationScoped
@DefaultBean
public class PostgresMigrationLogStore implements IMigrationLogStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresMigrationLogStore.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS migration_log (
                id BIGSERIAL PRIMARY KEY,
                name VARCHAR(512) NOT NULL UNIQUE,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresMigrationLogStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            schemaInitialized = true;
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize migration_log table", e);
        }
    }

    @Override
    public MigrationLog readMigrationLog(String name) {
        ensureSchema();
        String sql = "SELECT name, created_at FROM migration_log WHERE name = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new MigrationLog(rs.getString("name"));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to read migration log for name=" + name, e);
        }
        return null;
    }

    @Override
    public void createMigrationLog(MigrationLog migrationLog) {
        ensureSchema();
        String sql = "INSERT INTO migration_log (name) VALUES (?) ON CONFLICT (name) DO NOTHING";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, migrationLog.getName());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to create migration log", e);
        }
    }
}
