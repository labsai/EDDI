package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.botmanagement.IBotTriggerStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.model.BotTriggerConfiguration;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL implementation of {@link IBotTriggerStore}.
 */
@ApplicationScoped
@IfBuildProfile("postgres")
public class PostgresBotTriggerStore implements IBotTriggerStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresBotTriggerStore.class);

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
    public PostgresBotTriggerStore(DataSource dataSource,
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
    public List<BotTriggerConfiguration> readAllBotTriggers() throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT data FROM bot_triggers";
        List<BotTriggerConfiguration> triggers = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                triggers.add(jsonSerialization.deserialize(
                        rs.getString("data"), BotTriggerConfiguration.class));
            }
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
        return triggers;
    }

    @Override
    public BotTriggerConfiguration readBotTrigger(String intent)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT data FROM bot_triggers WHERE intent = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, intent);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return jsonSerialization.deserialize(
                            rs.getString("data"), BotTriggerConfiguration.class);
                }
            }
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
        throw new IResourceStore.ResourceNotFoundException(
                String.format("BotTriggerConfiguration with intent=%s does not exist", intent));
    }

    @Override
    public void updateBotTrigger(String intent, BotTriggerConfiguration botTriggerConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        ensureSchema();
        String sql = "UPDATE bot_triggers SET data = ?::jsonb WHERE intent = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jsonSerialization.serialize(botTriggerConfiguration));
            ps.setString(2, intent);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new IResourceStore.ResourceNotFoundException(
                        String.format("BotTriggerConfiguration with intent=%s does not exist", intent));
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void createBotTrigger(BotTriggerConfiguration botTriggerConfiguration)
            throws IResourceStore.ResourceAlreadyExistsException, IResourceStore.ResourceStoreException {
        ensureSchema();
        // Check existence
        String check = "SELECT 1 FROM bot_triggers WHERE intent = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(check)) {
            ps.setString(1, botTriggerConfiguration.getIntent());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new IResourceStore.ResourceAlreadyExistsException(
                            String.format("BotTriggerConfiguration with intent=%s already exists",
                                    botTriggerConfiguration.getIntent()));
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
            ps.setString(1, botTriggerConfiguration.getIntent());
            ps.setString(2, jsonSerialization.serialize(botTriggerConfiguration));
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
            LOGGER.error("Failed to delete bot trigger intent=" + intent, e);
        }
    }
}
