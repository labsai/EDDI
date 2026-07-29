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
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.stubbing.Answer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

    /**
     * Registers the pipeline stub. Not a {@link java.util.function.Consumer}: the
     * {@code ILifecycleManager} methods being stubbed declare checked exceptions,
     * so the registration lambda has to be allowed to throw.
     */
    @FunctionalInterface
    private interface StubRegistration {
        void register(Answer<Object> answer) throws Exception;
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
     * <p>
     * The stub is registered by the caller because the say path and the resume path
     * enter the pipeline through different {@code ILifecycleManager} methods.
     */
    private Thread cancelWhilePipelineRuns(StubRegistration stubRegistration) throws Exception {
        var pipelineEntered = new CountDownLatch(1);
        var cancelApplied = new CountDownLatch(1);

        stubRegistration.register(invocation -> {
            pipelineEntered.countDown();
            assertTrue(cancelApplied.await(10, TimeUnit.SECONDS), "canceller thread did not run");
            throw new ConversationStopException();
        });

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

    /**
     * Models watchdog abandonment: a second thread interrupts the pipeline thread
     * while the workflow is running, and the workflow then returns NORMALLY without
     * observing it — the state the guard sees when the interrupt lands in the
     * residual window after {@code LifecycleManager}'s own exit check.
     * <p>
     * The stub spins on the flag instead of awaiting a latch on purpose: any
     * interruptible wait would throw {@code InterruptedException} and CLEAR the
     * flag, destroying the very condition under test. The spin ends exactly when
     * the interrupt lands, so the interleaving is deterministic without a sleep.
     */
    private Thread interruptWhilePipelineRuns(StubRegistration stubRegistration) throws Exception {
        var pipelineThread = Thread.currentThread();
        var pipelineEntered = new CountDownLatch(1);

        stubRegistration.register(invocation -> {
            pipelineEntered.countDown();
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Thread.currentThread().isInterrupted()) {
                if (System.nanoTime() - deadlineNanos > 0) {
                    fail("watchdog thread never interrupted the pipeline thread");
                }
                Thread.onSpinWait();
            }
            return null;
        });

        var watchdog = new Thread(() -> {
            try {
                assertTrue(pipelineEntered.await(10, TimeUnit.SECONDS), "pipeline never started");
                pipelineThread.interrupt();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "watchdog-abandon");
        watchdog.start();
        return watchdog;
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

        var canceller = cancelWhilePipelineRuns(
                stub -> doAnswer(stub).when(lifecycleManager).executeLifecycle(any(), any()));
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

    // =====================================================================
    // Watchdog abandonment — the OTHER abort signal, which is an interrupt
    // and NOT the cancel flag.
    // =====================================================================

    /**
     * A timed-out turn is abandoned by interrupting the pipeline thread:
     * {@code BaseRuntime.AbandonableFuture#cancel} sets its own {@code abandoned}
     * flag and interrupts, it never sets {@code memory.setCancelled(true)}, and the
     * late completion is routed to {@code onFailure} so the conversation document
     * is discarded. If the interrupt lands after the pipeline's last observation
     * point, the workflow returns normally and — without the interrupt half of the
     * guard — the turn still upserts its changed longTerm properties into the
     * user-memory store, for a turn that has no persisted conversation document.
     * <p>
     * The stub returns normally with the flag set, which is exactly the state at
     * the guard when the interrupt lands in the residual window after
     * {@code LifecycleManager}'s own exit check.
     */
    @Test
    @Timeout(30)
    @DisplayName("a turn abandoned by the watchdog (interrupt, cancel flag NOT set) does not upsert its longTerm properties")
    void abandonedTurnDoesNotPersistProperties() throws Exception {
        Conversation conversation = nextTurn();
        memory.getConversationProperties()
                .put("dietary_restriction", new Property("dietary_restriction", "vegan", Scope.longTerm));

        var watchdog = interruptWhilePipelineRuns(
                stub -> doAnswer(stub).when(lifecycleManager).executeLifecycle(any(), any()));

        boolean interruptFlagSurvived;
        try {
            conversation.say("I am vegan", new LinkedHashMap<>());
        } finally {
            // Read (and clear) the flag BEFORE joining: join() is interruptible, so on
            // a still-interrupted thread it would throw and clear the flag first.
            interruptFlagSurvived = Thread.interrupted();
            watchdog.join(10_000);
        }

        assertFalse(memory.isCancelled(), "abandonment must not be confused with a cooperative cancel");
        assertTrue(interruptFlagSurvived, "the guard must only read the interrupt flag, never clear it");
        verify(userMemoryStore, never()).upsert(any(UserMemoryEntry.class));
    }

    // =====================================================================
    // resume() — the same guard, on the HITL path
    // =====================================================================

    /** Parks the memory in AWAITING_HUMAN with a RULE-pause bookmark on wf-1. */
    private void parkAwaitingHuman() {
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        memory.setHitlPausedWorkflowId("wf-1");
        memory.setHitlPausedAbsoluteTaskIndex(0);
    }

    private static HitlDecision approved() {
        var decision = new HitlDecision();
        decision.setVerdict(HitlDecision.HitlVerdict.APPROVED);
        return decision;
    }

    @Test
    @Timeout(30)
    @DisplayName("a resume cancelled mid-pipeline does not upsert the longTerm properties it set")
    void cancelledResumeDoesNotPersistProperties() throws Exception {
        parkAwaitingHuman();
        Conversation conversation = nextTurn();
        memory.getConversationProperties()
                .put("dietary_restriction", new Property("dietary_restriction", "vegan", Scope.longTerm));

        var canceller = cancelWhilePipelineRuns(
                stub -> doAnswer(stub).when(lifecycleManager).executeLifecycleFromIndex(any(), anyInt()));
        try {
            conversation.resume(approved());
        } finally {
            canceller.join(10_000);
        }

        // The cancel wins the outcome, and nothing may reach the user-memory store.
        assertEquals(ConversationState.ENDED, memory.getConversationState());
        verify(userMemoryStore, never()).upsert(any(UserMemoryEntry.class));
    }

    @Test
    @Timeout(30)
    @DisplayName("control: the SAME resume stop path without a cancel still persists")
    void stoppedButNotCancelledResumeStillPersists() throws Exception {
        parkAwaitingHuman();
        Conversation conversation = nextTurn();
        memory.getConversationProperties()
                .put("dietary_restriction", new Property("dietary_restriction", "vegan", Scope.longTerm));

        doThrow(new ConversationStopException())
                .when(lifecycleManager).executeLifecycleFromIndex(any(), anyInt());

        conversation.resume(approved());

        assertEquals(ConversationState.ENDED, memory.getConversationState());
        verify(userMemoryStore, times(1)).upsert(any(UserMemoryEntry.class));
    }

    @Test
    @Timeout(30)
    @DisplayName("a resume abandoned by the watchdog (interrupt) does not upsert its longTerm properties")
    void abandonedResumeDoesNotPersistProperties() throws Exception {
        parkAwaitingHuman();
        Conversation conversation = nextTurn();
        memory.getConversationProperties()
                .put("dietary_restriction", new Property("dietary_restriction", "vegan", Scope.longTerm));

        var watchdog = interruptWhilePipelineRuns(
                stub -> doAnswer(stub).when(lifecycleManager).executeLifecycleFromIndex(any(), anyInt()));

        boolean interruptFlagSurvived;
        try {
            conversation.resume(approved());
        } finally {
            // Read (and clear) the flag BEFORE joining — see the say-path twin.
            interruptFlagSurvived = Thread.interrupted();
            watchdog.join(10_000);
        }

        // The resume itself "succeeded" (READY, not ERROR) — the ERROR/AWAITING_HUMAN
        // exclusions therefore cannot be what suppresses the write; only the
        // abandonment check can.
        assertEquals(ConversationState.READY, memory.getConversationState());
        assertFalse(memory.isCancelled(), "abandonment must not be confused with a cooperative cancel");
        assertTrue(interruptFlagSurvived, "the guard must only read the interrupt flag, never clear it");
        verify(userMemoryStore, never()).upsert(any(UserMemoryEntry.class));
    }
}
