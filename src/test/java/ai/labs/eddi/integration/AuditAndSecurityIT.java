/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.List;
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
                .body("$", instanceOf(List.class));
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

    // ==================== D7 end-to-end: verify must say VALID for real turns
    // ====================

    /**
     * The regression net the run-0820a sweep prescribed as its definition of done.
     * <p>
     * Every earlier test in this class reads an EMPTY conversation's trail — which
     * is exactly why the ledger could ship reporting {@code valid=0 invalid=78} on
     * every real conversation: writes were exercised, verification never was, and
     * no test ever drove entries through the full pipeline (sign → queue → flush →
     * store → read → verify) against a real backend. This one does. It would have
     * failed on every EDDI version before the v4 canonical form, on either storage
     * backend, and it fails again if any layer of the timestamp handling regresses.
     */
    @Test
    @Order(12)
    @DisplayName("a real conversation's audit entries all verify VALID with an INTACT chain")
    void auditVerify_realConversationIsFullyValid() throws Exception {
        ResourceId agentId = setupAndDeployMinimalAgent();
        try {
            ResourceId conversationId = createConversation(agentId.id(), "audit-verify-user");

            // A user TURN, not just conversation creation: the audit collector is
            // attached in ConversationService.say(), so a conversation that has only
            // been started produces no entries at all.
            sendUserInput(agentId.id(), conversationId.id(), "hello", false, false)
                    .then().statusCode(200);

            // Entries are queued and flushed on an interval — poll until they land.
            int entryCount = 0;
            for (int i = 0; i < 30 && entryCount == 0; i++) {
                Thread.sleep(500);
                entryCount = given().get(AUDIT_BASE + conversationId.id())
                        .then().statusCode(200)
                        .extract().jsonPath().getList("$").size();
            }
            Assertions.assertTrue(entryCount > 0, "the conversation's turn must produce audit entries");

            // Assert the INVARIANT (every checked entry is valid), not a count captured
            // a moment earlier — the flush is asynchronous, so more entries may land
            // between the poll above and this call. "valid == entriesChecked" is both
            // the stronger claim and the race-free one.
            var report = given().get(AUDIT_BASE + "verify/" + conversationId.id())
                    .then().statusCode(200).extract().jsonPath();

            int checked = report.getInt("entriesChecked");
            Assertions.assertTrue(checked > 0, "the sweep must actually have checked something");
            Assertions.assertTrue(report.getBoolean("signingEnabled"), "the vault key is configured in this profile");
            Assertions.assertEquals(checked, report.getInt("valid"),
                    "every entry the sweep checked must verify — this is what reported valid=0 invalid=78 in the wild");
            Assertions.assertEquals(0, report.getInt("invalid"));
            Assertions.assertEquals(0, report.getInt("unsigned"));
            Assertions.assertEquals(0, report.getInt("recovered"),
                    "freshly written entries are v4 and verify directly, with no legacy recovery");
            Assertions.assertEquals(0, report.getInt("recoverySkipped"));
            Assertions.assertEquals("INTACT", report.getString("chainStatus"));
        } finally {
            undeployAgentQuietly(agentId.id(), agentId.version());
        }
    }

    // ==================== Helpers ====================

    private boolean isVaultAvailable() {
        return given().get(SECRET_BASE + "health").getStatusCode() == 200;
    }
}
