package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Integration test profile for PostgreSQL.
 * <p>
 * Activates the {@code postgres} Quarkus profile which:
 * <ul>
 *   <li>Sets {@code eddi.datastore.type=postgres}</li>
 *   <li>Enables PostgreSQL DevServices (Testcontainers)</li>
 *   <li>Disables MongoDB DevServices</li>
 *   <li>Disables authentication</li>
 * </ul>
 */
public class PostgresIntegrationTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                // Datastore type
                "eddi.datastore.type", "postgres",
                // PostgreSQL DevServices
                "quarkus.datasource.db-kind", "postgresql",
                "quarkus.datasource.active", "true",
                "quarkus.datasource.devservices.enabled", "true",
                // Disable MongoDB
                "quarkus.mongodb.devservices.enabled", "false",
                // Auth disabled
                "quarkus.oidc.tenant-enabled", "false",
                "authorization.enabled", "false",
                // Test HTTP port — different from MongoDB ITs (8081)
                "quarkus.http.test-port", "8082"
        );
    }

    @Override
    public String getConfigProfile() {
        return "postgres";
    }
}
