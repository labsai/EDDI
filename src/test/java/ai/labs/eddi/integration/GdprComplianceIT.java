package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for GDPR compliance REST endpoints.
 * <p>
 * Tests the full GDPR lifecycle: export (Art. 15/20), erasure (Art. 17), and
 * processing restriction (Art. 18) via {@code /admin/gdpr}.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GdprComplianceIT extends BaseIntegrationIT {

    private static final String GDPR_BASE = "/admin/gdpr/";
    private static final String TEST_USER_ID = "gdpr-test-user-" + System.currentTimeMillis();

    private static ResourceId agentResourceId;
    private static boolean agentDeployed = false;

    @BeforeEach
    void setUp() throws Exception {
        if (!agentDeployed) {
            agentResourceId = setupAndDeployMinimalAgent();
            agentDeployed = true;
        }
    }

    @AfterAll
    static void cleanup() {
        if (agentResourceId != null) {
            undeployAgentQuietly(agentResourceId.id(), agentResourceId.version());
        }
    }

    // ==================== Export (Art. 15/20) ====================

    @Test
    @Order(1)
    @DisplayName("Export user data should return valid structure")
    void exportUserData_returnsValidStructure() {
        // Create some data first — start a conversation and send input
        ResourceId convId = createConversation(agentResourceId.id(), TEST_USER_ID);
        sendUserInput(agentResourceId.id(), convId.id(), "hello", false, false);

        // Export
        Response response = given().get(GDPR_BASE + TEST_USER_ID + "/export");

        response.then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("userId", equalTo(TEST_USER_ID));
    }

    @Test
    @Order(2)
    @DisplayName("Export empty user should return valid but empty structure")
    void exportEmptyUser_returnsValidStructure() {
        String emptyUser = "gdpr-empty-user-" + System.currentTimeMillis();

        given().get(GDPR_BASE + emptyUser + "/export")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("userId", equalTo(emptyUser));
    }

    // ==================== Processing Restriction (Art. 18) ====================

    @Test
    @Order(3)
    @DisplayName("Check restriction status for unrestricted user should return false")
    void checkRestriction_unrestricted() {
        String userId = "gdpr-restrict-test-" + System.currentTimeMillis();

        given().get(GDPR_BASE + userId + "/restrict")
                .then().assertThat()
                .statusCode(200)
                .body(equalTo("false"));
    }

    @Test
    @Order(4)
    @DisplayName("Restrict processing should succeed")
    void restrictProcessing() {
        String userId = "gdpr-restrict-test2-" + System.currentTimeMillis();

        // Restrict
        given().post(GDPR_BASE + userId + "/restrict")
                .then().assertThat()
                .statusCode(204);

        // Verify restricted
        given().get(GDPR_BASE + userId + "/restrict")
                .then().assertThat()
                .statusCode(200)
                .body(equalTo("true"));
    }

    @Test
    @Order(5)
    @DisplayName("Unrestrict processing should succeed")
    void unrestrictProcessing() {
        String userId = "gdpr-unrestrict-test-" + System.currentTimeMillis();

        // Restrict first
        given().post(GDPR_BASE + userId + "/restrict")
                .then().statusCode(204);

        // Unrestrict
        given().delete(GDPR_BASE + userId + "/restrict")
                .then().assertThat()
                .statusCode(204);

        // Verify unrestricted
        given().get(GDPR_BASE + userId + "/restrict")
                .then().assertThat()
                .statusCode(200)
                .body(equalTo("false"));
    }

    // ==================== Erasure (Art. 17) ====================

    @Test
    @Order(6)
    @DisplayName("Delete user data should return deletion summary")
    void deleteUserData_returnsSummary() {
        String userId = "gdpr-delete-test-" + System.currentTimeMillis();

        // Create some data
        ResourceId convId = createConversation(agentResourceId.id(), userId);
        sendUserInput(agentResourceId.id(), convId.id(), "hello", false, false);

        // Delete
        Response response = given().delete(GDPR_BASE + userId);

        response.then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("userId", equalTo(userId));
    }

    @Test
    @Order(7)
    @DisplayName("After erasure, export should return empty data")
    void afterErasure_exportIsEmpty() {
        String userId = "gdpr-erase-verify-" + System.currentTimeMillis();

        // Create data
        ResourceId convId = createConversation(agentResourceId.id(), userId);
        sendUserInput(agentResourceId.id(), convId.id(), "hello", false, false);

        // Erase
        given().delete(GDPR_BASE + userId)
                .then().statusCode(200);

        // Export — should have empty conversations
        given().get(GDPR_BASE + userId + "/export")
                .then().assertThat()
                .statusCode(200)
                .body("conversations", empty());
    }

    @Test
    @Order(8)
    @DisplayName("Restrict user, then verify conversation attempt is rejected")
    void restrictedUser_cannotConverse() throws InterruptedException {
        String userId = "gdpr-restricted-conv-" + System.currentTimeMillis();

        // Create a conversation first (while unrestricted)
        ResourceId convId = createConversation(agentResourceId.id(), userId);

        // Restrict processing
        given().post(GDPR_BASE + userId + "/restrict")
                .then().statusCode(204);

        // Attempt to send input — should be rejected with 403 Forbidden (GDPR Art. 18)
        Response response = given().contentType(ContentType.TEXT).body("hello")
                .post(String.format("agents/%s?returnDetailed=false&returnCurrentStepOnly=false",
                        convId.id()));

        response.then().assertThat()
                .statusCode(403);

        // Clean up restriction
        given().delete(GDPR_BASE + userId + "/restrict");
    }

}
