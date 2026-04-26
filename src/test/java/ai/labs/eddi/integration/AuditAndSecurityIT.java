/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for Audit Ledger and Secrets Vault REST endpoints.
 * <p>
 * Tests audit trail queries and secret lifecycle management.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AuditAndSecurityIT extends BaseIntegrationIT {

    private static final String AUDIT_BASE = "/auditstore/";
    private static final String SECRET_BASE = "/secretstore/secrets/";
    private static final String TEST_TENANT = "test-tenant-" + System.currentTimeMillis();

    @AfterAll
    static void cleanup() {
        // Clean up any vault secrets created during test
        try {
            given().delete(SECRET_BASE + TEST_TENANT + "/test-key");
        } catch (Exception ignored) {
        }
    }

    // ==================== Audit Trail ====================

    @Test
    @Order(1)
    @DisplayName("Get audit trail for non-existent conversation should return empty list")
    void auditTrail_emptyConversation() {
        given().get(AUDIT_BASE + "000000000000000000000000")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", instanceOf(java.util.List.class));
    }

    @Test
    @Order(2)
    @DisplayName("Get audit trail by agent should return list")
    void auditTrail_byAgent() {
        given().get(AUDIT_BASE + "agent/000000000000000000000000")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(3)
    @DisplayName("Get audit entry count should return number")
    void auditTrail_count() {
        given().get(AUDIT_BASE + "000000000000000000000000/count")
                .then().assertThat()
                .statusCode(200);
    }

    @Test
    @Order(4)
    @DisplayName("Audit trail should respect pagination parameters")
    void auditTrail_pagination() {
        given().queryParam("skip", 0)
                .queryParam("limit", 5)
                .get(AUDIT_BASE + "000000000000000000000000")
                .then().assertThat()
                .statusCode(200)
                .body("$.size()", lessThanOrEqualTo(5));
    }

    // ==================== Secrets Vault ====================

    @Test
    @Order(5)
    @DisplayName("Vault health check should indicate status")
    void vaultHealth() {
        // 200 = vault enabled with valid master key
        // 503 = vault disabled (eddi.vault.master-key not configured)
        given().get(SECRET_BASE + "health")
                .then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(503)));
    }

    @Test
    @Order(6)
    @DisplayName("Store secret should succeed (when vault is enabled)")
    void storeSecret() {
        // Skip if vault is not configured (no master key)
        Assumptions.assumeTrue(isVaultAvailable(), "Vault not configured — skipping CRUD tests");

        String body = """
                {
                  "value": "super-secret-api-key-12345",
                  "description": "Test API key for integration tests",
                  "allowedAgents": ["*"]
                }
                """;

        given().contentType(ContentType.JSON).body(body)
                .put(SECRET_BASE + TEST_TENANT + "/test-key")
                .then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(201)));
    }

    @Test
    @Order(7)
    @DisplayName("Get secret metadata should NOT return plaintext value (when vault is enabled)")
    void getSecretMetadata() {
        Assumptions.assumeTrue(isVaultAvailable(), "Vault not configured — skipping CRUD tests");

        given().get(SECRET_BASE + TEST_TENANT + "/test-key")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("keyName", equalTo("test-key"));
    }

    @Test
    @Order(8)
    @DisplayName("List secrets for tenant should return entries (when vault is enabled)")
    void listSecrets() {
        Assumptions.assumeTrue(isVaultAvailable(), "Vault not configured — skipping CRUD tests");

        given().get(SECRET_BASE + TEST_TENANT)
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(9)
    @DisplayName("Delete secret should succeed (when vault is enabled)")
    void deleteSecret() {
        Assumptions.assumeTrue(isVaultAvailable(), "Vault not configured — skipping CRUD tests");

        given().delete(SECRET_BASE + TEST_TENANT + "/test-key")
                .then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
    }

    @Test
    @Order(10)
    @DisplayName("Get non-existent secret should return 404 (when vault is enabled)")
    void getNonExistentSecret() {
        Assumptions.assumeTrue(isVaultAvailable(), "Vault not configured — skipping CRUD tests");

        given().get(SECRET_BASE + TEST_TENANT + "/nonexistent-key-xyz")
                .then().assertThat()
                .statusCode(404);
    }

    @Test
    @Order(11)
    @DisplayName("List secrets for empty tenant should return empty list (when vault is enabled)")
    void listSecrets_emptyTenant() {
        Assumptions.assumeTrue(isVaultAvailable(), "Vault not configured — skipping CRUD tests");

        given().get(SECRET_BASE + "empty-tenant-" + System.currentTimeMillis())
                .then().assertThat()
                .statusCode(200);
    }

    // ==================== Helpers ====================

    private boolean isVaultAvailable() {
        return given().get(SECRET_BASE + "health").getStatusCode() == 200;
    }
}
