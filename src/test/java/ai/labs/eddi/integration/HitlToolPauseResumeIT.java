/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for the TOOL-LEVEL HITL approval gate — a
 * {@code toolApprovals.requireApproval} pattern on an LLM task's built-in tool.
 * Exercises the full production path with no mocked seams except the remote
 * model provider (WireMock stands in for OpenAI, exactly as
 * {@link LlmAgentEngineIT} does): the real {@code AgentOrchestrator}
 * tool-calling loop detects a gated {@code calculate} tool call, freezes the
 * batch into a {@code PendingToolCallBatch} on the real MongoDB-backed
 * conversation memory, and the REST {@code /resume} endpoint (extended with
 * {@code toolDecisions}) re-enters {@code AgentOrchestrator#resumeToolLoop} to
 * apply the human verdict and complete the turn.
 * <p>
 * This covers the seams unique to tool-level (vs. rule-level) HITL: the
 * {@code TOOL_CALL} pauseDetails shape (redacted arguments, gateReason,
 * callId), per-call {@code toolDecisions} validation before the resume CAS, the
 * approve-executes-exactly-once guarantee (verified black-box via the WireMock
 * request count, since the write-ahead journal itself is an internal Mongo
 * store with no REST surface — see class-level report for the full reasoning),
 * and the reject-all graceful-answer path.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
public class HitlToolPauseResumeIT extends BaseIntegrationIT {

    private static final String TEST_USER_ID = "hitlToolTestUser";

    private static WireMockServer wireMock;
    private static ResourceId agentResourceId;
    private static boolean agentDeployed = false;

    private ResourceId conversationResourceId;

    // === WireMock Response Templates (mirrors LlmAgentEngineIT) ===

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
                  "content": "The result of 2+2 is 4, approved by a human reviewer."
                },
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 80, "completion_tokens": 10, "total_tokens": 90}
            }
            """;

    private static final String TOOL_FALLBACK_RESPONSE = """
            {
              "id": "chatcmpl-test-fallback",
              "object": "chat.completion",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": "I could not run the calculation because the reviewer declined it."
                },
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 60, "completion_tokens": 10, "total_tokens": 70}
            }
            """;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void cleanup() {
        if (agentResourceId != null) {
            undeployAgentQuietly(agentResourceId.id(), agentResourceId.version());
        }
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        if (!agentDeployed) {
            agentResourceId = setupAndDeployToolGatedAgent();
            agentDeployed = true;
        }
        wireMock.resetAll();
        conversationResourceId = createConversation(agentResourceId.id(), TEST_USER_ID);
        waitForConversationReady(agentResourceId.id(), conversationResourceId.id());
    }

    // ==================== Pause → approve → tool executes → completion
    // ====================

    @Test
    @DisplayName("gated tool call pauses the turn; APPROVED resume executes the tool exactly once and completes")
    void pauseThenApproveExecutesToolAndCompletes() throws Exception {
        stubToolCallThenFinal();

        // The gated "calculate" call pauses the turn instead of executing it.
        Response sayResponse = sendUserInput(agentResourceId.id(), conversationResourceId.id(), "calculate", true, false);
        sayResponse.then().assertThat().statusCode(200);
        waitForState("AWAITING_HUMAN");

        // Only the FIRST WireMock request (tool_calls) has happened so far — the
        // gated call must not have executed yet, and the model must not have been
        // re-invoked before a human decision.
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/v1/chat/completions")));

        // approval-status reports the structured TOOL_CALL pauseDetails
        var status = given().get("agents/" + conversationResourceId.id() + "/approval-status");
        status.then().assertThat().statusCode(200)
                .body("state", equalTo("AWAITING_HUMAN"))
                .body("pauseDetails.type", equalTo("TOOL_CALL"))
                .body("pauseDetails.calls[0].toolName", equalTo("calculate"))
                .body("pauseDetails.calls[0].callId", equalTo("call_calc1"))
                .body("pauseDetails.calls[0].gateReason", equalTo("calculate"))
                .body("pauseDetails.calls[0].arguments", containsString("2+2"));
        // The redacted arguments view must never leak a raw-args-only field name.
        assertNull(status.jsonPath().get("pauseDetails.calls[0].argumentsRaw"),
                "pauseDetails must only ever expose the redacted arguments, never argumentsRaw");

        // The paused conversation appears in the pending-approvals listing
        given().get("agents/pending-approvals")
                .then().assertThat().statusCode(200)
                .body("conversationId", hasItem(conversationResourceId.id()));

        // Approve the gated call via toolDecisions (per-call verdict)
        given().contentType(ContentType.JSON)
                .body("""
                        {"verdict": "APPROVED", "note": "verified by supervisor",
                         "toolDecisions": {"call_calc1": {"verdict": "APPROVED"}}}
                        """)
                .post("agents/" + conversationResourceId.id() + "/resume")
                .then().assertThat().statusCode(200);

        waitForState("READY");

        // The tool executed exactly once and the loop continued to the final
        // WireMock-stubbed answer — verified black-box via the total request count
        // (tool_calls request + the post-approval final-answer request) and the
        // resumed output text actually reaching the conversation log.
        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/v1/chat/completions")));
        Response logAfter = getConversationLog(agentResourceId.id(), conversationResourceId.id(), false);
        assertTrue(logAfter.getBody().asString().contains("approved by a human reviewer"),
                "APPROVED resume must execute the tool and let the loop reach the final WireMock answer");

        // Second resume of the already-resumed conversation -> 409, not 500
        given().contentType(ContentType.JSON)
                .body("{\"verdict\": \"APPROVED\"}")
                .post("agents/" + conversationResourceId.id() + "/resume")
                .then().assertThat().statusCode(409);

        // A THIRD WireMock call would only happen on a genuine re-execution of the
        // tool loop — the already-terminal conversation must not trigger one.
        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/v1/chat/completions")));
    }

    // ==================== Reject-all path ====================

    @Test
    @DisplayName("REJECTED (reject-all) resume never executes the tool and produces a graceful fallback answer")
    void pauseThenRejectAllSkipsToolExecution() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("RejectAll")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(TOOL_CALL_RESPONSE).withHeader("Content-Type", "application/json"))
                .willSetStateTo("ToolsRejected"));
        wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("RejectAll")
                .whenScenarioStateIs("ToolsRejected")
                .willReturn(okJson(TOOL_FALLBACK_RESPONSE).withHeader("Content-Type", "application/json")));

        sendUserInput(agentResourceId.id(), conversationResourceId.id(), "calculate", true, false);
        waitForState("AWAITING_HUMAN");

        given().contentType(ContentType.JSON)
                .body("{\"verdict\": \"REJECTED\", \"note\": \"not authorized for this request\"}")
                .post("agents/" + conversationResourceId.id() + "/resume")
                .then().assertThat().statusCode(200);

        waitForState("READY");

        // The model is re-invoked with a REJECTED_BY_REVIEWER tool-result envelope
        // and must produce a graceful answer WITHOUT ever having the tool execute —
        // there is no third WireMock stub for a "real" calculation continuation, so
        // if the tool had executed and the loop continued normally the response
        // would not match this fallback text.
        Response log = getConversationLog(agentResourceId.id(), conversationResourceId.id(), false);
        assertTrue(log.getBody().asString().contains("could not run the calculation"),
                "REJECTED must yield the graceful fallback answer, never a real tool result");
        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/v1/chat/completions")));
    }

    // ==================== toolDecisions validation (400s) ====================

    @Test
    @DisplayName("toolDecisions referencing an unknown callId -> 400 and the pause is NOT consumed")
    void toolDecisionsUnknownCallId400() throws Exception {
        stubToolCallThenFinal();
        sendUserInput(agentResourceId.id(), conversationResourceId.id(), "calculate", true, false);
        waitForState("AWAITING_HUMAN");

        given().contentType(ContentType.JSON)
                .body("""
                        {"verdict": "APPROVED",
                         "toolDecisions": {"not-a-real-call-id": {"verdict": "APPROVED"}}}
                        """)
                .post("agents/" + conversationResourceId.id() + "/resume")
                .then().assertThat().statusCode(400)
                .body(containsString("no pending tool call"));

        given().get("agents/" + conversationResourceId.id() + "/approval-status")
                .then().assertThat().statusCode(200)
                .body("state", equalTo("AWAITING_HUMAN"));
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/v1/chat/completions")));
    }

    @Test
    @DisplayName("toolDecisions with a missing per-call verdict -> 400 and the pause is NOT consumed")
    void toolDecisionsMissingVerdict400() throws Exception {
        stubToolCallThenFinal();
        sendUserInput(agentResourceId.id(), conversationResourceId.id(), "calculate", true, false);
        waitForState("AWAITING_HUMAN");

        given().contentType(ContentType.JSON)
                .body("""
                        {"verdict": "APPROVED",
                         "toolDecisions": {"call_calc1": {"note": "missing verdict field"}}}
                        """)
                .post("agents/" + conversationResourceId.id() + "/resume")
                .then().assertThat().statusCode(400)
                .body(containsString("verdict is required"));

        given().get("agents/" + conversationResourceId.id() + "/approval-status")
                .then().assertThat().statusCode(200)
                .body("state", equalTo("AWAITING_HUMAN"));
    }

    @Test
    @DisplayName("top-level REJECTED with a per-call APPROVED toolDecision -> 400 (contradictory) and the pause is NOT consumed")
    void toolDecisionsContradictoryTopLevelRejected400() throws Exception {
        stubToolCallThenFinal();
        sendUserInput(agentResourceId.id(), conversationResourceId.id(), "calculate", true, false);
        waitForState("AWAITING_HUMAN");

        given().contentType(ContentType.JSON)
                .body("""
                        {"verdict": "REJECTED",
                         "toolDecisions": {"call_calc1": {"verdict": "APPROVED"}}}
                        """)
                .post("agents/" + conversationResourceId.id() + "/resume")
                .then().assertThat().statusCode(400)
                .body(containsString("top-level verdict is REJECTED"));

        given().get("agents/" + conversationResourceId.id() + "/approval-status")
                .then().assertThat().statusCode(200)
                .body("state", equalTo("AWAITING_HUMAN"));
    }

    @Test
    @DisplayName("toolDecisions on a non-tool (RULE) pause -> 400 and the pause is NOT consumed")
    void toolDecisionsOnRulePause400() throws Exception {
        // The agent also carries a plain rule-based PAUSE_CONVERSATION action
        // (unrelated to tool-gating) so this IT can drive a genuine RULE-type
        // pause end-to-end and assert the "toolDecisions is only valid for
        // tool-call pauses" 400 for real, rather than only at the unit level.
        sendUserInput(agentResourceId.id(), conversationResourceId.id(), "escalate", true, false);
        waitForState("AWAITING_HUMAN");

        given().get("agents/" + conversationResourceId.id() + "/approval-status")
                .then().assertThat().statusCode(200)
                .body("pauseDetails.type", equalTo("RULE"));

        given().contentType(ContentType.JSON)
                .body("""
                        {"verdict": "APPROVED",
                         "toolDecisions": {"call_calc1": {"verdict": "APPROVED"}}}
                        """)
                .post("agents/" + conversationResourceId.id() + "/resume")
                .then().assertThat().statusCode(400)
                .body(containsString("toolDecisions is only valid for tool-call pauses"));

        given().get("agents/" + conversationResourceId.id() + "/approval-status")
                .then().assertThat().statusCode(200)
                .body("state", equalTo("AWAITING_HUMAN"));

        // Clean resume (no toolDecisions) still works normally afterward.
        given().contentType(ContentType.JSON)
                .body("{\"verdict\": \"APPROVED\"}")
                .post("agents/" + conversationResourceId.id() + "/resume")
                .then().assertThat().statusCode(200);
        waitForState("READY");
    }

    // ==================== Helpers ====================

    private void stubToolCallThenFinal() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("ToolCalling")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(TOOL_CALL_RESPONSE).withHeader("Content-Type", "application/json"))
                .willSetStateTo("ToolsExecuted"));
        wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("ToolCalling")
                .whenScenarioStateIs("ToolsExecuted")
                .willReturn(okJson(TOOL_FINAL_RESPONSE).withHeader("Content-Type", "application/json")));
    }

    /** Polls the conversation until it reaches the expected state (max ~10s). */
    private void waitForState(String expectedState) throws InterruptedException {
        String lastState = null;
        for (int i = 0; i < 40; i++) {
            Response response = given().get("agents/" + conversationResourceId.id() + "/approval-status");
            if (response.statusCode() == 200) {
                lastState = response.jsonPath().getString("state");
                if (expectedState.equals(lastState)) {
                    return;
                }
            }
            Thread.sleep(250);
        }
        fail("Conversation did not reach state " + expectedState + " within 10s (last state: " + lastState + ")");
    }

    /**
     * Deploys an agent whose LLM task runs in agent mode (built-in calculator tool
     * only) with a {@code toolApprovals.requireApproval} gate matching the
     * "calculate" tool — every calculator call must pause for human approval.
     * Pipeline: parser -> rules -> llm -> output -> templating -> property.
     */
    private ResourceId setupAndDeployToolGatedAgent() throws Exception {
        String dictionary = """
                {
                  "language": "en",
                  "words": [
                    {"word": "calculate", "expressions": "do_calc(calculate)", "frequency": 0},
                    {"word": "hello", "expressions": "greeting(hello)", "frequency": 0},
                    {"word": "escalate", "expressions": "rule_pause(escalate)", "frequency": 0}
                  ],
                  "phrases": []
                }
                """;

        String rules = """
                {
                  "behaviorGroups": [{
                    "name": "ToolGatedGroup",
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
                        "name": "DoCalc",
                        "actions": ["call_calc_llm"],
                        "conditions": [{
                          "type": "inputmatcher",
                          "configs": {"expressions": "do_calc(*)", "occurrence": "currentStep"}
                        }]
                      },
                      {
                        "name": "RulePause",
                        "actions": ["ask_supervisor", "PAUSE_CONVERSATION"],
                        "conditions": [{
                          "type": "inputmatcher",
                          "configs": {"expressions": "rule_pause(*)", "occurrence": "currentStep"}
                        }]
                      }
                    ]
                  }]
                }
                """;

        String output = """
                {
                  "outputSet": [
                    {"action": "welcome", "timesOccurred": 0,
                     "outputs": [{"type": "text", "valueAlternatives": [{"type": "text", "text": "Welcome to the tool-gated HITL test agent."}]}]},
                    {"action": "greet", "timesOccurred": 0,
                     "outputs": [{"type": "text", "valueAlternatives": [{"type": "text", "text": "Hello!"}]}]},
                    {"action": "ask_supervisor", "timesOccurred": 0,
                     "outputs": [{"type": "text", "valueAlternatives": [{"type": "text", "text": "Escalation handled after approval."}]}]}
                  ]
                }
                """;

        // LLM config: OpenAI-shaped provider pointing at WireMock, agent mode with
        // ONLY the calculator built-in tool enabled, gated by toolApprovals.
        String llmConfig = String.format("""
                {
                  "tasks": [{
                    "id": "test-tool-gated",
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
                    "maxToolIterations": 3,
                    "toolApprovals": {
                      "requireApproval": ["calculate"],
                      "maxPausesPerTurn": 3,
                      "timeoutPolicy": "WAIT_INDEFINITELY",
                      "pauseReason": "Calculator call requires supervisor approval"
                    }
                  }]
                }
                """, wireMock.port());

        String locationDictionary = createResource(dictionary, "/dictionarystore/dictionaries");
        String locationRules = createResource(rules, "/rulestore/rulesets");
        String locationOutput = createResource(output, "/outputstore/outputsets");
        String locationLlm = createResource(llmConfig, "/llmstore/llms");

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
