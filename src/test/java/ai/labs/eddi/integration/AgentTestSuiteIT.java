/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent test harness against the full engine: a deterministic rules-only
 * agent (the {@code agentengine} fixture that greets on "hello"), a suite
 * stored through REST, a run started through REST that deploys the agent to the
 * {@code test} environment itself, and the result read back.
 * <p>
 * Requires Docker (Quarkus DevServices for MongoDB), like every {@code *IT}.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
public class AgentTestSuiteIT extends BaseIntegrationIT {

    private static ResourceId agent;

    @BeforeEach
    void setUp() throws Exception {
        if (agent == null) {
            agent = createRulesOnlyAgent();
        }
    }

    @AfterAll
    static void cleanup() {
        if (agent == null) {
            return;
        }
        try {
            given().post(String.format("administration/test/undeploy/%s?version=%s&endAllActiveConversations=true", agent.id(),
                    agent.version()));
        } catch (Exception e) {
            // best effort, like undeployAgentQuietly
        }
    }

    @Test
    @DisplayName("a suite that holds passes; the agent was deployed to test by the run, and no test conversation is left behind")
    void passingSuite() throws Exception {
        String suiteId = createSuite("greet", "(?i)nice to meet");

        Response run = runToCompletion(suiteId);

        assertEquals("PASSED", run.jsonPath().getString("status"), run.asString());
        assertEquals("greet", run.jsonPath().getString("cases[0].attempts[0].turns[0].actions[0]"));
        given().get(String.format("administration/test/deploymentstatus/%s?version=%s&format=text", agent.id(), agent.version())).then()
                .statusCode(200);
        Response listing = given().get("conversationstore/conversations?agentId=" + agent.id());
        assertTrue(!listing.asString().contains("agent-test:"), "test conversations are deleted after the run: " + listing.asString());
    }

    @Test
    @DisplayName("a suite whose expectation does not hold fails with the actual actions")
    void failingSuite() throws Exception {
        String suiteId = createSuite("book_room", null);

        Response run = runToCompletion(suiteId);

        assertEquals("FAILED", run.jsonPath().getString("status"), run.asString());
        assertEquals("actionsContain", run.jsonPath().getString("cases[0].attempts[0].turns[0].failures[0].expectation"));
    }

    /**
     * Two turns of one conversation: the second is judged on its own step, and the
     * agent's state carries over — the output set answers a repeated greeting
     * differently ({@code timesOccurred: 1}).
     */
    @Test
    @DisplayName("a two-turn case is judged turn by turn on one conversation")
    void twoTurnCase() throws Exception {
        String body = """
                {"name":"greeting twice","cases":[{"name":"hello twice","turns":[
                  {"input":"hello","expect":{"actionsContain":["greet"],"outputMatches":"(?i)nice to meet"}},
                  {"input":"hello","expect":{"actionsContain":["greet"],"outputMatches":"(?i)already say hi"}}
                ]}]}""";
        String suiteId = extractResourceId(createResource(body, "testsuitestore/testsuites")).id();

        Response run = runToCompletion(suiteId);

        assertEquals("PASSED", run.jsonPath().getString("status"), run.asString());
        assertEquals(2, run.jsonPath().getList("cases[0].attempts[0].turns").size(), run.asString());
        assertEquals(2, run.jsonPath().getInt("cases[0].attempts[0].turns[1].index"));
    }

    @Test
    @DisplayName("an invalid suite is refused with 400")
    void invalidSuiteIsRefused() {
        given().body("{\"defaults\":{\"toolMode\":\"replay\"}}").contentType(ContentType.JSON).post("testsuitestore/testsuites").then()
                .statusCode(400);
    }

    private String createSuite(String expectedAction, String outputRegex) {
        String expect = "{\"actionsContain\":[\"" + expectedAction + "\"]"
                + (outputRegex != null ? ",\"outputMatches\":\"" + outputRegex + "\"" : "") + "}";
        String body = "{\"name\":\"greeting\",\"cases\":[{\"name\":\"hello\",\"turns\":[{\"input\":\"hello\",\"expect\":" + expect + "}]}]}";
        return extractResourceId(createResource(body, "testsuitestore/testsuites")).id();
    }

    private Response runToCompletion(String suiteId) throws InterruptedException {
        Response started = given().body("{\"suiteId\":\"" + suiteId + "\",\"agentId\":\"" + agent.id() + "\",\"agentVersion\":" + agent.version()
                + "}").contentType(ContentType.JSON).post("agenttests/runs");
        started.then().statusCode(202);
        String runId = started.jsonPath().getString("id");
        assertNotNull(runId);
        for (int i = 0; i < 240; i++) {
            Response run = given().get("agenttests/runs/" + runId);
            if (run.statusCode() == 200 && !"RUNNING".equals(run.jsonPath().getString("status"))) {
                return run;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Agent test run " + runId + " did not finish within 120s");
    }

    private ResourceId createRulesOnlyAgent() throws Exception {
        String dictionary = createResource(load("agentengine/dictionary.json"), "/dictionarystore/dictionaries");
        String rules = createResource(load("agentengine/rules.json"), "/rulestore/rulesets");
        String output = createResource(load("agentengine/output.json"), "/outputstore/outputsets");
        String workflow = createResource(String.format("""
                {
                  "workflowSteps": [
                    {"type": "eddi://ai.labs.parser", "config": {},
                     "extensions": {
                       "dictionaries": [
                         {"type": "eddi://ai.labs.parser.dictionaries.integer", "config": {}},
                         {"type": "eddi://ai.labs.parser.dictionaries.punctuation", "config": {}},
                         {"type": "eddi://ai.labs.parser.dictionaries.regular", "config": {"uri": "%s"}}
                       ],
                       "corrections": [
                         {"type": "eddi://ai.labs.parser.corrections.levenshtein", "config": {"distance": "2"}},
                         {"type": "eddi://ai.labs.parser.corrections.mergedTerms", "config": {}}
                       ]
                     }},
                    {"type": "eddi://ai.labs.rules", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.templating", "config": {}}
                  ]
                }""", dictionary, rules, output), "/workflowstore/workflows");
        return extractResourceId(createResource(String.format("{\"packages\": [\"%s\"]}", workflow), "/agentstore/agents"));
    }
}
