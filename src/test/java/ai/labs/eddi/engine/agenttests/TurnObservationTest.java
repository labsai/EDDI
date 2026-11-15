/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.engine.agenttests.TurnObservation.ObservedToolCall;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot.ConversationStepData;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot.SimpleConversationStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading what one turn did out of a stored conversation or a turn snapshot —
 * the facts the agent-test expectations are judged against.
 */
class TurnObservationTest {

    private static ConversationOutput output(String text) {
        var output = new ConversationOutput();
        output.put("output", List.of(text));
        return output;
    }

    private static Map<String, Object> toolTrace(Object... calls) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("calls", Arrays.asList(calls));
        return trace;
    }

    private static Map<String, Object> call(String type, String tool, Object arguments) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("type", type);
        call.put("tool", tool);
        call.put("arguments", arguments);
        return call;
    }

    private static ResultSnapshot result(String key, Object value) {
        return new ResultSnapshot(key, value, null, new Date(), "wf", true);
    }

    private static ConversationStepSnapshot step(ResultSnapshot... results) {
        var workflow = new WorkflowRunSnapshot();
        workflow.setLifecycleTasks(new ArrayList<>(List.of(results)));
        var step = new ConversationStepSnapshot();
        step.setWorkflows(new ArrayList<>(List.of(workflow)));
        return step;
    }

    @Test
    void aStoredStepIsReadWithItsOutputPropertiesAndState() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationSteps(new ArrayList<>(List.of(step(result(MemoryKeys.ACTIONS.key(), List.of("greet"))),
                step(result(MemoryKeys.ACTIONS.key(), List.of("ask", 2)), result(MemoryKeys.AUDIT_COST, 0.25),
                        result(MemoryKeys.AUDIT_MODEL_NAME, "gpt-4o"), result(null, "ignored"), result("other", null)))));
        snapshot.setConversationOutputs(new ArrayList<>(List.of(output("hello"), output("how can I help?"))));
        Map<String, Property> properties = new LinkedHashMap<>();
        properties.put("name", new Property("name", "Ada", Scope.conversation));
        properties.put("empty", new Property());
        snapshot.setConversationProperties(properties);
        snapshot.setConversationState(ConversationState.READY);

        var turn = TurnObservation.fromSnapshot(snapshot, 1);
        assertEquals(List.of("ask", "2"), turn.actions());
        assertEquals(0.25, turn.costUsd());
        assertEquals(Set.of("gpt-4o"), turn.modelNames());
        assertEquals("how can I help?", turn.output());
        assertEquals(Map.of("name", "Ada"), turn.properties(), "a property without a value is left out");
        assertEquals("READY", turn.conversationState());

        var first = TurnObservation.fromSnapshot(snapshot, 0);
        assertEquals(List.of("greet"), first.actions());
        assertEquals("hello", first.output());
        assertEquals(0.0, first.costUsd());
    }

    @Test
    void aStepIndexOutsideTheConversationObservesNothing() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationSteps(new ArrayList<>(List.of(step(result(MemoryKeys.ACTIONS.key(), List.of("greet"))))));
        for (int index : new int[]{-1, 1}) {
            var turn = TurnObservation.fromSnapshot(snapshot, index);
            assertEquals(List.of(), turn.actions());
            assertEquals("", turn.output());
            assertTrue(turn.properties().isEmpty());
            assertNull(turn.conversationState());
        }
        snapshot.setConversationSteps(null);
        assertEquals(List.of(), TurnObservation.fromSnapshot(snapshot, 0).actions());
    }

    @Test
    void aTurnSnapshotIsReadFromItsLastStepAndLastOutput() {
        var snapshot = new SimpleConversationMemorySnapshot();
        var earlier = new SimpleConversationStep();
        earlier.setConversationStep(new ArrayList<>(List.of(new ConversationStepData(MemoryKeys.ACTIONS.key(), List.of("old"), new Date(), "wf"))));
        var last = new SimpleConversationStep();
        last.setConversationStep(new ArrayList<>(List.of(new ConversationStepData(MemoryKeys.ACTIONS.key(), List.of("new"), new Date(), "wf"),
                new ConversationStepData(MemoryKeys.AUDIT_TOOL_CALLS, toolTrace(call("tool_call", "calc", "{\"a\":1}")), new Date(), "wf"),
                new ConversationStepData(MemoryKeys.AUDIT_COST, "not-a-number", new Date(), "wf"))));
        snapshot.setConversationSteps(new ArrayList<>(List.of(earlier, last)));
        snapshot.setConversationOutputs(new ArrayList<>(List.of(output("first"), output("second"))));
        snapshot.setConversationState(ConversationState.ENDED);

        var turn = TurnObservation.fromTurnSnapshot(snapshot);
        assertEquals(List.of("new"), turn.actions());
        assertEquals(List.of(new ObservedToolCall("calc", "{\"a\":1}")), turn.toolCalls());
        assertEquals(0.0, turn.costUsd(), "a cost that is not a number is not a cost");
        assertEquals("second", turn.output());
        assertEquals("ENDED", turn.conversationState());
    }

    @Test
    void anEmptyTurnSnapshotObservesNothing() {
        var snapshot = new SimpleConversationMemorySnapshot();
        var turn = TurnObservation.fromTurnSnapshot(snapshot);
        assertEquals(List.of(), turn.actions());
        assertEquals("", turn.output());

        snapshot.setConversationSteps(new ArrayList<>());
        snapshot.setConversationOutputs(new ArrayList<>());
        assertEquals("", TurnObservation.fromTurnSnapshot(snapshot).output());

        var noText = new ConversationOutput();
        snapshot.setConversationOutputs(new ArrayList<>(List.of(noText)));
        assertEquals("", TurnObservation.fromTurnSnapshot(snapshot).output(), "an output without text reads as empty");
    }

    @Test
    void onlyToolCallEntriesWithAToolNameAreKept() {
        var calls = TurnObservation.toolCallsOf(toolTrace(call("tool_call", "search", null), call("tool_result", "search", "x"),
                call("tool_call", null, "{}"), "not-a-map", call("tool_call", "calc", 42)));
        assertEquals(List.of(new ObservedToolCall("search", null), new ObservedToolCall("calc", "42")), calls);

        assertTrue(TurnObservation.toolCallsOf("not a map").isEmpty());
        assertTrue(TurnObservation.toolCallsOf(Map.of("calls", "not a list")).isEmpty());
    }

    @Test
    void aPropertyReadsAsItsFirstSetValue() {
        assertNull(TurnObservation.valueOf(null));
        assertEquals("s", TurnObservation.valueOf(new Property("p", "s", Scope.step)));
        assertEquals(Map.of("k", "v"), TurnObservation.valueOf(new Property("p", Map.<String, Object>of("k", "v"), Scope.step)));
        assertEquals(List.of(1), TurnObservation.valueOf(new Property("p", List.<Object>of(1), Scope.step)));
        assertEquals(7, TurnObservation.valueOf(new Property("p", 7, Scope.step)));
        assertEquals(8L, TurnObservation.valueOf(new Property("p", 8L, Scope.step)));
        assertEquals(1.5f, TurnObservation.valueOf(new Property("p", 1.5f, Scope.step)));
        assertEquals(2.5d, TurnObservation.valueOf(new Property("p", 2.5d, Scope.step)));
        assertEquals(true, TurnObservation.valueOf(new Property("p", true, Scope.step)));
        assertNull(TurnObservation.valueOf(new Property()));
    }

    @Test
    void withersReplaceOnlyTheirOwnFields() {
        var base = new TurnObservation(List.of("a"), Map.of(), "out", List.of(), 0.0, "READY", Set.of());
        var costed = base.withCost(1.25, Set.of("m"));
        assertEquals(1.25, costed.costUsd());
        assertEquals(Set.of("m"), costed.modelNames());
        assertEquals(List.of("a"), costed.actions());
        var called = costed.withToolCalls(List.of(new ObservedToolCall("t", null)));
        assertEquals(1, called.toolCalls().size());
        assertEquals(1.25, called.costUsd());
        assertEquals(Set.of("m"), called.modelNames());
    }
}
