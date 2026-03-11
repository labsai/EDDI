package ai.labs.eddi.datastore.postgres;

import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Readiness health check for PostgreSQL connectivity.
 * <p>
 * Only active when {@code eddi.datastore.type=postgres}.
 * Reports at {@code /q/health/ready} alongside other readiness checks.
 */
@Readiness
@ApplicationScoped
@LookupIfProperty(name = "eddi.datastore.type", stringValue = "postgres")
public class PostgresHealthCheck implements HealthCheck {

    private final DataSource dataSource;

    @Inject
    public PostgresHealthCheck(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("PostgreSQL connection");
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
            return builder.up()
                    .withData("database", conn.getMetaData().getDatabaseProductName())
                    .withData("url", conn.getMetaData().getURL())
                    .build();
        } catch (Exception e) {
            return builder.down()
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
