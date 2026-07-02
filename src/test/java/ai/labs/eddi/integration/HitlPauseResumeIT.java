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
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for the HITL (human-in-the-loop) pause/resume
 * framework on the regular conversation surface. Exercises the FULL production
 * path with no mocked seams: a real behavior rule emits
 * {@code PAUSE_CONVERSATION}, the LifecycleManager pauses the pipeline, the
 * bookmark round-trips through the real MongoDB store, and the REST
 * {@code /resume} endpoint re-enters the pipeline at the bookmarked task —
 * completing the remaining tasks of the paused turn (output, templating).
 * <p>
 * This covers the seams every prior HITL defect lived in: snapshot state after
 * the CAS, component-cache key resolution on resume, Instant round-trips
 * through the persistence codec, and REST (de)serialization of HitlDecision.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
public class HitlPauseResumeIT extends BaseIntegrationIT {

    private static final String TEST_USER_ID = "hitlTestUser";

    private static ResourceId agentResourceId;
    private static boolean agentDeployed = false;

    private ResourceId conversationResourceId;

    @BeforeEach
    void setUpAgent() throws Exception {
        if (!agentDeployed) {
            agentResourceId = setupAndDeployHitlAgent();
            agentDeployed = true;
        }
        conversationResourceId = createConversation(agentResourceId.id(), TEST_USER_ID);
        waitForConversationReady(agentResourceId.id(), conversationResourceId.id());
    }

    @AfterAll
    static void cleanup() {
        if (agentResourceId != null) {
            undeployAgentQuietly(agentResourceId.id(), agentResourceId.version());
        }
    }

    // ==================== Full pause → approve → completion ====================

    @Test
    @DisplayName("PAUSE_CONVERSATION pauses the turn; APPROVED resume completes the remaining pipeline tasks")
    void pauseThenApproveCompletesTurn() throws Exception {
        // Trigger the approval-gated action
        sendUserInput(agentResourceId.id(), conversationResourceId.id(), "delete", false, false);
        waitForState("AWAITING_HUMAN");

        // Approval status reports the pause with the (default) timeout policy
        given().get("agents/" + conversationResourceId.id() + "/approval-status")
                .then().assertThat().statusCode(200)
                .body("state", equalTo("AWAITING_HUMAN"))
                .body("pauseReason", equalTo("PAUSE_CONVERSATION action"))
                .body("timeoutPolicy", equalTo("WAIT_INDEFINITELY"));

        // The paused conversation appears in the pending-approvals listing
        given().get("agents/pending-approvals")
                .then().assertThat().statusCode(200)
                .body("conversationId", hasItem(conversationResourceId.id()));

        // The paused turn must NOT yet contain the gated output — it is produced
        // by the output task AFTER the pause point
        Response logBefore = getConversationLog(agentResourceId.id(), conversationResourceId.id(), false);
        assertFalse(logBefore.getBody().asString().contains("Account deletion was executed after approval"),
                "output task must not have run before approval");

        // Approve
        given().contentType(ContentType.JSON)
                .body("{\"verdict\": \"APPROVED\", \"note\": \"verified by supervisor\"}")
                .post("agents/" + conversationResourceId.id() + "/resume")
                .then().assertThat().statusCode(200);

        // The resumed pipeline runs the REMAINING tasks to completion (the
        // original BLOCKER: stale PAUSE_CONVERSATION re-pausing after one task)
        waitForState("READY");
        Response logAfter = getConversationLog(agentResourceId.id(), conversationResourceId.id(), false);
        assertTrue(logAfter.getBody().asString().contains("Account deletion was executed after approval"),
                "APPROVED resume must execute the output task of the paused turn");

        // Second resume of the already-resumed conversation → 409, not 500
        given().contentType(ContentType.JSON)
                .body("{\"verdict\": \"APPROVED\"}")
                .post("agents/" + conversationResourceId.id() + "/resume")
                .then().assertThat().statusCode(409);

        // The conversation continues normally after the HITL cycle
        Response next = sendUserInput(agentResourceId.id(), conversationResourceId.id(), "hello", false, true);
        next.then().assertThat().statusCode(200);
    }

    // ==================== Reject path ====================

    @Test
    @DisplayName("REJECTED resume skips the remaining tasks and returns the conversation to READY")
    void pauseThenRejectSkipsGatedTasks() throws Exception {
        sendUserInput(agentResourceId.id(), conversationResourceId.id(), "delete", false, false);
        waitForState("AWAITING_HUMAN");

        given().contentType(ContentType.JSON)
                .body("{\"verdict\": \"REJECTED\", \"note\": \"could not verify identity\"}")
                .post("agents/" + conversationResourceId.id() + "/resume")
                .then().assertThat().statusCode(200);

        waitForState("READY");
        Response log = getConversationLog(agentResourceId.id(), conversationResourceId.id(), false);
        assertFalse(log.getBody().asString().contains("Account deletion was executed after approval"),
                "REJECTED must not execute the gated output task");
    }

    // ==================== Cancel path ====================

    @Test
    @DisplayName("cancel of a paused conversation transitions it out of AWAITING_HUMAN")
    void pauseThenCancel() throws Exception {
        sendUserInput(agentResourceId.id(), conversationResourceId.id(), "delete", false, false);
        waitForState("AWAITING_HUMAN");

        given().post("agents/" + conversationResourceId.id() + "/cancel")
                .then().assertThat().statusCode(200);

        waitForState("EXECUTION_INTERRUPTED");

        // Resume after cancel → 409 (pause was consumed by the cancel)
        given().contentType(ContentType.JSON)
                .body("{\"verdict\": \"APPROVED\"}")
                .post("agents/" + conversationResourceId.id() + "/resume")
                .then().assertThat().statusCode(409);
    }

    // ==================== Validation & error codes ====================

    @Test
    @DisplayName("resume with missing verdict → 400 and the pause is NOT consumed")
    void emptyBodyRejectedWithoutConsumingPause() throws Exception {
        sendUserInput(agentResourceId.id(), conversationResourceId.id(), "delete", false, false);
        waitForState("AWAITING_HUMAN");

        given().contentType(ContentType.JSON)
                .body("{}")
                .post("agents/" + conversationResourceId.id() + "/resume")
                .then().assertThat().statusCode(400);

        // The pending approval must survive the bad request
        given().get("agents/" + conversationResourceId.id() + "/approval-status")
                .then().assertThat().statusCode(200)
                .body("state", equalTo("AWAITING_HUMAN"));
    }

    @Test
    @DisplayName("resume of an unknown conversation → 404 (not 409)")
    void resumeUnknownConversation404() {
        given().contentType(ContentType.JSON)
                .body("{\"verdict\": \"APPROVED\"}")
                .post("agents/000000000000000000000000/resume")
                .then().assertThat().statusCode(404);
    }

    @Test
    @DisplayName("resume of a conversation that is not paused → 409 with the current state")
    void resumeNotPaused409() {
        given().contentType(ContentType.JSON)
                .body("{\"verdict\": \"APPROVED\"}")
                .post("agents/" + conversationResourceId.id() + "/resume")
                .then().assertThat().statusCode(409)
                .body(containsString("READY"));
    }

    @Test
    @DisplayName("undo is rejected with 409 while the conversation is paused")
    void undoRejectedWhilePaused() throws Exception {
        sendUserInput(agentResourceId.id(), conversationResourceId.id(), "delete", false, false);
        waitForState("AWAITING_HUMAN");

        given().post("agents/" + conversationResourceId.id() + "/undo")
                .then().assertThat().statusCode(409);
    }

    // ==================== Helpers ====================

    /** Polls the conversation until it reaches the expected state (max ~10s). */
    private void waitForState(String expectedState) throws InterruptedException {
        String lastState = null;
        for (int i = 0; i < 40; i++) {
            Response response = given().get("agents/" + conversationResourceId.id() + "/status");
            if (response.statusCode() == 200) {
                lastState = response.getBody().asString().replace("\"", "").trim();
                if (expectedState.equals(lastState)) {
                    return;
                }
            }
            Thread.sleep(250);
        }
        fail("Conversation did not reach state " + expectedState + " within 10s (last state: " + lastState + ")");
    }

    private ResourceId setupAndDeployHitlAgent() throws Exception {
        String dictionary = load("hitl/dictionary.json");
        String behavior = load("hitl/rules.json");
        String output = load("hitl/output.json");

        String locationDictionary = createResource(dictionary, "/dictionarystore/dictionaries");
        String locationBehavior = createResource(behavior, "/rulestore/rulesets");
        String locationOutput = createResource(output, "/outputstore/outputsets");

        String packageBody = String.format("""
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
                    {"type": "eddi://ai.labs.templating", "config": {}}
                  ]
                }""", locationDictionary, locationBehavior, locationOutput);

        String locationWorkflow = createResource(packageBody, "/workflowstore/workflows");
        String agentBody = String.format("""
                {"packages": ["%s"]}""", locationWorkflow);
        String agentLocation = createResource(agentBody, "/agentstore/agents");

        ResourceId agentId = extractResourceId(agentLocation);
        deployAgent(agentId.id(), agentId.version());
        return agentId;
    }
}
