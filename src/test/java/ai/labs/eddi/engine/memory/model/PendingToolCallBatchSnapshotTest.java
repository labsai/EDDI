/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trips the new tool-HITL fields through a plain Jackson
 * {@code ObjectMapper} and verifies backward compatibility with pre-feature
 * documents.
 * <p>
 * <strong>Scope:</strong> this is a <em>structural</em> proxy — it proves the
 * POJOs are bean-shaped and null-tolerant. The production Mongo path serializes
 * the snapshot through {@code JacksonCodec} (a BSON-backed
 * {@code ObjectMapper}) and Postgres stores it as JSONB; the true BSON
 * round-trip of a populated {@link PendingToolCallBatch} (incl.
 * {@code traceSoFar} nested maps) belongs in the Testcontainers integration
 * test (see the plan's later tasks) which is CI-only. This unit test
 * intentionally does not exercise that codec.
 */
class PendingToolCallBatchSnapshotTest {

    private static ObjectMapper mapper() {
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static PendingToolCallBatch fullBatch() {
        var call1 = new PendingToolCallBatch.PendingToolCall();
        call1.setCallId("call_abc");
        call1.setToolName("transfer_funds");
        call1.setSource("http");
        call1.setArgumentsRaw("{\"amount\":250}");
        call1.setArgsTruncated(false);
        call1.setArgumentsRedacted("{\"amount\":250}");
        call1.setGateReason("http:transfer_*");

        var call2 = new PendingToolCallBatch.PendingToolCall();
        call2.setCallId("call_def");
        call2.setToolName("delete_record");
        call2.setSource("mcp");
        call2.setArgumentsRaw("<huge>");
        call2.setArgsTruncated(true);
        call2.setArgumentsRedacted("<huge>");
        call2.setGateReason("mcp:*");

        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setLlmTaskId("task-a");
        batch.setLlmTaskIndex(2);
        batch.setWorkflowId("wf-1");
        batch.setChatTranscriptJson("[{\"type\":\"AI\"}]");
        batch.setTranscriptOmitted(false);
        batch.setCalls(List.of(call1, call2));
        batch.setExecutedUngatedCallNames(List.of("getCurrentDateTime"));
        batch.setIterationIndex(3);
        batch.setActivatedToolNames(List.of("delete_record"));
        batch.setTraceSoFar(List.of(Map.of("type", "tool_call", "tool", "transfer_funds")));
        batch.setFingerprint("sha256-xyz");
        batch.setAutoApproveCount(1);
        batch.setPauseCountThisTurn(2);

        var effective = new ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig();
        effective.setRequireApproval(List.of("delete_*", "mcp:*"));
        effective.setTimeoutPolicy(ai.labs.eddi.configs.hitl.HitlTimeoutPolicy.AUTO_REJECT);
        effective.setApprovalTimeout("PT1H");
        effective.setOnNoProgress("AUTO_REJECT");
        effective.setPendingMessage("Awaiting review for {toolNames}");
        batch.setEffectiveToolApprovals(effective);
        return batch;
    }

    @Test
    void snapshot_roundTrips_toolPauseFields() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setHitlPauseType("TOOL_CALL");
        snapshot.setHitlPendingToolCalls(fullBatch());

        var json = mapper().writeValueAsString(snapshot);
        var restored = mapper().readValue(json, ConversationMemorySnapshot.class);

        assertEquals("TOOL_CALL", restored.getHitlPauseType());
        var batch = restored.getHitlPendingToolCalls();
        assertNotNull(batch);
        assertEquals("epoch-1", batch.getPauseEpoch());
        assertEquals("task-a", batch.getLlmTaskId());
        assertEquals(2, batch.getLlmTaskIndex());
        assertEquals(2, batch.getCalls().size());
        assertEquals("call_abc", batch.getCalls().get(0).getCallId());
        assertEquals("transfer_funds", batch.getCalls().get(0).getToolName());
        assertFalse(batch.getCalls().get(0).isArgsTruncated());
        assertTrue(batch.getCalls().get(1).isArgsTruncated());
        assertEquals(List.of("getCurrentDateTime"), batch.getExecutedUngatedCallNames());
        assertEquals(3, batch.getIterationIndex());
        assertEquals("sha256-xyz", batch.getFingerprint());
        assertEquals(1, batch.getAutoApproveCount());
        assertEquals(2, batch.getPauseCountThisTurn());

        // Fix #1: the effective tool-approval config round-trips on the batch.
        assertNotNull(batch.getEffectiveToolApprovals());
        assertEquals(List.of("delete_*", "mcp:*"), batch.getEffectiveToolApprovals().getRequireApproval());
        assertEquals(ai.labs.eddi.configs.hitl.HitlTimeoutPolicy.AUTO_REJECT,
                batch.getEffectiveToolApprovals().getTimeoutPolicy());
        assertEquals("PT1H", batch.getEffectiveToolApprovals().getApprovalTimeout());
        assertEquals("AUTO_REJECT", batch.getEffectiveToolApprovals().getOnNoProgress());
        assertEquals("Awaiting review for {toolNames}", batch.getEffectiveToolApprovals().getPendingMessage());
    }

    @Test
    void legacySnapshot_withoutNewFields_deserializesToNull() throws Exception {
        // A pre-feature document simply lacks the two new keys.
        String legacyJson = "{\"conversationId\":\"c1\",\"agentId\":\"a1\"}";
        var restored = mapper().readValue(legacyJson, ConversationMemorySnapshot.class);
        assertNull(restored.getHitlPauseType());
        assertNull(restored.getHitlPendingToolCalls());
    }

    @Test
    void pauseException_threeArgCtor_defaultsToRuleOrigin() {
        var e = new ConversationPauseException("wf", 3, "reason");
        assertEquals(ConversationPauseException.PauseOrigin.RULE, e.getPauseOrigin());
        assertEquals("wf", e.getPausedWorkflowId());
        assertEquals(3, e.getPausedAbsoluteTaskIndex());
    }

    @Test
    void pauseException_fourArgCtor_carriesToolCallOrigin() {
        var e = new ConversationPauseException("wf", 5, "reason", ConversationPauseException.PauseOrigin.TOOL_CALL);
        assertEquals(ConversationPauseException.PauseOrigin.TOOL_CALL, e.getPauseOrigin());
    }
}
