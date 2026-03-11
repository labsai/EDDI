package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.runtime.IRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InMemoryConversationCoordinator}.
 *
 * <p>Tests verify ordering, retry/dead-letter logic, counters,
 * and dead-letter CRUD operations.</p>
 */
class InMemoryConversationCoordinatorTest {

    private IRuntime runtime;
    private InMemoryConversationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        runtime = mock(IRuntime.class);
        coordinator = new InMemoryConversationCoordinator(runtime);
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
        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> callbackCaptor =
                ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);
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
            ArgumentCaptor<IRuntime.IFinishedExecution<Void>> callbackCaptor =
                    ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);
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

    // ==================== Helpers ====================

    @SuppressWarnings("unchecked")
    private void causeDeadLetter(String conversationId, Callable<Void> task) {
        coordinator.submitInOrder(conversationId, task);

        // Simulate MAX_RETRIES (3) failures
        for (int i = 0; i < 3; i++) {
            ArgumentCaptor<IRuntime.IFinishedExecution<Void>> captor =
                    ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);
            verify(runtime, atLeast(1)).submitCallable(eq(task), captor.capture(), isNull());

            List<IRuntime.IFinishedExecution<Void>> callbacks = captor.getAllValues();
            callbacks.get(callbacks.size() - 1).onFailure(new RuntimeException("forced failure"));
        }
    }
}
