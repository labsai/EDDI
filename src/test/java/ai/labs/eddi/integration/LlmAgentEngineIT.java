/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.hamcrest.Matchers.*;

/**
 * Integration test exercising the full LLM pipeline via WireMock-backed fake
 * OpenAI endpoint.
 * <p>
 * Covers: {@code LlmTask}, {@code ChatModelRegistry},
 * {@code OpenAILanguageModelBuilder}, {@code ConversationHistoryBuilder},
 * {@code LegacyChatExecutor}, {@code AgentExecutionHelper},
 * {@code ObservableChatModel}, {@code AgentOrchestrator} (tool-calling loop),
 * {@code ToolExecutionService}, {@code CalculatorTool}.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class LlmAgentEngineIT extends BaseIntegrationIT {

    private static final String TEST_USER_ID = "llmTestUser";

    private static WireMockServer wireMock;
    private static ResourceId legacyAgentId;
    private static ResourceId toolCallingAgentId;
    private static boolean agentsDeployed = false;

    // === WireMock Response Templates ===

    private static final String CHAT_COMPLETION_RESPONSE = """
            {
              "id": "chatcmpl-test-legacy",
              "object": "chat.completion",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": "This is a test response from WireMock."
                },
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 10, "completion_tokens": 8, "total_tokens": 18}
            }
            """;

    private static final String TOOL_CALL_RESPONSE = """
            {
              "id": "chatcmpl-test-tool",
              "object": "chat.completion",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [{
                    "id": "call_calc1",
                    "type": "function",
                    "function": {
                      "name": "calculate",
                      "arguments": "{\\"expression\\":\\"2+2\\"}"
                    }
                  }]
                },
                "finish_reason": "tool_calls"
              }],
              "usage": {"prompt_tokens": 50, "completion_tokens": 20, "total_tokens": 70}
            }
            """;

    private static final String TOOL_FINAL_RESPONSE = """
            {
              "id": "chatcmpl-test-final",
              "object": "chat.completion",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": "The result of 2+2 is 4."
                },
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 80, "completion_tokens": 10, "total_tokens": 90}
            }
            """;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (legacyAgentId != null) {
            undeployAgentQuietly(legacyAgentId.id(), legacyAgentId.version());
        }
        if (toolCallingAgentId != null) {
            undeployAgentQuietly(toolCallingAgentId.id(), toolCallingAgentId.version());
        }
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    // @BeforeEach (not @BeforeAll) because RestAssured.given() requires the
    // Quarkus instance to be started, which isn't guaranteed in static context.
    @BeforeEach
    void setUp() throws Exception {
        if (!agentsDeployed) {
            legacyAgentId = setupLegacyLlmAgent();
            toolCallingAgentId = setupToolCallingLlmAgent();
            agentsDeployed = true;
        }
    }

    // ==================== Test 1: Legacy Chat Mode ====================

    @Test
    @Order(1)
    @DisplayName("LLM legacy chat mode should return WireMock response")
    void testLegacyChatMode() throws Exception {
        // Stub: simple chat completion (no tools)
        wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(okJson(CHAT_COMPLETION_RESPONSE)
                        .withHeader("Content-Type", "application/json")));

        ResourceId conversationId = createConversation(legacyAgentId.id(), TEST_USER_ID);

        // Wait for welcome message
        waitForConversationReady(legacyAgentId.id(), conversationId.id());

        // Send input that triggers the LLM action
        Response response = sendUserInput(legacyAgentId.id(), conversationId.id(), "ask", true, false);

        // The LLM task should have been triggered and returned WireMock's response
        response.then().assertThat()
                .statusCode(200)
                .body("conversationSteps", hasSize(greaterThanOrEqualTo(2)));
    }

    // ==================== Test 2: Agent Mode with Tool Calling
    // ====================

    @Test
    @Order(2)
    @DisplayName("LLM agent mode should execute calculator tool via WireMock")
    void testAgentModeWithToolCalling() throws Exception {
        // Reset WireMock stubs for the tool-calling scenario
        wireMock.resetAll();

        // Stateful stub: first request returns tool_calls, second returns final text
        wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("ToolCalling")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(TOOL_CALL_RESPONSE)
                        .withHeader("Content-Type", "application/json"))
                .willSetStateTo("ToolsExecuted"));

        wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("ToolCalling")
                .whenScenarioStateIs("ToolsExecuted")
                .willReturn(okJson(TOOL_FINAL_RESPONSE)
                        .withHeader("Content-Type", "application/json")));

        ResourceId conversationId = createConversation(toolCallingAgentId.id(), TEST_USER_ID);

        // Wait for welcome message
        waitForConversationReady(toolCallingAgentId.id(), conversationId.id());

        // Send input that triggers the tool-calling LLM action
        Response response = sendUserInput(toolCallingAgentId.id(), conversationId.id(), "calculate", true, false);

        // The LLM should have gone through the tool-calling loop:
        // 1. Called WireMock → got tool_calls (calculate "2+2")
        // 2. Executed CalculatorTool locally → got "4"
        // 3. Called WireMock again → got final response
        response.then().assertThat()
                .statusCode(200)
                .body("conversationSteps", hasSize(greaterThanOrEqualTo(2)));

        // Verify WireMock received exactly 2 requests (tool call + final)
        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/v1/chat/completions")));
    }

    // ==================== Agent Setup Helpers ====================

    /**
     * Sets up an agent with LLM in legacy mode (no tools). Pipeline: parser → rules
     * → llm → output → templating → property
     */
    private ResourceId setupLegacyLlmAgent() throws Exception {
        // Dictionary: "ask" → ask_llm(ask)
        String dictionary = """
                {
                  "language": "en",
                  "words": [
                    {"word": "ask", "expressions": "ask_llm(ask)", "frequency": 0},
                    {"word": "hello", "expressions": "greeting(hello)", "frequency": 0}
                  ],
                  "phrases": []
                }
                """;

        // Rules: ask_llm → action "call_llm"
        String rules = """
                {
                  "behaviorGroups": [{
                    "name": "LlmGroup",
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
                        "name": "AskLlm",
                        "actions": ["call_llm"],
                        "conditions": [{
                          "type": "inputmatcher",
                          "configs": {"expressions": "ask_llm(*)", "occurrence": "currentStep"}
                        }]
                      }
                    ]
                  }]
                }
                """;

        // Output: welcome message
        String output = """
                {
                  "outputSet": [{
                    "action": "welcome",
                    "timesOccurred": 0,
                    "outputs": [{"type": "text", "valueAlternatives": [{"type": "text", "text": "Welcome to LLM test"}]}]
                  }]
                }
                """;

        // LLM config: OpenAI pointing at WireMock, legacy mode (no tools)
        String llmConfig = String.format("""
                {
                  "tasks": [{
                    "id": "test-legacy",
                    "type": "openai",
                    "actions": ["call_llm"],
                    "parameters": {
                      "baseUrl": "http://localhost:%d/v1",
                      "apiKey": "sk-test-fake-key",
                      "modelName": "gpt-4o-test",
                      "systemMessage": "You are a test assistant.",
                      "addToOutput": "true",
                      "timeout": "30000"
                    },
                    "conversationHistoryLimit": 5
                  }]
                }
                """, wireMock.port());

        return createAndDeployLlmAgent(dictionary, rules, output, llmConfig);
    }

    /**
     * Sets up an agent with LLM in agent mode (with built-in calculator tool).
     * Pipeline: parser → rules → llm → output → templating → property
     */
    private ResourceId setupToolCallingLlmAgent() throws Exception {
        // Dictionary: "calculate" → do_calc(calculate)
        String dictionary = """
                {
                  "language": "en",
                  "words": [
                    {"word": "calculate", "expressions": "do_calc(calculate)", "frequency": 0},
                    {"word": "hello", "expressions": "greeting(hello)", "frequency": 0}
                  ],
                  "phrases": []
                }
                """;

        // Rules: do_calc → action "call_calc_llm"
        String rules = """
                {
                  "behaviorGroups": [{
                    "name": "CalcGroup",
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
                        "name": "DoCalc",
                        "actions": ["call_calc_llm"],
                        "conditions": [{
                          "type": "inputmatcher",
                          "configs": {"expressions": "do_calc(*)", "occurrence": "currentStep"}
                        }]
                      }
                    ]
                  }]
                }
                """;

        // Output: welcome message
        String output = """
                {
                  "outputSet": [{
                    "action": "welcome",
                    "timesOccurred": 0,
                    "outputs": [{"type": "text", "valueAlternatives": [{"type": "text", "text": "Welcome to calculator test"}]}]
                  }]
                }
                """;

        // LLM config: OpenAI with built-in tools enabled (calculator)
        String llmConfig = String.format("""
                {
                  "tasks": [{
                    "id": "test-tool-calling",
                    "type": "openai",
                    "actions": ["call_calc_llm"],
                    "parameters": {
                      "baseUrl": "http://localhost:%d/v1",
                      "apiKey": "sk-test-fake-key",
                      "modelName": "gpt-4o-test",
                      "systemMessage": "You are a calculator assistant. Use the calculator tool to solve math problems.",
                      "addToOutput": "true",
                      "timeout": "30000"
                    },
                    "enableBuiltInTools": true,
                    "builtInToolsWhitelist": ["calculator"],
                    "enableHttpCallTools": false,
                    "enableMcpCallTools": false,
                    "conversationHistoryLimit": 5,
                    "maxToolIterations": 3
                  }]
                }
                """, wireMock.port());

        return createAndDeployLlmAgent(dictionary, rules, output, llmConfig);
    }

    /**
     * Creates and deploys an agent with LLM step in the workflow.
     */
    private ResourceId createAndDeployLlmAgent(String dictionary, String rules, String output, String llmConfig) throws Exception {
        String locationDictionary = createResource(dictionary, "/dictionarystore/dictionaries");
        String locationRules = createResource(rules, "/rulestore/rulesets");
        String locationOutput = createResource(output, "/outputstore/outputsets");
        String locationLlm = createResource(llmConfig, "/llmstore/llms");

        // Workflow: parser → rules → llm → output → templating → property
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
                    {"type": "eddi://ai.labs.llm", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.templating", "config": {}},
                    {"type": "eddi://ai.labs.property", "config": {}}
                  ]
                }
                """, locationDictionary, locationRules, locationLlm, locationOutput);

        String locationWorkflow = createResource(workflowBody, "/workflowstore/workflows");
        String agentBody = String.format("""
                {"packages": ["%s"]}""", locationWorkflow);
        String agentLocation = createResource(agentBody, "/agentstore/agents");

        ResourceId agentId = extractResourceId(agentLocation);
        deployAgent(agentId.id(), agentId.version());
        return agentId;
    }

}
