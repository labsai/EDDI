/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.runtime.IDiscardableTask;
import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InMemoryConversationCoordinator}.
 *
 * <p>
 * Tests verify ordering, retry/dead-letter logic, counters, and dead-letter
 * CRUD operations.
 * </p>
 */
class InMemoryConversationCoordinatorTest {

    private IRuntime runtime;
    private InMemoryConversationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        runtime = mock(IRuntime.class);
        coordinator = new InMemoryConversationCoordinator(runtime, new SimpleMeterRegistry(), 10000, 1000);
    }

    // ==================== Status ====================

    @Test
    void shouldReportInMemoryType() {
        assertEquals("in-memory", coordinator.getCoordinatorType());
    }

    @Test
    void shouldAlwaysReportConnected() {
        assertTrue(coordinator.isConnected());
        assertEquals("CONNECTED", coordinator.getConnectionStatus());
    }

    @Test
    void shouldStartWithZeroProcessed() {
        assertEquals(0, coordinator.getTotalProcessed());
        assertEquals(0, coordinator.getTotalDeadLettered());
    }

    @Test
    void shouldStartWithEmptyQueueDepths() {
        assertTrue(coordinator.getQueueDepths().isEmpty());
    }

    // ==================== Task Processing ====================

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncrementProcessedOnComplete() {
        Callable<Void> task = mock(Callable.class);
        coordinator.submitInOrder("conv-1", task);

        // Capture the callback and simulate completion
        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> callbackCaptor = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);
        verify(runtime).submitCallable(eq(task), callbackCaptor.capture(), isNull());

        callbackCaptor.getValue().onComplete(null);

        assertEquals(1, coordinator.getTotalProcessed());
    }

    /**
     * C13 — a post-execution failure is NOT retried. {@code onFailure} is only ever
     * raised once the callable has started running, so the turn may already have
     * called an LLM, executed tools and spent money; re-running it repeats those
     * side effects. The task is dead-lettered on the first failure instead.
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldDeadLetterOnFirstFailureWithoutReExecutingTheTask() {
        Callable<Void> task = mock(Callable.class);
        coordinator.submitInOrder("conv-fail", task);

        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> callbackCaptor = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);
        verify(runtime).submitCallable(eq(task), callbackCaptor.capture(), isNull());

        callbackCaptor.getValue().onFailure(new RuntimeException("Test failure"));

        // The already-executed callable must NEVER be handed to the runtime again.
        verify(runtime, times(1)).submitCallable(eq(task), any(), isNull());

        assertEquals(1, coordinator.getTotalDeadLettered());
        assertEquals(1, coordinator.getDeadLetters().size());

        DeadLetterEntry entry = coordinator.getDeadLetters().get(0);
        assertEquals("conv-fail", entry.conversationId());
        assertTrue(entry.error().contains("Test failure"));
    }

    /**
     * C10 — a submission the runtime rejects must not wedge the conversation. The
     * offered task is rolled back off the queue (nothing is scheduled to run it),
     * so the next turn on the same conversation is dispatched normally.
     */
    @Test
    @SuppressWarnings("unchecked")
    void rejectedSubmissionLeavesTheConversationUsable() {
        Callable<Void> rejected = mock(Callable.class);
        Callable<Void> followUp = mock(Callable.class);

        doThrow(new RejectedExecutionException("pool saturated"))
                .when(runtime).submitCallable(eq(rejected), any(), isNull());

        assertThrows(RejectedExecutionException.class, () -> coordinator.submitInOrder("conv-wedge", rejected));

        // The queue must not retain the task nobody is going to run…
        assertFalse(coordinator.getQueueDepths().containsKey("conv-wedge"),
                "A rejected submission must not leave a task queued with nothing scheduled to run it");
        // …and the MAP ENTRY must be gone too, otherwise it leaks for the JVM's
        // lifetime. getQueueDepths() cannot see that: it filters out size==0
        // queues, so an orphaned empty queue is invisible to it. Only the raw
        // map size distinguishes "cleaned up" from "leaked".
        assertEquals(0, coordinator.activeConversationCount(),
                "the rolled-back submission must not leave an orphaned (empty) queue in the map");

        // The conversation is still usable: the next turn is dispatched.
        coordinator.submitInOrder("conv-wedge", followUp);
        verify(runtime).submitCallable(eq(followUp), any(), isNull());
    }

    /**
     * C10 (submitNext side) — a rejection while scheduling the NEXT queued task has
     * no caller to propagate to. It must dead-letter and keep draining rather than
     * leaving the queue populated with nothing scheduled to run it.
     */
    @Test
    @SuppressWarnings("unchecked")
    void rejectedSubmissionOfQueuedTaskDrainsInsteadOfWedging() {
        Callable<Void> first = mock(Callable.class);
        Callable<Void> second = mock(Callable.class);

        coordinator.submitInOrder("conv-drain", first);
        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> captor = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);
        verify(runtime).submitCallable(eq(first), captor.capture(), isNull());

        coordinator.submitInOrder("conv-drain", second);
        doThrow(new RejectedExecutionException("pool saturated"))
                .when(runtime).submitCallable(eq(second), any(), isNull());

        // first completes → submitNext tries (and fails) to schedule second
        captor.getValue().onComplete(null);

        assertFalse(coordinator.getQueueDepths().containsKey("conv-drain"),
                "The queue must drain even when the next task cannot be scheduled");
        assertEquals(0, coordinator.activeConversationCount(),
                "a fully drained conversation must not leave an orphaned (empty) queue in the map");
        assertEquals(1, coordinator.getTotalDeadLettered());
    }

    /**
     * C10 + C11 — a task the coordinator DROPS is never invoked, so the release
     * block inside its own body never runs. Without the discard hook the turn's
     * in-flight metrics reference leaks forever and the HTTP caller waits for a
     * response that can never arrive: exactly the leak the release block exists to
     * prevent, re-created by the drop.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aDroppedQueuedTaskIsToldItWasDiscardedInsteadOfSilentlyVanishing() {
        Callable<Void> first = mock(Callable.class);
        AtomicInteger called = new AtomicInteger();
        AtomicInteger discarded = new AtomicInteger();
        AtomicReference<Throwable> discardCause = new AtomicReference<>();

        IDiscardableTask second = new IDiscardableTask() {
            @Override
            public Void call() {
                called.incrementAndGet();
                return null;
            }

            @Override
            public void onDiscarded(Throwable cause) {
                discarded.incrementAndGet();
                discardCause.set(cause);
            }
        };

        coordinator.submitInOrder("conv-discard", first);
        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> captor = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);
        verify(runtime).submitCallable(eq(first), captor.capture(), isNull());

        coordinator.submitInOrder("conv-discard", second);
        RejectedExecutionException rejection = new RejectedExecutionException("pool saturated");
        doThrow(rejection).when(runtime).submitCallable(eq(second), any(), isNull());

        // first completes → submitNext tries (and fails) to schedule second
        captor.getValue().onComplete(null);

        assertEquals(1, discarded.get(), "a dropped task must be told exactly once that it will never run");
        assertSame(rejection, discardCause.get(), "the discard hook must receive the reason the task could not be scheduled");
        assertEquals(0, called.get(), "a dropped task must NOT be executed — that is what makes the hook necessary");
        assertEquals(0, coordinator.activeConversationCount());
    }

    /**
     * The hook is best effort: a throwing implementation must not break the drain
     * of the rest of the queue or escape into the completion callback.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aThrowingDiscardHookDoesNotBreakTheDrain() {
        Callable<Void> first = mock(Callable.class);
        AtomicInteger discarded = new AtomicInteger();

        IDiscardableTask exploding = new IDiscardableTask() {
            @Override
            public Void call() {
                return null;
            }

            @Override
            public void onDiscarded(Throwable cause) {
                discarded.incrementAndGet();
                throw new IllegalStateException("hook blew up");
            }
        };

        coordinator.submitInOrder("conv-hook-boom", first);
        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> captor = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);
        verify(runtime).submitCallable(eq(first), captor.capture(), isNull());

        coordinator.submitInOrder("conv-hook-boom", exploding);
        doThrow(new RejectedExecutionException("pool saturated")).when(runtime).submitCallable(eq(exploding), any(), isNull());

        captor.getValue().onComplete(null);

        assertEquals(1, discarded.get());
        assertEquals(0, coordinator.activeConversationCount(), "the queue must still be cleaned up after a failing hook");
        assertEquals(1, coordinator.getTotalDeadLettered());
    }

    // ==================== Dead-Letter CRUD ====================

    @Test
    @SuppressWarnings("unchecked")
    void shouldDiscardDeadLetter() {
        // Cause a dead-letter
        Callable<Void> task = mock(Callable.class);
        causeDeadLetter("conv-disc", task);

        List<DeadLetterEntry> entries = coordinator.getDeadLetters();
        assertEquals(1, entries.size());

        boolean discarded = coordinator.discardDeadLetter(entries.get(0).id());
        assertTrue(discarded);
        assertTrue(coordinator.getDeadLetters().isEmpty());
    }

    @Test
    void shouldReturnFalseDiscardingNonExistent() {
        assertFalse(coordinator.discardDeadLetter("nonexistent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPurgeAllDeadLetters() {
        Callable<Void> task1 = mock(Callable.class);
        Callable<Void> task2 = mock(Callable.class);
        causeDeadLetter("conv-1", task1);
        causeDeadLetter("conv-2", task2);

        assertEquals(2, coordinator.getDeadLetters().size());

        int purged = coordinator.purgeDeadLetters();
        assertEquals(2, purged);
        assertTrue(coordinator.getDeadLetters().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReplayDeadLetter() {
        Callable<Void> task = mock(Callable.class);
        causeDeadLetter("conv-replay", task);

        List<DeadLetterEntry> entries = coordinator.getDeadLetters();
        assertEquals(1, entries.size());

        boolean replayed = coordinator.replayDeadLetter(entries.get(0).id());
        assertTrue(replayed);
        assertTrue(coordinator.getDeadLetters().isEmpty());
    }

    @Test
    void shouldReturnFalseReplayingNonExistent() {
        assertFalse(coordinator.replayDeadLetter("nonexistent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldCapDeadLettersAndEvictOldest() {
        IRuntime localRuntime = mock(IRuntime.class);
        // maxDeadLetters = 2
        var capped = new InMemoryConversationCoordinator(localRuntime, new SimpleMeterRegistry(), 10000, 2);

        causeDeadLetter(capped, localRuntime, "conv-1", mock(Callable.class));
        causeDeadLetter(capped, localRuntime, "conv-2", mock(Callable.class));
        causeDeadLetter(capped, localRuntime, "conv-3", mock(Callable.class));

        // Cap is 2 → oldest (conv-1) evicted; all 3 still counted in the total.
        assertEquals(2, capped.getDeadLetters().size());
        assertEquals(3, capped.getTotalDeadLettered());
        var ids = capped.getDeadLetters().stream().map(DeadLetterEntry::conversationId).toList();
        assertTrue(ids.contains("conv-2") && ids.contains("conv-3"));
        assertFalse(ids.contains("conv-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldNotCapDeadLettersWhenDisabled() {
        IRuntime localRuntime = mock(IRuntime.class);
        // maxDeadLetters = -1 → unbounded
        var uncapped = new InMemoryConversationCoordinator(localRuntime, new SimpleMeterRegistry(), 10000, -1);

        causeDeadLetter(uncapped, localRuntime, "c-1", mock(Callable.class));
        causeDeadLetter(uncapped, localRuntime, "c-2", mock(Callable.class));
        causeDeadLetter(uncapped, localRuntime, "c-3", mock(Callable.class));

        assertEquals(3, uncapped.getDeadLetters().size());
    }

    @Test
    void shouldRejectMaxDeadLettersBelowMinusOne() {
        // -1 (unbounded) and 0 (retain none) are the only valid sentinels; -2 is a
        // typo.
        assertThrows(IllegalArgumentException.class, () -> new InMemoryConversationCoordinator(runtime, new SimpleMeterRegistry(), 10000, -2));
    }

    @Test
    void shouldAcceptUnboundedAndZeroSentinels() {
        assertDoesNotThrow(() -> new InMemoryConversationCoordinator(runtime, new SimpleMeterRegistry(), 10000, -1));
        assertDoesNotThrow(() -> new InMemoryConversationCoordinator(runtime, new SimpleMeterRegistry(), 10000, 0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReportQueueDepths() {
        Callable<Void> task1 = mock(Callable.class);
        Callable<Void> task2 = mock(Callable.class);
        coordinator.submitInOrder("conv-q", task1);
        coordinator.submitInOrder("conv-q", task2);

        Map<String, Integer> depths = coordinator.getQueueDepths();
        // Should have at least 1 entry for conv-q with depth >= 1
        assertTrue(depths.containsKey("conv-q"));
    }

    // ==================== Capacity Limit ====================

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectNewConversationAtCapacity() {
        // Create coordinator with maxActiveConversations=2
        var smallCoordinator = new InMemoryConversationCoordinator(runtime, new SimpleMeterRegistry(), 2, 1000);

        Callable<Void> task1 = mock(Callable.class);
        Callable<Void> task2 = mock(Callable.class);
        Callable<Void> task3 = mock(Callable.class);

        smallCoordinator.submitInOrder("conv-1", task1);
        smallCoordinator.submitInOrder("conv-2", task2);

        // Third NEW conversation should be rejected
        assertThrows(RejectedExecutionException.class,
                () -> smallCoordinator.submitInOrder("conv-3", task3));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldAllowFollowUpToExistingConversationAtCapacity() {
        // Create coordinator with maxActiveConversations=2
        var smallCoordinator = new InMemoryConversationCoordinator(runtime, new SimpleMeterRegistry(), 2, 1000);

        Callable<Void> task1 = mock(Callable.class);
        Callable<Void> task2 = mock(Callable.class);
        Callable<Void> followUp = mock(Callable.class);

        smallCoordinator.submitInOrder("conv-1", task1);
        smallCoordinator.submitInOrder("conv-2", task2);

        // Follow-up to existing conversation should NOT be rejected
        assertDoesNotThrow(() -> smallCoordinator.submitInOrder("conv-1", followUp));
    }

    // ==================== Eager Cleanup ====================

    @Test
    @SuppressWarnings("unchecked")
    void shouldRemoveQueueAfterDrain() {
        Callable<Void> task = mock(Callable.class);
        coordinator.submitInOrder("conv-cleanup", task);

        // Queue should exist after submission
        assertTrue(coordinator.getQueueDepths().containsKey("conv-cleanup"),
                "Queue should exist after submission");

        // Simulate completion
        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> captor = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);
        verify(runtime).submitCallable(eq(task), captor.capture(), isNull());
        captor.getValue().onComplete(null);

        // After completion, queue should be removed (eager cleanup)
        assertFalse(coordinator.getQueueDepths().containsKey("conv-cleanup"),
                "Queue should be removed after draining via eager cleanup");
        // getQueueDepths() hides empty-but-present queues, so it cannot tell an
        // eagerly-removed entry from a leaked one. The map size can.
        assertEquals(0, coordinator.activeConversationCount(),
                "the drained queue's MAP ENTRY must be removed, not just emptied");
    }

    /**
     * Verifies that resubmitting to a conversation after its queue has been drained
     * (and eagerly removed) works correctly: a fresh queue is created and the new
     * task is dispatched.
     *
     * <p>
     * Note: this is a sequential test (drain completes before resubmit). It does
     * NOT exercise the true concurrent race window where submitNext() holds the
     * lock while submitInOrder() races in. That race is guarded by the CAS identity
     * check in submitInOrder's while(true) loop, which is not practically testable
     * without internal hooks.
     * </p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldHandleResubmitAfterDrain() throws Exception {
        Callable<Void> task1 = mock(Callable.class);
        Callable<Void> task2 = mock(Callable.class);

        // Submit first task
        coordinator.submitInOrder("conv-race", task1);

        // Capture the callback so we can simulate completion
        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> captor1 = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);
        verify(runtime).submitCallable(eq(task1), captor1.capture(), isNull());

        // Complete task1 — this triggers submitNext → eager cleanup
        captor1.getValue().onComplete(null);

        // Queue should now be cleaned up
        assertFalse(coordinator.getQueueDepths().containsKey("conv-race"),
                "Queue should be removed after task1 completes");

        // Submit task2 to the same conversation — should create a fresh queue
        coordinator.submitInOrder("conv-race", task2);

        // task2 should have been submitted to the runtime
        verify(runtime).submitCallable(eq(task2), any(), isNull());

        // Verify exactly one queue exists for this conversation
        assertEquals(1, coordinator.getQueueDepths().getOrDefault("conv-race", 0),
                "Should have exactly one task in the new queue");

        // Total processed should reflect task1 completion
        assertEquals(1, coordinator.getTotalProcessed());
    }

    // ==================== Helpers ====================

    @SuppressWarnings("unchecked")
    private void causeDeadLetter(String conversationId, Callable<Void> task) {
        causeDeadLetter(coordinator, runtime, conversationId, task);
    }

    private void causeDeadLetter(InMemoryConversationCoordinator coord, IRuntime rt, String conversationId, Callable<Void> task) {
        coord.submitInOrder(conversationId, task);

        // A single post-execution failure dead-letters immediately (no retry — C13).
        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> captor = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);
        verify(rt, atLeast(1)).submitCallable(eq(task), captor.capture(), isNull());

        List<IRuntime.IFinishedExecution<Void>> callbacks = captor.getAllValues();
        callbacks.get(callbacks.size() - 1).onFailure(new RuntimeException("forced failure"));
    }
}
