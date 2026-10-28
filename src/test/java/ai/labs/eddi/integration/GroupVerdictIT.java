/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
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
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for the group side of E2, through the real group
 * engine, real member conversations and the real group conversation store, with
 * WireMock standing in for the model provider:
 * <ul>
 * <li>{@code convergence.mode: "VERDICT"} — a repeating phase stops on the
 * moderator's JSON boolean {@code "passed": true}, and an unparseable reply, a
 * string {@code "true"} or a failing judge never count as convergence;</li>
 * <li>TASK_FORCE {@code maxReworks} — a task that fails VERIFY goes back to its
 * member with the verifier's feedback inside the same run, is executed and
 * verified again, and the per-task rework count is persisted with the
 * discussion.</li>
 * </ul>
 * Members and the moderator are separate deployed LLM agents pointed at
 * different WireMock paths, so each one's requests can be scripted and counted
 * on its own. Every assertion is made on the group conversation re-read through
 * REST after the run.
 * <p>
 * {@code PostgresGroupVerdictIT} runs the same tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
public class GroupVerdictIT extends BaseIntegrationIT {

    private static final String GROUP_BASE = "/groupstore/groups/";
    private static final String TEST_USER_ID = "groupVerdictUser";
    private static final String MEMBER_PATH = "/member/v1/chat/completions";
    private static final String MODERATOR_PATH = "/moderator/v1/chat/completions";
    private static final String LAST_MESSAGE = "$.messages[-1].content";
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED", "REJECTED");

    private static final String QUESTION = "Draft a one-line release note for version 6.6.";
    private static final String VERDICT_PROMPT_MARK = "LATEST ROUND:";
    private static final String VERIFY_PROMPT_MARK = "reviewing the results of a collaborative task";
    private static final String TASK_SUBJECT = "Write summary";
    private static final String VERIFIER_FEEDBACK = "Missing the citation of the changelog.";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static WireMockServer wireMock;
    private static ResourceId memberAgent;
    private static ResourceId moderatorAgent;

    @BeforeAll
    static void startWireMock() {
        // Reset per class: the Postgres twin runs in the same JVM and must deploy
        // its own agents into its own database.
        memberAgent = null;
        moderatorAgent = null;
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (memberAgent != null) {
            undeployAgentQuietly(memberAgent.id(), memberAgent.version());
        }
        if (moderatorAgent != null) {
            undeployAgentQuietly(moderatorAgent.id(), moderatorAgent.version());
        }
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        if (memberAgent == null) {
            memberAgent = deployLlmAgent("member", "You are the drafter of the release team.");
            moderatorAgent = deployLlmAgent("moderator", "You are the moderator of the release team.");
        }
        wireMock.resetAll();
    }

    // ==================== convergence.mode: VERDICT ====================

    @Test
    @DisplayName("VERDICT: the phase stops on the first repeat the moderator passes, not after all repeats")
    void verdictStopsOnPass() throws Exception {
        stubMember(okJson(HitlRevisionIT.chatReply("m", "Release 6.6 adds verdicts.")));
        wireMock.stubFor(judge().inScenario("Verdict").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(HitlRevisionIT.chatReply("j1", "{\"passed\": false, \"feedback\": \"Name the version.\"}")))
                .willSetStateTo("Failed once"));
        wireMock.stubFor(judge().inScenario("Verdict").whenScenarioStateIs("Failed once")
                .willReturn(okJson(HitlRevisionIT.chatReply("j2", "{\"passed\": true, \"feedback\": \"Complete and correct.\"}"))));

        ResourceId group = createRepeatingGroup("verdict");
        JsonNode gc = discussAndRead(group);

        assertEquals("COMPLETED", gc.path("state").asText(), "the discussion completes: " + gc);
        assertEquals(2, memberEntries(gc, "Draft").size(),
                "the phase ran 2 of its 4 repeats: the second passed the verdict. Transcript: " + gc.path("transcript"));
        List<JsonNode> verdicts = entriesOfType(gc, "CONVERGENCE");
        assertEquals(2, verdicts.size(), "one verdict per checked repeat (minRepeats defaults to 1 in VERDICT mode)");
        assertTrue(verdicts.get(0).path("content").asText().startsWith("Verdict: not passed"), verdicts.get(0).toString());
        assertTrue(verdicts.get(0).path("content").asText().contains("Name the version."), "the failed verdict's feedback is recorded");
        assertTrue(verdicts.get(1).path("content").asText().startsWith("Verdict: passed"), verdicts.get(1).toString());

        List<LoggedRequest> judgeRequests = requests(MODERATOR_PATH);
        assertEquals(2, judgeRequests.size(), "one judge call per checked repeat");
        String judgeInput = lastUserMessage(judgeRequests.get(0));
        assertTrue(judgeInput.contains(QUESTION), "the verdict judges the round against the question: " + judgeInput);
        assertTrue(judgeInput.contains("Release 6.6 adds verdicts."), "the verdict reviews the latest round's content");
        assertEquals(2, requests(MEMBER_PATH).size());
    }

    @Test
    @DisplayName("VERDICT: prose and a string \"true\" are not a pass — every repeat runs")
    void unparseableVerdictNeverConverges() throws Exception {
        stubMember(okJson(HitlRevisionIT.chatReply("m", "Release 6.6 adds verdicts.")));
        wireMock.stubFor(judge().inScenario("Unparseable").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(HitlRevisionIT.chatReply("j1", "Looks good to me, it passed.")))
                .willSetStateTo("Prose"));
        wireMock.stubFor(judge().inScenario("Unparseable").whenScenarioStateIs("Prose")
                .willReturn(okJson(HitlRevisionIT.chatReply("j2", "{\"passed\": \"true\", \"feedback\": \"fine\"}"))));

        ResourceId group = createRepeatingGroup("verdict");
        JsonNode gc = discussAndRead(group);

        assertEquals("COMPLETED", gc.path("state").asText(), "the discussion completes: " + gc);
        assertEquals(4, memberEntries(gc, "Draft").size(), "no usable verdict, so all 4 repeats run: " + gc.path("transcript"));
        List<JsonNode> verdicts = entriesOfType(gc, "CONVERGENCE");
        assertEquals(4, verdicts.size(), "every repeat was checked");
        for (JsonNode verdict : verdicts) {
            assertFalse(verdict.path("content").asText().startsWith("Verdict: passed"), "never a pass: " + verdict);
        }
        assertTrue(verdicts.get(0).path("content").asText().contains("not parseable"), verdicts.get(0).toString());
        assertTrue(verdicts.get(1).path("content").asText().contains("no boolean 'passed'"), verdicts.get(1).toString());
    }

    @Test
    @DisplayName("VERDICT: a failing judge is not convergence — every repeat runs")
    void judgeFailureNeverConverges() throws Exception {
        stubMember(okJson(HitlRevisionIT.chatReply("m", "Release 6.6 adds verdicts.")));
        wireMock.stubFor(judge().willReturn(aResponse().withStatus(500).withBody("{\"error\": {\"message\": \"upstream down\"}}")));

        ResourceId group = createRepeatingGroup("VERDICT");
        JsonNode gc = discussAndRead(group);

        assertEquals("COMPLETED", gc.path("state").asText(), "a judge failure does not fail the discussion: " + gc);
        assertEquals(4, memberEntries(gc, "Draft").size(), "a failing judge never ends the phase: " + gc.path("transcript"));
        for (JsonNode verdict : entriesOfType(gc, "CONVERGENCE")) {
            assertFalse(verdict.path("content").asText().startsWith("Verdict: passed"), "never a pass: " + verdict);
        }
        assertFalse(requests(MODERATOR_PATH).isEmpty(), "the judge was asked");
    }

    @Test
    @DisplayName("an unknown convergence.mode is refused at save time with a 400")
    void unknownModeIsRefused() {
        given().contentType(ContentType.JSON).body(repeatingGroupJson("CONSENSUS"))
                .post(GROUP_BASE)
                .then().assertThat().statusCode(400);
    }

    // ==================== TASK_FORCE maxReworks ====================

    @Test
    @DisplayName("TASK_FORCE maxReworks: a failed verification sends the task back with the feedback, and the rework passes")
    void failedVerificationIsReworkedWithinTheRun() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo(MEMBER_PATH)).inScenario("Attempts").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(HitlRevisionIT.chatReply("a1", "FIRST-ATTEMPT summary without sources.")))
                .willSetStateTo("Attempted"));
        wireMock.stubFor(post(urlPathEqualTo(MEMBER_PATH)).inScenario("Attempts").whenScenarioStateIs("Attempted")
                .willReturn(okJson(HitlRevisionIT.chatReply("a2", "SECOND-ATTEMPT summary, see the changelog."))));
        // The moderator plays verifier (fails, then passes) and synthesizer.
        wireMock.stubFor(post(urlPathEqualTo(MODERATOR_PATH)).atPriority(5)
                .willReturn(okJson(HitlRevisionIT.chatReply("s", "FINAL-SYNTHESIS"))));
        wireMock.stubFor(verifier().inScenario("Verify").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(HitlRevisionIT.chatReply("v1",
                        "[{\"subject\": \"" + TASK_SUBJECT + "\", \"passed\": false, \"feedback\": \"" + VERIFIER_FEEDBACK + "\"}]")))
                .willSetStateTo("Rejected once"));
        wireMock.stubFor(verifier().inScenario("Verify").whenScenarioStateIs("Rejected once")
                .willReturn(okJson(HitlRevisionIT.chatReply("v2",
                        "[{\"subject\": \"" + TASK_SUBJECT + "\", \"passed\": true, \"feedback\": \"Cites the changelog.\"}]"))));

        String groupJson = String.format("""
                {
                  "name": "Rework IT Group",
                  "style": "TASK_FORCE",
                  "moderatorAgentId": "%s",
                  "maxReworks": 1,
                  "members": [
                    {"agentId": "%s", "displayName": "Writer", "speakingOrder": 1, "memberType": "AGENT"}
                  ],
                  "tasks": [
                    {"subject": "%s", "description": "Summarize release 6.6 in one line.", "assignToRole": "Writer", "priority": 0}
                  ],
                  "protocol": {"agentTimeoutSeconds": 30, "onAgentFailure": "SKIP", "maxRetries": 0,
                               "onMemberUnavailable": "SKIP", "maxTurns": 20}
                }
                """, moderatorAgent.id(), memberAgent.id(), TASK_SUBJECT);
        ResourceId group = createGroup(groupJson);
        JsonNode gc = discussAndRead(group);

        assertEquals("COMPLETED", gc.path("state").asText(), "the discussion completes: " + gc);
        JsonNode tasks = gc.path("taskList").path("tasks");
        assertEquals(1, tasks.size(), "one pre-configured task: " + gc.path("taskList"));
        JsonNode task = tasks.get(0);
        assertEquals("VERIFIED", task.path("status").asText(), "the rework passed verification: " + task);
        assertTrue(task.path("verified").asBoolean());
        assertTrue(task.path("result").asText().contains("SECOND-ATTEMPT"), "the verified result is the rework's: " + task);
        assertEquals(1, gc.path("taskList").path("reworkCounts").path(task.path("id").asText()).asInt(-1),
                "the rework count is persisted with the discussion: " + gc.path("taskList"));

        List<JsonNode> verification = entriesOfType(gc, "VERIFICATION");
        assertTrue(verification.stream().anyMatch(e -> e.path("content").asText()
                .equals("Returned for rework (1/1): " + TASK_SUBJECT + " — " + VERIFIER_FEEDBACK)),
                "the transcript records the return for rework: " + verification);

        List<LoggedRequest> memberRequests = requests(MEMBER_PATH);
        assertEquals(2, memberRequests.size(), "the task ran twice: the attempt and the rework");
        String reworkInput = lastUserMessage(memberRequests.get(1));
        assertTrue(reworkInput.contains("Reviewer feedback on the previous attempt (address this): " + VERIFIER_FEEDBACK),
                "the verifier's feedback must reach the member's rework input: " + reworkInput);
        assertFalse(lastUserMessage(memberRequests.get(0)).contains("Reviewer feedback"), "the first attempt carries no feedback");

        long verifyCalls = requests(MODERATOR_PATH).stream().filter(r -> lastUserMessage(r).contains(VERIFY_PROMPT_MARK)).count();
        assertEquals(2, verifyCalls, "VERIFY checked the task twice");
    }

    @Test
    @DisplayName("TASK_FORCE without maxReworks: a failed verification stays FAILED and the task is not re-run")
    void withoutMaxReworksAFailedVerificationIsFinal() throws Exception {
        stubMember(okJson(HitlRevisionIT.chatReply("a1", "FIRST-ATTEMPT summary without sources.")));
        wireMock.stubFor(post(urlPathEqualTo(MODERATOR_PATH)).atPriority(5)
                .willReturn(okJson(HitlRevisionIT.chatReply("s", "FINAL-SYNTHESIS"))));
        wireMock.stubFor(verifier().willReturn(okJson(HitlRevisionIT.chatReply("v1",
                "[{\"subject\": \"" + TASK_SUBJECT + "\", \"passed\": false, \"feedback\": \"" + VERIFIER_FEEDBACK + "\"}]"))));

        String groupJson = String.format("""
                {
                  "name": "No Rework IT Group",
                  "style": "TASK_FORCE",
                  "moderatorAgentId": "%s",
                  "members": [
                    {"agentId": "%s", "displayName": "Writer", "speakingOrder": 1, "memberType": "AGENT"}
                  ],
                  "tasks": [
                    {"subject": "%s", "description": "Summarize release 6.6 in one line.", "assignToRole": "Writer", "priority": 0}
                  ],
                  "protocol": {"agentTimeoutSeconds": 30, "onAgentFailure": "SKIP", "maxRetries": 0,
                               "onMemberUnavailable": "SKIP", "maxTurns": 20}
                }
                """, moderatorAgent.id(), memberAgent.id(), TASK_SUBJECT);
        JsonNode gc = discussAndRead(createGroup(groupJson));

        JsonNode task = gc.path("taskList").path("tasks").get(0);
        assertEquals("FAILED", task.path("status").asText(), "maxReworks absent keeps a failed verification final: " + task);
        assertEquals(1, requests(MEMBER_PATH).size(), "the task is not re-run");
        assertTrue(gc.path("taskList").path("reworkCounts").isEmpty() || gc.path("taskList").path("reworkCounts").isMissingNode(),
                "nothing was reworked: " + gc.path("taskList"));
    }

    // ==================== Helpers ====================

    private void stubMember(ResponseDefinitionBuilder response) {
        wireMock.stubFor(post(urlPathEqualTo(MEMBER_PATH)).willReturn(response));
    }

    /** A moderator request whose latest message is the VERDICT review. */
    private static MappingBuilder judge() {
        return post(urlPathEqualTo(MODERATOR_PATH)).atPriority(1)
                .withRequestBody(matchingJsonPath(LAST_MESSAGE, containing(VERDICT_PROMPT_MARK)));
    }

    /** A moderator request whose latest message is the VERIFY review. */
    private static MappingBuilder verifier() {
        return post(urlPathEqualTo(MODERATOR_PATH)).atPriority(1)
                .withRequestBody(matchingJsonPath(LAST_MESSAGE, containing(VERIFY_PROMPT_MARK)));
    }

    private static List<LoggedRequest> requests(String path) {
        List<LoggedRequest> requests = new ArrayList<>(wireMock.findAll(postRequestedFor(urlPathEqualTo(path))));
        requests.sort(Comparator.comparing(LoggedRequest::getLoggedDate));
        return requests;
    }

    private static String lastUserMessage(LoggedRequest request) {
        try {
            JsonNode messages = JSON.readTree(request.getBodyAsString()).path("messages");
            return messages.get(messages.size() - 1).path("content").asText();
        } catch (Exception e) {
            throw new AssertionError("unreadable model request: " + request.getBodyAsString(), e);
        }
    }

    private ResourceId createRepeatingGroup(String mode) {
        return createGroup(repeatingGroupJson(mode));
    }

    private String repeatingGroupJson(String mode) {
        return String.format("""
                {
                  "name": "Verdict IT Group",
                  "moderatorAgentId": "%s",
                  "members": [
                    {"agentId": "%s", "displayName": "Drafter", "speakingOrder": 1, "memberType": "AGENT"}
                  ],
                  "style": "CUSTOM",
                  "phases": [
                    {"name": "Draft", "type": "REVISION", "participants": "ALL", "turnOrder": "SEQUENTIAL",
                     "contextScope": "FULL", "targetEachPeer": false, "repeats": 4,
                     "convergence": {"enabled": true, "mode": "%s"}}
                  ],
                  "protocol": {"agentTimeoutSeconds": 30, "onAgentFailure": "SKIP", "maxRetries": 0,
                               "onMemberUnavailable": "SKIP", "maxTurns": 30}
                }
                """, moderatorAgent.id(), memberAgent.id(), mode);
    }

    private ResourceId createGroup(String groupJson) {
        var response = given().contentType(ContentType.JSON).body(groupJson).post(GROUP_BASE);
        response.then().assertThat().statusCode(201);
        return extractResourceId(response.getHeader("location"));
    }

    /**
     * Starts a discussion and re-reads it from the store through REST, polling with
     * a deadline until it is terminal.
     */
    private JsonNode discussAndRead(ResourceId group) throws Exception {
        Response started = given().contentType(ContentType.JSON)
                .body(String.format("{\"question\": \"%s\", \"userId\": \"%s\"}", QUESTION, TEST_USER_ID))
                .post("/groups/" + group.id() + "/conversations");
        assertTrue(started.statusCode() == 200 || started.statusCode() == 201, "discussion started: " + started.asString());
        String gcId = started.jsonPath().getString("id");
        assertNotNull(gcId, "the discussion carries its id: " + started.asString());

        long deadline = System.currentTimeMillis() + 60_000;
        JsonNode gc = null;
        while (System.currentTimeMillis() < deadline) {
            Response read = given().get("/groups/" + group.id() + "/conversations/" + gcId);
            if (read.statusCode() == 200) {
                gc = JSON.readTree(read.asString());
                if (TERMINAL.contains(gc.path("state").asText())) {
                    return gc;
                }
            }
            Thread.sleep(250);
        }
        fail("Group conversation " + gcId + " did not finish within 60s: " + gc);
        return gc;
    }

    private static List<JsonNode> entriesOfType(JsonNode gc, String type) {
        List<JsonNode> entries = new ArrayList<>();
        gc.path("transcript").forEach(entry -> {
            if (type.equals(entry.path("type").asText())) {
                entries.add(entry);
            }
        });
        return entries;
    }

    /** The member's contributions in a phase (not the system's or the judge's). */
    private List<JsonNode> memberEntries(JsonNode gc, String phaseName) {
        List<JsonNode> entries = new ArrayList<>();
        gc.path("transcript").forEach(entry -> {
            if (phaseName.equals(entry.path("phaseName").asText()) && memberAgent.id().equals(entry.path("speakerAgentId").asText())) {
                entries.add(entry);
            }
        });
        return entries;
    }

    /**
     * An LLM agent that answers every turn after its welcome turn, pointed at its
     * own WireMock path.
     */
    private ResourceId deployLlmAgent(String pathPrefix, String systemMessage) throws Exception {
        String rules = """
                {
                  "behaviorGroups": [{
                    "name": "Answering",
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
                        "name": "Answer",
                        "actions": ["call_llm"],
                        "conditions": [{
                          "type": "occurrence",
                          "configs": {"minTimesOccurred": "1", "behaviorRuleName": "Welcome"}
                        }]
                      }
                    ]
                  }]
                }
                """;
        String llmConfig = String.format("""
                {
                  "tasks": [{
                    "id": "%s-answer",
                    "type": "openai",
                    "actions": ["call_llm"],
                    "parameters": {
                      "baseUrl": "http://localhost:%d/%s/v1",
                      "apiKey": "sk-test-fake-key",
                      "modelName": "gpt-4o-test",
                      "systemMessage": "%s",
                      "addToOutput": "true",
                      "timeout": "30000"
                    },
                    "conversationHistoryLimit": 5
                  }]
                }
                """, pathPrefix, wireMock.port(), pathPrefix, systemMessage);

        String locationRules = createResource(rules, "/rulestore/rulesets");
        String locationLlm = createResource(llmConfig, "/llmstore/llms");
        String workflowBody = String.format("""
                {
                  "workflowSteps": [
                    {"type": "eddi://ai.labs.rules", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.llm", "config": {"uri": "%s"}}
                  ]
                }
                """, locationRules, locationLlm);
        String locationWorkflow = createResource(workflowBody, "/workflowstore/workflows");
        ResourceId agentId = extractResourceId(createResource(String.format("""
                {"packages": ["%s"]}""", locationWorkflow), "/agentstore/agents"));
        deployAgent(agentId.id(), agentId.version());
        return agentId;
    }
}
