/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.ConversationStep;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The failure handler's own fallback (security review L14): when reporting a
 * task failure to the caller overflows the stack, the caller still gets a
 * digest — with the task metadata {@code TurnError.fromTaskError} reads.
 */
@DisplayName("LifecycleManager — the failure report's StackOverflowError fallback")
class LifecycleManagerFailureReportFallbackTest {

    /**
     * Passes the pipeline's early {@code classifyError} (which only walks causes
     * and messages) and overflows inside the guarded handler, where
     * {@code LlmFailureClassifier.classify} reads the response.
     */
    private static final class OverflowingOnReport extends WebApplicationException {
        OverflowingOnReport() {
            super("provider rejected the request");
        }

        @Override
        public Response getResponse() {
            throw new StackOverflowError();
        }
    }

    @Test
    @DisplayName("the digest keeps taskType and errorType so the caller's error object is complete")
    @SuppressWarnings("unchecked")
    void fallbackDigestCarriesTaskMetadata() throws Exception {
        var componentCache = mock(IComponentCache.class);
        var workflowId = mock(IResourceStore.IResourceId.class);
        when(workflowId.getId()).thenReturn("pkg-1");
        when(workflowId.getVersion()).thenReturn(1);
        when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());
        var lifecycleManager = new LifecycleManager(componentCache, workflowId);

        var task = mock(ILifecycleTask.class);
        when(task.getId()).thenReturn(new TaskId("ai.labs.llm"));
        when(task.getType()).thenReturn("langchain");
        doThrow(new OverflowingOnReport()).when(task).execute(any(), any());
        lifecycleManager.addLifecycleTask(task);

        var memory = mock(IConversationMemory.class);
        var step = mock(ConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(step);
        when(memory.getConversationId()).thenReturn("conv1");
        when(memory.getAgentId()).thenReturn("agent1");
        when(step.snapshotDataIdentities()).thenReturn(new HashMap<>());
        when(step.snapshotOutputKeys()).thenReturn(new HashSet<>());
        when(step.getAllElements()).thenReturn(new LinkedList<>());
        when(step.getConversationOutput()).thenReturn(new ConversationOutput());

        assertThrows(LifecycleException.class, () -> lifecycleManager.executeLifecycle(memory, null));

        var reported = ArgumentCaptor.forClass(List.class);
        verify(step).addConversationOutputList(eq("taskErrors"), reported.capture());
        var entry = (Map<String, Object>) reported.getValue().getFirst();
        assertEquals("errorDigest", entry.get("type"));
        assertEquals("ai.labs.llm", entry.get("taskId"));
        assertEquals("langchain", entry.get("taskType"));
        assertEquals("stack_overflow", entry.get("errorType"));
        assertEquals("Task 'eddi://ai.labs.llm' failed: OverflowingOnReport", entry.get("text"));
    }
}
