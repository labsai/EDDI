package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for Agent Engine conversation lifecycle.
 * <p>
 * Ported from {@code RestAgentEngineTest} in EDDI-integration-tests.
 * Uses Quarkus DevServices for MongoDB (requires Docker).
 * <p>
 * Deploys two agents with different config, then tests:
 * <ul>
 * <li>Welcome message on conversation start</li>
 * <li>Word and phrase input recognition</li>
 * <li>Simple and detailed conversation logs</li>
 * <li>Quick reply generation and recognition</li>
 * <li>Context handling (string, expression, object)</li>
 * <li>Output templating</li>
 * <li>Property extraction</li>
 * <li>Undo/redo</li>
 * <li>Conversation ended (410)</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
public class AgentEngineIT extends BaseIntegrationIT {

        private static final String TEST_USER_ID = "testUser";

        private static ResourceId agentResourceId;
        private static ResourceId agent2ResourceId;
        private static boolean agentsDeployed = false;

        private ResourceId conversationResourceId;

        @BeforeEach
        void setUp() throws Exception {
                if (!agentsDeployed) {
                        agentResourceId = setupAndDeployAgent(
                                        "agentengine/dictionary.json",
                                        "agentengine/rules.json",
                                        "agentengine/output.json");
                        agent2ResourceId = setupAndDeployAgent(
                                        "agentengine/dictionary2.json",
                                        "agentengine/rules2.json",
                                        "agentengine/output2.json");
                        agentsDeployed = true;
                }
                conversationResourceId = createConversation(agentResourceId.id(), TEST_USER_ID);
        }

        @AfterAll
        static void cleanup() {
                if (agentResourceId != null) {
                        undeployAgentQuietly(agentResourceId.id(), agentResourceId.version());
                }
                if (agent2ResourceId != null) {
                        undeployAgentQuietly(agent2ResourceId.id(), agent2ResourceId.version());
                }
        }

        // ==================== Welcome Message ====================

        @Test
        @DisplayName("should return welcome message on conversation start")
        void checkWelcomeMessage() throws Exception {
                Thread.sleep(1000); // wait for async welcome processing
                Response response = getConversationLog(agentResourceId.id(), conversationResourceId.id(), false);

                response.then().assertThat()
                                .statusCode(200)
                                .body("agentId", equalTo(agentResourceId.id()))
                                .body("agentVersion", equalTo(agentResourceId.version()))
                                .body("conversationSteps", hasSize(1))
                                .body("conversationSteps[0].conversationStep[0].key", equalTo("actions"))
                                .body("conversationSteps[0].conversationStep[0].value[1]", equalTo("welcome"))
                                .body("conversationSteps[0].conversationStep[1].key", equalTo("output:text:welcome"))
                                .body("conversationSteps[0].conversationStep[1].value.text",
                                                equalTo("Welcome! I am E.D.D.I."))
                                .body("environment", equalTo("production"))
                                .body("conversationState", equalTo("READY"))
                                .body("undoAvailable", equalTo(false))
                                .body("redoAvailable", equalTo(false));
        }

        // ==================== Word Input ====================

        @Test
        @DisplayName("should process word input and return simple conversation log")
        void checkWordInputSimpleConversationLog() {
                Response response = sendUserInput(agentResourceId.id(), conversationResourceId.id(), "hello", false,
                                false);

                response.then().assertThat()
                                .statusCode(200)
                                .body("conversationSteps", hasSize(2))
                                .body("conversationSteps[1].conversationStep[0].key", equalTo("input:initial"))
                                .body("conversationSteps[1].conversationStep[0].value", equalTo("hello"))
                                .body("conversationSteps[1].conversationStep[1].key", equalTo("actions"))
                                .body("conversationSteps[1].conversationStep[1].value[0]", equalTo("greet"))
                                .body("conversationSteps[1].conversationStep[2].key", equalTo("output:text:greet"))
                                .body("conversationSteps[1].conversationStep[2].value.text",
                                                equalTo("Hi there! Nice to meet up! :-)"))
                                .body("undoAvailable", equalTo(true))
                                .body("redoAvailable", equalTo(false));
        }

        @Test
        @DisplayName("should return only current step when returnCurrentStepOnly=true")
        void checkWordInputCurrentStepOnly() {
                Response response = sendUserInput(agentResourceId.id(), conversationResourceId.id(), "hello", false,
                                true);

                response.then().assertThat()
                                .statusCode(200)
                                .body("conversationSteps", hasSize(1))
                                .body("conversationSteps[0].conversationStep[0].key", equalTo("input:initial"))
                                .body("conversationSteps[0].conversationStep[0].value", equalTo("hello"));
        }

        @Test
        @DisplayName("should handle repeated greeting with different output")
        void checkSecondTimeWordInput() {
                sendUserInput(agentResourceId.id(), conversationResourceId.id(), "hello", false, false);
                Response response = sendUserInput(agentResourceId.id(), conversationResourceId.id(), "hello", false,
                                false);

                response.then().assertThat()
                                .statusCode(200)
                                .body("conversationSteps", hasSize(3))
                                .body("conversationSteps[2].conversationStep[2].key", equalTo("output:text:greet"))
                                .body("conversationSteps[2].conversationStep[2].value.text",
                                                equalTo("Did we already say hi ?! Well, twice is better than not at all! ;-)"));
        }

        // ==================== Second Agent ====================

        @Test
        @DisplayName("should route to second deployed Agent correctly")
        void checkSecondAgentDeployed() {
                ResourceId convId2 = createConversation(agent2ResourceId.id(), TEST_USER_ID);
                Response response = sendUserInput(agent2ResourceId.id(), convId2.id(), "hi", true, false);

                response.then().assertThat()
                                .statusCode(200)
                                .body("agentId", equalTo(agent2ResourceId.id()))
                                .body("conversationSteps", hasSize(2))
                                .body("conversationSteps[1].conversationStep[0].value", equalTo("hi"));
        }

        // ==================== Quick Replies ====================

        @Test
        @DisplayName("should return quick replies for question input")
        void checkQuickReplyConversationLog() {
                Response response = sendUserInput(agentResourceId.id(), conversationResourceId.id(), "question", false,
                                false);

                response.then().assertThat()
                                .statusCode(200)
                                .body("conversationSteps", hasSize(2))
                                .body("conversationSteps[1].conversationStep[3].key",
                                                equalTo("quickReplies:giving_two_options"))
                                .body("conversationSteps[1].conversationStep[3].value[0].value", equalTo("Option 1"))
                                .body("conversationSteps[1].conversationStep[3].value[1].value", equalTo("Option 2"));
        }

        // ==================== Context Handling ====================

        @Test
        @DisplayName("should process string context sent with input")
        void testStringContextSendWithInput() {
                String body = """
                                {"input":"hello","context":{"someContextKeyString":{"type":"string","value":"someContextValue"}}}""";
                Response response = sendJsonInput(agentResourceId.id(), conversationResourceId.id(), body, true);

                response.then().assertThat()
                                .statusCode(200)
                                .body("conversationSteps[1].conversationStep[0].key",
                                                equalTo("context:someContextKeyString"))
                                .body("conversationSteps[1].conversationStep[0].value.type", equalTo("string"))
                                .body("conversationSteps[1].conversationStep[0].value.value",
                                                equalTo("someContextValue"));
        }

        @Test
        @DisplayName("should process expression context sent with input")
        void testExpressionContextSendWithInput() {
                String body = """
                                {"input":"hello","context":{"someContextKeyExpressions":{"type":"expressions","value":"expression(someValue), expression2(someOtherValue)"}}}""";
                Response response = sendJsonInput(agentResourceId.id(), conversationResourceId.id(), body, true);

                response.then().assertThat()
                                .statusCode(200)
                                .body("conversationSteps[1].conversationStep[0].key",
                                                equalTo("context:someContextKeyExpressions"))
                                .body("conversationSteps[1].conversationStep[0].value.type", equalTo("expressions"))
                                .body("conversationSteps[1].conversationStep[0].value.value",
                                                equalTo("expression(someValue), expression2(someOtherValue)"));
        }

        // ==================== Templating ====================

        @Test
        @DisplayName("should template output with context variables")
        void testTemplatingOfOutput() {
                String body = """
                                {"input":"hello","context":{"userInfo":{"type":"object","value":{"username":"John"}}}}""";
                Response response = sendJsonInput(agentResourceId.id(), conversationResourceId.id(), body, true);

                response.then().assertThat()
                                .statusCode(200)
                                .body("conversationSteps[1].conversationStep[8].key",
                                                equalTo("output:text:greet_personally"))
                                .body("conversationSteps[1].conversationStep[8].value.text",
                                                equalTo("Hello John! Nice to meet you! :-)"));
        }

        // ==================== Property Extraction ====================

        @Test
        @DisplayName("should extract properties from conversation input")
        void testPropertyExtraction() {
                String body = """
                                {"input":"property","context":{}}""";
                Response response = sendJsonInput(agentResourceId.id(), conversationResourceId.id(), body, true);

                response.then().assertThat()
                                .statusCode(200)
                                .body("conversationSteps[1].conversationStep[6].key", equalTo("properties:someMeaning"))
                                .body("conversationSteps[1].conversationStep[6].value[0].valueString",
                                                equalTo("someValue"));
        }

        // ==================== Conversation Ended ====================

        @Test
        @DisplayName("should return 410 when sending input to ended conversation")
        void testConversationEnded() throws Exception {
                String body = """
                                {"input":"bye","context":{"userInfo":{"type":"object","value":{"username":"John"}}}}""";
                sendJsonInput(agentResourceId.id(), conversationResourceId.id(), body, true);
                Thread.sleep(100);
                Response response = sendJsonInput(agentResourceId.id(), conversationResourceId.id(), body, true);

                response.then().assertThat()
                                .statusCode(410)
                                .body(equalTo("Conversation has ended!"));
        }

        // ==================== Helpers ====================

        private ResourceId setupAndDeployAgent(String dictionaryPath, String behaviorPath, String outputPath)
                        throws Exception {
                String dictionary = load(dictionaryPath);
                String behavior = load(behaviorPath);
                String output = load(outputPath);

                String locationDictionary = createAgentResource(dictionary,
                                "/regulardictionarystore/regulardictionaries");
                String locationBehavior = createAgentResource(behavior, "/behaviorstore/behaviorsets");
                String locationOutput = createAgentResource(output, "/outputstore/outputsets");

                // Create package with all extensions
                String packageBody = String.format(
                                """
                                                {
                                                  "WorkflowSteps": [
                                                    {
                                                      "type": "eddi://ai.labs.parser",
                                                      "config": {},
                                                      "extensions": {
                                                        "dictionaries": [
                                                          {"type": "eddi://ai.labs.parser.dictionaries.integer", "config": {}},
                                                          {"type": "eddi://ai.labs.parser.dictionaries.decimal", "config": {}},
                                                          {"type": "eddi://ai.labs.parser.dictionaries.punctuation", "config": {}},
                                                          {"type": "eddi://ai.labs.parser.dictionaries.email", "config": {}},
                                                          {"type": "eddi://ai.labs.parser.dictionaries.time", "config": {}},
                                                          {"type": "eddi://ai.labs.parser.dictionaries.ordinalNumber", "config": {}},
                                                          {"type": "eddi://ai.labs.parser.dictionaries.regular", "config": {"uri": "%s"}}
                                                        ],
                                                        "corrections": [
                                                          {"type": "eddi://ai.labs.parser.corrections.levenshtein", "config": {"distance": "2"}},
                                                          {"type": "eddi://ai.labs.parser.corrections.mergedTerms", "config": {}}
                                                        ]
                                                      }
                                                    },
                                                    {"type": "eddi://ai.labs.behavior", "config": {"uri": "%s"}},
                                                    {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}},
                                                    {"type": "eddi://ai.labs.templating", "config": {}},
                                                    {"type": "eddi://ai.labs.property", "config": {}}
                                                  ]
                                                }""",
                                locationDictionary, locationBehavior, locationOutput);

                String locationWorkflow = createAgentResource(packageBody, "/WorkflowStore/packages");

                // Create agent
                String agentBody = String.format("""
                                {"packages": ["%s"]}""", locationWorkflow);
                String agentLocation = createAgentResource(agentBody, "/AgentStore/agents");

                ResourceId agentId = extractResourceId(agentLocation);

                // Deploy the agent
                deployAgent(agentId.id(), agentId.version());

                return agentId;
        }

        private String createAgentResource(String body, String path) {
                Response response = given()
                                .body(body)
                                .contentType(ContentType.JSON)
                                .post(path);
                response.then().statusCode(201);
                return response.getHeader("location");
        }

        private void deployAgent(String id, int version) throws InterruptedException {
                given().post(String.format("administration/production/deploy/%s?version=%s&autoDeploy=false", id,
                                version));
                for (int i = 0; i < 60; i++) {
                        Response response = given()
                                        .get(String.format(
                                                        "administration/production/deploymentstatus/%s?version=%s&format=text",
                                                        id, version));
                        String status = response.getBody().print().trim();
                        if ("READY".equals(status))
                                return;
                        if ("ERROR".equals(status))
                                throw new RuntimeException("Agent deployment failed");
                        Thread.sleep(500);
                }
                throw new RuntimeException("Agent deployment timed out");
        }

        private Response sendJsonInput(String agentId, String conversationId, String jsonBody, boolean returnDetailed) {
                return given()
                                .contentType(ContentType.JSON)
                                .body(jsonBody)
                                .post(String.format(
                                                "agents/production/%s/%s?returnDetailed=%s&returnCurrentStepOnly=%s",
                                                agentId, conversationId, returnDetailed, false));
        }
}
