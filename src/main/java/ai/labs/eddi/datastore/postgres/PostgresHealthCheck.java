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
            return builder.up().withData("database", conn.getMetaData().getDatabaseProductName()).withData("url", conn.getMetaData().getURL())
                    .build();
        } catch (Exception e) {
            return builder.down().withData("error", e.getMessage()).build();
        }
    }
}
