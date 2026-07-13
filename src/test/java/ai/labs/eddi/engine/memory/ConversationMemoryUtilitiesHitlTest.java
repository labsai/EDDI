/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;

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
            memory.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            memory.setHitlApprovalTimeout("PT15M");
            memory.setHitlPauseType("TOOL_CALL");
            var batch = new PendingToolCallBatch();
            batch.setPauseEpoch("epoch-1");
            memory.setHitlPendingToolCalls(batch);

            // Must have at least one step with data for conversion to work
            memory.getCurrentStep().storeData(new Data<>("input", "hello"));

            var snapshot = ConversationMemoryUtilities.convertConversationMemory(memory);

            assertEquals("wf-alpha", snapshot.getHitlPausedWorkflowId());
            assertEquals(3, snapshot.getHitlPausedAbsoluteTaskIndex());
            assertEquals(pausedAt, snapshot.getHitlPausedAt());
            assertEquals("Needs review", snapshot.getHitlPauseReason());
            assertEquals(HitlTimeoutPolicy.AUTO_REJECT, snapshot.getHitlTimeoutPolicy());
            assertEquals("PT15M", snapshot.getHitlApprovalTimeout());
            assertEquals("TOOL_CALL", snapshot.getHitlPauseType());
            assertNotNull(snapshot.getHitlPendingToolCalls());
            assertEquals("epoch-1", snapshot.getHitlPendingToolCalls().getPauseEpoch());
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
            assertNull(snapshot.getHitlPauseType());
            assertNull(snapshot.getHitlPendingToolCalls());
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
            snapshot.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
            snapshot.setHitlApprovalTimeout("PT1H");
            snapshot.setHitlPauseType("TOOL_CALL");
            var batch = new PendingToolCallBatch();
            batch.setPauseEpoch("epoch-2");
            snapshot.setHitlPendingToolCalls(batch);

            var restored = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);

            assertEquals("wf-beta", restored.getHitlPausedWorkflowId());
            assertEquals(5, restored.getHitlPausedAbsoluteTaskIndex());
            assertEquals(pausedAt, restored.getHitlPausedAt());
            assertEquals("Approval required", restored.getHitlPauseReason());
            assertEquals(HitlTimeoutPolicy.AUTO_APPROVE, restored.getHitlTimeoutPolicy());
            assertEquals("PT1H", restored.getHitlApprovalTimeout());
            assertEquals("TOOL_CALL", restored.getHitlPauseType());
            assertNotNull(restored.getHitlPendingToolCalls());
            assertEquals("epoch-2", restored.getHitlPendingToolCalls().getPauseEpoch());
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
            assertNull(restored.getHitlPauseType());
            assertNull(restored.getHitlPendingToolCalls());
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
            snapshot.setHitlTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
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
    // Security: generic simple-snapshot must NOT leak raw args / transcript
    // =========================================================================

    @Nested
    @DisplayName("Simple snapshot HITL batch is a names-only projection (security)")
    class SimpleSnapshotProjectionSecurity {

        private static final String CANARY_SECRET = "sk-live-SECRET-9999";
        private static final String CANARY_ARGS = "{\"amount\":250,\"apiKey\":\"" + CANARY_SECRET + "\"}";
        private static final String CANARY_TRANSCRIPT = "[{\"type\":\"AI\",\"text\":\"" + CANARY_SECRET + "\"}]";

        private ConversationMemorySnapshot toolPausedSnapshot() {
            var snapshot = buildMinimalSnapshot();
            snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPauseType("TOOL_CALL");
            snapshot.setHitlPausedAt(Instant.parse("2026-07-04T10:00:00Z"));

            var call = new PendingToolCallBatch.PendingToolCall();
            call.setCallId("call_abc");
            call.setToolName("transfer_funds");
            call.setSource("http");
            call.setArgumentsRaw(CANARY_ARGS);
            call.setArgumentsRedacted(CANARY_ARGS);
            call.setArgsTruncated(false);
            call.setGateReason("http:transfer_*");

            var batch = new PendingToolCallBatch();
            batch.setPauseEpoch("epoch-1");
            batch.setLlmTaskId("task-a");
            batch.setChatTranscriptJson(CANARY_TRANSCRIPT);
            batch.setTraceSoFar(List.of(java.util.Map.of("args", CANARY_ARGS)));
            batch.setFingerprint("sha256-" + CANARY_SECRET);
            batch.setCalls(List.of(call));
            // Fix #1: the batch carries the effective tool-approval config — it must NOT
            // enter the fix-#4 names-only projection (config, not user data).
            var effective = new ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig();
            effective.setPendingMessage("Awaiting review for {toolNames}");
            batch.setEffectiveToolApprovals(effective);
            snapshot.setHitlPendingToolCalls(batch);
            return snapshot;
        }

        @Test
        @DisplayName("serialized simple snapshot omits raw args, redacted args, transcript, trace, and fingerprint")
        void rawArgsAndTranscriptNotSerialized() throws Exception {
            var simple = ConversationMemoryUtilities.convertSimpleConversationMemory(
                    toolPausedSnapshot(), true, false);

            // Serialize exactly the way the generic read surfaces do (JsonSerialization
            // wraps the app ObjectMapper over the SimpleConversationMemorySnapshot).
            // findAndRegisterModules() picks up jackson-datatype-jsr310 for Instant,
            // matching the production SerializationCustomizer.
            var json = new ObjectMapper().findAndRegisterModules().writeValueAsString(simple);

            // The canary raw value and the frozen transcript MUST NOT appear anywhere.
            assertFalse(json.contains(CANARY_SECRET),
                    "raw secret leaked into generic simple-snapshot JSON: " + json);
            assertFalse(json.contains(CANARY_TRANSCRIPT),
                    "chat transcript leaked into generic simple-snapshot JSON");
            // The heavy/sensitive batch fields must serialize as null (field name may
            // remain, but never a value).
            assertTrue(json.contains("\"chatTranscriptJson\":null"),
                    "transcript must be projected to null in generic simple-snapshot JSON");
            assertTrue(json.contains("\"traceSoFar\":null"),
                    "trace must be projected to null in generic simple-snapshot JSON");
            assertTrue(json.contains("\"fingerprint\":null"),
                    "fingerprint must be projected to null in generic simple-snapshot JSON");
            // And per-call argument fields must never carry a value.
            assertFalse(json.contains("\"argumentsRaw\":\""),
                    "argumentsRaw value leaked into generic simple-snapshot JSON");
            assertFalse(json.contains("\"argumentsRedacted\":\""),
                    "argumentsRedacted value leaked into generic simple-snapshot JSON");

            // But the safe metadata the delegated/group/MCP consumers rely on MUST appear.
            assertTrue(json.contains("TOOL_CALL"), "pauseType must be present");
            assertTrue(json.contains("transfer_funds"), "tool NAME must be present");
        }

        @Test
        @DisplayName("delegated/MCP consumer still reads tool names from the projected batch")
        void delegatedConsumerStillReadsToolNames() {
            var simple = ConversationMemoryUtilities.convertSimpleConversationMemory(
                    toolPausedSnapshot(), true, false);

            // Mirror exactly what McpConversationTools / ConverseWithAgentTool /
            // CreateSubAgentTool do: read batch.getCalls() → getToolName().
            var batch = simple.getHitlPendingToolCalls();
            assertNotNull(batch, "names-only batch must be present for a TOOL_CALL pause");
            assertNotNull(batch.getCalls(), "calls must be non-null so consumers can stream them");

            var toolNames = batch.getCalls().stream()
                    .map(PendingToolCallBatch.PendingToolCall::getToolName)
                    .filter(Objects::nonNull)
                    .toList();
            assertEquals(List.of("transfer_funds"), toolNames);

            // And the projected calls must carry NO sensitive payload.
            var call = batch.getCalls().getFirst();
            assertNull(call.getArgumentsRaw(), "projected call must not carry raw args");
            assertNull(call.getArgumentsRedacted(), "projected call must not carry redacted args");
            assertEquals("TOOL_CALL", simple.getHitlPauseType());

            // The batch-level heavy fields must be stripped too.
            assertNull(batch.getChatTranscriptJson(), "projected batch must not carry the transcript");
            assertNull(batch.getTraceSoFar(), "projected batch must not carry the trace");
            assertNull(batch.getFingerprint(), "projected batch must not carry the fingerprint");
            // Fix #1: the effective tool-approval config must NOT enter the names-only
            // projection — the generic read path does not need it.
            assertNull(batch.getEffectiveToolApprovals(),
                    "projected batch must not carry the effective tool-approval config");
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
