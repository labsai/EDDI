/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.Defaults;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.Expectation;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.PropertyExpectation;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.TestCase;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.Turn;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.agenttests.ScriptedEngine.Step;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun.AttemptResult;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.ConversationPurgeService;
import ai.labs.eddi.engine.model.Context;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The runner against a deterministic, rules-only agent (see
 * {@link ScriptedEngine}): a booking wizard that greets, asks for a city,
 * stores it and confirms. Everything the runner judges comes from snapshots in
 * the real stored shape.
 */
@DisplayName("AgentTestRunner")
class AgentTestRunnerTest {

    private static final String RUN_ID = "0123456789abcdef01234567";
    private static final String AGENT_ID = "abcdefabcdefabcdefabcdef";

    private IAgentTestRunStore runStore;
    private ConversationPurgeService conversationStore;
    private AuditLedgerService auditLedger;

    /** The scripted wizard: deterministic, like a rules-only agent. */
    private static final Function<String, Step> WIZARD = input -> switch (input) {
        case "hello" -> Step.of(List.of("greet", "ask_city"), "Hi! Which city?");
        case "Vienna" -> Step.of(List.of("store_city", "confirm"), "Vienna it is — 3 rooms free.").withProperties(Map.of("city", "Vienna"));
        case "expensive" -> Step.of(List.of("think"), "Thinking hard.").costing(0.6);
        case "approve me" -> Step.of(List.of("request_approval"), "Waiting for a human.").ending(ConversationState.AWAITING_HUMAN);
        default -> Step.of(List.of("fallback"), "Sorry?");
    };

    @BeforeEach
    void setUp() {
        runStore = mock(IAgentTestRunStore.class);
        conversationStore = mock(ConversationPurgeService.class);
        auditLedger = mock(AuditLedgerService.class);
    }

    private AgentTestRunner runner(ScriptedEngine engine, int maxParallel) {
        return new AgentTestRunner(engine.service, runStore, conversationStore, auditLedger, new SimpleMeterRegistry(), maxParallel, 50);
    }

    private static ScriptedEngine engine() throws Exception {
        return new ScriptedEngine(Step.of(List.of("CONVERSATION_START"), "Welcome"), WIZARD);
    }

    private static AgentTestRun started(boolean keep) {
        return new AgentTestRun(RUN_ID, "bbbbbbbbbbbbbbbbbbbbbbbb", 3, "wizard", AGENT_ID, 7, AgentTestRun.Status.RUNNING, "alice", 1L, null, null,
                null, null, null, null, null, null, null, null, keep, null, null, null);
    }

    private static AgentTestRun startedWithHeartbeat(long heartbeatAt) {
        return new AgentTestRun(RUN_ID, "bbbbbbbbbbbbbbbbbbbbbbbb", 3, "wizard", AGENT_ID, 7, AgentTestRun.Status.RUNNING, "alice", 1L, null, null,
                null, null, null, null, null, null, null, null, false, null, "node-a/boot0001", heartbeatAt);
    }

    private static Expectation actions(String... contain) {
        return new Expectation(List.of(contain), null, null, null, null, null, null, null, null, null);
    }

    private static AgentTestSuite suite(Defaults defaults, Double maxCost, TestCase... cases) {
        return new AgentTestSuite("wizard", null, defaults, maxCost, null, List.of(cases));
    }

    private static TestCase happyPath() {
        var cityStored = new Expectation(List.of("store_city"), null, Map.of("city", new PropertyExpectation("Vienna", null, null, null)),
                null, "(?i)3 rooms", null, null, 0, null, "READY");
        return new TestCase("happy path", Map.of("language", "en"), null, null,
                List.of(new Turn("hello", null, actions("greet", "ask_city")), new Turn("Vienna", null, cityStored)));
    }

    @Test
    @DisplayName("a deterministic suite that holds passes, and every conversation is ended and deleted")
    void passingRulesOnlySuite() throws Exception {
        var engine = engine();

        AgentTestRun run = runner(engine, 4).run(started(false), suite(null, null, happyPath()));

        assertEquals(AgentTestRun.Status.PASSED, run.status(), () -> String.valueOf(run.cases()));
        assertEquals(1, run.casesPassed());
        var attempt = run.cases().getFirst().attempts().getFirst();
        assertTrue(attempt.passed());
        assertNull(attempt.conversationId(), "not kept, so not reported");
        assertEquals(List.of("store_city", "confirm"), attempt.turns().get(1).actions());

        // Test conversations: test environment, the reserved user id, ended, deleted.
        assertEquals(List.of("test"), engine.environments);
        String conversationId = engine.conversations.keySet().iterator().next();
        assertEquals(AgentTestConversations.userIdFor(RUN_ID, 0, 1), engine.userIds.get(conversationId));
        verify(engine.service).endConversation(conversationId, AgentTestConversations.SYSTEM_ACTOR);
        verify(conversationStore).purgeAgentTestConversation(conversationId, RUN_ID);

        verify(runStore).update(run);
        verify(runStore).pruneOlderRuns(AGENT_ID, 50);
    }

    @Test
    @DisplayName("one summary audit entry per run")
    void writesOneAuditEntry() throws Exception {
        runner(engine(), 4).run(started(false), suite(null, null, happyPath()));

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLedger, times(1)).submit(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals(AgentTestRunner.AUDIT_TASK_ID, entry.taskId());
        assertEquals(AGENT_ID, entry.agentId());
        assertEquals(7, entry.agentVersion());
        assertEquals("alice", entry.userId());
        assertEquals("PASSED", entry.output().get("status"));
        assertEquals(RUN_ID, entry.input().get("runId"));
    }

    @Test
    @DisplayName("a turn that does not do what the suite says fails the case with the actual value")
    void expectationFailureIsDetected() throws Exception {
        var testCase = new TestCase("wrong", null, null, null, List.of(new Turn("hello", null, actions("book_room"))));

        AgentTestRun run = runner(engine(), 4).run(started(false), suite(null, null, testCase));

        assertEquals(AgentTestRun.Status.FAILED, run.status());
        var turn = run.cases().getFirst().attempts().getFirst().turns().getFirst();
        assertFalse(turn.passed());
        assertEquals("actionsContain", turn.failures().getFirst().expectation());
        assertEquals(List.of("greet", "ask_city"), turn.failures().getFirst().actual());
    }

    @Test
    @DisplayName("the cost ceiling is checked between turns and stops the run")
    void costCeilingStopsTheRun() throws Exception {
        var engine = engine();
        var testCase = new TestCase("costly", null, null, null,
                List.of(new Turn("expensive", null, null), new Turn("expensive", null, null), new Turn("expensive", null, null)));

        AgentTestRun run = runner(engine, 4).run(started(false), suite(null, 1.0, testCase));

        assertEquals(AgentTestRun.Status.FAILED, run.status());
        assertTrue(run.costCeilingReached());
        AttemptResult attempt = run.cases().getFirst().attempts().getFirst();
        assertEquals(2, attempt.turns().size(), "0.6 + 0.6 crosses $1, so the third turn never runs");
        assertTrue(attempt.error().contains("cost ceiling"), attempt.error());
        assertEquals(1.2, run.totalCostUsd(), 1e-9);
        assertEquals(List.of("scripted-model"), run.models());
    }

    @Test
    @DisplayName("a HITL pause is an observable outcome; the conversation is ended, never left waiting")
    void hitlPauseIsObservableAndCleanedUp() throws Exception {
        var engine = engine();
        var paused = new Expectation(null, null, null, null, null, null, null, null, null, "AWAITING_HUMAN");
        var testCase = new TestCase("approval", null, null, null, List.of(new Turn("approve me", null, paused)));

        AgentTestRun run = runner(engine, 4).run(started(false), suite(null, null, testCase));

        assertEquals(AgentTestRun.Status.PASSED, run.status(), () -> String.valueOf(run.cases()));
        String conversationId = engine.conversations.keySet().iterator().next();
        verify(engine.service).endConversation(conversationId, AgentTestConversations.SYSTEM_ACTOR);
    }

    @Test
    @DisplayName("a turn after a pause fails honestly instead of waiting for a human")
    void turnAfterPauseFails() throws Exception {
        var testCase = new TestCase("approval", null, null, null, List.of(new Turn("approve me", null, null), new Turn("hello", null, null)));

        AgentTestRun run = runner(engine(), 4).run(started(false), suite(null, null, testCase));

        var attempt = run.cases().getFirst().attempts().getFirst();
        assertFalse(attempt.passed());
        assertTrue(attempt.error().contains("AWAITING_HUMAN"), attempt.error());
    }

    @Test
    @DisplayName("a turn that never answers times out, fails, and the conversation is still ended")
    void turnTimeout() throws Exception {
        var engine = engine();
        engine.neverAnswer = true;
        var testCase = new TestCase("slow", null, null, null, List.of(new Turn("hello", null, null)));

        AgentTestRun run = runner(engine, 4).run(started(false), suite(new Defaults(null, null, null, null, 1), null, testCase));

        var turn = run.cases().getFirst().attempts().getFirst().turns().getFirst();
        assertFalse(turn.passed());
        assertEquals("timed out", turn.failures().getFirst().actual());
        verify(engine.service).endConversation(anyString(), eq(AgentTestConversations.SYSTEM_ACTOR));
    }

    @Test
    void keptConversationsAreNotDeletedAndAreReported() throws Exception {
        var engine = engine();

        AgentTestRun run = runner(engine, 4).run(started(true), suite(null, null, happyPath()));

        verify(conversationStore, never()).purgeAgentTestConversation(anyString(), anyString());
        assertNotNull(run.cases().getFirst().attempts().getFirst().conversationId());
    }

    @Test
    @DisplayName("repetitions run in parallel, never more than the configured cap at once")
    void parallelismIsCapped() throws Exception {
        var engine = engine();
        engine.startDelayMs = 30;
        var testCase = new TestCase("many", null, 8, null, List.of(new Turn("hello", null, actions("greet"))));

        AgentTestRun run = runner(engine, 2).run(started(false), suite(null, null, testCase));

        assertEquals(8, run.cases().getFirst().attempts().size());
        assertTrue(engine.maxActive.get() <= 2, "at most 2 at once, saw " + engine.maxActive.get());
        assertEquals(AgentTestRun.Status.PASSED, run.status());
    }

    @Test
    @DisplayName("a start failure becomes a failed attempt, not a crashed run")
    void startFailureIsAFailedAttempt() throws Exception {
        var engine = engine();
        doThrow(new IllegalStateException("agent not ready")).when(engine.service).startConversation(any(), anyString(), anyString(), any());

        AgentTestRun run = runner(engine, 4).run(started(false), suite(null, null, happyPath()));

        assertEquals(AgentTestRun.Status.FAILED, run.status());
        assertTrue(run.cases().getFirst().attempts().getFirst().error().contains("agent not ready"));
    }

    @Test
    @DisplayName("passRate compares counts: 4 of 5 meets 0.8 despite floating point")
    void passRateCountsRepetitions() {
        var pass = new AttemptResult(1, null, true, 0, 0, null, List.of(), List.of(), null);
        var fail = new AttemptResult(1, null, false, 0, 0, null, List.of(), List.of(), null);
        var four = new ArrayList<>(Collections.nCopies(4, pass));
        four.add(fail);

        assertTrue(AgentTestRunner.caseResult("c", four, 0.8).passed());
        assertFalse(AgentTestRunner.caseResult("c", four, 1.0).passed());
        assertEquals(4, AgentTestRunner.caseResult("c", four, 1.0).passedRepetitions());
    }

    @Test
    void contextValuesBecomeEngineContexts() {
        var context = AgentTestRunner.toContext(Map.of("language", "en", "user", Map.of("tier", "gold")), Map.of("language", "de"));

        assertEquals("de", context.get("language").getValue(), "the turn's value wins");
        assertEquals(Context.ContextType.object, context.get("user").getType());
    }

    @Test
    @DisplayName("a call without a mock fails the case and is reported — and was never executed")
    void unmockedToolCallFailsTheCase() throws Exception {
        var engine = engine();
        engine.toolCallsByInput.put("hello", List.of("createBooking"));

        AgentTestRun run = runner(engine, 4).run(started(false), suite(null, null, happyPath()));

        assertEquals(AgentTestRun.Status.FAILED, run.status());
        var attempt = run.cases().getFirst().attempts().getFirst();
        assertEquals(List.of("createBooking"), attempt.unmockedToolCalls());
        var turn = attempt.turns().getFirst();
        assertEquals("unmockedTool", turn.failures().getFirst().expectation());
        assertEquals(List.of("createBooking"), turn.toolCalls());
    }

    @Test
    @DisplayName("a mocked call satisfies toolCalled and the case passes")
    void mockedToolCallPasses() throws Exception {
        var engine = engine();
        engine.toolCallsByInput.put("hello", List.of("getAvailability"));
        var expect = new Expectation(null, null, null, null, null, List.of(new AgentTestSuite.ToolCallExpectation("getAvailability", null)),
                List.of("createBooking"), 1, null, null);
        var suite = new AgentTestSuite("wizard", null, null, null,
                List.of(new AgentTestSuite.ToolMock("getAvailability", null, new AgentTestSuite.MockResponse(200, "3"))),
                List.of(new TestCase("tools", null, null, null, List.of(new Turn("hello", null, expect)))));

        AgentTestRun run = runner(engine, 4).run(started(false), suite);

        assertEquals(AgentTestRun.Status.PASSED, run.status(), () -> String.valueOf(run.cases()));
    }

    /**
     * A run holds one of a node's few run slots for as long as it runs — up to
     * 2,000 turns of up to 900 s each. Cancel must free it without waiting for the
     * turn in flight, and still end and delete the conversation.
     */
    @Test
    @DisplayName("cancel stops a run mid-turn: CANCELLED, the conversation ended and deleted, the run no longer active")
    void cancelStopsARunMidTurn() throws Exception {
        var engine = engine();
        engine.neverAnswer = true;
        var runner = runner(engine, 4);
        var result = CompletableFuture.supplyAsync(() -> runner.run(started(false), suite(null, null, happyPath())));
        verify(engine.service, timeout(5_000)).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(),
                any());

        assertTrue(runner.cancel(RUN_ID, "bob"));
        AgentTestRun run = result.get(10, TimeUnit.SECONDS);

        assertEquals(AgentTestRun.Status.CANCELLED, run.status());
        assertTrue(run.error().contains("bob"), run.error());
        AttemptResult attempt = run.cases().getFirst().attempts().getFirst();
        assertFalse(attempt.passed());
        assertTrue(attempt.error().contains("cancelled"), attempt.error());
        String conversationId = engine.conversations.keySet().iterator().next();
        verify(engine.service).endConversation(conversationId, AgentTestConversations.SYSTEM_ACTOR);
        verify(conversationStore).purgeAgentTestConversation(conversationId, RUN_ID);
        assertFalse(runner.isActive(RUN_ID));
        assertFalse(runner.cancel(RUN_ID, "bob"), "a finished run is not cancellable");
        verify(runStore).update(run);
    }

    @Test
    @DisplayName("a run not executing on this node cannot be cancelled here")
    void unknownRunIsNotCancelled() throws Exception {
        assertFalse(runner(engine(), 4).cancel(RUN_ID, "bob"));
    }

    /**
     * Other replicas tell a live run from an orphan by its heartbeat. The final
     * write must be the last one: a heartbeat after it would put RUNNING back.
     */
    @Test
    @DisplayName("a running run's heartbeat is refreshed by compare-and-swap, and nothing is written after the final record")
    void heartbeatRunsUntilTheFinalWrite() throws Exception {
        var engine = engine();
        engine.neverAnswer = true;
        var runner = new AgentTestRunner(engine.service, runStore, conversationStore, auditLedger, new SimpleMeterRegistry(), 4, 50, 20);
        var result = CompletableFuture.supplyAsync(() -> runner.run(startedWithHeartbeat(5L), suite(null, null, happyPath())));
        verify(runStore, timeout(5_000).atLeast(2)).updateIfHeartbeat(argThat(r -> r.status() == AgentTestRun.Status.RUNNING), anyLong());

        runner.cancel(RUN_ID, "bob");
        AgentTestRun finished = result.get(10, TimeUnit.SECONDS);
        Thread.sleep(100);

        var records = ArgumentCaptor.forClass(AgentTestRun.class);
        var expected = ArgumentCaptor.forClass(Long.class);
        verify(runStore, atLeast(3)).updateIfHeartbeat(records.capture(), expected.capture());
        assertEquals(finished, records.getAllValues().getLast(), "the final record is the last write");
        // Each write expects the heartbeat the one before stored: a chain no other
        // writer can join.
        assertEquals(5L, expected.getAllValues().getFirst());
        for (int i = 1; i < expected.getAllValues().size(); i++) {
            assertEquals(records.getAllValues().get(i - 1).heartbeatAt(), expected.getAllValues().get(i));
        }
        verify(runStore, never()).update(any());
    }

    /**
     * Another node saw the heartbeat stop and closed the run as ABORTED. The
     * owner's next beat fails its compare-and-swap: it must stop the run, not write
     * RUNNING (or its own verdict) back over what pollers already saw.
     */
    @Test
    @DisplayName("a run closed by another node stops at its next heartbeat and writes nothing back")
    void lostOwnershipStopsTheRun() throws Exception {
        var engine = engine();
        engine.neverAnswer = true;
        doThrow(new IResourceStore.ResourceModifiedException("heartbeat changed")).when(runStore).updateIfHeartbeat(any(), anyLong());
        var runner = new AgentTestRunner(engine.service, runStore, conversationStore, auditLedger, new SimpleMeterRegistry(), 4, 50, 20);

        AgentTestRun run = CompletableFuture.supplyAsync(() -> runner.run(startedWithHeartbeat(5L), suite(null, null, happyPath())))
                .get(10, TimeUnit.SECONDS);

        assertNotNull(run);
        verify(runStore, never()).update(any());
        verify(auditLedger, never()).submit(any());
        assertFalse(runner.isActive(RUN_ID));
    }

    /**
     * A turn given up on can finish in the engine after its conversation was ended
     * and deleted, and write it back: once it completes, the conversation is
     * deleted again.
     */
    @Test
    @DisplayName("a conversation whose abandoned turn finishes late is deleted again")
    void abandonedTurnIsPurgedAgainWhenItFinishes() throws Exception {
        var engine = engine();
        engine.neverAnswer = true;
        var runner = runner(engine, 4);
        var result = CompletableFuture.supplyAsync(() -> runner.run(started(false), suite(null, null, happyPath())));
        verify(engine.service, timeout(5_000)).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(),
                any());
        runner.cancel(RUN_ID, "bob");
        result.get(10, TimeUnit.SECONDS);
        String conversationId = engine.conversations.keySet().iterator().next();
        verify(conversationStore, times(1)).purgeAgentTestConversation(conversationId, RUN_ID);

        engine.unanswered.getFirst().onComplete(null);

        verify(conversationStore, timeout(15_000).times(2)).purgeAgentTestConversation(conversationId, RUN_ID);
    }

    @Test
    @DisplayName("if the result cannot be stored, a terminal ERROR record replaces RUNNING")
    void storeFailureLeavesATerminalRecord() throws Exception {
        doThrow(new IllegalStateException("document too large")).doNothing().when(runStore).update(any(AgentTestRun.class));

        runner(engine(), 4).run(started(false), suite(null, null, happyPath()));

        var captor = ArgumentCaptor.forClass(AgentTestRun.class);
        verify(runStore, times(2)).update(captor.capture());
        AgentTestRun fallback = captor.getAllValues().get(1);
        assertEquals(AgentTestRun.Status.ERROR, fallback.status());
        assertTrue(fallback.cases().isEmpty(), "the per-case detail is dropped");
        assertTrue(fallback.error().contains("could not be stored"), fallback.error());
    }
}
