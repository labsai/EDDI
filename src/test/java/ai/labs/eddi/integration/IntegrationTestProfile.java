package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Test profile for integration tests.
 * <p>
 * Quarkus DevServices automatically starts MongoDB via Testcontainers.
 * This profile overrides the custom mongodb.connectionString to point
 * to the DevServices-managed MongoDB (mapped to port 27017 via
 * quarkus.mongodb.devservices.port=27017 in application.properties).
 */
public class IntegrationTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.oidc.tenant-enabled", "false",
                "authorization.enabled", "false",
                "quarkus.http.test-port", "8081",
                "quarkus.http.port", "8081",
                "mongodb.connectionString",
                "mongodb://localhost:27017/eddi?retryWrites=true&w=majority&connectTimeoutMS=10000&socketTimeoutMS=30000");
    }
}
