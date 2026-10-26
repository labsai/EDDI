/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for {@code hitlConfig.onRejection: REVISE} (E2):
 * a behavior rule pauses the turn AFTER the model answered, a human rejects the
 * draft with a note through the real REST {@code /resume} endpoint, and the
 * same model revises its answer with that note.
 * <p>
 * No seam is mocked except the model provider (WireMock stands in for OpenAI,
 * as in {@link LlmAgentEngineIT}). The point is the persistence boundary: the
 * draft and its frozen request are kept in the paused step
 * ({@code hitl:revision:candidate}), written by one request and read back from
 * the store by the next. Every field the revision depends on is asserted on the
 * stored document re-read through REST, and on the request the model actually
 * received — an in-memory test cannot tell a store loader that drops the
 * candidate from one that keeps it (the #1118 lesson).
 * <p>
 * {@code PostgresHitlRevisionIT} runs the same tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
public class HitlRevisionIT extends BaseIntegrationIT {

    private static final String TEST_USER_ID = "hitlRevisionUser";
    private static final String CHAT_PATH = "/v1/chat/completions";
    private static final String CANDIDATE_KEY = "hitl:revision:candidate";
    private static final String OUTCOME_KEY = "hitl:revision:outcome";
    private static final String AUDIT_COMPILED_PROMPT_KEY = "audit:compiled_prompt";

    /**
     * Unique marker: the system prompt must reach the model and never a raw read.
     */
    private static final String SYSTEM_PROMPT_MARKER = "SYSPROMPT-7Q4Z";
    private static final String SYSTEM_MESSAGE = SYSTEM_PROMPT_MARKER + " You draft customer replies for the refunds desk.";
    private static final String DRAFT = "DRAFT-ALPHA: your refund is on its way.";
    private static final String REVISION = "REVISED-BRAVO: your refund of 40 EUR arrives on 12 March.";
    private static final String NOTE = "Mention the refund date, which is 12 March.";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static WireMockServer wireMock;
    private static ResourceId agentResourceId;

    private ResourceId conversationResourceId;

    @BeforeAll
    static void startWireMock() {
        // Reset per class: the Postgres twin runs in the same JVM and must deploy
        // its own agent into its own database.
        agentResourceId = null;
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (agentResourceId != null) {
            undeployAgentQuietly(agentResourceId.id(), agentResourceId.version());
        }
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        if (agentResourceId == null) {
            agentResourceId = setupAndDeployReviewedDrafter();
        }
        wireMock.resetAll();
        wireMock.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .inScenario("Revision")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(chatReply("chatcmpl-draft", DRAFT)))
                .willSetStateTo("Drafted"));
        wireMock.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .inScenario("Revision")
                .whenScenarioStateIs("Drafted")
                .willReturn(okJson(chatReply("chatcmpl-revision", REVISION)))
                .willSetStateTo("Revised"));
        wireMock.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .inScenario("Revision")
                .whenScenarioStateIs("Revised")
                .willReturn(okJson(chatReply("chatcmpl-unexpected", "UNEXPECTED-THIRD-CALL"))));

        conversationResourceId = createConversation(agentResourceId.id(), TEST_USER_ID);
        waitForConversationReady(agentResourceId.id(), conversationResourceId.id());
    }

    // ==================== reject with a note → revise → approve
    // ====================

    @Test
    @DisplayName("REJECTED with a note: the model revises with the note, the rule re-pauses, APPROVED delivers the revision")
    void rejectWithNoteRevisesThenApproveDelivers() throws Exception {
        String conversationId = conversationResourceId.id();
        sendUserInput(agentResourceId.id(), conversationId, "draft", false, false).then().statusCode(200);
        waitForState("AWAITING_HUMAN");
        assertEquals(1, chatRequests().size(), "the draft is one model call");

        // The candidate is persisted with the pause: re-read the stored document.
        JsonNode stored = readRaw(conversationId);
        JsonNode candidate = lastStepData(stored, CANDIDATE_KEY);
        assertNotNull(candidate, "the paused step must keep the revision candidate in the store: " + stored);
        assertEquals(DRAFT, candidate.path("draft").asText(), "the stored candidate carries the draft");
        assertEquals(0, candidate.path("roundsUsed").asInt(-1), "no revision has been used yet");
        assertTrue(candidate.path("addedToOutput").asBoolean(), "the draft was added to the output");
        assertTrue(candidate.path("lifecycleIndex").asInt(-1) >= 0, "the candidate is stamped with its pipeline position");
        assertFalse(candidate.path("workflowId").asText("").isBlank(), "the candidate names its workflow");
        assertEquals("draft-reply", candidate.path("llmTaskId").asText(), "the candidate names the LLM task");
        // The frozen request is kept in the store (the revision below is asked over
        // it), but the raw read must not serve it: it holds the compiled system prompt.
        assertRawReadWithholdsFrozenRequest(stored, candidate);

        // ... but never served by the detailed conversation view.
        String detailedWhilePaused = getConversationLog(agentResourceId.id(), conversationId, true).then().statusCode(200).extract().asString();
        assertFalse(detailedWhilePaused.contains(CANDIDATE_KEY), "returnDetailed must withhold the revision candidate");

        // Reject with a note through the real REST endpoint.
        resume("{\"verdict\": \"REJECTED\", \"note\": \"" + NOTE + "\"}");

        // The rule that paused on the draft pauses again on the revision.
        waitForState("AWAITING_HUMAN");
        List<LoggedRequest> requests = chatRequests();
        assertEquals(2, requests.size(), "the revision is exactly one more model call");
        JsonNode revisionRequest = JSON.readTree(requests.get(1).getBodyAsString());
        String revisionBody = revisionRequest.toString();
        assertTrue(revisionBody.contains(NOTE), "the reviewer's note must reach the model: " + revisionBody);
        assertTrue(revisionBody.contains(SYSTEM_PROMPT_MARKER),
                "the revision is asked over the frozen system message — so the store kept the transcript the raw read withheld");
        assertFalse(revisionRequest.has("tools"), "the revision is tool-less");
        JsonNode messages = revisionRequest.path("messages");
        JsonNode draftTurn = messages.get(messages.size() - 2);
        JsonNode noteTurn = messages.get(messages.size() - 1);
        assertEquals("assistant", draftTurn.path("role").asText(), "the draft is the assistant turn");
        assertEquals(DRAFT, draftTurn.path("content").asText());
        assertEquals("user", noteTurn.path("role").asText(), "the note is the user turn");
        assertTrue(noteTurn.path("content").asText().contains(NOTE));

        // The second pause keeps the revision, with the round counted, in the store.
        JsonNode secondStored = readRaw(conversationId);
        JsonNode secondCandidate = lastStepData(secondStored, CANDIDATE_KEY);
        assertNotNull(secondCandidate, "the second pause must persist a candidate for the revision");
        assertRawReadWithholdsFrozenRequest(secondStored, secondCandidate);
        assertEquals(REVISION, secondCandidate.path("draft").asText());
        assertEquals(1, secondCandidate.path("roundsUsed").asInt(-1), "the round count rides on the stored candidate");
        JsonNode pausedOutput = lastConversationOutput(JSON.readTree(getConversationLog(agentResourceId.id(), conversationId, true)
                .then().statusCode(200).extract().asString()));
        assertEquals("REJECTED", pausedOutput.path("hitlDecision").asText(),
                "while the revision awaits review, hitlDecision is the decision on the draft: " + pausedOutput);
        assertEquals("1", pausedOutput.path("hitlRevision").asText(), "and hitlRevision numbers the revision: " + pausedOutput);
        assertTrue(pausedOutput.toString().contains("REVISED-BRAVO") && !pausedOutput.toString().contains("DRAFT-ALPHA"),
                "the reviewer sees the revision in place of the draft: " + pausedOutput);

        // Approve the revision.
        resume("{\"verdict\": \"APPROVED\", \"note\": \"good now\"}");
        waitForState("READY");
        assertEquals(2, chatRequests().size(), "approving delivers; it calls the model no more");

        // The stored conversation, re-read after the resume.
        JsonNode detailed = JSON.readTree(getConversationLog(agentResourceId.id(), conversationId, true)
                .then().statusCode(200).extract().asString());
        assertEquals("READY", detailed.path("conversationState").asText());
        JsonNode outcome = lastStepData(detailed, OUTCOME_KEY);
        assertNotNull(outcome, "the step records what the rejection did: " + detailed);
        assertEquals("revised", outcome.path("outcome").asText());
        assertEquals(1, outcome.path("round").asInt());

        JsonNode lastOutput = lastConversationOutput(detailed);
        String lastOutputText = lastOutput.toString();
        assertTrue(lastOutputText.contains("REVISED-BRAVO"), "the delivered turn carries the revision: " + lastOutputText);
        assertFalse(lastOutputText.contains("DRAFT-ALPHA"), "the draft is cut back, not shown beside the revision: " + lastOutputText);
        assertEquals("1", lastOutput.path("hitlRevision").asText(), "{memory.current.hitlRevision} is the revision's number");
        assertEquals("APPROVED", lastOutput.path("hitlDecision").asText(),
                "the latest decision of the turn is the approval of the revision; " + lastOutputText);

        assertNull(lastStepData(readRaw(conversationId), CANDIDATE_KEY), "a decided pause removes the candidate from the stored step");
    }

    // ==================== the round limit ====================

    @Test
    @DisplayName("a rejection after maxRevisionsPerTurn revisions skips, with outcome 'exhausted'")
    void rejectionAfterTheLimitSkips() throws Exception {
        String conversationId = conversationResourceId.id();
        sendUserInput(agentResourceId.id(), conversationId, "draft", false, false).then().statusCode(200);
        waitForState("AWAITING_HUMAN");

        resume("{\"verdict\": \"REJECTED\", \"note\": \"" + NOTE + "\"}");
        waitForState("AWAITING_HUMAN");
        assertEquals(2, chatRequests().size());

        // maxRevisionsPerTurn is 1: the round count was read back from the store.
        resume("{\"verdict\": \"REJECTED\", \"note\": \"Still not right.\"}");
        waitForState("READY");
        assertEquals(2, chatRequests().size(), "the exhausted rejection must not call the model again");

        JsonNode detailed = JSON.readTree(getConversationLog(agentResourceId.id(), conversationId, true)
                .then().statusCode(200).extract().asString());
        JsonNode outcome = lastStepData(detailed, OUTCOME_KEY);
        assertNotNull(outcome, "the step records the exhausted outcome: " + detailed);
        assertEquals("exhausted", outcome.path("outcome").asText());
        assertEquals(1, outcome.path("round").asInt());
        String lastOutputText = lastConversationOutput(detailed).toString();
        assertTrue(lastOutputText.contains("rejected by a human reviewer"), "the rejection applies as under SKIP: " + lastOutputText);
        assertNull(lastStepData(readRaw(conversationId), CANDIDATE_KEY), "the candidate is removed on the final decision too");
    }

    // ==================== no note → SKIP ====================

    @Test
    @DisplayName("REJECTED without a note skips like SKIP, records 'no_note' and never calls the model again")
    void rejectionWithoutNoteSkips() throws Exception {
        String conversationId = conversationResourceId.id();
        sendUserInput(agentResourceId.id(), conversationId, "draft", false, false).then().statusCode(200);
        waitForState("AWAITING_HUMAN");

        resume("{\"verdict\": \"REJECTED\"}");
        waitForState("READY");
        assertEquals(1, chatRequests().size(), "no note, no revision");

        JsonNode detailed = JSON.readTree(getConversationLog(agentResourceId.id(), conversationId, true)
                .then().statusCode(200).extract().asString());
        JsonNode outcome = lastStepData(detailed, OUTCOME_KEY);
        assertNotNull(outcome, "the step records the no_note outcome: " + detailed);
        assertEquals("no_note", outcome.path("outcome").asText());
        assertEquals(0, outcome.path("round").asInt());
        assertTrue(lastConversationOutput(detailed).path("hitlRevision").isMissingNode(), "nothing was revised");
        assertNull(lastStepData(readRaw(conversationId), CANDIDATE_KEY));
    }

    // ==================== Helpers ====================

    /** Submits a decision and waits for the resumed leg to settle. */
    private void resume(String decisionJson) {
        given().contentType(ContentType.JSON)
                .body(decisionJson)
                .post("agents/" + conversationResourceId.id() + "/resume?returnCurrentStepOnly=true")
                .then().assertThat().statusCode(200);
    }

    /**
     * The raw read serves the candidate's draft and bookkeeping but not its frozen
     * request: no {@code transcriptJson} on the candidate, and the system prompt
     * nowhere in the response (possibleResults copies included).
     */
    private static void assertRawReadWithholdsFrozenRequest(JsonNode rawResponse, JsonNode candidate) {
        assertFalse(candidate.has("transcriptJson"), "the raw read must withhold the candidate's frozen request: " + candidate);
        String raw = rawResponse.toString();
        assertFalse(raw.contains("transcriptJson"), "no copy of the candidate may carry the transcript: " + raw);
        List<JsonNode> candidates = new ArrayList<>();
        collect(rawResponse, CANDIDATE_KEY, candidates);
        for (JsonNode entry : candidates) {
            assertFalse(entry.toString().contains(SYSTEM_PROMPT_MARKER),
                    "no candidate entry (result or possibleResults) may carry the system prompt: " + entry);
        }
        // The one other place the prompt is stored is the LLM task's own audit entry,
        // which the raw read served before E2 and still serves (see the changelog).
        // Every occurrence of the prompt must be accounted for by it, so the revision
        // machinery adds no copy of its own.
        List<JsonNode> auditPrompts = new ArrayList<>();
        collect(rawResponse, AUDIT_COMPILED_PROMPT_KEY, auditPrompts);
        int inAudit = auditPrompts.stream().mapToInt(entry -> occurrences(entry.toString(), SYSTEM_PROMPT_MARKER)).sum();
        assertEquals(inAudit, occurrences(raw, SYSTEM_PROMPT_MARKER),
                "the system prompt may appear in the raw read only inside " + AUDIT_COMPILED_PROMPT_KEY + ": " + raw);
    }

    private static int occurrences(String text, String marker) {
        int count = 0;
        for (int at = text.indexOf(marker); at >= 0; at = text.indexOf(marker, at + marker.length())) {
            count++;
        }
        return count;
    }

    private JsonNode readRaw(String conversationId) throws Exception {
        return JSON.readTree(given().get("/conversationstore/conversations/" + conversationId)
                .then().statusCode(200).extract().asString());
    }

    /** The model requests in the order they were received. */
    private static List<LoggedRequest> chatRequests() {
        List<LoggedRequest> requests = new ArrayList<>(wireMock.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH))));
        requests.sort(Comparator.comparing(LoggedRequest::getLoggedDate));
        return requests;
    }

    /**
     * The value stored under {@code key} in the LAST conversation step, from either
     * snapshot shape: the raw document ({@code conversationSteps[].packages[]
     * .lifecycleTasks[] {key, result}}) or the detailed view
     * ({@code conversationSteps[].conversationStep[] {key, value}}).
     */
    static JsonNode lastStepData(JsonNode snapshot, String key) {
        JsonNode steps = snapshot.path("conversationSteps");
        if (!steps.isArray() || steps.isEmpty()) {
            return null;
        }
        List<JsonNode> found = new ArrayList<>();
        collect(steps.get(steps.size() - 1), key, found);
        if (found.isEmpty()) {
            return null;
        }
        JsonNode entry = found.get(found.size() - 1);
        return entry.has("result") ? entry.get("result") : entry.get("value");
    }

    private static void collect(JsonNode node, String key, List<JsonNode> found) {
        if (node.isObject()) {
            if (key.equals(node.path("key").asText(null))) {
                found.add(node);
                return;
            }
            node.elements().forEachRemaining(child -> collect(child, key, found));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collect(child, key, found));
        }
    }

    static JsonNode lastConversationOutput(JsonNode snapshot) {
        JsonNode outputs = snapshot.path("conversationOutputs");
        assertTrue(outputs.isArray() && !outputs.isEmpty(), "the conversation has outputs: " + snapshot);
        return outputs.get(outputs.size() - 1);
    }

    /**
     * Polls the DB-backed approval status until the state is reached (max ~15s).
     */
    private void waitForState(String expectedState) throws InterruptedException {
        String lastState = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Response response = given().get("agents/" + conversationResourceId.id() + "/approval-status");
            if (response.statusCode() == 200) {
                lastState = response.jsonPath().getString("state");
                if (expectedState.equals(lastState)) {
                    return;
                }
            }
            Thread.sleep(200);
        }
        fail("Conversation did not reach state " + expectedState + " within 15s (last state: " + lastState + ")");
    }

    static String chatReply(String id, String content) {
        return """
                {
                  "id": "%s",
                  "object": "chat.completion",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "%s"},
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": 20, "completion_tokens": 10, "total_tokens": 30}
                }
                """.formatted(id, content.replace("\\", "\\\\").replace("\"", "\\\""));
    }

    /**
     * parser → rules (draft → call_llm) → llm → rules (a draft pauses for review) →
     * output → templating, with {@code onRejection: REVISE} and one revision per
     * turn.
     */
    private ResourceId setupAndDeployReviewedDrafter() throws Exception {
        String dictionary = """
                {
                  "words": [
                    {"word": "draft", "expressions": "draft_reply(draft)", "frequency": 0}
                  ],
                  "phrases": []
                }
                """;
        String rules = """
                {
                  "behaviorGroups": [{
                    "name": "Drafting",
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
                        "name": "Draft",
                        "actions": ["call_llm"],
                        "conditions": [{
                          "type": "inputmatcher",
                          "configs": {"expressions": "draft_reply(*)", "occurrence": "currentStep"}
                        }]
                      }
                    ]
                  }]
                }
                """;
        String reviewRules = """
                {
                  "behaviorGroups": [{
                    "name": "Review",
                    "behaviorRules": [
                      {
                        "name": "Review_Draft",
                        "actions": ["review_draft", "PAUSE_CONVERSATION"],
                        "conditions": [{
                          "type": "actionmatcher",
                          "configs": {"actions": "call_llm", "occurrence": "currentStep"}
                        }]
                      }
                    ]
                  }]
                }
                """;
        String output = """
                {
                  "outputSet": [{
                    "action": "welcome",
                    "timesOccurred": 0,
                    "outputs": [{"valueAlternatives": [{"type": "text", "text": "Welcome to the refunds desk"}]}]
                  }]
                }
                """;
        String llmConfig = String.format("""
                {
                  "tasks": [{
                    "id": "draft-reply",
                    "type": "openai",
                    "actions": ["call_llm"],
                    "parameters": {
                      "baseUrl": "http://localhost:%d/v1",
                      "apiKey": "sk-test-fake-key",
                      "modelName": "gpt-4o-test",
                      "systemMessage": "%s",
                      "addToOutput": "true",
                      "timeout": "30000"
                    },
                    "conversationHistoryLimit": 5
                  }]
                }
                """, wireMock.port(), SYSTEM_MESSAGE);

        String locationDictionary = createResource(dictionary, "/dictionarystore/dictionaries");
        String locationRules = createResource(rules, "/rulestore/rulesets");
        String locationReviewRules = createResource(reviewRules, "/rulestore/rulesets");
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
                    {"type": "eddi://ai.labs.rules", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.templating", "config": {}}
                  ]
                }
                """, locationDictionary, locationRules, locationLlm, locationReviewRules, locationOutput);
        String locationWorkflow = createResource(workflowBody, "/workflowstore/workflows");

        String agentBody = String.format("""
                {
                  "packages": ["%s"],
                  "hitlConfig": {
                    "onRejection": "revise",
                    "maxRevisionsPerTurn": 1,
                    "pauseReason": "Review the draft reply before it is sent"
                  }
                }""", locationWorkflow);
        ResourceId agentId = extractResourceId(createResource(agentBody, "/agentstore/agents"));
        deployAgent(agentId.id(), agentId.version());
        return agentId;
    }
}
