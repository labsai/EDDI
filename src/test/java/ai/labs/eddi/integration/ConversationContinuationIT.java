/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.scheduler.Scheduler;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end test of self-continuation (the reserved
 * {@code CONTINUE_CONVERSATION} action) through the running application and the
 * real datastore: the decision is committed with the turn, the one-shot
 * schedule is written to the real schedule store, the real
 * {@code SchedulePollerService} claims and fires it, and the continuation turn
 * runs through {@code say}. Everything is asserted as an operator observes it:
 * the stored conversation re-read through REST ({@code returnDetailed=true})
 * and the schedule listing.
 * <p>
 * The agent is deterministic (parser, behaviour rules, output — no LLM): the
 * user message {@code startjob} emits {@code job_step} and
 * {@code CONTINUE_CONVERSATION}, and every following turn whose previous step
 * did {@code job_step} does the same again, so the chain runs until
 * {@code maxContinuations} refuses it.
 * <p>
 * The poller runs every second ({@link ContinuationTestProfile}). The cancel
 * and end scenarios pause it through the Quarkus {@link Scheduler} so the
 * pending continuation is observed before it can fire, and resume it to prove
 * that nothing fires afterwards.
 */
@QuarkusTest
@TestProfile(ContinuationTestProfile.class)
public class ConversationContinuationIT extends BaseIntegrationIT {

    private static final String POLLER_IDENTITY = "schedule-poller";
    private static final String SCHEDULE_BASE = "/schedulestore/schedules/";
    private static final String USER_ID = "continuationTestUser";
    private static final int MAX_CONTINUATIONS = 3;
    private static final String CONTINUATION_INPUT = "continue";

    private static final String STEP_NUMBER = "continuation:number";
    private static final String STEP_NEXT = "continuation:next";
    private static final String STEP_REFUSED = "continuation:refused";
    private static final String CONTEXT_KEY = "context:selfContinuation";

    /**
     * Long enough for several one-second polls, short enough to keep the IT fast.
     */
    private static final Duration QUIET_WINDOW = Duration.ofSeconds(4);
    private static final Duration CHAIN_DEADLINE = Duration.ofSeconds(60);

    /**
     * The agents are deployed once per test class: the Postgres subclass runs
     * against another database, so it must not reuse the Mongo run's ids.
     */
    private static String fixturesFor;
    private static ResourceId continuingAgent;
    private static ResourceId defaultOffAgent;

    /** The body of the last conversation read, for failure messages. */
    private static volatile String lastConversationRead;

    @Inject
    Scheduler scheduler;

    @BeforeEach
    void deployAgents() throws Exception {
        if (!getClass().getName().equals(fixturesFor)) {
            continuingAgent = deployJobAgent(
                    String.format(", \"maxContinuations\": %d, \"continuationInput\": \"%s\"", MAX_CONTINUATIONS, CONTINUATION_INPUT));
            defaultOffAgent = deployJobAgent("");
            fixturesFor = getClass().getName();
        }
    }

    @AfterEach
    void resumePoller() {
        if (scheduler.isPaused(POLLER_IDENTITY)) {
            scheduler.resume(POLLER_IDENTITY);
        }
    }

    // ==================== 1. A real multi-turn job ====================

    @Test
    @DisplayName("one message runs the first turn; the poller runs continuations 1..max on its own and the chain stops at the cap")
    void multiTurnJobRunsUntilTheCap() throws Exception {
        String conversationId = startConversation(continuingAgent);
        int stepsBefore = steps(conversationId).size();

        sendUserInput(continuingAgent.id(), conversationId, "startjob", false, true).then().statusCode(200);

        // The poller alone drives the job: no further request is sent.
        awaitUntil(CHAIN_DEADLINE, () -> {
            List<Map<String, Object>> steps = steps(conversationId);
            return steps.size() >= stepsBefore + 1 + MAX_CONTINUATIONS
                    && value(steps.getLast(), STEP_REFUSED) != null;
        }, "the job did not run " + MAX_CONTINUATIONS + " continuations and stop at the cap (" + stepsBefore + " steps before)");

        // ...and it stays stopped: the refused turn armed nothing.
        assertStaysTrue(QUIET_WINDOW, () -> steps(conversationId).size() == stepsBefore + 1 + MAX_CONTINUATIONS,
                "a turn ran after the cap was reached");

        List<Map<String, Object>> steps = steps(conversationId);
        assertEquals(stepsBefore + 1 + MAX_CONTINUATIONS, steps.size(), "user turn + " + MAX_CONTINUATIONS + " continuation turns");

        // The user's turn: not a continuation itself, it asked for continuation 1.
        Map<String, Object> userTurn = steps.get(stepsBefore);
        assertEquals("startjob", value(userTurn, "input:initial"));
        assertNull(value(userTurn, STEP_NUMBER), "the user's turn is not a continuation turn");
        assertNull(value(userTurn, CONTEXT_KEY), "the user's turn carries no selfContinuation context");
        assertEquals(1, asInt(value(userTurn, STEP_NEXT)));

        // Continuations 1..max: each marked with its number, carrying the reserved
        // context and the designer's literal input; each but the last armed the next.
        for (int n = 1; n <= MAX_CONTINUATIONS; n++) {
            Map<String, Object> turn = steps.get(stepsBefore + n);
            assertEquals(n, asInt(value(turn, STEP_NUMBER)), "continuation marker of turn " + n);
            assertEquals(CONTINUATION_INPUT, value(turn, "input:initial"), "input of continuation " + n);
            assertNotNull(value(turn, CONTEXT_KEY), "continuation " + n + " carries the selfContinuation context");
            assertTrue(String.valueOf(value(turn, "actions")).contains("job_step"), "continuation " + n + " ran the job rule");
            if (n < MAX_CONTINUATIONS) {
                assertEquals(n + 1, asInt(value(turn, STEP_NEXT)), "continuation " + n + " armed the next one");
                assertNull(value(turn, STEP_REFUSED));
            } else {
                assertNull(value(turn, STEP_NEXT), "the last continuation armed nothing");
                assertEquals("limit", value(turn, STEP_REFUSED), "the last continuation was refused at the cap");
            }
        }

        // No pending continuation is left behind.
        assertTrue(pendingContinuations(continuingAgent, conversationId).isEmpty(),
                "no enabled continuation schedule may remain: " + continuationSchedules(continuingAgent, conversationId));
        // Spent continuations are pruned as the job goes: only the latest one stays,
        // with its fire log, not one row per continuation that ran.
        List<Map<String, Object>> left = continuationSchedules(continuingAgent, conversationId);
        assertEquals(1, left.size(), "exactly the last continuation's used-up row may remain: " + left);
        for (Map<String, Object> schedule : left) {
            assertEquals(MAX_CONTINUATIONS, asInt(((Map<?, ?>) schedule.get("metadata")).get("continuationNumber")),
                    "the row left is the last continuation's: " + schedule);
            List<Map<String, Object>> fires = given().get(SCHEDULE_BASE + schedule.get("id") + "/fires").then().statusCode(200)
                    .extract().jsonPath().getList("$");
            assertFalse(fires.isEmpty(), "fire log of " + schedule.get("id"));
            assertTrue(fires.stream().anyMatch(f -> "COMPLETED".equals(f.get("status"))), "a COMPLETED fire of " + schedule.get("id"));
        }

        // A finished job has nothing left to cancel.
        given().post("agents/" + conversationId + "/cancel").then().statusCode(409);
        assertEquals("READY", state(conversationId));
    }

    // ==================== 2. Cancel stops the job ====================

    @Test
    @DisplayName("cancel between two turns withdraws the pending continuation, answers CANCELLED, and no further turn runs")
    void cancelBetweenTurnsStopsTheJob() throws Exception {
        String conversationId = startConversation(continuingAgent);
        scheduler.pause(POLLER_IDENTITY);

        sendUserInput(continuingAgent.id(), conversationId, "startjob", false, true).then().statusCode(200);
        awaitUntil(Duration.ofSeconds(10), () -> pendingContinuations(continuingAgent, conversationId).size() == 1,
                "the first turn did not arm its continuation");
        int stepsAfterTurn = steps(conversationId).size();
        assertUserTurnArmedContinuationOne(conversationId);

        // Cancel → CANCELLED (200), not 409: the job's next turn was queued.
        given().post("agents/" + conversationId + "/cancel").then().statusCode(200);
        assertTrue(continuationSchedules(continuingAgent, conversationId).isEmpty(), "cancel withdraws the continuation schedule");

        scheduler.resume(POLLER_IDENTITY);
        assertStaysTrue(QUIET_WINDOW, () -> steps(conversationId).size() == stepsAfterTurn, "a continuation ran after the cancel");
        assertEquals("READY", state(conversationId));

        // Cancelling again finds nothing to cancel.
        given().post("agents/" + conversationId + "/cancel").then().statusCode(409);
    }

    // ==================== 3. End withdraws ====================

    @Test
    @DisplayName("ending the conversation withdraws the pending continuation")
    void endConversationWithdrawsTheContinuation() throws Exception {
        String conversationId = startConversation(continuingAgent);
        scheduler.pause(POLLER_IDENTITY);

        sendUserInput(continuingAgent.id(), conversationId, "startjob", false, true).then().statusCode(200);
        awaitUntil(Duration.ofSeconds(10), () -> pendingContinuations(continuingAgent, conversationId).size() == 1,
                "the first turn did not arm its continuation");
        int stepsAfterTurn = steps(conversationId).size();
        assertUserTurnArmedContinuationOne(conversationId);

        given().post("agents/" + conversationId + "/endConversation").then().statusCode(200);
        assertTrue(continuationSchedules(continuingAgent, conversationId).isEmpty(), "end withdraws the continuation schedule");

        scheduler.resume(POLLER_IDENTITY);
        assertStaysTrue(QUIET_WINDOW, () -> steps(conversationId).size() == stepsAfterTurn, "a continuation ran after the end");
        assertEquals("ENDED", state(conversationId));
    }

    // ==================== 4. REST cannot forge a continuation ====================

    @Test
    @DisplayName("a schedule create or update carrying the continuation marker is refused with 400")
    void restCannotForgeAContinuation() {
        String conversationId = startConversation(continuingAgent);
        String forged = String.format("""
                {
                  "name": "continuation-%1$s",
                  "agentId": "%2$s",
                  "triggerType": "CRON",
                  "cronExpression": "0 0 * * *",
                  "timeZone": "UTC",
                  "enabled": false,
                  "userId": "%3$s",
                  "message": "continue",
                  "conversationStrategy": "new",
                  "metadata": {
                    "continuationType": "conversation_continuation",
                    "conversationId": "%1$s",
                    "continuationNumber": 1,
                    "maxContinuations": 3
                  }
                }""", conversationId, continuingAgent.id(), USER_ID);

        given().contentType(ContentType.JSON).body(forged).post(SCHEDULE_BASE).then().statusCode(400);
        assertTrue(continuationSchedules(continuingAgent, conversationId).isEmpty(), "the refused create wrote nothing");

        // An ordinary schedule may not be turned into one by an update either.
        String plain = String.format("""
                {
                  "name": "continuation-it-plain-%s",
                  "agentId": "%s",
                  "triggerType": "CRON",
                  "cronExpression": "0 0 * * *",
                  "timeZone": "UTC",
                  "enabled": false,
                  "userId": "%s",
                  "message": "hello",
                  "conversationStrategy": "new"
                }""", conversationId, continuingAgent.id(), USER_ID);
        Response created = given().contentType(ContentType.JSON).body(plain).post(SCHEDULE_BASE);
        assertTrue(created.statusCode() == 200 || created.statusCode() == 201, "plain schedule create: " + created.statusCode());
        String scheduleId = created.jsonPath().getString("id");
        assertNotNull(scheduleId);
        try {
            given().contentType(ContentType.JSON).body(forged).put(SCHEDULE_BASE + scheduleId).then().statusCode(400);
            given().get(SCHEDULE_BASE + scheduleId).then().statusCode(200)
                    .body("metadata.continuationType", nullValue())
                    .body("name", equalTo("continuation-it-plain-" + conversationId));
        } finally {
            given().delete(SCHEDULE_BASE + scheduleId);
        }
    }

    // ==================== 5. Default off ====================

    @Test
    @DisplayName("an agent without maxContinuations arms nothing, even when the action is emitted")
    void defaultOffArmsNothing() throws Exception {
        String conversationId = startConversation(defaultOffAgent);

        sendUserInput(defaultOffAgent.id(), conversationId, "startjob", false, true).then().statusCode(200);
        awaitUntil(Duration.ofSeconds(10), () -> "disabled".equals(value(steps(conversationId).getLast(), STEP_REFUSED)),
                "the stored turn does not record continuation:refused = disabled");
        int stepsAfterTurn = steps(conversationId).size();

        Map<String, Object> turn = steps(conversationId).getLast();
        assertTrue(String.valueOf(value(turn, "actions")).contains("CONTINUE_CONVERSATION"),
                "the rule did emit CONTINUE_CONVERSATION");
        assertNull(value(turn, STEP_NEXT), "nothing was scheduled");

        assertStaysTrue(QUIET_WINDOW, () -> steps(conversationId).size() == stepsAfterTurn, "a continuation ran although it is off");
        assertTrue(continuationSchedules(defaultOffAgent, conversationId).isEmpty(), "no continuation schedule was written");
    }

    // ==================== Helpers ====================

    /**
     * The newest stored step is the user's {@code startjob} turn, which asked for
     * continuation 1.
     */
    private void assertUserTurnArmedContinuationOne(String conversationId) {
        Map<String, Object> last = steps(conversationId).getLast();
        assertEquals("startjob", value(last, "input:initial"), "the newest step is the user's turn");
        assertEquals(1, asInt(value(last, STEP_NEXT)), "the user's turn asked for continuation 1");
    }

    private ResourceId deployJobAgent(String continuationFields) throws Exception {
        String locationDictionary = createResource(load("continuation/dictionary.json"), "/dictionarystore/dictionaries");
        String locationBehavior = createResource(load("continuation/rules.json"), "/rulestore/rulesets");
        String locationOutput = createResource(load("continuation/output.json"), "/outputstore/outputsets");

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
                    {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}}
                  ]
                }""", locationDictionary, locationBehavior, locationOutput);
        String locationWorkflow = createResource(workflowBody, "/workflowstore/workflows");

        String agentBody = String.format("""
                {"workflows": ["%s"]%s}""", locationWorkflow, continuationFields);
        ResourceId agentId = extractResourceId(createResource(agentBody, "/agentstore/agents"));
        deployAgent(agentId.id(), agentId.version());
        return agentId;
    }

    private String startConversation(ResourceId agent) {
        ResourceId conversation = createConversation(agent.id(), USER_ID);
        try {
            waitForConversationReady(agent.id(), conversation.id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for the conversation");
        }
        return conversation.id();
    }

    /** The stored conversation's steps, with every data key (returnDetailed). */
    private List<Map<String, Object>> steps(String conversationId) {
        // returnCurrentStepOnly defaults to true on this endpoint: ask for every step.
        Response response = given().get("agents/" + conversationId + "?returnDetailed=true&returnCurrentStepOnly=false");
        response.then().statusCode(200);
        List<Map<String, Object>> steps = response.jsonPath().getList("conversationSteps");
        lastConversationRead = response.getBody().asString();
        return steps != null ? steps : List.of();
    }

    private String state(String conversationId) {
        return given().get("agents/" + conversationId + "?returnDetailed=false").then().statusCode(200).extract().jsonPath()
                .getString("conversationState");
    }

    /** The value of {@code key} in a step, or {@code null}. */
    @SuppressWarnings("unchecked")
    private static Object value(Map<String, Object> step, String key) {
        Object data = step != null ? step.get("conversationStep") : null;
        if (!(data instanceof List<?> entries)) {
            return null;
        }
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> map && key.equals(map.get("key"))) {
                return ((Map<String, Object>) map).get("value");
            }
        }
        return null;
    }

    private static Integer asInt(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    /** Every continuation schedule of the conversation, enabled or not. */
    private List<Map<String, Object>> continuationSchedules(ResourceId agent, String conversationId) {
        List<Map<String, Object>> all = given().get(SCHEDULE_BASE + "?agentId=" + agent.id()).then().statusCode(200).extract().jsonPath()
                .getList("$");
        List<Map<String, Object>> mine = new ArrayList<>();
        for (Map<String, Object> schedule : all) {
            if (("continuation-" + conversationId).equals(schedule.get("name"))) {
                mine.add(schedule);
            }
        }
        return mine;
    }

    /** The continuation schedules of the conversation that can still fire. */
    private List<Map<String, Object>> pendingContinuations(ResourceId agent, String conversationId) {
        return continuationSchedules(agent, conversationId).stream().filter(s -> Boolean.TRUE.equals(s.get("enabled"))).toList();
    }

    /** Polls {@code condition} until it holds, failing at the deadline. */
    private static void awaitUntil(Duration deadline, BooleanSupplier condition, String message) throws InterruptedException {
        long end = System.nanoTime() + deadline.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        if (!condition.getAsBoolean()) {
            fail(message + " within " + deadline + "; last conversation read: " + lastConversationRead);
        }
    }

    /** Checks that {@code condition} holds throughout {@code window}. */
    private static void assertStaysTrue(Duration window, BooleanSupplier condition, String message) throws InterruptedException {
        long end = System.nanoTime() + window.toNanos();
        do {
            assertTrue(condition.getAsBoolean(), message);
            Thread.sleep(250);
        } while (System.nanoTime() < end);
    }
}
