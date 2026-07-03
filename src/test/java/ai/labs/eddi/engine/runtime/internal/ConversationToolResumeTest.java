/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
