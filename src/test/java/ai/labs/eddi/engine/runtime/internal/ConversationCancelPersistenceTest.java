/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationStopException;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * F6 — a turn that is cancelled mid-flight must not commit its side effects.
 * <p>
 * {@code ConversationService} discards the SNAPSHOT of a cancelled turn, but
 * {@code Conversation#storePropertiesPermanently} writes straight to the
 * user-memory store, outside that snapshot. So a client that disconnected (or a
 * reviewer who hit {@code /cancel}) was told the turn was cancelled while the
 * partial {@code longTerm} properties it had set were still upserted into
 * persistent user memory — the exact inconsistency the ERROR path already
 * guards against.
 * <p>
 * The interleaving is forced with latches: the cancel flag is flipped by
 * another thread strictly WHILE the pipeline is running, which is when a real
 * cancel arrives (a REST/admin thread that is not serialized by the
 * per-conversation coordinator).
 */
class ConversationCancelPersistenceTest {

    private ConversationMemory memory;
    private IUserMemoryStore userMemoryStore;
    private IPropertiesHandler propertiesHandler;
    private ILifecycleManager lifecycleManager;
    private IExecutableWorkflow workflow;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("aabbccddeeff112233445566", "agent-1", 1, "user-1");
        userMemoryStore = mock(IUserMemoryStore.class);
        propertiesHandler = mock(IPropertiesHandler.class);
        when(propertiesHandler.getUserMemoryStore()).thenReturn(userMemoryStore);

        lifecycleManager = mock(ILifecycleManager.class);
        workflow = mock(IExecutableWorkflow.class);
        when(workflow.getWorkflowId()).thenReturn("wf-1");
        when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
    }

    /** Mirrors {@code Agent#continueConversation}: a NEW Conversation per turn. */
    private Conversation nextTurn() {
        return new Conversation(List.of(workflow), memory, propertiesHandler,
                (IConversation.IConversationOutputRenderer) null);
    }

    /**
     * Wires the pipeline to behave exactly like the fixed {@code LifecycleManager}
     * under a cancel: it runs, observes the flag, and aborts with
     * {@link ConversationStopException}. The flag itself is set by a second thread
     * while the pipeline is inside the workflow.
     */
    private Thread cancelWhilePipelineRuns() throws Exception {
        var pipelineEntered = new CountDownLatch(1);
        var cancelApplied = new CountDownLatch(1);

        doAnswer(invocation -> {
            pipelineEntered.countDown();
            assertTrue(cancelApplied.await(10, TimeUnit.SECONDS), "canceller thread did not run");
            throw new ConversationStopException();
        }).when(lifecycleManager).executeLifecycle(any(), any());

        var canceller = new Thread(() -> {
            try {
                assertTrue(pipelineEntered.await(10, TimeUnit.SECONDS), "pipeline never started");
                memory.setCancelled(true);
                cancelApplied.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "cancel-signal");
        canceller.start();
        return canceller;
    }

    @Test
    @Timeout(30)
    @DisplayName("a turn cancelled mid-pipeline does not upsert the longTerm properties it set")
    void cancelledTurnDoesNotPersistProperties() throws Exception {
        Conversation conversation = nextTurn();
        // Set during the turn — not part of the constructor baseline, so an
        // unguarded storePropertiesPermanently() would definitely write it.
        memory.getConversationProperties()
                .put("dietary_restriction", new Property("dietary_restriction", "vegan", Scope.longTerm));

        var canceller = cancelWhilePipelineRuns();
        try {
            conversation.say("I am vegan", new LinkedHashMap<>());
        } finally {
            canceller.join(10_000);
        }

        verify(userMemoryStore, never()).upsert(any(UserMemoryEntry.class));
    }

    @Test
    @Timeout(30)
    @DisplayName("control: the SAME stop path without a cancel still persists — the guard is the cancel, not the stop")
    void stoppedButNotCancelledTurnStillPersists() throws Exception {
        Conversation conversation = nextTurn();
        memory.getConversationProperties()
                .put("dietary_restriction", new Property("dietary_restriction", "vegan", Scope.longTerm));

        // Same abort as above (STOP_CONVERSATION), but nobody cancelled the turn.
        doThrow(new ConversationStopException()).when(lifecycleManager).executeLifecycle(any(), any());

        conversation.say("I am vegan", new LinkedHashMap<>());

        verify(userMemoryStore, times(1)).upsert(any(UserMemoryEntry.class));
    }
}
