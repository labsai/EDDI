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
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B6 / live S-5: the streaming {@code task_failed} event reaches every client of
 * the stream, end users included. It used to carry the old substring classifier
 * ("unknown" for 401, 429, 500 and timeouts) and the audit summary meant for a
 * privileged audience. It now carries what the REST error carries: the
 * {@code LlmFailureClassifier} class and the redacted digest.
 */
@DisplayName("LifecycleManager — the task_failed event a client sees")
class LifecycleManagerTaskFailedEventTest {

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
        when(memory.getCurrentStep()).thenReturn(mock(IConversationMemory.IWritableConversationStep.class));
        when(memory.getConversationId()).thenReturn("conv1");
        when(memory.getAgentId()).thenReturn("agent1");
        eventSink = mock(ConversationEventSink.class);
        when(memory.getEventSink()).thenReturn(eventSink);

        task = mock(ILifecycleTask.class);
        when(task.getId()).thenReturn(new TaskId("ai.labs.llm"));
        when(task.getType()).thenReturn("langchain");
        lifecycleManager.addLifecycleTask(task);
    }

    @Test
    @DisplayName("a rate limit is reported as RATE_LIMITED with the redacted digest — no key, no URL")
    void classifiedAndRedacted() throws Exception {
        doThrow(new RuntimeException("Chat model execution failed after 3 attempts",
                new RateLimitException("429 too many requests for https://api.example.com/v1 with key sk-abcdefghijklmnopqrstuvwxyz0123456789")))
                .when(task).execute(any(), any());

        assertThrows(LifecycleException.class, () -> lifecycleManager.executeLifecycle(memory, null));

        var summary = ArgumentCaptor.forClass(String.class);
        verify(eventSink).onTaskFailed(eq(new TaskId("ai.labs.llm")), eq("langchain"), anyLong(), eq("RATE_LIMITED"), summary.capture());
        assertTrue(summary.getValue().startsWith("Task 'eddi://ai.labs.llm' failed:"), summary.getValue());
        assertFalse(summary.getValue().contains("sk-abcdefghijklmnopqrstuvwxyz0123456789"), summary.getValue());
        assertFalse(summary.getValue().contains("https://"), "URLs are for the audit log only: " + summary.getValue());
        assertEquals(LifecycleManager.clientFailureDigest(task, new RuntimeException("x")), "Task 'eddi://ai.labs.llm' failed: x");
    }

    @Test
    @DisplayName("a cancelled turn reports its cancel, not the failure the cancel caused")
    void cancelledTurnSendsNoTaskFailed() throws Exception {
        // The cancel lands while the task runs: the flag is up from then on.
        var cancelled = new AtomicBoolean();
        when(memory.isCancelled()).thenAnswer(invocation -> cancelled.get());
        doAnswer(invocation -> {
            cancelled.set(true);
            throw new RuntimeException("Streaming model call stopped: the turn was cancelled");
        }).when(task).execute(any(), any());

        assertThrows(Exception.class, () -> lifecycleManager.executeLifecycle(memory, null));

        assertTrue(cancelled.get(), "the task ran");
        verify(eventSink, never()).onTaskFailed(any(), anyString(), anyLong(), anyString(), anyString());
    }
}
