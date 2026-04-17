package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        coordinator = new InMemoryConversationCoordinator(runtime, new SimpleMeterRegistry(), 10000);
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

    @Test
    @SuppressWarnings("unchecked")
    void shouldDeadLetterAfterMaxRetries() {
        Callable<Void> task = mock(Callable.class);
        coordinator.submitInOrder("conv-fail", task);

        // Simulate 3 failures (MAX_RETRIES = 3)
        for (int i = 0; i < 3; i++) {
            ArgumentCaptor<IRuntime.IFinishedExecution<Void>> callbackCaptor = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);
            verify(runtime, times(i + 1)).submitCallable(eq(task), callbackCaptor.capture(), isNull());

            List<IRuntime.IFinishedExecution<Void>> callbacks = callbackCaptor.getAllValues();
            callbacks.get(i).onFailure(new RuntimeException("Test failure " + (i + 1)));
        }

        // Should be dead-lettered after 3 retries
        assertEquals(1, coordinator.getTotalDeadLettered());
        assertEquals(1, coordinator.getDeadLetters().size());

        DeadLetterEntry entry = coordinator.getDeadLetters().get(0);
        assertEquals("conv-fail", entry.conversationId());
        assertTrue(entry.error().contains("Test failure 3"));
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
        var smallCoordinator = new InMemoryConversationCoordinator(runtime, new SimpleMeterRegistry(), 2);

        Callable<Void> task1 = mock(Callable.class);
        Callable<Void> task2 = mock(Callable.class);
        Callable<Void> task3 = mock(Callable.class);

        smallCoordinator.submitInOrder("conv-1", task1);
        smallCoordinator.submitInOrder("conv-2", task2);

        // Third NEW conversation should be rejected
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> smallCoordinator.submitInOrder("conv-3", task3));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldAllowFollowUpToExistingConversationAtCapacity() {
        // Create coordinator with maxActiveConversations=2
        var smallCoordinator = new InMemoryConversationCoordinator(runtime, new SimpleMeterRegistry(), 2);

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
        coordinator.submitInOrder(conversationId, task);

        // Simulate MAX_RETRIES (3) failures
        for (int i = 0; i < 3; i++) {
            ArgumentCaptor<IRuntime.IFinishedExecution<Void>> captor = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);
            verify(runtime, atLeast(1)).submitCallable(eq(task), captor.capture(), isNull());

            List<IRuntime.IFinishedExecution<Void>> callbacks = captor.getAllValues();
            callbacks.get(callbacks.size() - 1).onFailure(new RuntimeException("forced failure"));
        }
    }
}
