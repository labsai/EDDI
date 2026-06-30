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
import ai.labs.eddi.engine.memory.model.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HITL bookmark field round-trip through
 * {@link ConversationMemoryUtilities} static conversion methods.
 */
class ConversationMemoryUtilitiesHitlTest {

    // =========================================================================
    // memory → snapshot
    // =========================================================================

    @Nested
    @DisplayName("convertConversationMemory (memory → snapshot)")
    class MemoryToSnapshot {

        @Test
        @DisplayName("HITL fields are copied from memory to snapshot")
        void hitlFieldsCopied() {
            var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
            var pausedAt = Instant.parse("2026-06-30T12:00:00Z");

            memory.setHitlPausedWorkflowId("wf-alpha");
            memory.setHitlPausedAbsoluteTaskIndex(3);
            memory.setHitlPausedAt(pausedAt);
            memory.setHitlPauseReason("Needs review");
            memory.setHitlTimeoutPolicy("FAIL_TASK");
            memory.setHitlApprovalTimeout("PT15M");

            // Must have at least one step with data for conversion to work
            memory.getCurrentStep().storeData(new Data<>("input", "hello"));

            var snapshot = ConversationMemoryUtilities.convertConversationMemory(memory);

            assertEquals("wf-alpha", snapshot.getHitlPausedWorkflowId());
            assertEquals(3, snapshot.getHitlPausedAbsoluteTaskIndex());
            assertEquals(pausedAt, snapshot.getHitlPausedAt());
            assertEquals("Needs review", snapshot.getHitlPauseReason());
            assertEquals("FAIL_TASK", snapshot.getHitlTimeoutPolicy());
            assertEquals("PT15M", snapshot.getHitlApprovalTimeout());
        }

        @Test
        @DisplayName("null HITL fields are preserved as null/-1 in snapshot")
        void nullFieldsPreserved() {
            var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
            memory.getCurrentStep().storeData(new Data<>("input", "hello"));

            var snapshot = ConversationMemoryUtilities.convertConversationMemory(memory);

            assertNull(snapshot.getHitlPausedWorkflowId());
            assertEquals(-1, snapshot.getHitlPausedAbsoluteTaskIndex());
            assertNull(snapshot.getHitlPausedAt());
            assertNull(snapshot.getHitlPauseReason());
            assertNull(snapshot.getHitlTimeoutPolicy());
            assertNull(snapshot.getHitlApprovalTimeout());
        }
    }

    // =========================================================================
    // snapshot → memory
    // =========================================================================

    @Nested
    @DisplayName("convertConversationMemorySnapshot (snapshot → memory)")
    class SnapshotToMemory {

        @Test
        @DisplayName("HITL fields are restored from snapshot to memory")
        void hitlFieldsRestored() {
            var snapshot = buildMinimalSnapshot();
            var pausedAt = Instant.parse("2026-06-30T14:00:00Z");

            snapshot.setHitlPausedWorkflowId("wf-beta");
            snapshot.setHitlPausedAbsoluteTaskIndex(5);
            snapshot.setHitlPausedAt(pausedAt);
            snapshot.setHitlPauseReason("Approval required");
            snapshot.setHitlTimeoutPolicy("SKIP_TASK");
            snapshot.setHitlApprovalTimeout("PT1H");

            var restored = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);

            assertEquals("wf-beta", restored.getHitlPausedWorkflowId());
            assertEquals(5, restored.getHitlPausedAbsoluteTaskIndex());
            assertEquals(pausedAt, restored.getHitlPausedAt());
            assertEquals("Approval required", restored.getHitlPauseReason());
            assertEquals("SKIP_TASK", restored.getHitlTimeoutPolicy());
            assertEquals("PT1H", restored.getHitlApprovalTimeout());
        }

        @Test
        @DisplayName("null HITL fields on snapshot produce defaults on memory")
        void nullFieldsProduceDefaults() {
            var snapshot = buildMinimalSnapshot();
            // Do not set any HITL fields

            var restored = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);

            assertNull(restored.getHitlPausedWorkflowId());
            assertEquals(-1, restored.getHitlPausedAbsoluteTaskIndex());
            assertNull(restored.getHitlPausedAt());
            assertNull(restored.getHitlPauseReason());
            assertNull(restored.getHitlTimeoutPolicy());
            assertNull(restored.getHitlApprovalTimeout());
        }
    }

    // =========================================================================
    // snapshot → simple
    // =========================================================================

    @Nested
    @DisplayName("convertSimpleConversationMemory (snapshot → simple)")
    class SnapshotToSimple {

        @Test
        @DisplayName("only hitlPausedAt is copied to simple snapshot")
        void onlyPausedAtCopied() {
            var snapshot = buildMinimalSnapshot();
            var pausedAt = Instant.parse("2026-06-30T16:00:00Z");

            snapshot.setHitlPausedWorkflowId("wf-gamma");
            snapshot.setHitlPausedAbsoluteTaskIndex(9);
            snapshot.setHitlPausedAt(pausedAt);
            snapshot.setHitlPauseReason("Sensitive action");
            snapshot.setHitlTimeoutPolicy("ESCALATE");
            snapshot.setHitlApprovalTimeout("PT2H");

            var simple = ConversationMemoryUtilities.convertSimpleConversationMemory(
                    snapshot, true, false);

            assertEquals(pausedAt, simple.getHitlPausedAt(),
                    "hitlPausedAt must be copied to simple snapshot");
        }

        @Test
        @DisplayName("null hitlPausedAt produces null in simple snapshot")
        void nullPausedAt() {
            var snapshot = buildMinimalSnapshot();

            var simple = ConversationMemoryUtilities.convertSimpleConversationMemory(
                    snapshot, true, false);

            assertNull(simple.getHitlPausedAt());
        }
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Builds a minimal valid snapshot with one step and one output, so the
     * conversion methods don't fail on empty collections.
     */
    private ConversationMemorySnapshot buildMinimalSnapshot() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId("conv-test");
        snapshot.setAgentId("agent-test");
        snapshot.setAgentVersion(1);

        var output = new ConversationOutput();
        output.put("input:initial", "test-input");
        snapshot.getConversationOutputs().add(output);

        var step = new ConversationStepSnapshot();
        var workflow = new WorkflowRunSnapshot();
        workflow.getLifecycleTasks().add(
                new ResultSnapshot("input:initial", "test-input", null, new Date(), null, true));
        step.getWorkflows().add(workflow);
        snapshot.getConversationSteps().add(step);

        return snapshot;
    }
}
