package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.deployment.IDeploymentStorage;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.model.Deployment.Environment;
import io.quarkus.arc.profile.IfBuildProfile;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL implementation of {@link IDeploymentStorage}.
 * <p>
 * Uses a dedicated {@code deployments} table with composite PK.
 */
@ApplicationScoped
@IfBuildProfile("postgres")
public class PostgresDeploymentStorage implements IDeploymentStorage {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS deployments (
                environment TEXT NOT NULL,
                bot_id TEXT NOT NULL,
                bot_version INTEGER NOT NULL,
                deployment_status TEXT NOT NULL,
                PRIMARY KEY (environment, bot_id, bot_version)
            )
            """;

    private final DataSource dataSource;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresDeploymentStorage(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) return;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize deployments table", e);
        }
    }

    @Override
    public void setDeploymentInfo(String environment, String botId, Integer botVersion,
                                   DeploymentInfo.DeploymentStatus deploymentStatus) {
        ensureSchema();
        String sql = """
                INSERT INTO deployments (environment, bot_id, bot_version, deployment_status)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (environment, bot_id, bot_version) DO UPDATE
                SET deployment_status = EXCLUDED.deployment_status
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, environment);
            ps.setString(2, botId);
            ps.setInt(3, botVersion);
            ps.setString(4, deploymentStatus.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set deployment info", e);
        }
    }

    @Override
    public DeploymentInfo readDeploymentInfo(String environment, String botId, Integer botVersion)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT environment, bot_id, bot_version, deployment_status FROM deployments " +
                "WHERE environment = ? AND bot_id = ? AND bot_version = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, environment);
            ps.setString(2, botId);
            ps.setInt(3, botVersion);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return toDeploymentInfo(rs);
                }
                return null;
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
    public List<DeploymentInfo> readDeploymentInfos(String deploymentStatus)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        List<DeploymentInfo> results = new ArrayList<>();
        String sql = deploymentStatus != null
                ? "SELECT environment, bot_id, bot_version, deployment_status FROM deployments WHERE deployment_status = ?"
                : "SELECT environment, bot_id, bot_version, deployment_status FROM deployments";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (deploymentStatus != null) {
                ps.setString(1, deploymentStatus);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(toDeploymentInfo(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to read deployment infos", e);
        }
    }

    private DeploymentInfo toDeploymentInfo(ResultSet rs) throws SQLException {
        DeploymentInfo info = new DeploymentInfo();
        info.setEnvironment(Environment.valueOf(rs.getString("environment")));
        info.setBotId(rs.getString("bot_id"));
        info.setBotVersion(rs.getInt("bot_version"));
        info.setDeploymentStatus(DeploymentInfo.DeploymentStatus.valueOf(rs.getString("deployment_status")));
        return info;
    }
}
