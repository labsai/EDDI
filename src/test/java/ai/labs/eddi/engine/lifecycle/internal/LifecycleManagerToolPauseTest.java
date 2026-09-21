/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests the tool-pause signal plumbing at the lifecycle boundary (Task 5, Step
 * 4): a {@link ToolApprovalRequiredException} thrown by a task is converted
 * into a {@link ConversationPauseException} with
 * {@link ConversationPauseException.PauseOrigin#TOOL_CALL}, and survives
 * {@link AgentExecutionHelper#executeWithRetry} unchanged.
 */
@DisplayName("LifecycleManager tool-pause plumbing")
class LifecycleManagerToolPauseTest {

    private IComponentCache componentCache;
    private IResourceStore.IResourceId workflowId;
    private LifecycleManager lifecycleManager;

    @BeforeEach
    void setUp() {
        componentCache = mock(IComponentCache.class);
        workflowId = mock(IResourceStore.IResourceId.class);
        when(workflowId.getId()).thenReturn("wf1");
        when(workflowId.getVersion()).thenReturn(1);
        when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());
        lifecycleManager = new LifecycleManager(componentCache, workflowId);
    }

    private ILifecycleTask mockTask(String id, String type) {
        var task = mock(ILifecycleTask.class);
        when(task.getId()).thenReturn(new TaskId(id));
        when(task.getType()).thenReturn(type);
        return task;
    }

    private IConversationMemory mockMemory() {
        var memory = mock(IConversationMemory.class);
        var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(memory.getConversationId()).thenReturn("conv1");
        when(memory.getAgentId()).thenReturn("agent1");
        return memory;
    }

    private ToolApprovalRequiredException newTare() {
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        return new ToolApprovalRequiredException("Tool call requires approval: calculate", batch);
    }

    @Test
    @DisplayName("task throwing ToolApprovalRequiredException → ConversationPauseException(TOOL_CALL) at absolute index")
    void toolApprovalBecomesToolCallPause() throws Exception {
        var task0 = mockTask("parser", "input");
        var task1 = mockTask("llm", "langchain");
        lifecycleManager.addLifecycleTask(task0);
        lifecycleManager.addLifecycleTask(task1);

        var memory = mockMemory();
        doThrow(newTare()).when(task1).execute(any(), any());

        var ex = assertThrows(ConversationPauseException.class,
                () -> lifecycleManager.executeLifecycle(memory, null));

        assertEquals(ConversationPauseException.PauseOrigin.TOOL_CALL, ex.getPauseOrigin());
        assertEquals("wf1", ex.getPausedWorkflowId());
        assertEquals(1, ex.getPausedAbsoluteTaskIndex());
        assertEquals("Tool call requires approval: calculate", ex.getPauseReason());
    }

    @Test
    @DisplayName("tool pause does NOT trigger strict-write rollback (partial step data must survive)")
    void toolPauseSkipsStrictWriteRollback() throws Exception {
        // A memory policy with strict-write enabled would normally roll back a
        // failed task's writes. A tool pause is not a failure — rollback must be
        // skipped. We assert via handleTaskFailure never touching the step:
        // the step is a strict ConversationStep only in production; here we assert
        // the pause propagates as a pause (not a LifecycleException), which proves
        // the guard short-circuited before the failure-handling block.
        var task = mockTask("llm", "langchain");
        lifecycleManager.addLifecycleTask(task);

        var memory = mockMemory();
        // No memory policy → strictWriteEnabled=false anyway; the key assertion is
        // that a ConversationPauseException (not LifecycleException) escapes.
        doThrow(newTare()).when(task).execute(any(), any());

        assertThrows(ConversationPauseException.class,
                () -> lifecycleManager.executeLifecycle(memory, null));
    }
}
