/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import jakarta.enterprise.inject.Instance;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Readiness health check for PostgreSQL connectivity.
 * <p>
 * Only active when {@code eddi.datastore.type=postgres}. Reports at
 * {@code /q/health/ready} alongside other readiness checks.
 */
@Readiness
@ApplicationScoped
@DefaultBean
public class PostgresHealthCheck implements HealthCheck {

    private final Instance<DataSource> dataSourceInstance;

    @Inject
    public PostgresHealthCheck(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("PostgreSQL connection");
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
            // Status only. The JDBC URL (host, port, database, sometimes credentials)
            // and the raw exception text were previously returned here, but
            // /q/health/* is anonymous — neither may reach an unauthenticated caller.
            // The connection failure detail stays in the server log.
            return builder.up().build();
        } catch (Exception e) {
            return builder.down().build();
        }
    }
}
