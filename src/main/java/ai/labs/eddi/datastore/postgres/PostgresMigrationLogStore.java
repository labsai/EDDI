package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.migration.IMigrationLogStore;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import jakarta.inject.Inject;
import java.sql.*;

/**
 * PostgreSQL implementation of {@link IMigrationLogStore}.
 * Tracks migration status in a simple table.
 */
@ApplicationScoped
@IfBuildProfile("postgres")
public class PostgresMigrationLogStore implements IMigrationLogStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresMigrationLogStore.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS migration_log (
                id BIGSERIAL PRIMARY KEY,
                name VARCHAR(512) NOT NULL UNIQUE,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

    private final DataSource dataSource;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresMigrationLogStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) return;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
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
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
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
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, migrationLog.getName());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to create migration log", e);
        }
    }
}
