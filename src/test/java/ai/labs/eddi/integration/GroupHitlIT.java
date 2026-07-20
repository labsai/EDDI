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
 * End-to-end integration test for the HITL pause/resume framework on the GROUP
 * conversation surface — the full production path with no mocked seams: a
 * {@code requiresApproval} phase commits a real pause through the group
 * conversation store, the REST {@code /approve} endpoint applies the human
 * decision, and the background resume leg re-enters the phase loop at the
 * bookmarked index and runs the remaining phases to completion.
 * <p>
 * Covers the group-specific seams the regular-surface IT cannot reach: pause
 * bookmark round-trips on the {@code GroupConversation} document, the
 * summary/full split of the group approval-status endpoint, resume-vs-resume
 * and cancel-after-terminal conflicts (409), REJECTED-is-terminal semantics,
 * and the pending-approvals summary listing.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
public class GroupHitlIT extends BaseIntegrationIT {

    private static final String GROUP_BASE = "/groupstore/groups/";
    private static final String TEST_USER_ID = "groupHitlUser";

    private static ResourceId agentId1;
    private static ResourceId agentId2;
    private static ResourceId groupResourceId;
    private static boolean setUpDone = false;

    @BeforeEach
    void setUpGroup() throws Exception {
        if (!setUpDone) {
            agentId1 = deployTemplateAgent();
            agentId2 = deployTemplateAgent();
            groupResourceId = createHitlGroup();
            setUpDone = true;
        }
    }

    @AfterAll
    static void cleanup() {
        if (agentId1 != null)
            undeployAgentQuietly(agentId1.id(), agentId1.version());
        if (agentId2 != null)
            undeployAgentQuietly(agentId2.id(), agentId2.version());
    }

    // ==================== Pause + status + listing ====================

    @Test
    @DisplayName("requiresApproval phase pauses the discussion; status + pending listing report the pause")
    void pauseStatusAndListing() {
        String gcId = startPausedDiscussion();

        // Summary (default): pause coordinates, no transcript leak
        var status = given().get(approvalStatusPath(gcId));
        status.then().assertThat().statusCode(200)
                .body("state", equalTo("AWAITING_APPROVAL"))
                .body("pausedPhaseName", equalTo("Draft"))
                .body("pauseType", equalTo("PHASE"))
                .body("timeoutPolicy", equalTo("WAIT_INDEFINITELY"));
        assertNull(status.jsonPath().get("transcript"),
                "the summary view must not include the transcript");

        // detail=full returns the whole conversation incl. transcript (auth is
        // disabled in ITs, so the caller counts as admin)
        given().get(approvalStatusPath(gcId) + "?detail=full")
                .then().assertThat().statusCode(200)
                .body("id", equalTo(gcId))
                .body("transcript", notNullValue());

        // The paused discussion appears in this group's pending listing as a summary
        given().get("/groups/" + groupResourceId.id() + "/conversations/pending-approvals")
                .then().assertThat().statusCode(200)
                .body("conversationId", hasItem(gcId))
                .body("find { it.conversationId == '" + gcId + "' }.groupId",
                        equalTo(groupResourceId.id()));
    }

    // ==================== Approve → resume → complete ====================

    @Test
    @DisplayName("APPROVED resume re-enters the phase loop and runs the remaining phase to completion")
    void approveResumesAndCompletes() throws Exception {
        String gcId = startPausedDiscussion();

        given().contentType(ContentType.JSON)
                .body("{\"decision\": {\"verdict\": \"APPROVED\", \"note\": \"go ahead\"}}")
                .post(approvePath(gcId))
                .then().assertThat().statusCode(200);

        // The resume executes on a background thread — poll the DB-backed status
        waitForGcState(gcId, "COMPLETED");

        // The post-approval phase actually ran: its name appears in the transcript
        var full = given().get(approvalStatusPath(gcId) + "?detail=full");
        full.then().assertThat().statusCode(200);
        assertTrue(full.getBody().asString().contains("Refine"),
                "the resumed leg must execute the phase AFTER the approved gate");

        // A second approve of the completed discussion → 409, not 500
        given().contentType(ContentType.JSON)
                .body("{\"decision\": {\"verdict\": \"APPROVED\"}}")
                .post(approvePath(gcId))
                .then().assertThat().statusCode(409);
    }

    // ==================== Reject is terminal ====================

    @Test
    @DisplayName("REJECTED verdict fails the discussion terminally; later approve/cancel → 409")
    void rejectIsTerminal() {
        String gcId = startPausedDiscussion();

        given().contentType(ContentType.JSON)
                .body("{\"decision\": {\"verdict\": \"REJECTED\", \"note\": \"not convincing\"}}")
                .post(approvePath(gcId))
                .then().assertThat().statusCode(200)
                .body("state", equalTo("FAILED"));

        given().contentType(ContentType.JSON)
                .body("{\"decision\": {\"verdict\": \"APPROVED\"}}")
                .post(approvePath(gcId))
                .then().assertThat().statusCode(409);

        given().contentType(ContentType.JSON)
                .post(cancelPath(gcId))
                .then().assertThat().statusCode(409);
    }

    // ==================== Cancel of a pending approval ====================

    @Test
    @DisplayName("cancel of a paused discussion → CANCELLED; second cancel and late approve → 409")
    void cancelPausedDiscussion() {
        String gcId = startPausedDiscussion();

        given().contentType(ContentType.JSON)
                .post(cancelPath(gcId))
                .then().assertThat().statusCode(200)
                .body("state", equalTo("CANCELLED"));

        given().contentType(ContentType.JSON)
                .post(cancelPath(gcId))
                .then().assertThat().statusCode(409);

        given().contentType(ContentType.JSON)
                .body("{\"decision\": {\"verdict\": \"APPROVED\"}}")
                .post(approvePath(gcId))
                .then().assertThat().statusCode(409);
    }

    // ==================== Validation & error codes ====================

    @Test
    @DisplayName("invalid approve bodies → 400 and the pause is NOT consumed")
    void badRequestsDoNotConsumePause() {
        String gcId = startPausedDiscussion();

        // Missing decision/verdict
        given().contentType(ContentType.JSON)
                .body("{}")
                .post(approvePath(gcId))
                .then().assertThat().statusCode(400);

        // Note over the 4 KB cap
        given().contentType(ContentType.JSON)
                .body("{\"decision\": {\"verdict\": \"APPROVED\", \"note\": \"" + "x".repeat(4097) + "\"}}")
                .post(approvePath(gcId))
                .then().assertThat().statusCode(400);

        // The pending approval survived both bad requests
        given().get(approvalStatusPath(gcId))
                .then().assertThat().statusCode(200)
                .body("state", equalTo("AWAITING_APPROVAL"));
    }

    @Test
    @DisplayName("approve of an unknown group conversation → 404")
    void approveUnknownGc404() {
        given().contentType(ContentType.JSON)
                .body("{\"decision\": {\"verdict\": \"APPROVED\"}}")
                .post(approvePath("000000000000000000000000"))
                .then().assertThat().statusCode(404);
    }

    // ==================== Helpers ====================

    /**
     * Starts a discussion synchronously; the gated Draft phase commits a pause, so
     * the response entity IS the paused conversation.
     */
    private String startPausedDiscussion() {
        var response = given().contentType(ContentType.JSON)
                .body("{\"question\": \"Should we proceed with the launch?\", \"userId\": \"" + TEST_USER_ID + "\"}")
                .post("/groups/" + groupResourceId.id() + "/conversations");

        response.then().assertThat()
                .statusCode(201)
                .body("state", equalTo("AWAITING_APPROVAL"));
        String gcId = response.jsonPath().getString("id");
        assertNotNull(gcId, "the paused conversation must carry its id");
        return gcId;
    }

    private String approvalStatusPath(String gcId) {
        return "/groups/" + groupResourceId.id() + "/conversations/" + gcId + "/approval-status";
    }

    private String approvePath(String gcId) {
        return "/groups/" + groupResourceId.id() + "/conversations/" + gcId + "/approve";
    }

    private String cancelPath(String gcId) {
        return "/groups/" + groupResourceId.id() + "/conversations/" + gcId + "/cancel";
    }

    /**
     * Polls the DB-backed approval-status until the state is reached (max ~15s).
     */
    private void waitForGcState(String gcId, String expectedState) throws InterruptedException {
        String lastState = null;
        for (int i = 0; i < 60; i++) {
            Response response = given().get(approvalStatusPath(gcId));
            if (response.statusCode() == 200) {
                lastState = response.jsonPath().getString("state");
                if (expectedState.equals(lastState)) {
                    return;
                }
            }
            Thread.sleep(250);
        }
        fail("Group conversation did not reach state " + expectedState + " within 15s (last state: " + lastState + ")");
    }

    private ResourceId createHitlGroup() {
        String groupJson = String.format("""
                {
                  "name": "HITL IT Group",
                  "description": "Group HITL integration test",
                  "members": [
                    {"agentId": "%s", "displayName": "Drafter", "speakingOrder": 1, "role": null, "memberType": "AGENT"},
                    {"agentId": "%s", "displayName": "Reviewer", "speakingOrder": 2, "role": null, "memberType": "AGENT"}
                  ],
                  "style": "CUSTOM",
                  "phases": [
                    {"name": "Draft", "type": "OPINION", "participants": "ALL", "turnOrder": "SEQUENTIAL",
                     "contextScope": "FULL", "targetEachPeer": false, "inputTemplate": null,
                     "repeats": 1, "requiresApproval": true},
                    {"name": "Refine", "type": "OPINION", "participants": "ALL", "turnOrder": "SEQUENTIAL",
                     "contextScope": "FULL", "targetEachPeer": false, "inputTemplate": null,
                     "repeats": 1, "requiresApproval": false}
                  ],
                  "protocol": {"agentTimeoutSeconds": 30, "onAgentFailure": "SKIP",
                               "maxRetries": 1, "onMemberUnavailable": "SKIP", "maxTurns": 20},
                  "hitlConfig": {"timeoutPolicy": "WAIT_INDEFINITELY", "granularity": "PHASE"}
                }
                """, agentId1.id(), agentId2.id());

        var response = given().contentType(ContentType.JSON).body(groupJson).post(GROUP_BASE);
        response.then().assertThat().statusCode(201).header("location", notNullValue());
        return extractResourceId(response.getHeader("location"));
    }

    private ResourceId deployTemplateAgent() throws Exception {
        String dictionary = load("agentengine/dictionary.json");
        String behavior = load("agentengine/rules.json");
        String output = load("agentengine/output.json");

        String locDict = createResource(dictionary, "/dictionarystore/dictionaries");
        String locRules = createResource(behavior, "/rulestore/rulesets");
        String locOutput = createResource(output, "/outputstore/outputsets");

        String packageBody = String.format(
                """
                        {
                          "workflowSteps": [
                            {"type": "eddi://ai.labs.parser", "config": {},
                             "extensions": {"dictionaries": [{"type": "eddi://ai.labs.parser.dictionaries.regular", "config": {"uri": "%s"}}], "corrections": []}},
                            {"type": "eddi://ai.labs.rules", "config": {"uri": "%s"}},
                            {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}},
                            {"type": "eddi://ai.labs.templating", "config": {}}
                          ]
                        }""",
                locDict, locRules, locOutput);

        String locWorkflow = createResource(packageBody, "/workflowstore/workflows");
        String agentBody = String.format("""
                {"packages": ["%s"]}""", locWorkflow);
        String agentLocation = createResource(agentBody, "/agentstore/agents");
        ResourceId agentId = extractResourceId(agentLocation);
        deployAgent(agentId.id(), agentId.version());
        return agentId;
    }
}
