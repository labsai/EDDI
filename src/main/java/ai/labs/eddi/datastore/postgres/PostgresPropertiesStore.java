package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.properties.IPropertiesStore;
import ai.labs.eddi.configs.properties.model.Properties;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.*;

/**
 * PostgreSQL implementation of {@link IPropertiesStore}. Stores user properties
 * as JSON in a key-value table.
 */
@ApplicationScoped
@IfBuildProfile("postgres")
public class PostgresPropertiesStore implements IPropertiesStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresPropertiesStore.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS properties (
                user_id VARCHAR(255) PRIMARY KEY,
                data JSONB NOT NULL DEFAULT '{}'::jsonb,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

    private final DataSource dataSource;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresPropertiesStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            schemaInitialized = true;
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize properties table", e);
        }
    }

    @Override
    public Properties readProperties(String userId) {
        ensureSchema();
        String sql = "SELECT data FROM properties WHERE user_id = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString("data");
                    // Parse the JSON into Properties (which extends LinkedHashMap)
                    Properties props = new Properties();
                    if (json != null && !json.isEmpty() && !json.equals("{}")) {
                        @SuppressWarnings("unchecked")
                        var map = (java.util.Map<String, Object>) new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                                java.util.Map.class);
                        if (map != null) {
                            props.putAll(map);
                        }
                    }
                    return props;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read properties for userId=" + userId, e);
        }
        return null;
    }

    @Override
    public void mergeProperties(String userId, Properties properties) {
        ensureSchema();
        if (properties == null || properties.isEmpty())
            return;

        Properties existing = readProperties(userId);
        Properties current = (existing != null) ? existing : new Properties();
        boolean create = (existing == null);
        current.putAll(properties);

        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(current);
            if (create) {
                String sql = "INSERT INTO properties (user_id, data) VALUES (?, ?::jsonb)";
                try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, userId);
                    ps.setString(2, json);
                    ps.executeUpdate();
                }
            } else {
                String sql = "UPDATE properties SET data = ?::jsonb WHERE user_id = ?";
                try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, json);
                    ps.setString(2, userId);
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to merge properties for userId=" + userId, e);
        }
    }

    @Override
    public void deleteProperties(String userId) {
        ensureSchema();
        String sql = "DELETE FROM properties WHERE user_id = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to delete properties for userId=" + userId, e);
        }
    }
}
