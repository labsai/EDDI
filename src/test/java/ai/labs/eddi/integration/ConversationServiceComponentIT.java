package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Component test for the Conversation Service lifecycle with real MongoDB.
 * <p>
 * Tests full conversation creation, user input processing, undo/redo, and
 * conversation end — exercising the full service stack including
 * ConversationService, LifecycleManager, and MongoDB persistence.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
public class ConversationServiceComponentIT extends BaseIntegrationIT {

    private static final String TEST_USER_ID = "componentTestUser";

    private static ResourceId agentResourceId;
    private static boolean agentsDeployed = false;

    @BeforeEach
    void setUp() throws Exception {
        if (!agentsDeployed) {
            agentResourceId = setupAndDeployMinimalAgent();
            agentsDeployed = true;
        }
    }

    @AfterAll
    static void cleanup() {
        if (agentResourceId != null) {
            undeployAgentQuietly(agentResourceId.id(), agentResourceId.version());
        }
    }

    // ==================== Conversation Lifecycle ====================

    @Test
    @DisplayName("should create conversation and return location header")
    void createConversation_returnsLocation() {
        Response response = given().post("agents/" + agentResourceId.id() + "/start?environment=production&userId=" + TEST_USER_ID);

        response.then().assertThat().statusCode(anyOf(equalTo(200), equalTo(201))).header("location", notNullValue());
    }

    @Test
    @DisplayName("should process user input and return conversation log")
    void processInput_returnsConversationLog() {
        ResourceId conversationId = createConversation(agentResourceId.id(), TEST_USER_ID);

        Response response = sendUserInput(agentResourceId.id(), conversationId.id(), "hello", false, false);

        response.then().assertThat().statusCode(200).body("conversationSteps", hasSize(greaterThanOrEqualTo(2)))
                .body("conversationSteps[1].conversationStep[0].key", equalTo("input:initial"))
                .body("conversationSteps[1].conversationStep[0].value", equalTo("hello"));
    }

    @Test
    @DisplayName("should support returnCurrentStepOnly parameter")
    void processInput_currentStepOnly() {
        ResourceId conversationId = createConversation(agentResourceId.id(), TEST_USER_ID);

        Response response = sendUserInput(agentResourceId.id(), conversationId.id(), "hello", false, true);

        response.then().assertThat().statusCode(200).body("conversationSteps", hasSize(1)).body("conversationSteps[0].conversationStep[0].key",
                equalTo("input:initial"));
    }

    @Test
    @DisplayName("should support undo after user input")
    void undoUserInput() throws Exception {
        ResourceId conversationId = createConversation(agentResourceId.id(), TEST_USER_ID);

        // Send input first and verify it was processed
        Response sayResponse = sendUserInput(agentResourceId.id(), conversationId.id(), "hello", false, false);
        sayResponse.then().assertThat().statusCode(200)
                .body("conversationSteps", hasSize(greaterThanOrEqualTo(2)));

        // Undo — may need a brief wait for the DB write to be visible on Postgres
        retryUntilOk(() -> given().post(String.format("agents/%s/undo", conversationId.id())),
                "Undo should succeed after user input");
    }

    @Test
    @DisplayName("should support redo after undo")
    void redoAfterUndo() throws Exception {
        ResourceId conversationId = createConversation(agentResourceId.id(), TEST_USER_ID);

        // Send input and verify
        Response sayResponse = sendUserInput(agentResourceId.id(), conversationId.id(), "hello", false, false);
        sayResponse.then().assertThat().statusCode(200)
                .body("conversationSteps", hasSize(greaterThanOrEqualTo(2)));

        // Undo — retry until available, then assert success
        retryUntilOk(() -> given().post(String.format("agents/%s/undo", conversationId.id())),
                "Undo should succeed after user input");

        // Redo — retry until available
        retryUntilOk(() -> given().post(String.format("agents/%s/redo", conversationId.id())),
                "Redo should succeed after undo");
    }

    @Test
    @DisplayName("should end conversation with bye input")
    void endConversation_returns410() throws Exception {
        ResourceId conversationId = createConversation(agentResourceId.id(), TEST_USER_ID);

        // Send bye to end conversation
        given().contentType(ContentType.JSON).body("{\"input\":\"bye\"}")
                .post(String.format("agents/%s?returnDetailed=true", conversationId.id()));

        // Poll until the conversation state has propagated (avoids hardcoded sleep)
        for (int i = 0; i < 20; i++) {
            Response probe = given().contentType(ContentType.JSON).body("{\"input\":\"hello\"}")
                    .post(String.format("agents/%s?returnDetailed=true", conversationId.id()));
            if (probe.statusCode() == 410) {
                probe.then().assertThat().statusCode(410);
                return;
            }
            Thread.sleep(500);
        }

        // Final attempt (will fail with assertion error if state never propagated)
        Response response = given().contentType(ContentType.JSON).body("{\"input\":\"hello\"}")
                .post(String.format("agents/%s?returnDetailed=true", conversationId.id()));

        response.then().assertThat().statusCode(410);
    }

    @Test
    @DisplayName("should handle concurrent conversations independently")
    void concurrentConversations() throws Exception {
        String user1 = "user1_" + System.currentTimeMillis();
        String user2 = "user2_" + System.currentTimeMillis();

        ResourceId conv1 = createConversation(agentResourceId.id(), user1);
        ResourceId conv2 = createConversation(agentResourceId.id(), user2);

        // Send input to both conversations
        Response response1 = sendUserInput(agentResourceId.id(), conv1.id(), "hello", false, false);
        Response response2 = sendUserInput(agentResourceId.id(), conv2.id(), "hello", false, false);

        // both conversations should be in READY state and contain the input
        response1.then().statusCode(200).body("conversationState", equalTo("READY"));
        response2.then().statusCode(200).body("conversationState", equalTo("READY"));
    }
}
