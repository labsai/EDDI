/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;

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
        call2.setMatchedRule("mcp:delete_*");

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
        batch.setInterimText("Config checks out — deleting the record next, which needs your approval.");

        var effective = new ToolApprovalsConfig();
        effective.setRequireApproval(List.of("delete_*", "mcp:*"));
        effective.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        effective.setApprovalTimeout("PT1H");
        effective.setOnNoProgress("AUTO_REJECT");
        effective.setPendingMessage("Awaiting review for {toolNames}");
        batch.setEffectiveToolApprovals(effective);

        var rule = new ToolApprovalsConfig.ApprovalRule();
        rule.setMatch("mcp:delete_*");
        rule.setTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
        rule.setPauseReason("Deleting a record — check the id");
        rule.setPendingMessage("Waiting on a reviewer for {toolNames}");
        batch.setEffectiveRule(rule);
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
        assertEquals(HitlTimeoutPolicy.AUTO_REJECT,
                batch.getEffectiveToolApprovals().getTimeoutPolicy());
        assertEquals("PT1H", batch.getEffectiveToolApprovals().getApprovalTimeout());
        assertEquals("AUTO_REJECT", batch.getEffectiveToolApprovals().getOnNoProgress());
        assertEquals("Awaiting review for {toolNames}", batch.getEffectiveToolApprovals().getPendingMessage());

        // The governing per-tool friction rule must round-trip too. It is resolved
        // ONCE at gate time and read back after the pause by ConversationService (the
        // timeout policy) and Conversation.resolvePendingMessage (the end-user text).
        // If it did not survive persistence, a paused conversation would show the
        // rule's message and the resume would recompute the scalar one — leaving the
        // pending-approval placeholder stranded in the resolved turn's output, because
        // dropPendingApprovalPlaceholder removes it by recomputing that exact string.
        assertNotNull(batch.getEffectiveRule());
        assertEquals("mcp:delete_*", batch.getEffectiveRule().getMatch());
        assertEquals(HitlTimeoutPolicy.WAIT_INDEFINITELY,
                batch.getEffectiveRule().getTimeoutPolicy());
        assertEquals("Deleting a record — check the id", batch.getEffectiveRule().getPauseReason());
        assertEquals("Waiting on a reviewer for {toolNames}", batch.getEffectiveRule().getPendingMessage());
        assertEquals("mcp:delete_*", batch.getCalls().get(1).getMatchedRule());

        // The model's narration must survive persistence:
        // Conversation.pauseConversation
        // reads it back from the STORED batch, and a reload of a paused conversation
        // rebuilds the transcript from the persisted step — both would show only the
        // placeholder if this field did not round-trip.
        assertEquals("Config checks out — deleting the record next, which needs your approval.",
                batch.getInterimText());
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
