package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for InMemoryConversationCoordinator, focusing on: - L3 fix: Race
 * condition in submitInOrder (synchronized isEmpty+offer+submit) - Sequential
 * ordering guarantee per conversation - Concurrent conversations handled
 * independently
 */
class ConversationCoordinatorTest {

    private IRuntime runtime;
    private InMemoryConversationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        runtime = mock(IRuntime.class);
        coordinator = new InMemoryConversationCoordinator(runtime, new SimpleMeterRegistry(), 10000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitInOrder_firstMessage_submittedImmediately() {
        // Given: a new conversation with no queued messages
        Callable<Void> callable = mock(Callable.class);

        // When: first message submitted
        coordinator.submitInOrder("conv-1", callable);

        // Then: should immediately submit to runtime
        verify(runtime, times(1)).submitCallable(eq(callable), any(IRuntime.IFinishedExecution.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitInOrder_secondMessage_notSubmittedImmediately() {
        // Given: first message already submitted
        Callable<Void> firstCallable = mock(Callable.class);
        Callable<Void> secondCallable = mock(Callable.class);

        coordinator.submitInOrder("conv-1", firstCallable);

        // When: second message submitted while first is still processing
        coordinator.submitInOrder("conv-1", secondCallable);

        // Then: runtime should only have been called once (for the first message)
        verify(runtime, times(1)).submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitInOrder_differentConversations_agenthSubmittedImmediately() {
        // Given: two different conversations
        Callable<Void> callable1 = mock(Callable.class);
        Callable<Void> callable2 = mock(Callable.class);

        // When: messages from different conversations submitted
        coordinator.submitInOrder("conv-1", callable1);
        coordinator.submitInOrder("conv-2", callable2);

        // Then: both should be submitted to runtime immediately
        verify(runtime, times(2)).submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitInOrder_afterCompletion_nextMessageSubmitted() {
        // Given: first message submitted, capture the completion callback
        Callable<Void> firstCallable = mock(Callable.class);
        Callable<Void> secondCallable = mock(Callable.class);

        // Capture the IFinishedExecution callback
        var callbackCaptor = org.mockito.ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);

        coordinator.submitInOrder("conv-1", firstCallable);
        verify(runtime).submitCallable(eq(firstCallable), callbackCaptor.capture(), any());

        // Submit second message (gets queued)
        coordinator.submitInOrder("conv-1", secondCallable);

        // When: first message completes
        IRuntime.IFinishedExecution<Void> callback = callbackCaptor.getValue();
        callback.onComplete(null);

        // Then: second message should now be submitted
        verify(runtime, times(2)).submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitInOrder_afterFailure_nextMessageStillSubmitted() {
        // Given: first message submitted
        Callable<Void> firstCallable = mock(Callable.class);
        Callable<Void> secondCallable = mock(Callable.class);

        var callbackCaptor = org.mockito.ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);

        coordinator.submitInOrder("conv-1", firstCallable);
        verify(runtime).submitCallable(eq(firstCallable), callbackCaptor.capture(), any());

        // Submit second message (gets queued)
        coordinator.submitInOrder("conv-1", secondCallable);

        // When: first message fails
        IRuntime.IFinishedExecution<Void> callback = callbackCaptor.getValue();
        callback.onFailure(new RuntimeException("Test failure"));

        // Then: second message should still be submitted (error doesn't block queue)
        verify(runtime, times(2)).submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitInOrder_concurrentSubmissions_onlyOneSubmittedToRuntime() throws Exception {
        // L3 fix test: Concurrent submissions should not both be submitted to
        // runtime.
        // We simulate concurrent access by using threads with a latch.

        int concurrentCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentCount);
        AtomicInteger submitCount = new AtomicInteger(0);

        // Mock runtime to count submissions
        when(runtime.submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any())).thenAnswer(inv -> {
            submitCount.incrementAndGet();
            return mock(Future.class);
        });

        // Launch concurrent submissions for the same conversation
        for (int i = 0; i < concurrentCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await(); // All threads start at once
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Callable<Void> callable = () -> null;
                coordinator.submitInOrder("conv-race", callable);
                doneLatch.countDown();
            }).start();
        }

        // Release all threads simultaneously
        startLatch.countDown();

        // Wait for all to finish
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Threads should complete within timeout");

        // Then: only ONE message should be submitted to runtime (the first one)
        // The rest should be queued and submitted sequentially via callbacks.
        assertEquals(1, submitCount.get(), "With L3 fix, only one concurrent submission should reach runtime.submitCallable");
    }
}
