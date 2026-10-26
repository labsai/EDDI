/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of the {@code expression} output guardrail through the
 * running app, with a WireMock fake OpenAI endpoint standing in for the task's
 * model. The guardrail calls no second model, so every call WireMock sees is
 * the task model's: the number of calls is the number of attempts.
 * <p>
 * What a client sees is asserted through the REST conversation response; the
 * guardrail's audit record (reason {@code expression_failed:<ISSUE>} and so on)
 * is read from the audit ledger, flushed asynchronously, so it is polled.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
public class ExpressionGuardrailIT extends BaseIntegrationIT {

    private static final String PATH = "/v1/chat/completions";
    private static final String MODEL = "gpt-4o-test";
    private static final String BLOCK_MESSAGE = "BLOCKED-BY-EXPRESSION-GUARDRAIL";
    private static final String FIX = "Make total equal the sum of the line amounts, to the cent.";
    private static final String HINT_HEADER = "Your previous reply needs these corrections:";
    private static final String HINT_FOOTER = "Write a new reply that applies them. Reply with the answer only.";
    private static final String LINE_SUM = "sum(lines[*].amount) == total";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static WireMockServer wireMock;

    /** The conversation id of the last {@link #talk} call. */
    private String lastConversationId;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    // ==================== 1. Pass ====================

    @Test
    @DisplayName("a JSON answer that satisfies the expression is delivered after one model call")
    void passDeliversTheAnswer() throws Exception {
        stubSequence(invoice("PASS-OK", "30.30", "10.10", "20.20"));

        String body = talk(deploy(guardrail("reprompt", 1, LINE_SUM, null)));

        assertTrue(body.contains("PASS-OK"), "the answer must reach the user: " + body);
        assertFalse(body.contains(BLOCK_MESSAGE), body);
        assertCalls(1);
        assertFalse(auditOf().contains("expression_"), "a pass records no finding");
    }

    @Test
    @DisplayName("the answer may come in a Markdown code fence with prose around it")
    void fencedAnswerPasses() throws Exception {
        stubSequence("Here is the invoice:\n```json\n" + invoice("FENCED-OK", "30.30", "10.10", "20.20") + "\n```");

        String body = talk(deploy(guardrail("reprompt", 1, LINE_SUM, null)));

        assertTrue(body.contains("FENCED-OK"), body);
        assertFalse(body.contains(BLOCK_MESSAGE), body);
        assertCalls(1);
    }

    // ==================== 2. Fail, then repair ====================

    @Test
    @DisplayName("a false expression re-prompts with EDDI's frame around the author's fix text and the repaired answer is delivered")
    void failThenRepair() throws Exception {
        stubSequence(invoice("DRAFT-ONE", "31.00", "10.10", "20.20"), invoice("FINAL-TWO", "30.30", "10.10", "20.20"));

        String body = talk(deploy(guardrail("reprompt", 1, LINE_SUM, null)));

        assertTrue(body.contains("FINAL-TWO"), "the repaired answer must be delivered: " + body);
        assertFalse(body.contains("DRAFT-ONE"), "the rejected draft must not reach the user: " + body);
        assertCalls(2);

        List<LoggedRequest> sent = requests();
        String first = sent.get(0).getBodyAsString();
        String second = sent.get(1).getBodyAsString();
        assertFalse(first.contains(HINT_HEADER), "the first request carries no correction: " + first);
        assertTrue(second.contains(HINT_HEADER), "corrective header missing: " + second);
        assertTrue(second.contains(FIX), "the author's fix text must be sent: " + second);
        assertTrue(second.contains(HINT_FOOTER), "corrective footer missing: " + second);
        assertTrue(second.contains("DRAFT-ONE"), "the rejected answer travels as the assistant turn: " + second);
        // Closed vocabulary: nothing computed from the answer (the true sum) is fed
        // back.
        String correction = second.substring(second.indexOf(HINT_HEADER));
        assertFalse(correction.contains("30.3"), "no computed value may be in the corrective message: " + correction);

        String audit = auditOf();
        assertTrue(audit.contains("expression_failed:LINE_SUM"), "the finding is recorded in the audit ledger: " + audit);
    }

    // ==================== 3. Exhausted / block ====================

    @Test
    @DisplayName("reprompt attempts exhausted: the block message is the answer and no attempt leaks")
    void exhaustedBlocks() throws Exception {
        stubSequence(invoice("ATTEMPT-A", "31.00", "10.10", "20.20"), invoice("ATTEMPT-B", "99.00", "10.10", "20.20"));

        String body = talk(deploy(guardrail("reprompt", 1, LINE_SUM, null)));

        assertTrue(body.contains(BLOCK_MESSAGE), "the block message must be delivered: " + body);
        assertFalse(body.contains("ATTEMPT-A") || body.contains("ATTEMPT-B"), "no attempt may leak past the block: " + body);
        assertCalls(2);
        assertTrue(auditOf().contains("expression_failed:LINE_SUM"));
    }

    @Test
    @DisplayName("action block: a wrong total is never delivered and the model is not re-asked")
    void blockActionWithholds() throws Exception {
        stubSequence(invoice("WRONG-TOTAL", "31.00", "10.10", "20.20"));

        String body = talk(deploy(guardrail("block", 0, LINE_SUM, null)));

        assertTrue(body.contains(BLOCK_MESSAGE), body);
        assertFalse(body.contains("WRONG-TOTAL"), body);
        assertCalls(1);
    }

    @Test
    @DisplayName("action warn: the wrong answer is delivered and the finding is audited")
    void warnActionDelivers() throws Exception {
        stubSequence(invoice("WARNED-ANSWER", "31.00", "10.10", "20.20"));

        String body = talk(deploy(guardrail("warn", 0, LINE_SUM, null)));

        assertTrue(body.contains("WARNED-ANSWER"), body);
        assertFalse(body.contains(BLOCK_MESSAGE), body);
        assertCalls(1);
        assertTrue(auditOf().contains("expression_failed:LINE_SUM"));
    }

    // ==================== 4. Not JSON / cannot decide ====================

    @Test
    @DisplayName("non-JSON answer, default failMode: withheld without a reprompt")
    void nonJsonClosedBlocks() throws Exception {
        stubSequence("Sure, the total is thirty dollars and thirty cents. UNVERIFIED-PROSE");

        String body = talk(deploy(guardrail("reprompt", 1, LINE_SUM, null)));

        assertTrue(body.contains(BLOCK_MESSAGE), body);
        assertFalse(body.contains("UNVERIFIED-PROSE"), body);
        assertCalls(1);
        assertTrue(auditOf().contains("expression_unparseable"));
    }

    @Test
    @DisplayName("non-JSON answer, failMode open: delivered, and the gap is audited")
    void nonJsonOpenDelivers() throws Exception {
        stubSequence("Plain prose, OPEN-PROSE");

        String body = talk(deploy(guardrail("reprompt", 1, LINE_SUM, "open")));

        assertTrue(body.contains("OPEN-PROSE"), body);
        assertFalse(body.contains(BLOCK_MESSAGE), body);
        assertCalls(1);
        assertTrue(auditOf().contains("expression_unparseable"));
    }

    @Test
    @DisplayName("an answer repeating a key is unparseable, never a pass")
    void duplicateKeyIsUnparseable() throws Exception {
        stubSequence("{\"memo\":\"DUP-KEY\",\"total\":30.30,\"total\":99.00,\"lines\":[{\"amount\":10.10},{\"amount\":20.20}]}");

        String body = talk(deploy(guardrail("reprompt", 1, LINE_SUM, null)));

        assertTrue(body.contains(BLOCK_MESSAGE), body);
        assertFalse(body.contains("DUP-KEY"), body);
        assertCalls(1);
        assertTrue(auditOf().contains("expression_unparseable"));
    }

    @Test
    @DisplayName("a missing amount inside sum() is could-not-decide (null_operand), failing closed")
    void nullOperandClosedBlocks() throws Exception {
        stubSequence("{\"memo\":\"NULL-LINE\",\"total\":10.10,\"lines\":[{\"amount\":10.10},{\"label\":\"no amount\"}]}");

        String body = talk(deploy(guardrail("reprompt", 1, LINE_SUM, null)));

        assertTrue(body.contains(BLOCK_MESSAGE), body);
        assertFalse(body.contains("NULL-LINE"), body);
        assertCalls(1);
        assertTrue(auditOf().contains("expression_undecided:null_operand"));
    }

    @Test
    @DisplayName("a non-boolean expression is could-not-decide (not_boolean), delivered under failMode open")
    void notBooleanOpenDelivers() throws Exception {
        stubSequence(invoice("NOT-BOOL", "30.30", "10.10", "20.20"));

        String body = talk(deploy(guardrail("reprompt", 1, "sum(lines[*].amount)", "open")));

        assertTrue(body.contains("NOT-BOOL"), body);
        assertCalls(1);
        assertTrue(auditOf().contains("expression_undecided:not_boolean"));
    }

    // ==================== 5. Decimal precision ====================

    @Test
    @DisplayName("decimal arithmetic is exact: 0.1 + 0.2 == 0.3 passes, where binary floating point fails")
    void decimalSumIsExact() throws Exception {
        // In IEEE-754 doubles 0.1 + 0.2 is 0.30000000000000004, which is not 0.3.
        stubSequence("{\"memo\":\"EXACT-OK\",\"a\":0.1,\"b\":0.2,\"c\":0.3}");

        String body = talk(deploy(guardrail("block", 0, "a + b == c", null)));

        assertTrue(body.contains("EXACT-OK"), "0.1 + 0.2 must equal 0.3 exactly: " + body);
        assertFalse(body.contains(BLOCK_MESSAGE), body);
        assertCalls(1);
    }

    @Test
    @DisplayName("decimal sum of line amounts matches the total to the cent, and a one-cent difference is caught")
    void oneCentDifferenceIsCaught() throws Exception {
        stubSequence(invoice("ONE-CENT-OFF", "30.31", "10.10", "20.20"));

        String body = talk(deploy(guardrail("block", 0, LINE_SUM, null)));

        assertTrue(body.contains(BLOCK_MESSAGE), body);
        assertFalse(body.contains("ONE-CENT-OFF"), body);
        assertTrue(auditOf().contains("expression_failed:LINE_SUM"));
    }

    // ==================== 6. Sandbox and bounds, when the config is used
    // ====================
    //
    // The expression, issue code and fix are checked when the agent is deployed
    // (docs/langchain.md: "All three are checked at deploy time"), not when the
    // langchain resource is stored: the store accepts the document (201) and the
    // deployment lands in ERROR, so the agent cannot serve a single turn.

    @Test
    @DisplayName("an invalid expression is stored but its agent refuses to deploy")
    void syntaxErrorRefusesDeploy() throws Exception {
        assertDeployRefused(guardrail("reprompt", 1, "sum(lines[*].amount) = total", null));
    }

    @Test
    @DisplayName("an expression longer than 2000 characters refuses to deploy")
    void tooLongRefusesDeploy() throws Exception {
        assertDeployRefused(guardrail("reprompt", 1, "total == 1" + " + 0".repeat(700), null));
        assertTrue(("total == 1" + " + 0".repeat(700)).length() > 2000);
    }

    @Test
    @DisplayName("an expression nested deeper than 32 levels refuses to deploy")
    void tooDeepRefusesDeploy() throws Exception {
        assertDeployRefused(guardrail("reprompt", 1, "(".repeat(40) + "total" + ")".repeat(40) + " > 0", null));
    }

    @ParameterizedTest(name = "outside the sandbox: {0}")
    @ValueSource(strings = {"eval('1') == 1", "Runtime.getRuntime().exec('x') == 1", "total.getClass() == null", "exec(total) > 0",
            "total @ 1", "${total} > 0", "{#eval total /}"})
    @DisplayName("anything outside the sandbox refuses to deploy")
    void outsideTheSandboxRefusesDeploy(String expr) throws Exception {
        assertDeployRefused(guardrail("reprompt", 1, expr, null));
    }

    @Test
    @DisplayName("a missing or malformed issue code, or a missing fix, refuses to deploy")
    void badIssueOrFixRefusesDeploy() throws Exception {
        Map<String, Object> badIssue = guardrail("reprompt", 1, LINE_SUM, null);
        parametersOf(badIssue).put("issue", "line sum");
        assertDeployRefused(badIssue);

        Map<String, Object> noFix = guardrail("reprompt", 1, LINE_SUM, null);
        parametersOf(noFix).remove("fix");
        assertDeployRefused(noFix);

        Map<String, Object> longFix = guardrail("reprompt", 1, LINE_SUM, null);
        parametersOf(longFix).put("fix", "x".repeat(1001));
        assertDeployRefused(longFix);
    }

    // ==================== Helpers ====================

    /** A JSON invoice answer; {@code memo} tells the attempts apart. */
    private static String invoice(String memo, String total, String first, String second) {
        return "{\"memo\":\"" + memo + "\",\"total\":" + total + ",\"lines\":[{\"amount\":" + first + "},{\"amount\":" + second + "}]}";
    }

    private static String chat(String content) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("id", "chatcmpl-expression-it");
        reply.put("object", "chat.completion");
        reply.put("choices", List.of(choice));
        reply.put("usage", Map.of("prompt_tokens", 10, "completion_tokens", 8, "total_tokens", 18));
        return JSON.writeValueAsString(reply);
    }

    /** Replies in order to the task model; the last reply repeats. */
    private static void stubSequence(String... contents) throws Exception {
        String scenario = "Main";
        for (int i = 0; i < contents.length; i++) {
            String from = i == 0 ? Scenario.STARTED : scenario + i;
            var stub = post(urlPathEqualTo(PATH)).inScenario(scenario).whenScenarioStateIs(from)
                    .withRequestBody(matchingJsonPath("$.model", equalTo(MODEL)))
                    .willReturn(okJson(chat(contents[i])).withHeader("Content-Type", "application/json"));
            if (i < contents.length - 1) {
                stub.willSetStateTo(scenario + (i + 1));
            }
            wireMock.stubFor(stub);
        }
    }

    private static List<LoggedRequest> requests() {
        return wireMock.findAll(postRequestedFor(urlPathEqualTo(PATH)));
    }

    private static void assertCalls(int expected) {
        List<LoggedRequest> seen = requests();
        assertEquals(expected, seen.size(), "calls to the model were: " + seen);
    }

    /** The expression guardrail config, as an author writes it. */
    private static Map<String, Object> guardrail(String action, int maxRetries, String expr, String failMode) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("expr", expr);
        parameters.put("issue", "LINE_SUM");
        parameters.put("fix", FIX);
        if (failMode != null) {
            parameters.put("failMode", failMode);
        }
        Map<String, Object> guardrail = new LinkedHashMap<>();
        guardrail.put("type", "expression");
        guardrail.put("action", action);
        guardrail.put("maxRetries", maxRetries);
        guardrail.put("blockMessage", BLOCK_MESSAGE);
        guardrail.put("parameters", parameters);
        return guardrail;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parametersOf(Map<String, Object> guardrail) {
        return (Map<String, String>) guardrail.get("parameters");
    }

    /**
     * Sends one turn to a fresh conversation and returns the turn's REST response.
     */
    private String talk(ResourceId agent) throws Exception {
        try {
            ResourceId conversation = createConversation(agent.id(), "expressionIT");
            waitForConversationReady(agent.id(), conversation.id());
            lastConversationId = conversation.id();
            return sendUserInput(agent.id(), conversation.id(), "ask", false, false).then().statusCode(200).extract().asString();
        } finally {
            undeployAgentQuietly(agent.id(), agent.version());
        }
    }

    /**
     * The audit-ledger entries of the last conversation, where the guardrail
     * records are collected. The ledger is flushed asynchronously, so poll until
     * the turn's entry has landed.
     */
    private String auditOf() throws Exception {
        String audit = "[]";
        for (int i = 0; i < 40; i++) {
            audit = given().get("/auditstore/" + lastConversationId).then().statusCode(200).extract().asString();
            if (!audit.strip().equals("[]")) {
                break;
            }
            Thread.sleep(500);
        }
        return audit;
    }

    /**
     * Stores an agent carrying the guardrail (the store accepts it), then deploys
     * it and expects the deployment to end in ERROR rather than READY.
     */
    private void assertDeployRefused(Map<String, Object> guardrail) throws Exception {
        ResourceId agent = createAgent(guardrail);
        try {
            given().post(String.format("administration/production/deploy/%s?version=%s&autoDeploy=false&waitForCompletion=true", agent.id(),
                    agent.version()));
            String status = "";
            for (int i = 0; i < 60; i++) {
                Response response = given()
                        .get(String.format("administration/production/deploymentstatus/%s?version=%s&format=text", agent.id(), agent.version()));
                status = response.getBody().asString().trim();
                if ("ERROR".equals(status) || "READY".equals(status)) {
                    break;
                }
                Thread.sleep(500);
            }
            assertEquals("ERROR", status, "an invalid expression guardrail must not deploy, but the status was " + status);
        } finally {
            undeployAgentQuietly(agent.id(), agent.version());
        }
    }

    private ResourceId deploy(Map<String, Object> guardrail) throws Exception {
        ResourceId agentId = createAgent(guardrail);
        deployAgent(agentId.id(), agentId.version());
        return agentId;
    }

    private ResourceId createAgent(Map<String, Object> guardrail) throws Exception {
        String dictionary = """
                {"words": [{"word": "ask", "expressions": "ask_llm(ask)", "frequency": 0}], "phrases": []}
                """;
        String rules = """
                {
                  "behaviorGroups": [{
                    "name": "ExpressionGroup",
                    "behaviorRules": [
                      {"name": "Welcome", "actions": ["welcome"],
                       "conditions": [{"type": "occurrence", "configs": {"maxTimesOccurred": "0", "behaviorRuleName": "Welcome"}}]},
                      {"name": "AskLlm", "actions": ["call_llm"],
                       "conditions": [{"type": "inputmatcher", "configs": {"expressions": "ask_llm(*)", "occurrence": "currentStep"}}]}
                    ]
                  }]
                }
                """;
        String output = """
                {"outputSet": [{"action": "welcome", "timesOccurred": 0,
                  "outputs": [{"valueAlternatives": [{"type": "text", "text": "Welcome to expression test"}]}]}]}
                """;
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("baseUrl", "http://localhost:" + wireMock.port() + "/v1");
        parameters.put("apiKey", "sk-test-fake-key");
        parameters.put("modelName", MODEL);
        parameters.put("systemMessage", "You are a test assistant.");
        parameters.put("addToOutput", "true");
        parameters.put("timeout", "30000");
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", "expression-it");
        task.put("type", "openai");
        task.put("actions", List.of("call_llm"));
        task.put("parameters", parameters);
        task.put("conversationHistoryLimit", 5);
        task.put("outputGuardrails", List.of(guardrail));
        String llmConfig = JSON.writeValueAsString(Map.of("tasks", List.of(task)));

        String locationDictionary = createResource(dictionary, "/dictionarystore/dictionaries");
        String locationRules = createResource(rules, "/rulestore/rulesets");
        String locationOutput = createResource(output, "/outputstore/outputsets");
        String locationLlm = createResource(llmConfig, "/llmstore/llms");
        String workflowBody = String.format("""
                {
                  "workflowSteps": [
                    {"type": "eddi://ai.labs.parser", "config": {},
                     "extensions": {"dictionaries": [{"type": "eddi://ai.labs.parser.dictionaries.regular", "config": {"uri": "%s"}}],
                                    "corrections": []}},
                    {"type": "eddi://ai.labs.rules", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.llm", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.templating", "config": {}},
                    {"type": "eddi://ai.labs.property", "config": {}}
                  ]
                }
                """, locationDictionary, locationRules, locationLlm, locationOutput);
        String locationWorkflow = createResource(workflowBody, "/workflowstore/workflows");
        String agentLocation = createResource(String.format("{\"packages\": [\"%s\"]}", locationWorkflow), "/agentstore/agents");
        return extractResourceId(agentLocation);
    }
}
