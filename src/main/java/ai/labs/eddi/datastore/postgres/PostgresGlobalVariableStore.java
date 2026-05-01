/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.variables.IGlobalVariableStore;
import ai.labs.eddi.configs.variables.model.GlobalVariable;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * PostgreSQL implementation of {@link IGlobalVariableStore}.
 * <p>
 * Uses a dedicated {@code global_variables} table with a composite primary key
 * {@code (tenant_id, key)}. Schema is auto-created on first access.
 *
 * <pre>
 * CREATE TABLE IF NOT EXISTS global_variables (
 *     tenant_id   VARCHAR(255) NOT NULL DEFAULT 'default',
 *     "key"       VARCHAR(255) NOT NULL,
 *     "value"     TEXT NOT NULL,
 *     description TEXT,
 *     exportable  BOOLEAN DEFAULT TRUE,
 *     PRIMARY KEY (tenant_id, "key")
 * );
 * </pre>
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresGlobalVariableStore implements IGlobalVariableStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresGlobalVariableStore.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS global_variables (
                tenant_id VARCHAR(255) NOT NULL DEFAULT 'default',
                "key" VARCHAR(255) NOT NULL,
                "value" TEXT NOT NULL,
                description TEXT,
                exportable BOOLEAN DEFAULT TRUE,
                PRIMARY KEY (tenant_id, "key")
            )
            """;

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresGlobalVariableStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSourceInstance.get().getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            schemaInitialized = true;
            LOGGER.info("PostgresGlobalVariableStore initialized (table=global_variables)");
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize global_variables table", e);
        }
    }

    @Override
    public Map<String, String> getAll(String tenantId) {
        ensureSchema();
        Map<String, String> result = new LinkedHashMap<>();
        String sql = "SELECT \"key\", \"value\" FROM global_variables WHERE tenant_id = ? ORDER BY \"key\"";
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("key"), rs.getString("value"));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to list all global variables for tenant: " + tenantId, e);
        }
        return result;
    }

    @Override
    public GlobalVariable get(String tenantId, String key) {
        ensureSchema();
        String sql = "SELECT tenant_id, \"key\", \"value\", description, exportable FROM global_variables WHERE tenant_id = ? AND \"key\" = ?";
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return toGlobalVariable(rs);
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to get global variable: " + tenantId + "/" + key, e);
        }
        return null;
    }

    @Override
    public void upsert(GlobalVariable variable) {
        ensureSchema();
        String sql = """
                INSERT INTO global_variables (tenant_id, "key", "value", description, exportable)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, "key") DO UPDATE
                SET "value" = EXCLUDED."value",
                    description = EXCLUDED.description,
                    exportable = EXCLUDED.exportable
                """;
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, variable.tenantId());
            ps.setString(2, variable.key());
            ps.setString(3, variable.value());
            ps.setString(4, variable.description());
            ps.setBoolean(5, variable.exportable());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to upsert global variable: " + variable.tenantId() + "/" + variable.key(), e);
        }
    }

    @Override
    public void delete(String tenantId, String key) {
        ensureSchema();
        String sql = "DELETE FROM global_variables WHERE tenant_id = ? AND \"key\" = ?";
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to delete global variable: " + tenantId + "/" + key, e);
        }
    }

    @Override
    public List<GlobalVariable> listAll(String tenantId) {
        ensureSchema();
        List<GlobalVariable> result = new ArrayList<>();
        String sql = "SELECT tenant_id, \"key\", \"value\", description, exportable FROM global_variables WHERE tenant_id = ? ORDER BY \"key\"";
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(toGlobalVariable(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to list all global variables for tenant: " + tenantId, e);
        }
        return result;
    }

    private static GlobalVariable toGlobalVariable(ResultSet rs) throws SQLException {
        return new GlobalVariable(
                rs.getString("tenant_id"),
                rs.getString("key"),
                rs.getString("value"),
                rs.getString("description"),
                rs.getBoolean("exportable"));
    }
}
