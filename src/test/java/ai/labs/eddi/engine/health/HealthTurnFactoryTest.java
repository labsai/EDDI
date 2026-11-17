/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.health.HealthTurnFactory.Capture;
import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.security.ClientContextGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HealthTurnFactory — what a turn row is built from")
class HealthTurnFactoryTest {

    private static final String CONVERSATION = "6ac11c7a3dc075f0b3dbb8ec";
    private static final String AGENT = "agent-1";
    private static final Instant NOW = Instant.parse("2026-11-10T23:59:30Z");

    private static ConversationMemory memory(String userId) {
        ConversationMemory memory = new ConversationMemory(CONVERSATION, AGENT, 3, userId);
        memory.setConversationState(ConversationState.READY);
        return memory;
    }

    private static <T> void put(IWritableConversationStep step, String key, T value) {
        step.storeData(new Data<>(key, value));
    }

    private static HealthTurn turn(ConversationMemory memory) {
        return capture(memory).turn();
    }

    private static Capture capture(ConversationMemory memory) {
        Capture capture = HealthTurnFactory.capture(memory, null, null, 1234L, NOW, false);
        assertNotNull(capture);
        return capture;
    }

    @Test
    @DisplayName("identity: conversation, step index, version, user, UTC day and the step's execution id")
    void identity() {
        ConversationMemory memory = memory("user-1");
        memory.startNextStep();
        IWritableConversationStep step = memory.getCurrentStep();
        put(step, MemoryKeys.HEALTH_EXECUTION_ID, "exec-1");
        put(step, MemoryKeys.AGENT_VERSION.key(), 5);
        put(step, MemoryKeys.AGENT_VERSION_CHANGE.key(), Map.of("from", 4, "to", 5));

        HealthTurn turn = turn(memory);

        assertEquals(CONVERSATION, turn.conversationId());
        assertEquals(1, turn.stepIndex());
        assertEquals("exec-1", turn.executionId());
        assertEquals(AGENT, turn.agentId());
        assertEquals(5, turn.agentVersion(), "the version that ran the step, not the conversation's current one");
        assertEquals(4, turn.previousAgentVersion());
        assertEquals("user-1", turn.userId());
        assertEquals(LocalDate.of(2026, 11, 10), turn.day());
        assertEquals(NOW, turn.timestamp());
        assertEquals(1234L, turn.durationMs());
        assertFalse(turn.discarded());
    }

    @Test
    @DisplayName("H3: the self-reported outcome is carried when it is one the tool may claim, ignored otherwise")
    void selfReportedOutcome() {
        ConversationMemory memory = memory("user-1");
        memory.startNextStep();
        assertNull(turn(memory).selfReportedOutcome());
        put(memory.getCurrentStep(), MemoryKeys.HEALTH_SELF_REPORTED_OUTCOME, "ESCALATED");
        assertEquals("ESCALATED", turn(memory).selfReportedOutcome());
        put(memory.getCurrentStep(), MemoryKeys.HEALTH_SELF_REPORTED_OUTCOME, "ASSUMED_RESOLVED");
        assertNull(turn(memory).selfReportedOutcome());
        put(memory.getCurrentStep(), MemoryKeys.HEALTH_SELF_REPORTED_OUTCOME, 42);
        assertNull(turn(memory).selfReportedOutcome());
    }

    @Test
    @DisplayName("a step stored before Agent Health gets the stable legacy id; the memory's version stands in")
    void legacyStep() {
        ConversationMemory memory = memory("u");
        // agent:version without an execution id: the step finished an execution
        // before Agent Health existed.
        put(memory.getCurrentStep(), MemoryKeys.AGENT_VERSION.key(), 3);
        HealthTurn turn = turn(memory);
        assertEquals("legacy-0", turn.executionId());
        assertEquals(3, turn.agentVersion());
        assertNull(turn.previousAgentVersion());
    }

    @Test
    @DisplayName("a step persisted before this turn without an id is legacy too")
    void persistedLegacyStep() {
        ConversationMemory memory = memory("u");
        memory.startNextStep();
        memory.setPersistedStepCount(2);
        assertEquals("legacy-1", turn(memory).executionId());
    }

    @Nested
    @DisplayName("unsettled turns")
    class Unsettled {

        @Test
        @DisplayName("TIMED_OUT before the step was stamped: a fresh id per capture, never legacy, inserted only")
        void abandonedGetsFreshIds() {
            ConversationMemory memory = memory("u");
            memory.startNextStep();
            Capture first = HealthTurnFactory.capture(memory, null, ErrorKind.TURN_DEADLINE, 60_000L, NOW, true);
            Capture second = HealthTurnFactory.capture(memory, null, ErrorKind.TURN_DEADLINE, 60_000L, NOW, true);

            assertFalse(first.turn().executionId().startsWith("legacy-"));
            assertNotEquals(first.turn().executionId(), second.turn().executionId(), "repeated timeouts at one step are separate rows");
            assertFalse(first.settled());
        }

        @Test
        @DisplayName("a cancelled say after an undo discards nothing: its snapshot is thrown away and the undone step survives")
        void cancelledDiscardsNothing() {
            ConversationMemory memory = memory("u");
            put(memory.getCurrentStep(), MemoryKeys.HEALTH_DISCARDED_EXECUTIONS, List.of("undone"));
            memory.setCancelled(true);

            Capture capture = HealthTurnFactory.capture(memory, null, ErrorKind.TURN_INTERRUPTED, null, NOW, false);

            assertFalse(capture.settled());
            assertTrue(capture.discardedExecutionIds().isEmpty());
        }

        @Test
        @DisplayName("an interrupted or deadline turn is unsettled; an error turn is settled")
        void kinds() {
            assertFalse(HealthTurnFactory.capture(memory("u"), null, ErrorKind.TURN_DEADLINE, null, NOW, false).settled());
            assertFalse(HealthTurnFactory.capture(memory("u"), null, ErrorKind.TURN_INTERRUPTED, null, NOW, false).settled());
            assertTrue(HealthTurnFactory.capture(memory("u"), null, ErrorKind.TURN_ERROR, null, NOW, false).settled());
            assertTrue(HealthTurnFactory.capture(memory("u"), null, null, null, NOW, false).settled());
        }
    }

    @Test
    @DisplayName("the start turn names its conversation explicitly; without a conversation or agent there is no row")
    void conversationIdFallbacks() {
        ConversationMemory unsaved = new ConversationMemory(AGENT, 1, "u");
        assertNull(HealthTurnFactory.capture(unsaved, null, null, null, NOW, false));
        assertEquals("fresh-id", HealthTurnFactory.capture(unsaved, "fresh-id", null, null, NOW, false).turn().conversationId());
        assertNull(HealthTurnFactory.capture(new ConversationMemory("c", null, 1, "u"), null, null, null, NOW, false));
        assertNull(HealthTurnFactory.capture(null, "c", null, null, NOW, false));
    }

    @Nested
    @DisplayName("totals from the audit keys")
    class Totals {

        @Test
        @DisplayName("cost, discarded cost and tokens come from audit:cost, audit:discarded_cost and audit:token_usage")
        void costAndTokens() {
            ConversationMemory memory = memory("u");
            IWritableConversationStep step = memory.getCurrentStep();
            put(step, MemoryKeys.AUDIT_COST, 0.0123);
            put(step, MemoryKeys.AUDIT_DISCARDED_COST, 0.5);
            put(step, MemoryKeys.AUDIT_TOKEN_USAGE, Map.of("inputTokens", 100, "outputTokens", 20, "totalTokens", 120));

            HealthTurn turn = turn(memory);

            assertEquals(0.0123, turn.costUsd());
            assertEquals(0.5, turn.discardedCostUsd());
            assertEquals(100L, turn.inputTokens());
            assertEquals(20L, turn.outputTokens());
            assertEquals(120L, turn.totalTokens());
        }

        @Test
        @DisplayName("a NaN, negative or missing amount is no amount — the conversationCost filter")
        void invalidAmounts() {
            ConversationMemory memory = memory("u");
            put(memory.getCurrentStep(), MemoryKeys.AUDIT_COST, Double.NaN);
            put(memory.getCurrentStep(), MemoryKeys.AUDIT_DISCARDED_COST, -1.0);
            HealthTurn turn = turn(memory);
            assertNull(turn.costUsd());
            assertNull(turn.discardedCostUsd());
            assertNull(turn.totalTokens());
            assertNull(HealthTurnFactory.capture(memory, null, null, -5L, NOW, false).turn().durationMs(), "a negative duration is no duration");
        }
    }

    @Nested
    @DisplayName("failure markers")
    class Failures {

        @Test
        @DisplayName("error kind, pause, recovered and unrecovered LLM failures, fallback")
        void markers() {
            ConversationMemory memory = memory("u");
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            IWritableConversationStep step = memory.getCurrentStep();
            put(step, MemoryKeys.HEALTH_LLM_FAILURES, List.of(Map.of("class", "TRANSIENT", "recovery", "retry"),
                    Map.of("class", "TIMEOUT", "model", "gpt-x", "recovery", "cascade"), Map.of("class", "AUTH", "recovery", "none"), "junk"));
            put(step, MemoryKeys.LLM_FALLBACK_PREFIX + "task1", Boolean.TRUE);

            HealthTurn turn = HealthTurnFactory.capture(memory, null, ErrorKind.TURN_ERROR, null, NOW, false).turn();

            assertEquals("TURN_ERROR", turn.errorKind());
            assertTrue(turn.failed());
            assertTrue(turn.paused());
            assertEquals(List.of(new HealthFailure("TRANSIENT", null, "retry"), new HealthFailure("TIMEOUT", "gpt-x", "cascade"),
                    new HealthFailure("AUTH", null, "none")), turn.failures());
            assertTrue(turn.failures().get(0).recovered());
            assertFalse(turn.failures().get(2).recovered());
            assertTrue(turn.fallbackServed());
        }

        @Test
        @DisplayName("a pause a cancel converted after it was stored is not recorded as paused")
        void cancelledPauseIsNotPaused() {
            ConversationMemory memory = memory("u");
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setCancelled(true);

            HealthTurn turn = HealthTurnFactory.capture(memory, null, ErrorKind.TURN_INTERRUPTED, null, NOW, false).turn();

            assertFalse(turn.paused());
        }

        @Test
        @DisplayName("a clean turn has no error kind, no failures and no fallback")
        void clean() {
            HealthTurn turn = turn(memory("u"));
            assertNull(turn.errorKind());
            assertFalse(turn.paused());
            assertTrue(turn.failures().isEmpty());
            assertFalse(turn.fallbackServed());
        }

        @Test
        @DisplayName("at most MAX_FAILURES entries are kept")
        void capped() {
            ConversationMemory memory = memory("u");
            List<Map<String, Object>> many = new ArrayList<>();
            IntStream.range(0, 50).forEach(i -> many.add(Map.of("class", "TRANSIENT", "recovery", "retry")));
            put(memory.getCurrentStep(), MemoryKeys.HEALTH_LLM_FAILURES, many);
            assertEquals(HealthTurnFactory.MAX_FAILURES, turn(memory).failures().size());
        }
    }

    @Test
    @DisplayName("tool calls and failures from the LLM traces, guardrail trips from their records")
    void toolsAndGuardrails() {
        ConversationMemory memory = memory("u");
        IWritableConversationStep step = memory.getCurrentStep();
        // Ran and succeeded, ran and failed, never ran (quota), refused before it ran
        // (budget writes a tool_call and a tool_error), a mocked success.
        put(step, MemoryKeys.LANGCHAIN_TRACE_PREFIX + "openai:t1",
                List.of(Map.of("type", "tool_call", "tool", "a"), Map.of("type", "tool_result", "tool", "a"),
                        Map.of("type", "tool_call", "tool", "b"),
                        Map.of("type", "tool_result", "tool", "b", "failed", true), Map.of("type", "tool_call", "tool", "c"),
                        Map.of("type", "tool_result", "tool", "c", "notExecuted", true), Map.of("type", "tool_call", "tool", "d"),
                        Map.of("type", "tool_error", "tool", "d", "error", "budget")));
        put(step, MemoryKeys.LANGCHAIN_TRACE_PREFIX + "openai:t2", List.of(Map.of("type", "tool_result", "mocked", true)));
        // A cascade trace is not a tool trace.
        put(step, "langchain:cascade:trace:t1", List.of(Map.of("type", "tool_result")));
        put(step, MemoryKeys.AUDIT_GUARDRAIL_PREFIX + "input:regex", List.of(Map.of(), Map.of()));
        put(step, MemoryKeys.AUDIT_GUARDRAIL_PREFIX + "output:classifier", List.of(Map.of()));

        HealthTurn turn = turn(memory);

        assertEquals(3, turn.toolCalls(), "the calls that ran");
        assertEquals(1, turn.toolFailures(), "ran and failed");
        assertEquals(2, turn.toolRefusals(), "never ran");
        assertEquals(3, turn.guardrailTrips());
    }

    @Test
    @DisplayName("a call answered with a refusal instead of a tool_result is a refusal, not a call")
    void refusalTraceTypes() {
        ConversationMemory memory = memory("u");
        IWritableConversationStep step = memory.getCurrentStep();
        List<Map<String, Object>> trace = new ArrayList<>();
        for (String type : List.of("tool_repeat_refused", "tool_call_capped", "tool_not_activated", "hitl_self_conversation",
                "hitl_self_ungating", "hitl_request_changed", "hitl_not_executed", "hitl_rejected")) {
            trace.add(Map.of("type", "tool_call", "tool", "x"));
            trace.add(Map.of("type", type, "tool", "x"));
        }
        // Ends the loop, answers no call; a guardrail finding is counted as a trip.
        trace.add(Map.of("type", "tool_repeat_stopped", "refusals", 3));
        trace.add(Map.of("type", "tool_call_guardrail", "tool", "x", "action", "block"));
        put(step, MemoryKeys.LANGCHAIN_TRACE_PREFIX + "openai:t1", trace);

        HealthTurn turn = turn(memory);

        assertEquals(0, turn.toolCalls());
        assertEquals(0, turn.toolFailures());
        assertEquals(8, turn.toolRefusals());
    }

    @Nested
    @DisplayName("engagement inputs")
    class Engagement {

        @Test
        @DisplayName("a turn with a person's input and an answer")
        void userTurn() {
            ConversationMemory memory = memory("u");
            IWritableConversationStep step = memory.getCurrentStep();
            put(step, MemoryKeys.INPUT_INITIAL.key(), "Where is my order?");
            step.addConversationOutputList("output", List.of("It ships tomorrow."));

            HealthTurn turn = turn(memory);

            assertTrue(turn.userInput());
            assertTrue(turn.agentOutput());
            assertFalse(turn.lastOutputWasQuestion());
        }

        @Test
        @DisplayName("the start turn has no user input; a greeting is output")
        void startTurn() {
            ConversationMemory memory = memory("u");
            put(memory.getCurrentStep(), MemoryKeys.INPUT_INITIAL.key(), "");
            memory.getCurrentStep().addConversationOutputList("output", List.of("Hi! How can I help?  "));

            HealthTurn turn = turn(memory);

            assertFalse(turn.userInput());
            assertTrue(turn.agentOutput());
            assertTrue(turn.lastOutputWasQuestion(), "trimmed text ending in '?' — the heuristic");
        }

        @Test
        @DisplayName("a self-continuation is not user input; a full-width question mark is a question")
        void selfContinuation() {
            ConversationMemory memory = memory("u");
            IWritableConversationStep step = memory.getCurrentStep();
            put(step, MemoryKeys.INPUT_INITIAL.key(), "continue");
            put(step, "context:" + ClientContextGuard.KEY_SELF_CONTINUATION, new Context(Context.ContextType.string, "1"));
            step.addConversationOutputList("output", List.of("要继续吗？"));

            HealthTurn turn = turn(memory);

            assertFalse(turn.userInput());
            assertTrue(turn.lastOutputWasQuestion());
        }

        @Test
        @DisplayName("no output is no output and no question")
        void silent() {
            HealthTurn turn = turn(memory("u"));
            assertFalse(turn.agentOutput());
            assertFalse(turn.lastOutputWasQuestion());
        }
    }

    @Test
    @DisplayName("actions are copied, capped in number and length — of too many, the last ones, where a mapped ending action is")
    void actions() {
        ConversationMemory memory = memory("u");
        List<String> many = new ArrayList<>();
        IntStream.range(0, 60).forEach(i -> many.add("action_" + i));
        many.add("x".repeat(500));
        many.add("ESCALATED");
        memory.getCurrentStep().set(MemoryKeys.ACTIONS, many);

        HealthTurn turn = turn(memory);

        assertEquals(HealthTurnFactory.MAX_ACTIONS, turn.actions().size());
        assertEquals("ESCALATED", turn.actions().get(HealthTurnFactory.MAX_ACTIONS - 1), "the last action survives the cap");
        assertEquals(HealthTurnFactory.MAX_ACTION_LENGTH, turn.actions().get(HealthTurnFactory.MAX_ACTIONS - 2).length());
        assertEquals("action_30", turn.actions().get(0), "the first ones are dropped, the order kept");
        assertEquals(List.of("a", "b"), HealthTurnFactory.actions(Arrays.asList("a", null, "b")));
    }

    @Test
    @DisplayName("the executions a turn discarded travel with its capture, not on the row")
    void discardedExecutions() {
        ConversationMemory memory = memory("u");
        put(memory.getCurrentStep(), MemoryKeys.HEALTH_DISCARDED_EXECUTIONS, List.of("old-1", "old-2"));
        Capture capture = capture(memory);
        assertEquals(List.of("old-1", "old-2"), capture.discardedExecutionIds());
        assertFalse(capture.turn().discarded());
    }

    @Test
    @DisplayName("the origin is classified from the step")
    void origin() {
        ConversationMemory memory = memory("slack:U123");
        assertEquals(HealthOrigins.SLACK, turn(memory).origin());
        assertEquals(HealthOrigins.API, turn(memory("someone")).origin());
    }
}
