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
import io.restassured.path.json.JsonPath;
import io.restassured.path.json.config.JsonPathConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end through the running app and its database: the agent's
 * {@code maxCostPerConversationUsd} and the tenant's {@code maxMonthlyCostUsd}
 * are checked before <em>every</em> paid LLM call of a turn — the task's first
 * call, a same-model re-ask, a cascade escalation, an output guardrail's
 * reprompt and a {@code classifier} guardrail's call — with the spend coming
 * from the {@code usage} block the (WireMock) provider reports, priced at the
 * task's {@code inputPricePer1M} / {@code outputPricePer1M}.
 * <p>
 * Every refusal is asserted the way an operator sees it: the answer the client
 * gets, what the stored conversation records, the tenant usage endpoint, and —
 * the point of each test — how many requests the provider actually received.
 * Every "refused" test has a control that makes the same call when nothing
 * stops it, so a refusal can never pass because the call was never going to
 * happen.
 * <p>
 * Prices: input $10 / 1M tokens, output $20 / 1M. A {@link #CHEAP} reply (500
 * in, 50 out) costs $0.006; a {@link #DEAR} reply (1000 in, 100 out) costs
 * $0.012. The ceilings and budgets are $0.01, so one dear call — or two cheap
 * ones — crosses them.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class LlmSpendChecksIT extends BaseIntegrationIT {

    private static final String COMPLETIONS = "/v1/chat/completions";
    private static final String CLASSIFIER_COMPLETIONS = "/classifier/v1/chat/completions";
    private static final String TASK_ID = "spend-task";
    private static final String FALLBACK = "SPEND_FALLBACK_ANSWER";
    private static final String TENANT = "default";

    private static final double INPUT_PRICE = 10.0;
    private static final double OUTPUT_PRICE = 20.0;
    private static final int[] CHEAP = {500, 50};
    private static final int[] DEAR = {1000, 100};
    private static final double CHEAP_USD = cost(CHEAP);
    private static final double DEAR_USD = cost(DEAR);
    private static final double LIMIT_USD = 0.01;

    /** Small dollar figures must not be read back as floats. */
    private static final JsonPathConfig DOUBLES = new JsonPathConfig(JsonPathConfig.NumberReturnType.DOUBLE);

    private static WireMockServer wireMock;
    private final List<ResourceId> deployed = new ArrayList<>();

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
    void resetProvider() {
        wireMock.resetAll();
    }

    @AfterEach
    void undeploy() {
        deployed.forEach(id -> undeployAgentQuietly(id.id(), id.version()));
        deployed.clear();
    }

    // ==================== Per-conversation ceiling ====================

    @Test
    @Order(1)
    @DisplayName("the call that crosses the ceiling completes; the next turn's call is never sent")
    void ceilingRefusesTheCallAfterTheCrossingOne() throws Exception {
        stubCompletion(reply("CHEAP_ANSWER", CHEAP));
        ResourceId agent = deploySpendAgent(baseTask(""), LIMIT_USD);
        String conversation = start(agent);

        // $0 spent -> call ($0.006); $0.006 < $0.01 -> call ($0.012, crosses it); then
        // refused.
        assertTrue(say(conversation).contains("CHEAP_ANSWER"), "turn 1 is under the ceiling");
        assertTrue(say(conversation).contains("CHEAP_ANSWER"), "turn 2 starts below the ceiling and completes");
        double refusedBefore = metric("eddi_llm_spend_refused_total", "reason=\"conversation_ceiling\"");
        String third = say(conversation);

        assertTrue(third.contains(FALLBACK), "turn 3 is answered with the fallback, not the model: " + third);
        assertFalse(third.contains("CHEAP_ANSWER"), third);
        wireMock.verify(exactly(2), postRequestedFor(urlPathEqualTo(COMPLETIONS)));

        // The third turn's stored step records why the model was not called.
        Map<String, Object> refusal = awaitStoredValue(conversation, "llm:spend_refused:" + TASK_ID);
        assertEquals("conversation_ceiling", refusal.get("reason"));
        assertEquals(2 * CHEAP_USD, auditedCost(conversation, 3), 1e-9, "the recorded spend is exactly the two calls the provider billed");
        assertTrue(metric("eddi_llm_spend_refused_total", "reason=\"conversation_ceiling\"") > refusedBefore,
                "the refusal is counted in eddi_llm_spend_refused_total");
    }

    // ==================== A same-model re-ask is checked too ====================

    @Test
    @Order(2)
    @DisplayName("control: without a ceiling, an empty reply is re-asked")
    void reaskHappensWithoutCeiling() throws Exception {
        stubSequence("Reask", reply("", DEAR), reply("REASKED_ANSWER", CHEAP));
        ResourceId agent = deploySpendAgent(baseTask(retryOnEmpty()), null);
        String conversation = start(agent);

        String answer = say(conversation);

        assertTrue(answer.contains("REASKED_ANSWER"), "the re-ask answers the turn: " + answer);
        wireMock.verify(exactly(2), postRequestedFor(urlPathEqualTo(COMPLETIONS)));
        assertEquals(DEAR_USD + CHEAP_USD, auditedCost(conversation, 1), 1e-9,
                "both attempts are in the turn's audit:cost");
    }

    @Test
    @Order(3)
    @DisplayName("a re-ask that would start past the ceiling is refused, counting the first attempt's unrecorded cost")
    void reaskRefusedByCeiling() throws Exception {
        stubSequence("Reask", reply("", DEAR), reply("REASKED_ANSWER", CHEAP));
        ResourceId agent = deploySpendAgent(baseTask(retryOnEmpty()), LIMIT_USD);
        String conversation = start(agent);
        double skippedBefore = metric("eddi_llm_recovery_total", "outcome=\"skipped_spend\"");

        // The first call starts at $0 (allowed) and costs $0.012 — still not in
        // audit:cost when the re-ask is checked. Only the pending figure can refuse it.
        String answer = say(conversation);

        assertTrue(answer.contains(FALLBACK), "the unresolved reply is replaced by the fallback: " + answer);
        assertFalse(answer.contains("REASKED_ANSWER"), answer);
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo(COMPLETIONS)));
        assertTrue(metric("eddi_llm_recovery_total", "outcome=\"skipped_spend\"") > skippedBefore,
                "the skipped re-ask is counted as eddi_llm_recovery_total{outcome=skipped_spend}");
        assertEquals(DEAR_USD, auditedCost(conversation, 1), 1e-9, "only the call that was made is recorded");
        assertNotNull(awaitStoredValue(conversation, "llm:fallback:" + TASK_ID), "the stored step is flagged as a fallback");
    }

    // ==================== A guardrail reprompt is checked too ====================

    @Test
    @Order(4)
    @DisplayName("control: without a ceiling, an output guardrail reprompts")
    void repromptHappensWithoutCeiling() throws Exception {
        stubSequence("Reprompt", reply("This answer is far longer than twenty characters.", DEAR), reply("Short.", CHEAP));
        ResourceId agent = deploySpendAgent(baseTask(maxLengthReprompt()), null);
        String conversation = start(agent);

        String answer = say(conversation);

        assertTrue(answer.contains("Short."), "the reprompted answer is served: " + answer);
        wireMock.verify(exactly(2), postRequestedFor(urlPathEqualTo(COMPLETIONS)));
    }

    @Test
    @Order(5)
    @DisplayName("a reprompt is refused once the primary answer's (unrecorded) cost reached the ceiling")
    void repromptRefusedByCeiling() throws Exception {
        stubSequence("Reprompt", reply("This answer is far longer than twenty characters.", DEAR), reply("Short.", CHEAP));
        ResourceId agent = deploySpendAgent(baseTask(maxLengthReprompt()), LIMIT_USD);
        String conversation = start(agent);

        String answer = say(conversation);

        assertTrue(answer.contains("REPROMPT_WITHHELD"), "the answer is blocked with the guardrail's blockMessage: " + answer);
        assertFalse(answer.contains("Short."), answer);
        assertFalse(answer.contains("far longer"), "the over-long answer is not delivered either: " + answer);
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo(COMPLETIONS)));
        assertEquals(DEAR_USD, auditedCost(conversation, 1), 1e-9);
    }

    // ==================== A cascade escalation is checked too ====================

    @Test
    @Order(6)
    @DisplayName("control: without a ceiling, an unusable cheap step escalates to the strong step")
    void escalationHappensWithoutCeiling() throws Exception {
        stubCascade();
        ResourceId agent = deploySpendAgent(baseTask(retryOnEmpty() + "," + cascade()), null);
        String conversation = start(agent);

        String answer = say(conversation);

        assertTrue(answer.contains("STRONG_ANSWER"), "the strong step answers: " + answer);
        wireMock.verify(exactly(1),
                postRequestedFor(urlPathEqualTo(COMPLETIONS)).withRequestBody(matchingJsonPath("$.model", equalTo("cheap-model"))));
        wireMock.verify(exactly(1),
                postRequestedFor(urlPathEqualTo(COMPLETIONS)).withRequestBody(matchingJsonPath("$.model", equalTo("strong-model"))));
    }

    @Test
    @Order(7)
    @DisplayName("an escalation is refused once the earlier step's cost reached the ceiling")
    void escalationRefusedByCeiling() throws Exception {
        stubCascade();
        ResourceId agent = deploySpendAgent(baseTask(retryOnEmpty() + "," + cascade()), LIMIT_USD);
        String conversation = start(agent);

        String answer = say(conversation);

        assertFalse(answer.contains("STRONG_ANSWER"), "the strong step must not answer: " + answer);
        assertTrue(answer.contains(FALLBACK), "nothing usable was produced, so the fallback is served: " + answer);
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo(COMPLETIONS)));
        wireMock.verify(exactly(0),
                postRequestedFor(urlPathEqualTo(COMPLETIONS)).withRequestBody(matchingJsonPath("$.model", equalTo("strong-model"))));
        assertEquals(DEAR_USD, auditedCost(conversation, 1), 1e-9, "only the cheap step was billed");
    }

    // ==================== A classifier guardrail's call is checked too
    // ====================

    @Test
    @Order(8)
    @DisplayName("control: without a ceiling, the output classifier is called and passes the answer")
    void classifierCalledWithoutCeiling() throws Exception {
        stubCompletion(reply("PRIMARY_ANSWER", DEAR));
        stubClassifier();
        ResourceId agent = deploySpendAgent(baseTask(outputClassifier()), null);
        String conversation = start(agent);

        String answer = say(conversation);

        assertTrue(answer.contains("PRIMARY_ANSWER"), "the classifier said safe: " + answer);
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo(CLASSIFIER_COMPLETIONS)));
    }

    @Test
    @Order(9)
    @DisplayName("an output classifier call is refused once the answer's cost reached the ceiling; failMode closed blocks")
    void classifierRefusedByCeiling() throws Exception {
        stubCompletion(reply("PRIMARY_ANSWER", DEAR));
        stubClassifier();
        ResourceId agent = deploySpendAgent(baseTask(outputClassifier()), LIMIT_USD);
        String conversation = start(agent);

        String answer = say(conversation);

        assertTrue(answer.contains("CLASSIFIER_WITHHELD"), "could not decide -> closed -> blocked: " + answer);
        assertFalse(answer.contains("PRIMARY_ANSWER"), answer);
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo(COMPLETIONS)));
        wireMock.verify(exactly(0), postRequestedFor(urlPathEqualTo(CLASSIFIER_COMPLETIONS)));
    }

    // ==================== Tenant monthly budget ====================

    @Test
    @Order(10)
    @DisplayName("one conversation's spend is charged to the tenant; a second conversation is then refused")
    void tenantBudgetSpentByOneConversationRefusesTheNext() throws Exception {
        stubCompletion(reply("DEAR_ANSWER", DEAR));
        ResourceId agent = deploySpendAgent(baseTask(""), null);
        withTenantBudget(LIMIT_USD, () -> {
            String first = start(agent);
            assertTrue(say(first).contains("DEAR_ANSWER"), "the budget is untouched, so the first call runs");
            assertEquals(DEAR_USD, awaitMonthlyCost(DEAR_USD), 1e-9, "the settled turn is charged to the tenant's monthly counter");

            String second = start(agent);
            String answer = say(second);

            assertTrue(answer.contains(FALLBACK), "the tenant budget is spent: " + answer);
            wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo(COMPLETIONS)));
            Map<String, Object> refusal = awaitStoredValue(second, "llm:spend_refused:" + TASK_ID);
            assertEquals("tenant_budget", refusal.get("reason"));
            assertEquals(DEAR_USD, usage().getDouble("monthlyCostUsd"), 1e-9, "the refused turn charged nothing");
        });
    }

    @Test
    @Order(11)
    @DisplayName("a re-ask is refused by the tenant budget on the strength of the same turn's unsettled spend")
    void tenantBudgetRefusesReaskWithinTheTurn() throws Exception {
        stubSequence("Reask", reply("", DEAR), reply("REASKED_ANSWER", CHEAP));
        ResourceId agent = deploySpendAgent(baseTask(retryOnEmpty()), null);
        withTenantBudget(LIMIT_USD, () -> {
            String conversation = start(agent);

            // The tenant counter is $0 when the re-ask is checked: only the turn's own
            // unsettled $0.012 can refuse it.
            String answer = say(conversation);

            assertTrue(answer.contains(FALLBACK), "the re-ask is refused, the reply stays unresolved: " + answer);
            wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo(COMPLETIONS)));
            assertEquals(DEAR_USD, awaitMonthlyCost(DEAR_USD), 1e-9, "the call that was made is charged once");
        });
    }

    // ==================== Under budget nothing changes ====================

    @Test
    @Order(12)
    @DisplayName("under both limits a turn completes and its recorded cost is what the provider's usage prices to")
    void underBudgetNothingChanges() throws Exception {
        stubCompletion(reply("NORMAL_ANSWER", CHEAP));
        ResourceId agent = deploySpendAgent(baseTask(""), 1.0);
        withTenantBudget(1.0, () -> {
            String conversation = start(agent);

            String answer = say(conversation);

            assertTrue(answer.contains("NORMAL_ANSWER"), answer);
            assertFalse(answer.contains(FALLBACK), answer);
            wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo(COMPLETIONS)));
            assertEquals(CHEAP_USD, auditedCost(conversation, 1), 1e-9, "audit:cost = 500 x $10/1M + 50 x $20/1M");
            // The stored step carries the model's answer, and nothing about a refusal.
            assertEquals("NORMAL_ANSWER", awaitStoredValue(conversation, "langchain:openai:" + TASK_ID).get("value"));
            JsonPath stored = storedConversation(conversation);
            assertNull(lastStepValue(stored, "llm:spend_refused:" + TASK_ID), "nothing was refused");
            assertNull(lastStepValue(stored, "llm:fallback:" + TASK_ID), "no fallback was served");
            assertEquals(CHEAP_USD, awaitMonthlyCost(CHEAP_USD), 1e-9, "the tenant is charged exactly the turn's cost");
        });
    }

    // ==================== Agent and config builders ====================

    /**
     * The LLM task: priced, with {@code extra} fields. Every task carries the
     * fallback text; when {@code extra} brings no {@code responseValidation} of its
     * own, a block with just the fallback is added.
     */
    private String baseTask(String extra) {
        String fields = extra.contains("\"responseValidation\"")
                ? extra
                : "\"responseValidation\": {\"enabled\": true, \"fallbackMessage\": \"" + FALLBACK + "\"}" + (extra.isEmpty() ? "" : "," + extra);
        return String.format("""
                {
                  "id": "%s",
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
                  "inputPricePer1M": %s,
                  "outputPricePer1M": %s,
                  "conversationHistoryLimit": 5,
                  %s
                }
                """, TASK_ID, wireMock.port(), INPUT_PRICE, OUTPUT_PRICE, fields);
    }

    private static String retryOnEmpty() {
        return """
                "responseValidation": {"enabled": true, "onEmpty": "retry", "maxRetries": 1, "fallbackAction": "fallback",
                  "fallbackMessage": "%s"}""".formatted(FALLBACK);
    }

    private static String maxLengthReprompt() {
        return """
                "outputGuardrails": [{"type": "max-length", "action": "reprompt", "maxRetries": 1,
                  "blockMessage": "REPROMPT_WITHHELD", "parameters": {"maxChars": "20"}}]""";
    }

    private String outputClassifier() {
        return """
                "outputGuardrails": [{"type": "classifier", "action": "block", "blockMessage": "CLASSIFIER_WITHHELD",
                  "parameters": {"provider": "openai", "baseUrl": "http://localhost:%d/classifier/v1", "apiKey": "sk-test-fake-key",
                    "modelName": "guard-model", "timeoutMs": "20000"}}]""".formatted(wireMock.port());
    }

    /**
     * Cheap step answers empty (unusable, no re-ask of its own), strong step
     * answers.
     */
    private static String cascade() {
        String step = """
                {"type": "openai", "parameters": {"modelName": "%s"}, "confidenceThreshold": %s, "maxFormatRetries": 0,
                 "timeoutMs": 20000, "inputPricePer1M": %s, "outputPricePer1M": %s}""";
        return """
                "modelCascade": {"enabled": true, "strategy": "cascade", "evaluationStrategy": "none", "steps": [%s, %s]}"""
                .formatted(step.formatted("cheap-model", "0.5", INPUT_PRICE, OUTPUT_PRICE),
                        step.formatted("strong-model", "null", INPUT_PRICE, OUTPUT_PRICE));
    }

    private ResourceId deploySpendAgent(String llmTask, Double ceilingUsd) throws Exception {
        String dictionary = """
                {"words": [{"word": "ask", "expressions": "ask_llm(ask)", "frequency": 0}], "phrases": []}
                """;
        String rules = """
                {
                  "behaviorGroups": [
                    {"name": "Welcome", "behaviorRules": [{
                      "name": "Welcome", "actions": ["welcome"],
                      "conditions": [{"type": "occurrence", "configs": {"maxTimesOccurred": "0", "behaviorRuleName": "Welcome"}}]
                    }]},
                    {"name": "Ask", "behaviorRules": [{
                      "name": "AskLlm", "actions": ["call_llm"],
                      "conditions": [{"type": "inputmatcher", "configs": {"expressions": "ask_llm(*)", "occurrence": "currentStep"}}]
                    }]}
                  ]
                }
                """;
        String output = """
                {"outputSet": [{"action": "welcome", "timesOccurred": 0,
                  "outputs": [{"valueAlternatives": [{"type": "text", "text": "Welcome to the spend test"}]}]}]}
                """;
        String locationDictionary = createResource(dictionary, "/dictionarystore/dictionaries");
        String locationRules = createResource(rules, "/rulestore/rulesets");
        String locationOutput = createResource(output, "/outputstore/outputsets");
        String locationLlm = createResource("{\"tasks\": [" + llmTask + "]}", "/llmstore/llms");
        String workflow = String.format("""
                {
                  "workflowSteps": [
                    {"type": "eddi://ai.labs.parser", "config": {}, "extensions": {
                      "dictionaries": [{"type": "eddi://ai.labs.parser.dictionaries.regular", "config": {"uri": "%s"}}],
                      "corrections": []}},
                    {"type": "eddi://ai.labs.rules", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.llm", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.templating", "config": {}},
                    {"type": "eddi://ai.labs.property", "config": {}}
                  ]
                }
                """, locationDictionary, locationRules, locationLlm, locationOutput);
        String locationWorkflow = createResource(workflow, "/workflowstore/workflows");
        String agentBody = ceilingUsd == null
                ? "{\"packages\": [\"%s\"]}".formatted(locationWorkflow)
                : "{\"packages\": [\"%s\"], \"maxCostPerConversationUsd\": %s}".formatted(locationWorkflow, ceilingUsd);
        ResourceId agent = extractResourceId(createResource(agentBody, "/agentstore/agents"));
        deployAgent(agent.id(), agent.version());
        deployed.add(agent);
        return agent;
    }

    // ==================== Provider stubs ====================

    private static String reply(String content, int[] usage) {
        return """
                {"id": "chatcmpl-spend", "object": "chat.completion",
                 "choices": [{"index": 0, "message": {"role": "assistant", "content": "%s"}, "finish_reason": "stop"}],
                 "usage": {"prompt_tokens": %d, "completion_tokens": %d, "total_tokens": %d}}
                """.formatted(content, usage[0], usage[1], usage[0] + usage[1]);
    }

    private static void stubCompletion(String body) {
        wireMock.stubFor(post(urlPathEqualTo(COMPLETIONS)).willReturn(okJson(body)));
    }

    private static void stubSequence(String scenario, String first, String second) {
        wireMock.stubFor(post(urlPathEqualTo(COMPLETIONS)).inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(first)).willSetStateTo("second"));
        wireMock.stubFor(post(urlPathEqualTo(COMPLETIONS)).inScenario(scenario).whenScenarioStateIs("second")
                .willReturn(okJson(second)));
    }

    private static void stubCascade() {
        wireMock.stubFor(post(urlPathEqualTo(COMPLETIONS)).withRequestBody(matchingJsonPath("$.model", equalTo("cheap-model")))
                .willReturn(okJson(reply("", DEAR))));
        wireMock.stubFor(post(urlPathEqualTo(COMPLETIONS)).withRequestBody(matchingJsonPath("$.model", equalTo("strong-model")))
                .willReturn(okJson(reply("STRONG_ANSWER", CHEAP))));
    }

    private static void stubClassifier() {
        wireMock.stubFor(post(urlPathEqualTo(CLASSIFIER_COMPLETIONS)).willReturn(okJson(reply("no", new int[]{10, 1}))));
    }

    // ==================== Conversation and REST helpers ====================

    private String start(ResourceId agent) throws InterruptedException {
        ResourceId conversation = createConversation(agent.id(), "spendUser");
        waitForConversationReady(agent.id(), conversation.id());
        return conversation.id();
    }

    /** One turn; the whole conversation as the client gets it back. */
    private String say(String conversationId) {
        return sendUserInput(null, conversationId, "ask", false, true).then().statusCode(200).extract().asString();
    }

    /**
     * The stored conversation's latest step with every data key (the read returns
     * the current step only).
     */
    private JsonPath storedConversation(String conversationId) {
        return getConversationLog(null, conversationId, true).then().statusCode(200).extract().jsonPath().using(DOUBLES);
    }

    /**
     * {@code key}'s value in the stored conversation's latest step, waiting briefly
     * for the turn to be persisted; fails with the step's keys when it never
     * appears.
     */
    private Map<String, Object> awaitStoredValue(String conversationId, String key) throws InterruptedException {
        JsonPath stored = null;
        for (int i = 0; i < 40; i++) {
            stored = storedConversation(conversationId);
            Map<String, Object> value = lastStepValue(stored, key);
            if (value != null) {
                return value;
            }
            Thread.sleep(250);
        }
        List<Map<String, Object>> steps = stored.getList("conversationSteps");
        Object lastKeys = steps.isEmpty() ? List.of() : steps.get(steps.size() - 1).get("conversationStep");
        throw new AssertionError("'" + key + "' never appeared in the stored step: " + lastKeys);
    }

    /** {@code key}'s value in the last step, or null. */
    private static Map<String, Object> lastStepValue(JsonPath conversation, String key) {
        List<Map<String, Object>> steps = conversation.getList("conversationSteps");
        Object value = valueOf(steps.get(steps.size() - 1), key);
        if (value == null) {
            return null;
        }
        return value instanceof Map<?, ?> m ? castMap(m) : Map.of("value", value);
    }

    /** {@code key}'s value in one step of a detailed snapshot, or null. */
    private static Object valueOf(Map<String, Object> step, String key) {
        if (step.get("conversationStep") instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> m && key.equals(m.get("key"))) {
                    return m.get("value");
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /**
     * The conversation's recorded LLM spend as an operator reads it: the
     * {@code cost} of every LLM-task entry in the audit ledger, which is the step's
     * {@code audit:cost} (the REST snapshot withholds {@code audit:*} keys). The
     * ledger is written in batches, so this waits until the entries of all
     * {@code llmTurns} turns are there.
     */
    private static double auditedCost(String conversationId, int llmTurns) throws InterruptedException {
        List<Map<String, Object>> llmEntries = List.of();
        for (int i = 0; i < 60; i++) {
            List<Map<String, Object>> entries = given().get("/auditstore/" + conversationId + "?limit=500").then().statusCode(200).extract()
                    .jsonPath().using(DOUBLES).getList("$");
            llmEntries = entries.stream().filter(e -> "langchain".equals(e.get("taskType"))).toList();
            if (llmEntries.size() >= llmTurns) {
                break;
            }
            Thread.sleep(500);
        }
        assertEquals(llmTurns, llmEntries.size(), "one audit entry per LLM turn: " + llmEntries);
        double total = 0.0;
        for (Map<String, Object> entry : llmEntries) {
            if (entry.get("cost") instanceof Number n) {
                total += n.doubleValue();
            }
        }
        return total;
    }

    private static double cost(int[] usage) {
        return usage[0] * INPUT_PRICE / 1_000_000.0 + usage[1] * OUTPUT_PRICE / 1_000_000.0;
    }

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    /**
     * Runs {@code body} with the default tenant's monthly budget on, and turns it
     * off again.
     */
    private void withTenantBudget(double budgetUsd, Body body) throws Exception {
        putQuota(budgetUsd, true);
        given().contentType(ContentType.JSON).post("/administration/quotas/" + TENANT + "/usage/reset").then().statusCode(lessThan(300));
        try {
            body.run();
        } finally {
            putQuota(-1, false);
            given().contentType(ContentType.JSON).post("/administration/quotas/" + TENANT + "/usage/reset");
        }
    }

    private static void putQuota(double maxMonthlyCostUsd, boolean enabled) {
        String quota = """
                {"tenantId": "%s", "maxConversationsPerDay": -1, "maxAgentsPerTenant": -1, "maxApiCallsPerMinute": -1,
                 "maxMonthlyCostUsd": %s, "enabled": %s}""".formatted(TENANT, maxMonthlyCostUsd, enabled);
        int status = given().contentType(ContentType.JSON).body(quota).put("/administration/quotas/" + TENANT).statusCode();
        assertTrue(status == 200 || status == 204, "quota update returned " + status);
    }

    private static JsonPath usage() {
        return given().get("/administration/quotas/" + TENANT + "/usage").then().statusCode(200).extract().jsonPath().using(DOUBLES);
    }

    /**
     * The tenant's monthly cost once it reaches {@code expected} (the charge is
     * made at settle).
     */
    private static double awaitMonthlyCost(double expected) throws InterruptedException {
        double seen = 0.0;
        for (int i = 0; i < 20; i++) {
            seen = usage().getDouble("monthlyCostUsd");
            if (seen >= expected - 1e-9) {
                return seen;
            }
            Thread.sleep(250);
        }
        return seen;
    }

    /**
     * The sum of a Prometheus counter's series whose labels contain
     * {@code labelFragment}.
     */
    private static double metric(String name, String labelFragment) {
        String scrape = given().accept("text/plain").get("/q/metrics").then().statusCode(200).extract().asString();
        double total = 0.0;
        for (String line : scrape.split("\n")) {
            if (line.startsWith(name + "{") && line.contains(labelFragment)) {
                total += Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1).trim());
            }
        }
        return total;
    }
}
