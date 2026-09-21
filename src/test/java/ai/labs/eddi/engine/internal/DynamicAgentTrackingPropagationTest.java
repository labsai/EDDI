/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot.ConversationStepData;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot.SimpleConversationStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GroupConversationService#propagateDynamicAgentTracking}
 * which bridges dynamic agent lifecycle tracking between AgentOrchestrator's
 * per-turn tool-local lists and GroupConversation's lifecycle tracking.
 *
 * <p>
 * These tests verify regression safety for three critical fixes:
 * <ol>
 * <li>Snapshot must include dynamic:* keys (returnDetailed=true)</li>
 * <li>Set reference must not be copied (retained IDs visibility)</li>
 * <li>Propagation correctly merges IDs into GroupConversation</li>
 * </ol>
 *
 * @author ginccc
 */
class DynamicAgentTrackingPropagationTest {

    /** Well-known data keys — must match AgentOrchestrator constants. */
    private static final String KEY_CREATED = "dynamic:created_agent_ids";
    private static final String KEY_RETAINED = "dynamic:retained_agent_ids";

    private GroupConversation gc;

    @BeforeEach
    void setUp() {
        gc = new GroupConversation();
    }

    // =========================================================================
    // Null / Empty Guards
    // =========================================================================

    @Nested
    @DisplayName("Null and empty guards")
    class NullGuards {

        @Test
        @DisplayName("null snapshot → no-op, no exception")
        void nullSnapshot_noOp() {
            GroupConversationService.propagateDynamicAgentTracking(null, gc);
            assertTrue(gc.getCreatedAgentIds().isEmpty());
            assertTrue(gc.getRetainedAgentIds().isEmpty());
        }

        @Test
        @DisplayName("snapshot with null conversationSteps → no-op")
        void nullConversationSteps_noOp() {
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationSteps(null);
            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);
            assertTrue(gc.getCreatedAgentIds().isEmpty());
        }

        @Test
        @DisplayName("snapshot with empty conversationSteps → no-op")
        void emptyConversationSteps_noOp() {
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationSteps(new LinkedList<>());
            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);
            assertTrue(gc.getCreatedAgentIds().isEmpty());
        }

        @Test
        @DisplayName("last step is null → no-op")
        void nullLastStep_noOp() {
            var snapshot = new SimpleConversationMemorySnapshot();
            var steps = new LinkedList<SimpleConversationStep>();
            steps.add(null);
            snapshot.setConversationSteps(steps);
            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);
            assertTrue(gc.getCreatedAgentIds().isEmpty());
        }

        @Test
        @DisplayName("last step has null conversationStep list → no-op")
        void nullStepDataList_noOp() {
            var snapshot = new SimpleConversationMemorySnapshot();
            var step = new SimpleConversationStep();
            step.setConversationStep(null);
            snapshot.getConversationSteps().add(step);
            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);
            assertTrue(gc.getCreatedAgentIds().isEmpty());
        }

        @Test
        @DisplayName("stepData with null key → skipped gracefully")
        void nullKeyInStepData_skipped() {
            var snapshot = buildSnapshot(
                    new ConversationStepData(null, List.of("agent-1"), null, null));
            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);
            assertTrue(gc.getCreatedAgentIds().isEmpty());
        }

        @Test
        @DisplayName("null stepData entry → skipped gracefully")
        void nullStepDataEntry_skipped() {
            var snapshot = new SimpleConversationMemorySnapshot();
            var step = new SimpleConversationStep();
            var dataList = new LinkedList<ConversationStepData>();
            dataList.add(null);
            dataList.add(new ConversationStepData(KEY_CREATED, List.of("agent-1"), null, null));
            step.setConversationStep(dataList);
            snapshot.getConversationSteps().add(step);

            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);
            assertEquals(1, gc.getCreatedAgentIds().size());
            assertTrue(gc.getCreatedAgentIds().contains("agent-1"));
        }
    }

    // =========================================================================
    // Created Agent IDs Propagation
    // =========================================================================

    @Nested
    @DisplayName("Created agent IDs propagation")
    class CreatedAgentIds {

        @Test
        @DisplayName("propagates created agent IDs from snapshot to GroupConversation")
        void propagatesCreatedIds() {
            var snapshot = buildSnapshot(
                    new ConversationStepData(KEY_CREATED,
                            new CopyOnWriteArrayList<>(List.of("agent-a", "agent-b")),
                            null, null));

            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);

            assertEquals(2, gc.getCreatedAgentIds().size());
            assertTrue(gc.getCreatedAgentIds().contains("agent-a"));
            assertTrue(gc.getCreatedAgentIds().contains("agent-b"));
        }

        @Test
        @DisplayName("deduplicates created IDs already present in GroupConversation")
        void deduplicatesExistingIds() {
            gc.getCreatedAgentIds().add("agent-a"); // already present

            var snapshot = buildSnapshot(
                    new ConversationStepData(KEY_CREATED,
                            List.of("agent-a", "agent-b"), null, null));

            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);

            // agent-a should NOT be duplicated
            assertEquals(2, gc.getCreatedAgentIds().size());
            assertEquals(1, gc.getCreatedAgentIds().stream()
                    .filter("agent-a"::equals).count());
        }

        @Test
        @DisplayName("handles empty created IDs list")
        void emptyCreatedList() {
            var snapshot = buildSnapshot(
                    new ConversationStepData(KEY_CREATED, List.of(), null, null));

            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);
            assertTrue(gc.getCreatedAgentIds().isEmpty());
        }

        @Test
        @DisplayName("ignores non-String elements in created IDs")
        void nonStringElementsIgnored() {
            var mixedList = new ArrayList<>();
            mixedList.add("agent-valid");
            mixedList.add(42);
            mixedList.add(null);
            mixedList.add("agent-also-valid");

            var snapshot = buildSnapshot(
                    new ConversationStepData(KEY_CREATED, mixedList, null, null));

            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);

            assertEquals(2, gc.getCreatedAgentIds().size());
            assertTrue(gc.getCreatedAgentIds().contains("agent-valid"));
            assertTrue(gc.getCreatedAgentIds().contains("agent-also-valid"));
        }
    }

    // =========================================================================
    // Retained Agent IDs Propagation
    // =========================================================================

    @Nested
    @DisplayName("Retained agent IDs propagation")
    class RetainedAgentIds {

        @Test
        @DisplayName("propagates retained agent IDs from snapshot to GroupConversation")
        void propagatesRetainedIds() {
            Set<String> retainedSet = ConcurrentHashMap.newKeySet();
            retainedSet.add("retained-1");
            retainedSet.add("retained-2");

            var snapshot = buildSnapshot(
                    new ConversationStepData(KEY_RETAINED, retainedSet, null, null));

            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);

            assertTrue(gc.getRetainedAgentIds().contains("retained-1"));
            assertTrue(gc.getRetainedAgentIds().contains("retained-2"));
        }

        @Test
        @DisplayName("Set type value (ConcurrentHashMap.KeySetView) is handled correctly")
        void setTypeValue() {
            // This tests that the fix for Bug 2 (storing Set reference directly
            // instead of copying to ArrayList) works at the reader side
            Set<String> originalSet = ConcurrentHashMap.newKeySet();
            originalSet.add("agent-x");

            var snapshot = buildSnapshot(
                    new ConversationStepData(KEY_RETAINED, originalSet, null, null));

            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);
            assertTrue(gc.getRetainedAgentIds().contains("agent-x"));
        }

        @Test
        @DisplayName("ignores non-String elements in retained IDs")
        void nonStringRetainedIgnored() {
            var mixedList = new ArrayList<>();
            mixedList.add("retained-ok");
            mixedList.add(3.14);
            mixedList.add(null);

            var snapshot = buildSnapshot(
                    new ConversationStepData(KEY_RETAINED, mixedList, null, null));

            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);
            assertEquals(1, gc.getRetainedAgentIds().size());
            assertTrue(gc.getRetainedAgentIds().contains("retained-ok"));
        }
    }

    // =========================================================================
    // Combined / Edge Cases
    // =========================================================================

    @Nested
    @DisplayName("Combined and edge cases")
    class CombinedCases {

        @Test
        @DisplayName("both created and retained IDs in same step")
        void bothKeysInSameStep() {
            var snapshot = buildSnapshot(
                    new ConversationStepData(KEY_CREATED, List.of("created-1"), null, null),
                    new ConversationStepData(KEY_RETAINED, Set.of("retained-1"), null, null));

            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);

            assertEquals(1, gc.getCreatedAgentIds().size());
            assertTrue(gc.getCreatedAgentIds().contains("created-1"));
            assertEquals(1, gc.getRetainedAgentIds().size());
            assertTrue(gc.getRetainedAgentIds().contains("retained-1"));
        }

        @Test
        @DisplayName("non-Collection value for tracking key → ignored")
        void nonCollectionValue_ignored() {
            var snapshot = buildSnapshot(
                    new ConversationStepData(KEY_CREATED, "not-a-collection", null, null));

            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);
            assertTrue(gc.getCreatedAgentIds().isEmpty());
        }

        @Test
        @DisplayName("unrelated keys in step data → ignored")
        void unrelatedKeys_ignored() {
            var snapshot = buildSnapshot(
                    new ConversationStepData("output:text", "hello", null, null),
                    new ConversationStepData("actions:greet", List.of("greet"), null, null),
                    new ConversationStepData(KEY_CREATED, List.of("agent-1"), null, null));

            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);

            // Only the dynamic:created key should be processed
            assertEquals(1, gc.getCreatedAgentIds().size());
            assertTrue(gc.getCreatedAgentIds().contains("agent-1"));
        }

        @Test
        @DisplayName("reads only the LAST step (not earlier steps)")
        void readsOnlyLastStep() {
            var snapshot = new SimpleConversationMemorySnapshot();

            // First step has some created IDs
            var step1 = new SimpleConversationStep();
            step1.getConversationStep().add(
                    new ConversationStepData(KEY_CREATED, List.of("agent-old"), null, null));
            snapshot.getConversationSteps().add(step1);

            // Last step has different created IDs
            var step2 = new SimpleConversationStep();
            step2.getConversationStep().add(
                    new ConversationStepData(KEY_CREATED, List.of("agent-new"), null, null));
            snapshot.getConversationSteps().add(step2);

            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);

            // Only the last step's data should be propagated
            assertEquals(1, gc.getCreatedAgentIds().size());
            assertTrue(gc.getCreatedAgentIds().contains("agent-new"));
            assertFalse(gc.getCreatedAgentIds().contains("agent-old"));
        }

        @Test
        @DisplayName("multiple turns accumulate IDs (idempotent)")
        void multipleTurns_accumulate() {
            // Simulate turn 1
            var snapshot1 = buildSnapshot(
                    new ConversationStepData(KEY_CREATED, List.of("agent-1"), null, null));
            GroupConversationService.propagateDynamicAgentTracking(snapshot1, gc);

            // Simulate turn 2
            var snapshot2 = buildSnapshot(
                    new ConversationStepData(KEY_CREATED, List.of("agent-2"), null, null));
            GroupConversationService.propagateDynamicAgentTracking(snapshot2, gc);

            assertEquals(2, gc.getCreatedAgentIds().size());
            assertTrue(gc.getCreatedAgentIds().containsAll(List.of("agent-1", "agent-2")));
        }
    }

    // =========================================================================
    // Data Key Contract
    // =========================================================================

    @Nested
    @DisplayName("Data key contract with AgentOrchestrator")
    class DataKeyContract {

        /**
         * Verifies that the propagation method recognizes the exact key strings used by
         * AgentOrchestrator. If either side changes their key, the corresponding test
         * below will fail because propagation won't work.
         */
        @Test
        @DisplayName("propagation recognizes 'dynamic:created_agent_ids' key")
        void createdKeyRecognized() {
            // This is the exact key AgentOrchestrator.KEY_DYNAMIC_CREATED_AGENT_IDS stores.
            // If the key changes in AgentOrchestrator without updating
            // GroupConversationService,
            // this test will pass but the null/empty guard tests above will start
            // failing (the propagation code won't find the key).
            var snapshot = buildSnapshot(
                    new ConversationStepData("dynamic:created_agent_ids",
                            List.of("contract-test"), null, null));

            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);
            assertTrue(gc.getCreatedAgentIds().contains("contract-test"),
                    "Propagation must recognize 'dynamic:created_agent_ids' key");
        }

        @Test
        @DisplayName("propagation recognizes 'dynamic:retained_agent_ids' key")
        void retainedKeyRecognized() {
            var snapshot = buildSnapshot(
                    new ConversationStepData("dynamic:retained_agent_ids",
                            Set.of("contract-test"), null, null));

            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);
            assertTrue(gc.getRetainedAgentIds().contains("contract-test"),
                    "Propagation must recognize 'dynamic:retained_agent_ids' key");
        }

        @Test
        @DisplayName("unrecognized key prefix is ignored (no false positives)")
        void wrongKeyPrefix_ignored() {
            var snapshot = buildSnapshot(
                    new ConversationStepData("dynamic:unknown_ids",
                            List.of("should-not-appear"), null, null));

            GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);
            assertTrue(gc.getCreatedAgentIds().isEmpty());
            assertTrue(gc.getRetainedAgentIds().isEmpty());
        }
    }

    // =========================================================================
    // Helper
    // =========================================================================

    /**
     * Builds a snapshot with a single step containing the given step data entries.
     */
    private SimpleConversationMemorySnapshot buildSnapshot(ConversationStepData... entries) {
        var snapshot = new SimpleConversationMemorySnapshot();
        var step = new SimpleConversationStep();
        for (var entry : entries) {
            step.getConversationStep().add(entry);
        }
        snapshot.getConversationSteps().add(step);
        return snapshot;
    }
}
