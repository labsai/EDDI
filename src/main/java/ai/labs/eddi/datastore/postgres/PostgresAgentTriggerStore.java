package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.triggermanagement.IAgentTriggerStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL implementation of {@link IAgentTriggerStore}.
 */
@ApplicationScoped
@IfBuildProfile("postgres")
public class PostgresAgentTriggerStore implements IAgentTriggerStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresAgentTriggerStore.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS agent_triggers (
                intent VARCHAR(255) PRIMARY KEY,
                data JSONB NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

    private final DataSource dataSource;
    private final IJsonSerialization jsonSerialization;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresAgentTriggerStore(DataSource dataSource,
            IJsonSerialization jsonSerialization) {
        this.dataSource = dataSource;
        this.jsonSerialization = jsonSerialization;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            schemaInitialized = true;
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize agent_triggers table", e);
        }
    }

    @Override
    public List<AgentTriggerConfiguration> readAllAgentTriggers() throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT data FROM agent_triggers";
        List<AgentTriggerConfiguration> triggers = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                triggers.add(jsonSerialization.deserialize(
                        rs.getString("data"), AgentTriggerConfiguration.class));
            }
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
        return triggers;
    }

    @Override
    public AgentTriggerConfiguration readAgentTrigger(String intent)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT data FROM agent_triggers WHERE intent = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, intent);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return jsonSerialization.deserialize(
                            rs.getString("data"), AgentTriggerConfiguration.class);
                }
            }
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
        throw new IResourceStore.ResourceNotFoundException(
                String.format("AgentTriggerConfiguration with intent=%s does not exist", intent));
    }

    @Override
    public void updateAgentTrigger(String intent, AgentTriggerConfiguration agentTriggerConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        ensureSchema();
        String sql = "UPDATE agent_triggers SET data = ?::jsonb WHERE intent = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jsonSerialization.serialize(agentTriggerConfiguration));
            ps.setString(2, intent);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new IResourceStore.ResourceNotFoundException(
                        String.format("AgentTriggerConfiguration with intent=%s does not exist", intent));
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void createAgentTrigger(AgentTriggerConfiguration agentTriggerConfiguration)
            throws IResourceStore.ResourceAlreadyExistsException, IResourceStore.ResourceStoreException {
        ensureSchema();
        // Check existence
        String check = "SELECT 1 FROM agent_triggers WHERE intent = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(check)) {
            ps.setString(1, agentTriggerConfiguration.getIntent());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new IResourceStore.ResourceAlreadyExistsException(
                            String.format("AgentTriggerConfiguration with intent=%s already exists",
                                    agentTriggerConfiguration.getIntent()));
                }
            }
        } catch (IResourceStore.ResourceAlreadyExistsException e) {
            throw e;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }

        String sql = "INSERT INTO agent_triggers (intent, data) VALUES (?, ?::jsonb)";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentTriggerConfiguration.getIntent());
            ps.setString(2, jsonSerialization.serialize(agentTriggerConfiguration));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void deleteAgentTrigger(String intent) {
        ensureSchema();
        String sql = "DELETE FROM agent_triggers WHERE intent = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, intent);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to delete Agent trigger intent=" + intent, e);
        }
    }
}
