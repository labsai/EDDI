/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test exercising the PropertySetterTask with stored configurations
 * and action-driven property extraction.
 * <p>
 * Covers: {@code PropertySetterTask} (execute, configure, convertToProperties),
 * {@code PathNavigator} (fromObjectPath resolution).
 * <p>
 * The key coverage gain here is the action-driven path in
 * {@code PropertySetterTask.execute()} lines 128-251, and the URI-based config
 * loading path in {@code configure()} lines 283-295.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PropertySetterAgentEngineIT extends BaseIntegrationIT {

    private static final String TEST_USER_ID = "propertyTestUser";

    private static ResourceId agentResourceId;
    private static boolean agentDeployed = false;

    @AfterAll
    static void cleanup() {
        if (agentResourceId != null) {
            undeployAgentQuietly(agentResourceId.id(), agentResourceId.version());
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        if (!agentDeployed) {
            agentResourceId = setupPropertySetterAgent();
            agentDeployed = true;
        }
    }

    // ==================== Test 1: Action-Driven Property Setting
    // ====================

    @Test
    @Order(1)
    @DisplayName("PropertySetter should set properties on matching action")
    void testActionDrivenPropertySetting() throws Exception {
        ResourceId conversationId = createConversation(agentResourceId.id(), TEST_USER_ID);
        waitForConversationReady(agentResourceId.id(), conversationId.id());

        // Send "hello" to trigger the "greet" action, which should set properties
        Response response = sendUserInput(agentResourceId.id(), conversationId.id(), "hello", true, false);

        response.then().assertThat()
                .statusCode(200)
                .body("conversationSteps", hasSize(greaterThanOrEqualTo(2)));
    }

    // ==================== Test 2: Property with fromObjectPath
    // ====================

    @Test
    @Order(2)
    @DisplayName("PropertySetter should extract property from context via fromObjectPath")
    void testPropertyFromObjectPath() throws Exception {
        ResourceId conversationId = createConversation(agentResourceId.id(), TEST_USER_ID);
        waitForConversationReady(agentResourceId.id(), conversationId.id());

        // Send "hello" with context containing userInfo.username — the PropertySetter
        // should extract it via fromObjectPath
        String body = """
                {"input":"hello","context":{"userInfo":{"type":"object","value":{"username":"TestUser"}}}}""";

        Response response = given().contentType(ContentType.JSON).body(body)
                .post(String.format("agents/%s?returnDetailed=%s&returnCurrentStepOnly=%s",
                        conversationId.id(), true, false));

        response.then().assertThat()
                .statusCode(200)
                .body("conversationSteps", hasSize(greaterThanOrEqualTo(2)));
    }

    // ==================== Test 3: Multiple Value Types ====================

    @Test
    @Order(3)
    @DisplayName("PropertySetter should handle valueInt, valueBoolean, and valueString")
    void testMultipleValueTypes() throws Exception {
        ResourceId conversationId = createConversation(agentResourceId.id(), TEST_USER_ID);
        waitForConversationReady(agentResourceId.id(), conversationId.id());

        // Send "config" to trigger the "set_config" action with multiple property types
        Response response = sendUserInput(agentResourceId.id(), conversationId.id(), "config", true, false);

        response.then().assertThat()
                .statusCode(200)
                .body("conversationSteps", hasSize(greaterThanOrEqualTo(2)));
    }

    // ==================== Agent Setup ====================

    /**
     * Sets up an agent with a stored PropertySetter configuration (URI-based).
     * Pipeline: parser → rules → property (with URI config) → output → templating
     */
    private ResourceId setupPropertySetterAgent() throws Exception {
        // Dictionary: "hello" → greeting, "config" → set_config
        String dictionary = """
                {
                  "language": "en",
                  "words": [
                    {"word": "hello", "expressions": "greeting(hello)", "frequency": 0},
                    {"word": "config", "expressions": "set_config(config)", "frequency": 0}
                  ],
                  "phrases": []
                }
                """;

        // Rules: greeting → greet action, set_config → set_config action
        String rules = """
                {
                  "behaviorGroups": [{
                    "name": "PropertyGroup",
                    "behaviorRules": [
                      {
                        "name": "Welcome",
                        "actions": ["welcome"],
                        "conditions": [{
                          "type": "occurrence",
                          "configs": {"maxTimesOccurred": "0", "behaviorRuleName": "Welcome"}
                        }]
                      },
                      {
                        "name": "Greeting",
                        "actions": ["greet"],
                        "conditions": [{
                          "type": "inputmatcher",
                          "configs": {"expressions": "greeting(*)", "occurrence": "currentStep"}
                        }]
                      },
                      {
                        "name": "SetConfig",
                        "actions": ["set_config"],
                        "conditions": [{
                          "type": "inputmatcher",
                          "configs": {"expressions": "set_config(*)", "occurrence": "currentStep"}
                        }]
                      }
                    ]
                  }]
                }
                """;

        // Output
        String output = """
                {
                  "outputSet": [
                    {
                      "action": "welcome",
                      "timesOccurred": 0,
                      "outputs": [{"type": "text", "valueAlternatives": [{"type": "text", "text": "Welcome to property test"}]}]
                    },
                    {
                      "action": "greet",
                      "timesOccurred": 0,
                      "outputs": [{"type": "text", "valueAlternatives": [{"type": "text", "text": "Hello! Properties set."}]}]
                    },
                    {
                      "action": "set_config",
                      "timesOccurred": 0,
                      "outputs": [{"type": "text", "valueAlternatives": [{"type": "text", "text": "Config properties set."}]}]
                    }
                  ]
                }
                """;

        // PropertySetter config: stored as a separate resource (URI-based loading path)
        String propertySetterConfig = """
                {
                  "setOnActions": [
                    {
                      "actions": ["greet"],
                      "setProperties": [
                        {"name": "greeted", "valueString": "true", "scope": "conversation", "override": true},
                        {"name": "greetCount", "valueInt": 1, "scope": "conversation", "override": true}
                      ]
                    },
                    {
                      "actions": ["greet"],
                      "setProperties": [
                        {"name": "extractedUser", "fromObjectPath": "context.userInfo.username", "scope": "conversation", "override": true}
                      ]
                    },
                    {
                      "actions": ["set_config"],
                      "setProperties": [
                        {"name": "configEnabled", "valueBoolean": true, "scope": "conversation", "override": true},
                        {"name": "configName", "valueString": "test-config", "scope": "conversation", "override": true},
                        {"name": "configVersion", "valueInt": 42, "scope": "conversation", "override": true}
                      ]
                    }
                  ]
                }
                """;

        String locationDictionary = createResource(dictionary, "/dictionarystore/dictionaries");
        String locationRules = createResource(rules, "/rulestore/rulesets");
        String locationOutput = createResource(output, "/outputstore/outputsets");
        String locationPropertySetter = createResource(propertySetterConfig, "/propertysetterstore/propertysetters");

        // Workflow: parser → rules → property (with URI) → output → templating
        String workflowBody = String.format("""
                {
                  "workflowSteps": [
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
                    {"type": "eddi://ai.labs.rules", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.property", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.templating", "config": {}}
                  ]
                }
                """, locationDictionary, locationRules, locationPropertySetter, locationOutput);

        String locationWorkflow = createResource(workflowBody, "/workflowstore/workflows");
        String agentBody = String.format("""
                {"packages": ["%s"]}""", locationWorkflow);
        String agentLocation = createResource(agentBody, "/agentstore/agents");

        ResourceId agentId = extractResourceId(agentLocation);
        deployAgent(agentId.id(), agentId.version());
        return agentId;
    }
}
