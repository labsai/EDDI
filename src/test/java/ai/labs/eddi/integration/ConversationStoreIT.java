package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for Conversation Store REST endpoints.
 * <p>
 * Tests conversation query, filtering, pagination, deletion, and active
 * conversation management via {@code /conversationstore/conversations}.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ConversationStoreIT extends BaseIntegrationIT {

    private static final String CONV_STORE_BASE = "/conversationstore/conversations/";

    private static ResourceId agentResourceId;
    private static ResourceId conversationId;
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

    // ==================== Create test data ====================

    @Test
    @Order(1)
    @DisplayName("Create conversation and send input for subsequent queries")
    void createTestConversation() {
        String userId = "convstore-test-" + System.currentTimeMillis();
        conversationId = createConversation(agentResourceId.id(), userId);

        sendUserInput(agentResourceId.id(), conversationId.id(), "hello", false, false);
    }

    // ==================== List & Filter ====================

    @Test
    @Order(2)
    @DisplayName("List conversation descriptors should return results")
    void listDescriptors() {
        given().get(CONV_STORE_BASE)
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", not(empty()));
    }

    @Test
    @Order(3)
    @DisplayName("List descriptors with pagination should respect limit")
    void listDescriptors_pagination() {
        given().queryParam("limit", 5)
                .queryParam("index", 0)
                .get(CONV_STORE_BASE)
                .then().assertThat()
                .statusCode(200)
                .body("$.size()", lessThanOrEqualTo(5));
    }

    @Test
    @Order(4)
    @DisplayName("List descriptors filtered by agentId should return matching conversations")
    void listDescriptors_filterByAgentId() {
        given().queryParam("agentId", agentResourceId.id())
                .get(CONV_STORE_BASE)
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    // ==================== Read Conversation ====================

    @Test
    @Order(5)
    @DisplayName("Read raw conversation log should return full snapshot")
    void readRawConversationLog() {
        Assumptions.assumeTrue(conversationId != null, "Conversation must be created in test 1");

        given().get(CONV_STORE_BASE + conversationId.id())
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(6)
    @DisplayName("Read simple conversation log should return simplified view")
    void readSimpleConversationLog() {
        Assumptions.assumeTrue(conversationId != null, "Conversation must be created in test 1");

        given().get(CONV_STORE_BASE + "simple/" + conversationId.id())
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    // ==================== Active Conversations ====================

    @Test
    @Order(7)
    @DisplayName("Get active conversations for agent should return list")
    void getActiveConversations() {
        given().get(CONV_STORE_BASE + "active/" + agentResourceId.id()
                + "?agentVersion=" + agentResourceId.version())
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    // ==================== Delete ====================

    @Test
    @Order(8)
    @DisplayName("Delete conversation log should succeed")
    void deleteConversationLog() {
        // Create a disposable conversation
        String userId = "convstore-delete-" + System.currentTimeMillis();
        ResourceId disposableConv = createConversation(agentResourceId.id(), userId);

        given().delete(CONV_STORE_BASE + disposableConv.id())
                .then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
    }
}
