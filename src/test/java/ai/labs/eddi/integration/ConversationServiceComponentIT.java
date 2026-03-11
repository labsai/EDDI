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

    private static ResourceId botResourceId;
    private static boolean botsDeployed = false;

    @BeforeEach
    void setUp() throws Exception {
        if (!botsDeployed) {
            botResourceId = setupAndDeployMinimalBot();
            botsDeployed = true;
        }
    }

    @AfterAll
    static void cleanup() {
        if (botResourceId != null) {
            undeployBotQuietly(botResourceId.id(), botResourceId.version());
        }
    }

    // ==================== Conversation Lifecycle ====================

    @Test
    @DisplayName("should create conversation and return location header")
    void createConversation_returnsLocation() {
        Response response = given()
                .post("bots/unrestricted/" + botResourceId.id() + "?userId=" + TEST_USER_ID);

        response.then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(201)))
                .header("location", notNullValue());
    }

    @Test
    @DisplayName("should process user input and return conversation log")
    void processInput_returnsConversationLog() {
        ResourceId conversationId = createConversation(botResourceId.id(), TEST_USER_ID);

        Response response = sendUserInput(botResourceId.id(), conversationId.id(), "hello", false, false);

        response.then().assertThat()
                .statusCode(200)
                .body("conversationSteps", hasSize(greaterThanOrEqualTo(2)))
                .body("conversationSteps[1].conversationStep[0].key", equalTo("input:initial"))
                .body("conversationSteps[1].conversationStep[0].value", equalTo("hello"));
    }

    @Test
    @DisplayName("should support returnCurrentStepOnly parameter")
    void processInput_currentStepOnly() {
        ResourceId conversationId = createConversation(botResourceId.id(), TEST_USER_ID);

        Response response = sendUserInput(botResourceId.id(), conversationId.id(), "hello", false, true);

        response.then().assertThat()
                .statusCode(200)
                .body("conversationSteps", hasSize(1))
                .body("conversationSteps[0].conversationStep[0].key", equalTo("input:initial"));
    }

    @Test
    @DisplayName("should support undo after user input")
    void undoUserInput() {
        ResourceId conversationId = createConversation(botResourceId.id(), TEST_USER_ID);

        // Send input first
        sendUserInput(botResourceId.id(), conversationId.id(), "hello", false, false);

        // Undo — path is /{env}/{botId}/undo/{convId}
        Response undoResponse = given()
                .post(String.format("bots/unrestricted/%s/undo/%s", botResourceId.id(), conversationId.id()));

        undoResponse.then().assertThat()
                .statusCode(200);
    }

    @Test
    @DisplayName("should support redo after undo")
    void redoAfterUndo() {
        ResourceId conversationId = createConversation(botResourceId.id(), TEST_USER_ID);

        // Send input
        sendUserInput(botResourceId.id(), conversationId.id(), "hello", false, false);

        // Undo — path is /{env}/{botId}/undo/{convId}
        given().post(String.format("bots/unrestricted/%s/undo/%s", botResourceId.id(), conversationId.id()));

        // Redo — path is /{env}/{botId}/redo/{convId}
        Response redoResponse = given()
                .post(String.format("bots/unrestricted/%s/redo/%s", botResourceId.id(), conversationId.id()));

        redoResponse.then().assertThat()
                .statusCode(200);
    }

    @Test
    @DisplayName("should end conversation with bye input")
    void endConversation_returns410() throws Exception {
        ResourceId conversationId = createConversation(botResourceId.id(), TEST_USER_ID);

        // Send bye to end conversation
        given().contentType(ContentType.JSON)
                .body("{\"input\":\"bye\"}")
                .post(String.format("bots/unrestricted/%s/%s?returnDetailed=true", botResourceId.id(),
                        conversationId.id()));

        Thread.sleep(200);

        // Verify conversation is ended
        Response response = given().contentType(ContentType.JSON)
                .body("{\"input\":\"hello\"}")
                .post(String.format("bots/unrestricted/%s/%s?returnDetailed=true", botResourceId.id(),
                        conversationId.id()));

        response.then().assertThat()
                .statusCode(410);
    }

    @Test
    @DisplayName("should handle concurrent conversations independently")
    void concurrentConversations() throws Exception {
        String user1 = "user1_" + System.currentTimeMillis();
        String user2 = "user2_" + System.currentTimeMillis();

        ResourceId conv1 = createConversation(botResourceId.id(), user1);
        ResourceId conv2 = createConversation(botResourceId.id(), user2);

        // Send input to both conversations
        Response response1 = sendUserInput(botResourceId.id(), conv1.id(), "hello", false, false);
        Response response2 = sendUserInput(botResourceId.id(), conv2.id(), "hello", false, false);

        // Both conversations should be in READY state and contain the input
        response1.then().statusCode(200).body("conversationState", equalTo("READY"));
        response2.then().statusCode(200).body("conversationState", equalTo("READY"));
    }

    // ==================== Helpers ====================

    private ResourceId setupAndDeployMinimalBot() throws Exception {
        String dictionary = load("botengine/regularDictionary.json");
        String behavior = load("botengine/behavior.json");
        String output = load("botengine/output.json");

        String locationDictionary = createResource(dictionary, "/regulardictionarystore/regulardictionaries");
        String locationBehavior = createResource(behavior, "/behaviorstore/behaviorsets");
        String locationOutput = createResource(output, "/outputstore/outputsets");

        String packageBody = String.format("""
                {
                  "packageExtensions": [
                    {
                      "type": "eddi://ai.labs.parser",
                      "config": {},
                      "extensions": {
                        "dictionaries": [
                          {"type": "eddi://ai.labs.parser.dictionaries.regular", "config": {"uri": "%s"}}
                        ],
                        "corrections": []
                      }
                    },
                    {"type": "eddi://ai.labs.behavior", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.templating", "config": {}},
                    {"type": "eddi://ai.labs.property", "config": {}}
                  ]
                }""", locationDictionary, locationBehavior, locationOutput);

        String locationPackage = createResource(packageBody, "/packagestore/packages");

        String botBody = String.format("""
                {"packages": ["%s"]}""", locationPackage);
        String botLocation = createResource(botBody, "/botstore/bots");

        ResourceId botId = extractResourceId(botLocation);
        deployBot(botId.id(), botId.version());
        return botId;
    }
}
