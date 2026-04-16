package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Integration test profile for PostgreSQL.
 * <p>
 * Activates the {@code postgres} Quarkus profile which:
 * <ul>
 * <li>Sets {@code eddi.datastore.type=postgres}</li>
 * <li>Enables PostgreSQL DevServices (Testcontainers)</li>
 * <li>Disables MongoDB DevServices</li>
 * <li>Disables authentication</li>
 * </ul>
 */
public class PostgresIntegrationTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.ofEntries(
                // Datastore type
                Map.entry("eddi.datastore.type", "postgres"),
                // PostgreSQL DevServices
                Map.entry("quarkus.datasource.db-kind", "postgresql"),
                Map.entry("quarkus.datasource.active", "true"),
                Map.entry("quarkus.datasource.devservices.enabled", "true"),
                // Disable MongoDB
                Map.entry("quarkus.mongodb.devservices.enabled", "false"),
                // Auth disabled
                Map.entry("quarkus.oidc.tenant-enabled", "false"),
                Map.entry("authorization.enabled", "false"),
                Map.entry("eddi.security.allow-unauthenticated", "true"),
                // Enable vault for vault CRUD tests
                Map.entry("eddi.vault.master-key", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                // Test HTTP port — different from MongoDB ITs (8081)
                Map.entry("quarkus.http.test-port", "8082"),
                // Must also set quarkus.http.port so RestInterfaceFactory
                // (used by /backup/import) connects to the correct port
                Map.entry("quarkus.http.port", "8082"));
    }

    @Override
    public String getConfigProfile() {
        return "postgres";
    }
}
