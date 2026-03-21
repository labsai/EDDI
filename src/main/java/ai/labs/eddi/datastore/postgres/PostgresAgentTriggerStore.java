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
            CREATE TABLE IF NOT EXISTS bot_triggers (
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
        if (schemaInitialized) return;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            schemaInitialized = true;
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize bot_triggers table", e);
        }
    }

    @Override
    public List<AgentTriggerConfiguration> readAllBotTriggers() throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT data FROM bot_triggers";
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
    public AgentTriggerConfiguration readBotTrigger(String intent)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT data FROM bot_triggers WHERE intent = ?";
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
    public void updateBotTrigger(String intent, AgentTriggerConfiguration AgentTriggerConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        ensureSchema();
        String sql = "UPDATE bot_triggers SET data = ?::jsonb WHERE intent = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jsonSerialization.serialize(AgentTriggerConfiguration));
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
    public void createAgentTrigger(AgentTriggerConfiguration AgentTriggerConfiguration)
            throws IResourceStore.ResourceAlreadyExistsException, IResourceStore.ResourceStoreException {
        ensureSchema();
        // Check existence
        String check = "SELECT 1 FROM bot_triggers WHERE intent = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(check)) {
            ps.setString(1, AgentTriggerConfiguration.getIntent());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new IResourceStore.ResourceAlreadyExistsException(
                            String.format("AgentTriggerConfiguration with intent=%s already exists",
                                    AgentTriggerConfiguration.getIntent()));
                }
            }
        } catch (IResourceStore.ResourceAlreadyExistsException e) {
            throw e;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }

        String sql = "INSERT INTO bot_triggers (intent, data) VALUES (?, ?::jsonb)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, AgentTriggerConfiguration.getIntent());
            ps.setString(2, jsonSerialization.serialize(AgentTriggerConfiguration));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void deleteBotTrigger(String intent) {
        ensureSchema();
        String sql = "DELETE FROM bot_triggers WHERE intent = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, intent);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to delete Agent trigger intent=" + intent, e);
        }
    }
}
