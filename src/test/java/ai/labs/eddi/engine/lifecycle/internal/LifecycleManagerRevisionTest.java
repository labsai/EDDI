/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.lifecycle.IAnswerRevisingTask;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionCandidate;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionRequest;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The lifecycle manager's part in {@code onRejection: REVISE}: it records where
 * the LLM task that answered sits, and runs one task's revision on request.
 */
@DisplayName("LifecycleManager — answer revision (E2)")
class LifecycleManagerRevisionTest {

    private static final Object COMPONENT = new Object();

    private IComponentCache componentCache;
    private LifecycleManager lifecycleManager;
    private ConversationMemory memory;

    /**
     * An LLM-typed task that leaves a candidate, and revises through a function.
     */
    private static final class RevisingTask implements ILifecycleTask, IAnswerRevisingTask {
        private final Function<HitlRevisionRequest, Boolean> revise;
        Object componentSeen;

        RevisingTask(Function<HitlRevisionRequest, Boolean> revise) {
            this.revise = revise;
        }

        @Override
        public TaskId getId() {
            return new TaskId("ai.labs.llm");
        }

        @Override
        public String getType() {
            return "langchain";
        }

        @Override
        public void execute(IConversationMemory memory, Object component) {
            var candidate = new HitlRevisionCandidate();
            candidate.setDraft("draft");
            memory.setHitlRevisionCandidate(candidate);
        }

        @Override
        public boolean reviseAnswer(IConversationMemory memory, Object component, HitlRevisionRequest request) {
            componentSeen = component;
            return revise.apply(request);
        }
    }

    @BeforeEach
    void setUp() {
        componentCache = mock(IComponentCache.class);
        var workflowId = mock(IResourceStore.IResourceId.class);
        when(workflowId.getId()).thenReturn("wf1");
        when(workflowId.getVersion()).thenReturn(1);
        lifecycleManager = new LifecycleManager(componentCache, workflowId);
        memory = new ConversationMemory("conv1", "agent1", 1, "user1");
        when(componentCache.getComponentMap("ai.labs.llm")).thenReturn(Map.of("wf1:1:1", COMPONENT));
        when(componentCache.getComponentMap("parser")).thenReturn(Map.of());
    }

    private static ILifecycleTask plainTask() {
        var task = mock(ILifecycleTask.class);
        when(task.getId()).thenReturn(new TaskId("parser"));
        when(task.getType()).thenReturn("input");
        return task;
    }

    @Test
    @DisplayName("after an LLM task answers, its candidate is stamped with workflow and absolute index")
    void stampsTheCandidate() throws Exception {
        lifecycleManager.addLifecycleTask(plainTask());
        lifecycleManager.addLifecycleTask(new RevisingTask(r -> true));

        lifecycleManager.executeLifecycle(memory, null);

        var candidate = memory.getHitlRevisionCandidate();
        assertEquals("wf1", candidate.getWorkflowId());
        assertEquals(1, candidate.getLifecycleIndex());
    }

    @Test
    @DisplayName("reviseAnswerAt hands the request and the task's component to the task at the index")
    void revisesAtTheIndex() throws Exception {
        var task = new RevisingTask(r -> {
            var next = new HitlRevisionCandidate();
            next.setDraft("revision " + r.round());
            memory.setHitlRevisionCandidate(next);
            return true;
        });
        lifecycleManager.addLifecycleTask(plainTask());
        lifecycleManager.addLifecycleTask(task);

        assertTrue(lifecycleManager.reviseAnswerAt(memory, 1, new HitlRevisionRequest(new HitlRevisionCandidate(), "note", 1)));

        assertSame(COMPONENT, task.componentSeen);
        assertEquals(1, memory.getHitlRevisionCandidate().getLifecycleIndex(), "the revision's candidate is stamped too");
    }

    @Test
    @DisplayName("reviseAnswerAt is false for a task that cannot revise or an index out of range")
    void falseWhenNotRevisable() throws Exception {
        lifecycleManager.addLifecycleTask(plainTask());
        var request = new HitlRevisionRequest(new HitlRevisionCandidate(), "note", 1);

        assertFalse(lifecycleManager.reviseAnswerAt(memory, 0, request));
        assertFalse(lifecycleManager.reviseAnswerAt(memory, 5, request));
        assertFalse(lifecycleManager.reviseAnswerAt(memory, -1, request));
        assertFalse(lifecycleManager.reviseAnswerAt(memory, 0, null));
    }

    @Test
    @DisplayName("a gated tool call in a later task of the revision is a TOOL_CALL pause at that index")
    void toolGateBecomesPause() {
        lifecycleManager.addLifecycleTask(plainTask());
        lifecycleManager.addLifecycleTask(new RevisingTask(r -> {
            throw new ToolApprovalRequiredException("approve sendEmail", null);
        }));

        var pause = assertThrows(ConversationPauseException.class,
                () -> lifecycleManager.reviseAnswerAt(memory, 1, new HitlRevisionRequest(new HitlRevisionCandidate(), "note", 1)));
        assertEquals(ConversationPauseException.PauseOrigin.TOOL_CALL, pause.getPauseOrigin());
        assertEquals(1, pause.getPausedAbsoluteTaskIndex());
    }
}
