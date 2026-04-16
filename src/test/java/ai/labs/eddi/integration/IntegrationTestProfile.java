package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Test profile for integration tests.
 * <p>
 * Quarkus DevServices automatically starts MongoDB via Testcontainers. This
 * profile overrides the custom mongodb.connectionString to point to the
 * DevServices-managed MongoDB (mapped to port 27017 via
 * quarkus.mongodb.devservices.port=27017 in application.properties).
 */
public class IntegrationTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.ofEntries(
                Map.entry("quarkus.oidc.tenant-enabled", "false"),
                Map.entry("authorization.enabled", "false"),
                Map.entry("eddi.security.allow-unauthenticated", "true"),
                Map.entry("quarkus.http.test-port", "8081"),
                Map.entry("quarkus.http.port", "8081"),
                Map.entry("quarkus.log.category.\"ai.labs.eddi\".level", "INFO"),
                // Enable vault for AuditAndSecurityIT vault CRUD tests
                Map.entry("eddi.vault.master-key", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                Map.entry("mongodb.connectionString",
                        "mongodb://localhost:27017/eddi?retryWrites=true&w=majority&connectTimeoutMS=10000&socketTimeoutMS=30000"));
    }
}
