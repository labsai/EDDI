/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A task that overflowed the stack — the expression parser on 20,000 nested
 * parentheses, before it was bounded — threw an {@code Error} past the
 * task-failure block: no error counter, no {@code task_failed} event, no audit
 * entry, and the caller saw a bare 500 with nothing in the conversation. The
 * overflow is now a task failure like any other.
 */
@DisplayName("LifecycleManager — a StackOverflowError in a task is a task failure")
class LifecycleManagerStackOverflowTest {

    private LifecycleManager lifecycleManager;
    private IConversationMemory memory;
    private ConversationEventSink eventSink;
    private ILifecycleTask task;

    @BeforeEach
    void setUp() throws Exception {
        var componentCache = mock(IComponentCache.class);
        var workflowId = mock(IResourceStore.IResourceId.class);
        when(workflowId.getId()).thenReturn("pkg-1");
        when(workflowId.getVersion()).thenReturn(1);
        when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());
        lifecycleManager = new LifecycleManager(componentCache, workflowId);

        memory = mock(IConversationMemory.class);
        var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(memory.getConversationId()).thenReturn("conv1");
        when(memory.getAgentId()).thenReturn("agent1");
        eventSink = mock(ConversationEventSink.class);
        when(memory.getEventSink()).thenReturn(eventSink);

        task = mock(ILifecycleTask.class);
        when(task.getId()).thenReturn(new TaskId("ai.labs.property"));
        when(task.getType()).thenReturn("property");
        doThrow(new StackOverflowError()).when(task).execute(any(), any());
        lifecycleManager.addLifecycleTask(task);
    }

    @Test
    @DisplayName("the Error does not escape: the pipeline fails with a LifecycleException that carries it")
    void overflowBecomesLifecycleException() {
        var e = assertThrows(LifecycleException.class, () -> lifecycleManager.executeLifecycle(memory, null));

        Throwable cause = e.getCause();
        assertInstanceOf(IllegalStateException.class, cause);
        assertInstanceOf(StackOverflowError.class, cause.getCause());
    }

    @Test
    @DisplayName("the failure is reported like any other: task_failed with its own error type")
    void overflowIsReportedAsTaskFailed() {
        assertThrows(LifecycleException.class, () -> lifecycleManager.executeLifecycle(memory, null));

        verify(eventSink).onTaskFailed(eq(new TaskId("ai.labs.property")), eq("property"), anyLong(), eq("stack_overflow"), any());
    }

    @Test
    @DisplayName("classifyError sees the overflow through the wrapper")
    void classified() {
        assertEquals("stack_overflow", LifecycleManager.classifyError(new IllegalStateException("wrapped", new StackOverflowError())));
        assertEquals("stack_overflow", LifecycleManager.classifyError(new StackOverflowError()));
    }
}
