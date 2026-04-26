/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConversationMemoryUtilities} static conversion methods.
 * These operate on in-memory snapshot objects — zero mocking needed.
 */
class ConversationMemoryUtilitiesTest {

    // ─── prepareContext ─────────────────────────────────────────

    @Nested
    @DisplayName("prepareContext")
    class PrepareContext {

        @Test
        @DisplayName("should extract string context value")
        void stringContext() {
            var context = new Context(Context.ContextType.string, "hello");
            IData<Context> data = new Data<>("context:greeting", context);

            var result = ConversationMemoryUtilities.prepareContext(List.of(data));
            assertEquals("hello", result.get("greeting"));
        }

        @Test
        @DisplayName("should extract object context value")
        void objectContext() {
            var payload = Map.of("key", "value");
            var context = new Context(Context.ContextType.object, payload);
            IData<Context> data = new Data<>("context:payload", context);

            var result = ConversationMemoryUtilities.prepareContext(List.of(data));
            assertEquals(payload, result.get("payload"));
        }

        @Test
        @DisplayName("should extract array context value")
        void arrayContext() {
            var items = List.of("a", "b", "c");
            var context = new Context(Context.ContextType.array, items);
            IData<Context> data = new Data<>("context:items", context);

            var result = ConversationMemoryUtilities.prepareContext(List.of(data));
            assertEquals(items, result.get("items"));
        }

        @Test
        @DisplayName("should extract expressions context value")
        void expressionsContext() {
            var context = new Context(Context.ContextType.expressions, "intent(greeting)");
            IData<Context> data = new Data<>("context:expr", context);

            var result = ConversationMemoryUtilities.prepareContext(List.of(data));
            assertEquals("intent(greeting)", result.get("expr"));
        }

        @Test
        @DisplayName("should handle null context by putting null value")
        void nullContext() {
            IData<Context> data = new Data<>("context:missing", null);

            var result = ConversationMemoryUtilities.prepareContext(List.of(data));
            assertTrue(result.containsKey("missing"));
            assertNull(result.get("missing"));
        }

        @Test
        @DisplayName("should handle empty list")
        void emptyList() {
            var result = ConversationMemoryUtilities.prepareContext(List.of());
            assertTrue(result.isEmpty());
        }
    }

    // ─── convertConversationMemory → convertConversationMemorySnapshot roundtrip
    // ───

    @Nested
    @DisplayName("convertConversationMemory roundtrip")
    class RoundTrip {

        @Test
        @DisplayName("should preserve metadata through convert→convertBack")
        void preservesMetadata() {
            // Build a ConversationMemory with data
            var memory = new ConversationMemory("conv-id", "agent-1", 5, "user-42");
            memory.setConversationState(ConversationState.ENDED);
            memory.getCurrentStep().storeData(new Data<>("input", "Hello"));
            memory.getCurrentStep().storeData(new Data<>("output", "World"));

            // Convert to snapshot
            var snapshot = ConversationMemoryUtilities.convertConversationMemory(memory);

            assertEquals("conv-id", snapshot.getConversationId());
            assertEquals("agent-1", snapshot.getAgentId());
            assertEquals(5, snapshot.getAgentVersion());
            assertEquals("user-42", snapshot.getUserId());
            assertEquals(ConversationState.ENDED, snapshot.getConversationState());
            assertFalse(snapshot.getConversationSteps().isEmpty());

            // Convert back
            var restored = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);
            assertEquals("conv-id", restored.getConversationId());
            assertEquals("agent-1", restored.getAgentId());
            assertEquals(5, restored.getAgentVersion());
            assertEquals("user-42", restored.getUserId());
        }
    }

    // ─── convertSimpleConversationMemory ─────────────────────────

    @Nested
    @DisplayName("convertSimpleConversationMemory")
    class ConvertSimple {

        @Test
        @DisplayName("returnDetailed=true should include all output keys")
        void detailedIncludesAll() {
            var snapshot = buildSnapshotWithOutputs("input:initial", "actions", "output", "internal:debug");

            var simple = ConversationMemoryUtilities.convertSimpleConversationMemory(snapshot, true, false);

            // Detailed mode returns all outputs
            assertFalse(simple.getConversationOutputs().isEmpty());
            var output = simple.getConversationOutputs().getFirst();
            assertTrue(output.containsKey("input:initial"));
            assertTrue(output.containsKey("actions"));
            assertTrue(output.containsKey("output"));
            assertTrue(output.containsKey("internal:debug"));
        }

        @Test
        @DisplayName("returnDetailed=false should filter to input/actions/output")
        void nonDetailedFiltersKeys() {
            var snapshot = buildSnapshotWithOutputs("input:initial", "actions", "output", "internal:debug");

            var simple = ConversationMemoryUtilities.convertSimpleConversationMemory(snapshot, false, false);

            var output = simple.getConversationOutputs().getFirst();
            assertTrue(output.containsKey("input:initial"));
            assertTrue(output.containsKey("actions"));
            assertTrue(output.containsKey("output"));
            assertFalse(output.containsKey("internal:debug"));
        }

        @Test
        @DisplayName("should set undoAvailable=true when >1 steps")
        void undoAvailable() {
            var snapshot = buildMultiStepSnapshot(3);
            var simple = ConversationMemoryUtilities.convertSimpleConversationMemory(snapshot, true, false);
            assertTrue(simple.isUndoAvailable());
        }

        @Test
        @DisplayName("should set undoAvailable=false for single step")
        void undoNotAvailable() {
            var snapshot = buildMultiStepSnapshot(1);
            var simple = ConversationMemoryUtilities.convertSimpleConversationMemory(snapshot, true, false);
            assertFalse(simple.isUndoAvailable());
        }
    }

    // ─── convertSimpleConversationMemorySnapshot with returningFields ───

    @Nested
    @DisplayName("convertSimpleConversationMemorySnapshot with returningFields")
    class ReturningFields {

        @Test
        @DisplayName("should null out steps when not in returningFields")
        void excludeSteps() {
            var snapshot = buildMultiStepSnapshot(2);
            var simple = ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(
                    snapshot, true, true, List.of("conversationOutputs"));
            assertNull(simple.getConversationSteps());
        }

        @Test
        @DisplayName("should null out properties when not in returningFields")
        void excludeProperties() {
            var snapshot = buildMultiStepSnapshot(2);
            var simple = ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(
                    snapshot, true, true, List.of("conversationSteps"));
            assertNull(simple.getConversationProperties());
        }

        @Test
        @DisplayName("should include everything when returningFields is null")
        void nullReturningFields() {
            var snapshot = buildMultiStepSnapshot(2);
            var simple = ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(
                    snapshot, true, true, null);
            assertNotNull(simple.getConversationSteps());
            assertNotNull(simple.getConversationOutputs());
            assertNotNull(simple.getConversationProperties());
        }
    }

    // ─── Test Helpers ────────────────────────────────────────────

    private ConversationMemorySnapshot buildSnapshotWithOutputs(String... keys) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId("conv-1");
        snapshot.setAgentId("agent-1");
        snapshot.setAgentVersion(1);

        var output = new ConversationOutput();
        for (String key : keys) {
            output.put(key, "value-of-" + key);
        }
        snapshot.getConversationOutputs().add(output);

        // Need at least one step with matching data
        var step = new ConversationStepSnapshot();
        var workflow = new WorkflowRunSnapshot();
        for (String key : keys) {
            workflow.getLifecycleTasks().add(
                    new ResultSnapshot(key, "value-of-" + key, null, new Date(), null, true));
        }
        step.getWorkflows().add(workflow);
        snapshot.getConversationSteps().add(step);

        return snapshot;
    }

    private ConversationMemorySnapshot buildMultiStepSnapshot(int stepCount) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId("conv-multi");
        snapshot.setAgentId("agent-1");
        snapshot.setAgentVersion(1);
        snapshot.setConversationState(ConversationState.ENDED);

        for (int i = 0; i < stepCount; i++) {
            var output = new ConversationOutput();
            output.put("input:initial", "input-" + i);
            output.put("output", "output-" + i);
            snapshot.getConversationOutputs().add(output);

            var step = new ConversationStepSnapshot();
            var workflow = new WorkflowRunSnapshot();
            workflow.getLifecycleTasks().add(
                    new ResultSnapshot("input:initial", "input-" + i, null, new Date(), null, true));
            workflow.getLifecycleTasks().add(
                    new ResultSnapshot("output", "output-" + i, null, new Date(), null, true));
            step.getWorkflows().add(workflow);
            snapshot.getConversationSteps().add(step);
        }

        return snapshot;
    }
}
