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
 * Integration test exercising complex behavior rule conditions not covered by
 * the existing {@link AgentEngineIT}.
 * <p>
 * Covers: {@code NegationCondition}, {@code ConnectorCondition} (AND operator),
 * {@code OccurrenceCondition}, and {@code expressionsAsActions} in
 * {@code RulesEvaluationTask}.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ComplexRulesAgentEngineIT extends BaseIntegrationIT {

    private static final String TEST_USER_ID = "complexRulesTestUser";

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
            agentResourceId = setupComplexRulesAgent();
            agentDeployed = true;
        }
    }

    // ==================== Test 1: Negation Condition ====================

    @Test
    @Order(1)
    @DisplayName("Negation condition should trigger when wrapped condition is false")
    void testNegationCondition() throws Exception {
        ResourceId conversationId = createConversation(agentResourceId.id(), TEST_USER_ID);
        waitForConversationReady(agentResourceId.id(), conversationId.id());

        // First input: "check" — the NotGreetedYet rule should fire because
        // Greeting has never occurred (negation of occurrence >= 1)
        Response response = sendUserInput(agentResourceId.id(), conversationId.id(), "check", true, false);

        response.then().assertThat()
                .statusCode(200)
                .body("conversationSteps", hasSize(greaterThanOrEqualTo(2)))
                // The "not_greeted" action should have triggered
                .body("conversationSteps[1].conversationStep.find { it.key == 'actions' }.value",
                        hasItem("not_greeted"));
    }

    @Test
    @Order(2)
    @DisplayName("Negation condition should NOT trigger after wrapped condition becomes true")
    void testNegationConditionAfterOccurrence() throws Exception {
        ResourceId conversationId = createConversation(agentResourceId.id(), TEST_USER_ID);
        waitForConversationReady(agentResourceId.id(), conversationId.id());

        // First: greet to make the Greeting rule occur once
        sendUserInput(agentResourceId.id(), conversationId.id(), "hello", true, false);

        // Second: "check" — now NotGreetedYet should NOT fire because
        // the Greeting rule has occurred (negation flips to false)
        Response response = sendUserInput(agentResourceId.id(), conversationId.id(), "check", true, false);

        response.then().assertThat()
                .statusCode(200)
                .body("conversationSteps", hasSize(greaterThanOrEqualTo(3)))
                // The "not_greeted" action should NOT be in the actions for step 2
                // because the Greeting rule has already occurred
                .body("conversationSteps[2].conversationStep.find { it.key == 'actions' }.value",
                        not(hasItem("not_greeted")));
    }

    // ==================== Test 2: Connector Condition (AND) ====================

    @Test
    @Order(3)
    @DisplayName("Connector AND condition should trigger only when all sub-conditions match")
    void testConnectorAndCondition() throws Exception {
        ResourceId conversationId = createConversation(agentResourceId.id(), TEST_USER_ID);
        waitForConversationReady(agentResourceId.id(), conversationId.id());

        // Send "hello" WITH context containing userInfo.username — the AND connector
        // requires both inputmatcher (greeting) AND contextmatcher (userInfo)
        String body = """
                {"input":"hello","context":{"userInfo":{"type":"object","value":{"username":"TestUser"}}}}""";

        Response response = given().contentType(ContentType.JSON).body(body)
                .post(String.format("agents/%s?returnDetailed=%s&returnCurrentStepOnly=%s",
                        conversationId.id(), true, false));

        response.then().assertThat()
                .statusCode(200)
                .body("conversationSteps", hasSize(greaterThanOrEqualTo(2)))
                // The "full_greeting" action should have triggered
                .body("conversationSteps[1].conversationStep.find { it.key == 'actions' }.value",
                        hasItem("full_greeting"));
    }

    @Test
    @Order(4)
    @DisplayName("Connector AND condition should NOT trigger when only one sub-condition matches")
    void testConnectorAndConditionPartialMatch() throws Exception {
        ResourceId conversationId = createConversation(agentResourceId.id(), TEST_USER_ID);
        waitForConversationReady(agentResourceId.id(), conversationId.id());

        // Send "hello" WITHOUT context — only inputmatcher matches, contextmatcher
        // fails
        // → AND connector should not trigger full_greeting
        Response response = sendUserInput(agentResourceId.id(), conversationId.id(), "hello", true, false);

        response.then().assertThat()
                .statusCode(200)
                .body("conversationSteps", hasSize(greaterThanOrEqualTo(2)))
                // The "greet" action should fire (simple greeting), but NOT "full_greeting"
                .body("conversationSteps[1].conversationStep.find { it.key == 'actions' }.value",
                        hasItem("greet"))
                .body("conversationSteps[1].conversationStep.find { it.key == 'actions' }.value",
                        not(hasItem("full_greeting")));
    }

    // ==================== Agent Setup ====================

    /**
     * Sets up an agent with complex behavior rules: - Negation condition wrapping
     * an occurrence check - Connector (AND) combining inputmatcher + contextmatcher
     */
    private ResourceId setupComplexRulesAgent() throws Exception {
        // Dictionary: "hello" → greeting, "check" → status_check
        String dictionary = """
                {
                  "language": "en",
                  "words": [
                    {"word": "hello", "expressions": "greeting(hello)", "frequency": 0},
                    {"word": "check", "expressions": "status_check(check)", "frequency": 0}
                  ],
                  "phrases": []
                }
                """;

        // Complex rules with negation, connector, and occurrence conditions
        String rules = """
                {
                  "behaviorGroups": [
                    {
                      "name": "WelcomeGroup",
                      "behaviorRules": [
                        {
                          "name": "Welcome",
                          "actions": ["welcome"],
                          "conditions": [{
                            "type": "occurrence",
                            "configs": {"maxTimesOccurred": "0", "behaviorRuleName": "Welcome"}
                          }]
                        }
                      ]
                    },
                    {
                      "name": "ComplexRulesGroup",
                      "behaviorRules": [
                        {
                          "name": "FullGreeting",
                          "actions": ["full_greeting"],
                          "conditions": [{
                            "type": "connector",
                            "configs": {"operator": "AND"},
                            "conditions": [
                              {
                                "type": "inputmatcher",
                                "configs": {"expressions": "greeting(*)", "occurrence": "currentStep"}
                              },
                              {
                                "type": "contextmatcher",
                                "configs": {
                                  "contextKey": "userInfo",
                                  "contextType": "object",
                                  "objectKeyPath": "username"
                                }
                              }
                            ]
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
                          "name": "NotGreetedYet",
                          "actions": ["not_greeted"],
                          "conditions": [{
                            "type": "negation",
                            "conditions": [{
                              "type": "occurrence",
                              "configs": {"minTimesOccurred": "1", "behaviorRuleName": "Greeting"}
                            }]
                          },
                          {
                            "type": "inputmatcher",
                            "configs": {"expressions": "status_check(*)", "occurrence": "currentStep"}
                          }]
                        }
                      ]
                    }
                  ]
                }
                """;

        // Output
        String output = """
                {
                  "outputSet": [
                    {
                      "action": "welcome",
                      "timesOccurred": 0,
                      "outputs": [{"type": "text", "valueAlternatives": [{"type": "text", "text": "Welcome to complex rules test"}]}]
                    },
                    {
                      "action": "greet",
                      "timesOccurred": 0,
                      "outputs": [{"type": "text", "valueAlternatives": [{"type": "text", "text": "Simple greeting"}]}]
                    },
                    {
                      "action": "full_greeting",
                      "timesOccurred": 0,
                      "outputs": [{"type": "text", "valueAlternatives": [{"type": "text", "text": "Full greeting with context!"}]}]
                    },
                    {
                      "action": "not_greeted",
                      "timesOccurred": 0,
                      "outputs": [{"type": "text", "valueAlternatives": [{"type": "text", "text": "You haven't greeted yet!"}]}]
                    }
                  ]
                }
                """;

        String locationDictionary = createResource(dictionary, "/dictionarystore/dictionaries");
        String locationRules = createResource(rules, "/rulestore/rulesets");
        String locationOutput = createResource(output, "/outputstore/outputsets");

        // Workflow: parser → rules → output → templating → property
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
                    {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.templating", "config": {}},
                    {"type": "eddi://ai.labs.property", "config": {}}
                  ]
                }
                """, locationDictionary, locationRules, locationOutput);

        String locationWorkflow = createResource(workflowBody, "/workflowstore/workflows");
        String agentBody = String.format("""
                {"packages": ["%s"]}""", locationWorkflow);
        String agentLocation = createResource(agentBody, "/agentstore/agents");

        ResourceId agentId = extractResourceId(agentLocation);
        deployAgent(agentId.id(), agentId.version());
        return agentId;
    }
}
