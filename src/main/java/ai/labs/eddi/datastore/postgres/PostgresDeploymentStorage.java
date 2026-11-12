/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.deployment.IDeploymentStorage;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.model.Deployment.Environment;
import io.quarkus.arc.DefaultBean;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * PostgreSQL implementation of {@link IDeploymentStorage}.
 * <p>
 * Uses a dedicated {@code deployments} table with composite PK.
 */
@ApplicationScoped
@DefaultBean
public class PostgresDeploymentStorage implements IDeploymentStorage {

    private static final Logger LOGGER = Logger.getLogger(PostgresDeploymentStorage.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS deployments (
                environment TEXT NOT NULL,
                AGENT_ID TEXT NOT NULL,
                AGENT_VERSION INTEGER NOT NULL,
                deployment_status TEXT NOT NULL,
                PRIMARY KEY (environment, AGENT_ID, AGENT_VERSION)
            )
            """;

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresDeploymentStorage(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize deployments table", e);
        }
    }

    @Override
    public void setDeploymentInfo(String environment, String agentId, Integer agentVersion, DeploymentInfo.DeploymentStatus deploymentStatus) {
        ensureSchema();
        String sql = """
                INSERT INTO deployments (environment, AGENT_ID, AGENT_VERSION, deployment_status)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (environment, AGENT_ID, AGENT_VERSION) DO UPDATE
                SET deployment_status = EXCLUDED.deployment_status
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, environment);
            ps.setString(2, agentId);
            ps.setInt(3, agentVersion);
            ps.setString(4, deploymentStatus.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set deployment info", e);
        }
    }

    @Override
    public DeploymentInfo readDeploymentInfo(String environment, String agentId, Integer agentVersion) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT environment, AGENT_ID, AGENT_VERSION, deployment_status FROM deployments "
                + "WHERE environment = ? AND AGENT_ID = ? AND AGENT_VERSION = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, environment);
            ps.setString(2, agentId);
            ps.setInt(3, agentVersion);
            try (ResultSet rs = ps.executeQuery()) {
                // An unreadable row reads as no row (toDeploymentInfo logs it).
                return rs.next() ? toDeploymentInfo(rs) : null;
            }
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to read deployment info", e);
        }
    }

    @Override
    public List<DeploymentInfo> readDeploymentInfos() throws IResourceStore.ResourceStoreException {
        return readDeploymentInfos(null);
    }

    @Override
    public List<DeploymentInfo> readDeploymentInfos(String deploymentStatus) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        List<DeploymentInfo> results = new ArrayList<>();
        String sql = deploymentStatus != null
                ? "SELECT environment, AGENT_ID, AGENT_VERSION, deployment_status FROM deployments WHERE deployment_status = ?"
                : "SELECT environment, AGENT_ID, AGENT_VERSION, deployment_status FROM deployments";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            if (deploymentStatus != null) {
                ps.setString(1, deploymentStatus);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DeploymentInfo info = toDeploymentInfo(rs);
                    if (info != null) {
                        results.add(info);
                    }
                }
            }
            return results;
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to read deployment infos", e);
        }
    }

    @Override
    public int deleteDeploymentInfos(String agentId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM deployments WHERE AGENT_ID = ?")) {
            ps.setString(1, agentId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to delete deployment infos", e);
        }
    }

    @Override
    public int deleteDeploymentInfo(String environment, String agentId, Integer agentVersion) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "DELETE FROM deployments WHERE environment = ? AND AGENT_ID = ? AND AGENT_VERSION = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, environment);
            ps.setString(2, agentId);
            ps.setInt(3, agentVersion);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to delete deployment info", e);
        }
    }

    /**
     * One row as a {@link DeploymentInfo}, or {@code null} when its status is not
     * one this release knows — logged and skipped, so one bad row cannot fail the
     * read of every deployment (the startup redeploy included). It used to go
     * through {@code Enum.valueOf}, which threw for such a row.
     * <p>
     * The environment is read the way {@code MongoDeploymentStorage} reads it,
     * through {@link Environment#fromString}: the v5 names {@code unrestricted} and
     * {@code restricted} map to {@code production}, and an unknown name falls back
     * to {@code production} with a WARN. A status is never guessed: reading an
     * unknown one as {@code deployed} or {@code undeployed} would deploy or drop an
     * agent nobody asked for.
     */
    private DeploymentInfo toDeploymentInfo(ResultSet rs) throws SQLException {
        String environment = rs.getString("environment");
        String agentId = rs.getString("AGENT_ID");
        int agentVersion = rs.getInt("AGENT_VERSION");
        String status = rs.getString("deployment_status");
        DeploymentInfo.DeploymentStatus deploymentStatus = parseStatus(status);
        if (deploymentStatus == null) {
            LOGGER.warnf("Skipping deployment row agentId=%s version=%d: unknown deployment_status '%s' (expected one of %s)",
                    sanitize(agentId), agentVersion, sanitize(status), Arrays.toString(DeploymentInfo.DeploymentStatus.values()));
            return null;
        }
        DeploymentInfo info = new DeploymentInfo();
        info.setEnvironment(Environment.fromString(environment));
        info.setAgentId(agentId);
        info.setAgentVersion(agentVersion);
        info.setDeploymentStatus(deploymentStatus);
        return info;
    }

    private static DeploymentInfo.DeploymentStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return DeploymentInfo.DeploymentStatus.valueOf(status);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
