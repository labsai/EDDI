/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 8: same-index re-entry for TOOL_CALL pauses.
 * <p>
 * Verifies the {@link Conversation#resume(HitlDecision)} TOOL_CALL branch:
 * APPROVED/REJECTED both re-enter the pipeline at the SAME absolute task index
 * (not +1) so {@code LlmTask} re-runs the paused task; the RULE path (null
 * pauseType) keeps its +1 offset and REJECTED short-circuit for backward
 * compatibility; the decision-visibility writes fire for every pause type.
 */
@DisplayName("Conversation tool-call resume (same-index re-entry)")
class ConversationToolResumeTest {

    private ConversationMemory memory;
    private IPropertiesHandler propertiesHandler;
    private IConversation.IConversationOutputRenderer outputRenderer;
    private IExecutableWorkflow workflow;
    private ILifecycleManager lifecycleManager;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("conv1", "agent1", 1, "user1");
        propertiesHandler = mock(IPropertiesHandler.class);
        outputRenderer = mock(IConversation.IConversationOutputRenderer.class);
        workflow = mock(IExecutableWorkflow.class);
        lifecycleManager = mock(ILifecycleManager.class);

        when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
        when(workflow.getWorkflowId()).thenReturn("wf1");
        when(propertiesHandler.getUserMemoryStore()).thenReturn(null);
    }

    private Conversation createConversation() {
        return new Conversation(List.of(workflow), memory, propertiesHandler, outputRenderer);
    }

    private HitlDecision decision(HitlVerdict verdict) {
        var d = new HitlDecision();
        d.setVerdict(verdict);
        return d;
    }

    private HitlDecision decision(HitlVerdict verdict, String note, String decidedBy) {
        var d = new HitlDecision();
        d.setVerdict(verdict);
        d.setNote(note);
        d.setDecidedBy(decidedBy);
        return d;
    }

    private PendingToolCallBatch batch(String taskId, int taskIndex) {
        var b = new PendingToolCallBatch();
        b.setLlmTaskId(taskId);
        b.setLlmTaskIndex(taskIndex);
        return b;
    }

    private void armToolPause(int absoluteTaskIndex) {
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        memory.setHitlPausedWorkflowId("wf1");
        memory.setHitlPausedAbsoluteTaskIndex(absoluteTaskIndex);
        memory.setHitlPauseType("TOOL_CALL");
        memory.setHitlPendingToolCalls(batch("ai.labs.llm", 0));
    }

    // === TOOL_CALL + APPROVED → same-index re-entry ===

    @Test
    @DisplayName("TOOL_CALL + APPROVED re-enters at the SAME absolute index (not +1)")
    void toolApprovedResumesSameIndex() throws Exception {
        armToolPause(2);

        var conv = createConversation();
        conv.resume(decision(HitlVerdict.APPROVED));

        // Tool pause resumes AT the paused index so LlmTask re-runs the paused task.
        verify(lifecycleManager).executeLifecycleFromIndex(memory, 2);
        verify(lifecycleManager, never()).executeLifecycleFromIndex(memory, 3);
    }

    @Test
    @DisplayName("TOOL_CALL + APPROVED stashes the decision on memory so LlmTask can consume it during re-entry")
    void toolApprovedStashesResumeDecision() throws Exception {
        armToolPause(2);
        // Capture the resume decision AT the moment the pipeline re-enters — LlmTask
        // consumes it there. Asserting post-return state would fail because the
        // finally safety-net clears the (uncomsumed, mock-lifecycle) batch.
        HitlDecision[] seen = new HitlDecision[1];
        doAnswer(inv -> {
            seen[0] = memory.getHitlResumeDecision();
            return null;
        }).when(lifecycleManager).executeLifecycleFromIndex(eq(memory), anyInt());

        var conv = createConversation();
        var d = decision(HitlVerdict.APPROVED);
        conv.resume(d);

        assertSame(d, seen[0],
                "resume decision must be stashed on memory before the pipeline re-enters");
    }

    // === RULE (null pauseType) → +1, unchanged (backward compat) ===

    @Test
    @DisplayName("RULE pause (null pauseType) keeps the +1 offset — backward compatible")
    void rulePauseResumesPlusOne() throws Exception {
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        memory.setHitlPausedWorkflowId("wf1");
        memory.setHitlPausedAbsoluteTaskIndex(2);
        // pauseType left null — legacy RULE pause

        var conv = createConversation();
        conv.resume(decision(HitlVerdict.APPROVED));

        verify(lifecycleManager).executeLifecycleFromIndex(memory, 3);
    }

    @Test
    @DisplayName("explicit RULE pauseType keeps the +1 offset")
    void explicitRulePauseResumesPlusOne() throws Exception {
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        memory.setHitlPausedWorkflowId("wf1");
        memory.setHitlPausedAbsoluteTaskIndex(4);
        memory.setHitlPauseType("RULE");

        var conv = createConversation();
        conv.resume(decision(HitlVerdict.APPROVED));

        verify(lifecycleManager).executeLifecycleFromIndex(memory, 5);
    }

    @Test
    @DisplayName("RULE pause + REJECTED short-circuits — NO pipeline re-entry (backward compat)")
    void rulePauseRejectedShortCircuits() throws Exception {
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        memory.setHitlPausedWorkflowId("wf1");
        memory.setHitlPausedAbsoluteTaskIndex(1);

        var conv = createConversation();
        conv.resume(decision(HitlVerdict.REJECTED));

        verify(lifecycleManager, never()).executeLifecycleFromIndex(any(), anyInt());
        verify(lifecycleManager, never()).executeLifecycle(any(), any());
    }

    // === TOOL_CALL + REJECTED → NO short-circuit, same-index re-entry ===

    @Test
    @DisplayName("TOOL_CALL + REJECTED does NOT short-circuit — re-enters at the SAME index so the model can answer gracefully")
    void toolRejectedReEntersSameIndex() throws Exception {
        armToolPause(2);

        var conv = createConversation();
        conv.resume(decision(HitlVerdict.REJECTED, "not authorized", "supervisor"));

        // Unlike a RULE REJECTED, a TOOL_CALL REJECTED must re-enter the pipeline at
        // the same index (Task 9 turns the rejection into tool results).
        verify(lifecycleManager).executeLifecycleFromIndex(memory, 2);
    }

    @Test
    @DisplayName("TOOL_CALL + REJECTED still writes the hitlDecision visibility outputs")
    void toolRejectedWritesDecisionVisibility() throws Exception {
        armToolPause(2);

        var conv = createConversation();
        conv.resume(decision(HitlVerdict.REJECTED, "not authorized", "supervisor"));

        var output = memory.getCurrentStep().getConversationOutput();
        assertEquals("REJECTED", output.get("hitlDecision"),
                "decision visibility must be written on the tool-pause REJECTED path too");
        assertEquals("not authorized", output.get("hitlDecisionNote"));
        assertTrue(memory.getConversationProperties().containsKey("hitlVerdict"));
    }

    @Test
    @DisplayName("TOOL_CALL + REJECTED stashes the resume decision (Task 9 reads it to build tool results)")
    void toolRejectedStashesResumeDecision() throws Exception {
        armToolPause(2);
        HitlDecision[] seen = new HitlDecision[1];
        doAnswer(inv -> {
            seen[0] = memory.getHitlResumeDecision();
            return null;
        }).when(lifecycleManager).executeLifecycleFromIndex(eq(memory), anyInt());

        var conv = createConversation();
        var d = decision(HitlVerdict.REJECTED, "not authorized", "supervisor");
        conv.resume(d);

        assertSame(d, seen[0],
                "resume decision must be stashed on memory before the pipeline re-enters");
    }

    // === finally safety-net clears the batch on a clean (non-re-pause) exit ===

    @Test
    @DisplayName("finally clears the tool-pause batch when the resume completes without re-pausing")
    void finallyClearsBatchOnCleanExit() throws Exception {
        armToolPause(2);

        var conv = createConversation();
        conv.resume(decision(HitlVerdict.APPROVED));

        // The lifecycle mock does nothing, so LlmTask never consumes the batch here.
        // The finally safety-net must clear it because the final state is READY (not
        // a fresh re-pause).
        assertEquals(ConversationState.READY, memory.getConversationState());
        assertNull(memory.getHitlPendingToolCalls(),
                "finally must clear the batch when the turn did not re-pause");
        assertNull(memory.getHitlPauseType());
    }

    // =========================================================================
    // Fix #2: the pending-approval placeholder must be DROPPED on the tool-pause
    // resume path so the resumed step renders ONLY the final answer — not
    // [placeholder, answer]. The placeholder still shows while AWAITING_HUMAN.
    // =========================================================================

    private static final String PENDING = "Approval required for delete_record";

    /**
     * Drives a realistic TOOL_CALL pause through {@code say()}: the mock lifecycle
     * arms a batch (with an effective pendingMessage so
     * {@code resolvePendingMessage} is deterministic) and throws a TOOL_CALL
     * {@link ConversationPauseException}, so {@code Conversation.pauseConversation}
     * writes the placeholder to the step's {@code "output"} conversation-output
     * exactly as production does.
     */
    private Conversation pauseViaToolCall() throws Exception {
        memory.setConversationState(ConversationState.READY);

        var effective = new ToolApprovalsConfig();
        effective.setPendingMessage("Approval required for {toolNames}");

        doAnswer(inv -> {
            var call = new PendingToolCallBatch.PendingToolCall();
            call.setToolName("delete_record");
            var b = new PendingToolCallBatch();
            b.setLlmTaskId("ai.labs.llm");
            b.setLlmTaskIndex(0);
            b.setCalls(List.of(call));
            b.setEffectiveToolApprovals(effective);
            memory.setHitlPendingToolCalls(b);
            throw new ConversationPauseException("wf1", 0, "gated tool call",
                    ConversationPauseException.PauseOrigin.TOOL_CALL);
        }).when(lifecycleManager).executeLifecycle(any(), any());

        var conv = createConversation();
        conv.say("delete it", Map.of());
        return conv;
    }

    @SuppressWarnings("unchecked")
    private List<Object> outputList() {
        return (List<Object>) memory.getCurrentStep().getConversationOutput().get(MemoryKeys.OUTPUT_PREFIX);
    }

    @Test
    @DisplayName("while AWAITING_HUMAN the placeholder IS present in the step output (not removed too early)")
    void placeholderPresentWhilePaused() throws Exception {
        pauseViaToolCall();

        assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
        var out = outputList();
        assertNotNull(out, "pause must publish an output entry so a polling client sees something");
        assertTrue(out.stream().anyMatch(o -> String.valueOf(o).equals(PENDING)),
                "the pending-approval placeholder must be visible while awaiting approval; got: " + out);
    }

    @Test
    @DisplayName("APPROVED tool resume: the step output contains ONLY the final answer — the placeholder is dropped")
    void approvedResumeDropsPlaceholder() throws Exception {
        var conv = pauseViaToolCall();

        // Simulate LlmTask.executeResume appending the final answer to the SAME
        // "output" list at the SAME index (as production does via TextOutputItem).
        String finalAnswer = "Record deleted successfully.";
        doAnswer(inv -> {
            memory.getCurrentStep().addConversationOutputList(
                    MemoryKeys.OUTPUT_PREFIX, List.of(new TextOutputItem(finalAnswer, 0)));
            return null;
        }).when(lifecycleManager).executeLifecycleFromIndex(eq(memory), anyInt());

        conv.resume(decision(HitlVerdict.APPROVED));

        var out = outputList();
        assertNotNull(out);
        assertFalse(out.stream().anyMatch(o -> String.valueOf(o).equals(PENDING)),
                "the stale pending-approval placeholder must be gone after resume; got: " + out);
        assertTrue(out.stream().anyMatch(o -> String.valueOf(o).equals(finalAnswer)),
                "the final answer must be present; got: " + out);
        assertEquals(1, out.size(),
                "the resumed step must render ONLY the final answer, not [placeholder, answer]; got: " + out);
    }

    @Test
    @DisplayName("REJECTED tool resume: the graceful answer replaces the placeholder (only the answer remains)")
    void rejectedResumeDropsPlaceholder() throws Exception {
        var conv = pauseViaToolCall();

        // A TOOL_CALL REJECTED does NOT short-circuit — it re-enters the pipeline so
        // the model answers gracefully without the tool. That graceful answer lands
        // in the same "output" list via executeResume.
        String gracefulAnswer = "I could not complete that action, but here is what I can tell you.";
        doAnswer(inv -> {
            memory.getCurrentStep().addConversationOutputList(
                    MemoryKeys.OUTPUT_PREFIX, List.of(new TextOutputItem(gracefulAnswer, 0)));
            return null;
        }).when(lifecycleManager).executeLifecycleFromIndex(eq(memory), anyInt());

        conv.resume(decision(HitlVerdict.REJECTED, "not authorized", "supervisor"));

        var out = outputList();
        assertNotNull(out);
        assertFalse(out.stream().anyMatch(o -> String.valueOf(o).equals(PENDING)),
                "the placeholder must be gone on the REJECTED-tool resume too; got: " + out);
        assertTrue(out.stream().anyMatch(o -> String.valueOf(o).equals(gracefulAnswer)),
                "the graceful tool-less answer must be present; got: " + out);
        assertEquals(1, out.size(),
                "only the graceful answer must remain; got: " + out);
    }

    @Test
    @DisplayName("multi-task step: an earlier task's legitimate output is preserved — only the placeholder is removed")
    void multiTaskEarlierOutputPreserved() throws Exception {
        var conv = pauseViaToolCall();

        // Simulate an EARLIER task in the same step having produced legitimate output
        // BEFORE the gated task paused — the "output" list is [earlier, placeholder].
        String earlier = "Here is the summary you asked for.";
        memory.getCurrentStep().getConversationOutput();
        var out = outputList();
        // insert the earlier output before the placeholder to mirror step ordering
        out.add(0, new TextOutputItem(earlier, 0));

        String finalAnswer = "Record deleted successfully.";
        doAnswer(inv -> {
            memory.getCurrentStep().addConversationOutputList(
                    MemoryKeys.OUTPUT_PREFIX, List.of(new TextOutputItem(finalAnswer, 0)));
            return null;
        }).when(lifecycleManager).executeLifecycleFromIndex(eq(memory), anyInt());

        conv.resume(decision(HitlVerdict.APPROVED));

        var after = outputList();
        assertFalse(after.stream().anyMatch(o -> String.valueOf(o).equals(PENDING)),
                "the placeholder must be removed; got: " + after);
        assertTrue(after.stream().anyMatch(o -> String.valueOf(o).equals(earlier)),
                "an earlier task's legitimate output must be PRESERVED; got: " + after);
        assertTrue(after.stream().anyMatch(o -> String.valueOf(o).equals(finalAnswer)),
                "the final answer must be present; got: " + after);
        assertEquals(2, after.size(),
                "exactly the placeholder is removed — earlier output + final answer remain; got: " + after);
    }
}
