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

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended coverage tests for the engine.memory package. Targets:
 * <ul>
 * <li>{@link ConversationMemory.ConversationStepStack} — 58% → branches for
 * getLatestData, getAllData, getAllLatestData, peek</li>
 * <li>{@link ConversationMemoryUtilities} — extended branches for redo cache,
 * null userId, currentStepOnly+returningFields</li>
 * <li>{@link ConversationMemory} — redo/undo edge cases,
 * eventSink/auditCollector/memoryPolicy accessors</li>
 * <li>{@link ConversationStep} — equals with non-ConversationStep,
 * hashCode</li>
 * </ul>
 */
@DisplayName("Engine Memory — Extended Coverage")
class EngineMemoryExtendedCoverageTest {

    // ==================== ConversationStepStack ====================

    @Nested
    @DisplayName("ConversationStepStack")
    class ConversationStepStackTests {

        @Test
        @DisplayName("getLatestData returns null when no steps have the key")
        void getLatestDataNoMatch() {
            var memory = new ConversationMemory("agent-1", 1);
            memory.getCurrentStep().storeData(new Data<>("output", "hello"));

            var allSteps = memory.getAllSteps();
            IData<String> result = allSteps.getLatestData("nonexistent");
            assertNull(result);
        }

        @Test
        @DisplayName("getLatestData with MemoryKey returns data from most recent step")
        void getLatestDataWithMemoryKey() {
            var memory = new ConversationMemory("agent-1", 1);
            var key = MemoryKey.<String>of("input");

            memory.getCurrentStep().set(key, "first");
            memory.startNextStep();
            memory.getCurrentStep().set(key, "second");

            var allSteps = memory.getAllSteps();
            IData<String> latest = allSteps.getLatestData(key);
            assertNotNull(latest);
            assertEquals("second", latest.getResult());
        }

        @Test
        @DisplayName("getAllData returns data grouped by step for matching prefix")
        void getAllData() {
            var memory = new ConversationMemory("agent-1", 1);
            memory.getCurrentStep().storeData(new Data<>("output:text", "hello"));
            memory.startNextStep();
            memory.getCurrentStep().storeData(new Data<>("output:text", "world"));

            var allSteps = memory.getAllSteps();
            List<List<IData<String>>> allData = allSteps.getAllData("output");
            assertEquals(2, allData.size());
        }

        @Test
        @DisplayName("getAllData returns empty when no steps have matching prefix")
        void getAllDataNoMatch() {
            var memory = new ConversationMemory("agent-1", 1);
            memory.getCurrentStep().storeData(new Data<>("input", "hello"));

            var allSteps = memory.getAllSteps();
            List<List<IData<String>>> allData = allSteps.getAllData("nonexistent");
            assertTrue(allData.isEmpty());
        }

        @Test
        @DisplayName("getAllLatestData returns one entry per step")
        void getAllLatestData() {
            var memory = new ConversationMemory("agent-1", 1);
            memory.getCurrentStep().storeData(new Data<>("output:text", "hello"));
            memory.startNextStep();
            memory.getCurrentStep().storeData(new Data<>("output:text", "world"));

            var allSteps = memory.getAllSteps();
            List<IData<String>> allLatest = allSteps.getAllLatestData("output");
            assertEquals(2, allLatest.size());
        }

        @Test
        @DisplayName("peek returns the most recently added step")
        void peek() {
            var memory = new ConversationMemory("agent-1", 1);
            memory.getCurrentStep().storeData(new Data<>("input", "first"));
            memory.startNextStep();
            memory.getCurrentStep().storeData(new Data<>("input", "second"));

            var allSteps = memory.getAllSteps();
            var peeked = allSteps.peek();
            assertNotNull(peeked);
            IData<String> data = peeked.getData("input");
            assertEquals("second", data.getResult());
        }

        @Test
        @DisplayName("size returns correct count")
        void size() {
            var memory = new ConversationMemory("agent-1", 1);
            assertEquals(1, memory.getAllSteps().size());

            memory.startNextStep();
            assertEquals(2, memory.getAllSteps().size());

            memory.startNextStep();
            assertEquals(3, memory.getAllSteps().size());
        }

        @Test
        @DisplayName("get(0) returns the most recent step")
        void getByIndex() {
            var memory = new ConversationMemory("agent-1", 1);
            memory.getCurrentStep().storeData(new Data<>("input", "first"));
            memory.startNextStep();
            memory.getCurrentStep().storeData(new Data<>("input", "second"));

            var allSteps = memory.getAllSteps();
            IData<String> latest = allSteps.get(0).getData("input");
            assertEquals("second", latest.getResult());

            IData<String> previous = allSteps.get(1).getData("input");
            assertEquals("first", previous.getResult());
        }
    }

    // ==================== ConversationMemory Extended ====================

    @Nested
    @DisplayName("ConversationMemory — Extended")
    class ConversationMemoryExtendedTests {

        @Test
        @DisplayName("eventSink is null by default, can be set and retrieved")
        void eventSink() {
            var memory = new ConversationMemory("agent-1", 1);
            assertNull(memory.getEventSink());
            // Setting null is valid
            memory.setEventSink(null);
            assertNull(memory.getEventSink());
        }

        @Test
        @DisplayName("auditCollector is null by default, can be set and retrieved")
        void auditCollector() {
            var memory = new ConversationMemory("agent-1", 1);
            assertNull(memory.getAuditCollector());
            memory.setAuditCollector(null);
            assertNull(memory.getAuditCollector());
        }

        @Test
        @DisplayName("memoryPolicy is null by default, can be set and retrieved")
        void memoryPolicy() {
            var memory = new ConversationMemory("agent-1", 1);
            assertNull(memory.getMemoryPolicy());
            memory.setMemoryPolicy(null);
            assertNull(memory.getMemoryPolicy());
        }

        @Test
        @DisplayName("userMemoryConfig is null by default, can be set and retrieved")
        void userMemoryConfig() {
            var memory = new ConversationMemory("agent-1", 1);
            assertNull(memory.getUserMemoryConfig());
            memory.setUserMemoryConfig(null);
            assertNull(memory.getUserMemoryConfig());
        }

        @Test
        @DisplayName("3-arg constructor sets userId")
        void threeArgConstructor() {
            var memory = new ConversationMemory("agent-1", 2, "user-42");
            assertEquals("user-42", memory.getUserId());
            assertEquals("agent-1", memory.getAgentId());
            assertEquals(2, memory.getAgentVersion());
        }

        @Test
        @DisplayName("undo+redo restores conversation output")
        void undoRedoConversationOutput() {
            var memory = new ConversationMemory("agent-1", 1);
            var step1Output = memory.getCurrentStep().getConversationOutput();

            memory.startNextStep();
            assertEquals(2, memory.getConversationOutputs().size());

            memory.undoLastStep();
            assertEquals(1, memory.getConversationOutputs().size());

            memory.redoLastStep();
            assertEquals(2, memory.getConversationOutputs().size());
            assertSame(step1Output, memory.getConversationOutputs().get(0));
        }
    }

    // ==================== ConversationStep Extended ====================

    @Nested
    @DisplayName("ConversationStep — Extended Branches")
    class ConversationStepExtendedTests {

        @Test
        @DisplayName("equals returns false for non-ConversationStep object")
        void equalsNotConversationStep() {
            var step = new ConversationStep(new ConversationOutput());
            assertNotEquals(step, "not a step");
        }

        @Test
        @DisplayName("equals returns false for null")
        void equalsNull() {
            var step = new ConversationStep(new ConversationOutput());
            assertNotEquals(null, step);
        }

        @Test
        @DisplayName("hashCode is consistent for equal steps")
        void hashCodeConsistent() {
            var step1 = new ConversationStep(new ConversationOutput());
            step1.storeData(new Data<>("key", "value"));

            var step2 = new ConversationStep(new ConversationOutput());
            step2.storeData(new Data<>("key", "value"));

            assertEquals(step1.hashCode(), step2.hashCode());
        }

        @Test
        @DisplayName("resetConversationOutput clears a key in output")
        void resetConversationOutput() {
            var step = new ConversationStep(new ConversationOutput());
            step.addConversationOutputList("items", List.of("a", "b"));
            step.resetConversationOutput("items");

            @SuppressWarnings("unchecked")
            var items = (List<Object>) step.getConversationOutput().get("items");
            assertTrue(items.isEmpty());
        }

        @Test
        @DisplayName("setCurrentWorkflowId sets origin on stored data")
        void setCurrentWorkflowId() {
            var step = new ConversationStep(new ConversationOutput());
            step.setCurrentWorkflowId("workflow-abc");
            step.storeData(new Data<>("key", "value"));

            IData<String> data = step.getData("key");
            assertEquals("workflow-abc", data.getOriginWorkflowId());
        }

        @Test
        @DisplayName("getLatestData with MemoryKey delegates to prefix-based lookup")
        void getLatestDataMemoryKey() {
            var step = new ConversationStep(new ConversationOutput());
            var key = MemoryKey.<String>of("output");
            step.storeData(new Data<>("output:text", "hello"));

            IData<String> latest = step.getLatestData(key);
            assertNotNull(latest);
            assertEquals("output:text", latest.getKey());
        }

        @Test
        @DisplayName("getData with MemoryKey delegates to key-based lookup")
        void getDataMemoryKey() {
            var step = new ConversationStep(new ConversationOutput());
            var key = MemoryKey.<String>of("exact-key");
            step.storeData(new Data<>("exact-key", "value"));

            IData<String> data = step.getData(key);
            assertNotNull(data);
            assertEquals("value", data.getResult());
        }
    }

    // ==================== ConversationMemoryUtilities Extended
    // ====================

    @Nested
    @DisplayName("ConversationMemoryUtilities — Extended Branches")
    class ConversationMemoryUtilitiesExtendedTests {

        @Test
        @DisplayName("convertConversationMemory with null userId does not set userId in snapshot")
        void convertMemoryNullUserId() {
            var memory = new ConversationMemory("agent-1", 1);
            // userId is null by default in 2-arg constructor

            var snapshot = ConversationMemoryUtilities.convertConversationMemory(memory);
            assertNull(snapshot.getUserId());
        }

        @Test
        @DisplayName("convertConversationMemory with null conversationId does not set it")
        void convertMemoryNullConversationId() {
            var memory = new ConversationMemory("agent-1", 1);
            // conversationId is null by default in 2-arg constructor

            var snapshot = ConversationMemoryUtilities.convertConversationMemory(memory);
            assertNull(snapshot.getConversationId());
        }

        @Test
        @DisplayName("convertConversationMemory preserves redo cache")
        void convertMemoryRedoCache() {
            var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
            memory.getCurrentStep().storeData(new Data<>("input", "hello"));
            memory.startNextStep();
            memory.getCurrentStep().storeData(new Data<>("input", "world"));
            memory.undoLastStep(); // Creates a redo entry

            var snapshot = ConversationMemoryUtilities.convertConversationMemory(memory);
            assertFalse(snapshot.getRedoCache().isEmpty());
        }

        @Test
        @DisplayName("convertConversationMemorySnapshot restores redo cache")
        void convertSnapshotRedoCache() {
            // Build snapshot with redo cache
            var snapshot = new ConversationMemorySnapshot();
            snapshot.setConversationId("conv-1");
            snapshot.setAgentId("agent-1");
            snapshot.setAgentVersion(1);
            snapshot.setUserId("user-1");
            snapshot.setConversationState(ConversationState.IN_PROGRESS);

            // Add step
            var output = new ConversationOutput();
            output.put("input:initial", "hello");
            snapshot.getConversationOutputs().add(output);

            var step = new ConversationStepSnapshot();
            var workflow = new WorkflowRunSnapshot();
            workflow.getLifecycleTasks().add(new ResultSnapshot("input", "hello", null, new Date(), null, true));
            step.getWorkflows().add(workflow);
            snapshot.getConversationSteps().add(step);

            // Add redo cache entry
            var redoStep = new ConversationStepSnapshot();
            var redoWorkflow = new WorkflowRunSnapshot();
            redoWorkflow.getLifecycleTasks().add(new ResultSnapshot("input", "world", null, new Date(), null, true));
            redoStep.getWorkflows().add(redoWorkflow);
            snapshot.getRedoCache().push(redoStep);

            var restored = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);
            assertFalse(restored.getRedoCache().isEmpty());
        }

        @Test
        @DisplayName("convertConversationMemory with multiple steps")
        void convertMemoryMultipleSteps() {
            var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
            memory.getCurrentStep().storeData(new Data<>("input", "step1"));
            memory.startNextStep();
            memory.getCurrentStep().storeData(new Data<>("input", "step2"));
            memory.startNextStep();
            memory.getCurrentStep().storeData(new Data<>("input", "step3"));

            var snapshot = ConversationMemoryUtilities.convertConversationMemory(memory);
            assertEquals(3, snapshot.getConversationSteps().size());
            assertEquals(3, snapshot.getConversationOutputs().size());
        }

        @Test
        @DisplayName("convertSimpleConversationMemorySnapshot — returnCurrentStepOnly with null excludes outputs")
        void currentStepOnlyExcludesOutputs() {
            var snapshot = buildMultiStepSnapshot(3);

            var simple = ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(
                    snapshot, true, true, List.of("conversationSteps"));

            // conversationOutputs should be null since not in returningFields
            assertNull(simple.getConversationOutputs());
        }

        @Test
        @DisplayName("convertSimpleConversationMemorySnapshot — returnCurrentStepOnly with all fields")
        void currentStepOnlyAllFields() {
            var snapshot = buildMultiStepSnapshot(3);

            var simple = ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(
                    snapshot, true, true,
                    List.of("conversationSteps", "conversationOutputs", "conversationProperties"));

            assertNotNull(simple.getConversationSteps());
            assertNotNull(simple.getConversationOutputs());
            assertNotNull(simple.getConversationProperties());
        }

        @Test
        @DisplayName("convertSimpleConversationMemorySnapshot — returnCurrentStepOnly=false returns all steps")
        void nonCurrentStepOnly() {
            var snapshot = buildMultiStepSnapshot(3);

            var simple = ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(
                    snapshot, true, false, null);

            assertEquals(3, simple.getConversationSteps().size());
        }

        @Test
        @DisplayName("prepareContext with multiple context types extracts all values")
        void prepareContextMultipleTypes() {
            var stringCtx = new Context(Context.ContextType.string, "hello");
            IData<Context> stringData = new Data<>("context:greeting", stringCtx);

            var objectCtx = new Context(Context.ContextType.object, Map.of("k", "v"));
            IData<Context> objectData = new Data<>("context:payload", objectCtx);

            var result = ConversationMemoryUtilities.prepareContext(List.of(stringData, objectData));
            assertEquals("hello", result.get("greeting"));
            assertEquals(Map.of("k", "v"), result.get("payload"));
        }
    }

    // ==================== Helpers ====================

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
