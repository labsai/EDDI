package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.api.PublishAck;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NatsConversationCoordinator}.
 *
 * <p>
 * Tests verify local ordering, retry/dead-letter logic, and metrics
 * without requiring a running NATS server. JetStream interactions are mocked.
 * </p>
 */
class NatsConversationCoordinatorTest {

    private IRuntime runtime;
    private JetStream jetStream;
    private PublishAck publishAck;
    private NatsConversationCoordinator coordinator;
    private NatsMetrics natsMetrics;
    private Counter publishCount;
    private Timer publishDuration;
    private Counter consumeCount;
    private Timer consumeDuration;
    private Counter deadLetterCount;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        runtime = mock(IRuntime.class);
        jetStream = mock(JetStream.class);
        publishAck = mock(PublishAck.class);

        // Mock metrics
        natsMetrics = mock(NatsMetrics.class);
        publishCount = mock(Counter.class);
        publishDuration = mock(Timer.class);
        consumeCount = mock(Counter.class);
        consumeDuration = mock(Timer.class);
        deadLetterCount = mock(Counter.class);

        when(natsMetrics.getPublishCount()).thenReturn(publishCount);
        when(natsMetrics.getPublishDuration()).thenReturn(publishDuration);
        when(natsMetrics.getConsumeCount()).thenReturn(consumeCount);
        when(natsMetrics.getConsumeDuration()).thenReturn(consumeDuration);
        when(natsMetrics.getDeadLetterCount()).thenReturn(deadLetterCount);

        Instance<NatsMetrics> metricsInstance = mock(Instance.class);
        when(metricsInstance.isResolvable()).thenReturn(true);
        when(metricsInstance.get()).thenReturn(natsMetrics);

        // Create coordinator with mocked dependencies (skip start() since that needs
        // real NATS)
        coordinator = new NatsConversationCoordinator(
                runtime,
                metricsInstance,
                "nats://localhost:4222",
                "EDDI_CONVERSATIONS",
                "EDDI_DEAD_LETTERS",
                3,
                60);

        // Inject the mocked JetStream via reflection
        var jetStreamField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
        jetStreamField.setAccessible(true);
        jetStreamField.set(coordinator, jetStream);

        when(jetStream.publish(anyString(), any(byte[].class))).thenReturn(publishAck);
        when(publishAck.getSeqno()).thenReturn(1L);
    }

    @Test
    void shouldSubmitSingleTask() throws Exception {
        Callable<Void> task = () -> null;

        coordinator.submitInOrder("conv-1", task);

        verify(runtime).submitCallable(eq(task), any(), isNull());
        verify(jetStream).publish(eq("eddi.conversation.conv-1"), any(byte[].class));
    }

    @Test
    void shouldEnqueueMultipleTasksInOrder() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        Callable<Void> task1 = () -> {
            counter.incrementAndGet();
            return null;
        };
        Callable<Void> task2 = () -> {
            counter.incrementAndGet();
            return null;
        };

        @SuppressWarnings("unchecked")
        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> callbackCaptor = ArgumentCaptor
                .forClass(IRuntime.IFinishedExecution.class);

        // Submit two tasks for the same conversation
        coordinator.submitInOrder("conv-1", task1);
        coordinator.submitInOrder("conv-1", task2);

        // Only task1 should have been submitted (task2 queued)
        verify(runtime, times(1)).submitCallable(eq(task1), callbackCaptor.capture(), isNull());

        // Simulate task1 completion
        callbackCaptor.getValue().onComplete(null);

        // Now task2 should be submitted
        verify(runtime, times(1)).submitCallable(eq(task2), any(), isNull());
    }

    @Test
    void shouldHandleMultipleConversationsIndependently() throws Exception {
        Callable<Void> taskA = () -> null;
        Callable<Void> taskB = () -> null;

        coordinator.submitInOrder("conv-A", taskA);
        coordinator.submitInOrder("conv-B", taskB);

        // Agenth submitted immediately (different conversations)
        verify(runtime).submitCallable(eq(taskA), any(), isNull());
        verify(runtime).submitCallable(eq(taskB), any(), isNull());

        // Different NATS subjects
        verify(jetStream).publish(eq("eddi.conversation.conv-A"), any(byte[].class));
        verify(jetStream).publish(eq("eddi.conversation.conv-B"), any(byte[].class));
    }

    @Test
    void shouldContinueOnNatsPublishFailure() throws Exception {
        // NATS publish fails
        when(jetStream.publish(anyString(), any(byte[].class)))
                .thenThrow(new IOException("NATS connection lost"));

        Callable<Void> task = () -> null;

        coordinator.submitInOrder("conv-1", task);

        // Task still submitted to runtime despite NATS failure
        verify(runtime).submitCallable(eq(task), any(), isNull());
    }

    @Test
    void shouldProcessNextTaskAfterFailure() throws Exception {
        Callable<Void> task1 = () -> {
            throw new RuntimeException("boom");
        };
        Callable<Void> task2 = () -> null;

        @SuppressWarnings("unchecked")
        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> callbackCaptor = ArgumentCaptor
                .forClass(IRuntime.IFinishedExecution.class);

        coordinator.submitInOrder("conv-1", task1);
        coordinator.submitInOrder("conv-1", task2);

        verify(runtime, times(1)).submitCallable(eq(task1), callbackCaptor.capture(), isNull());

        // Simulate task1 failure — first failure triggers retry, not next task
        callbackCaptor.getValue().onFailure(new RuntimeException("boom"));

        // task1 should be retried (attempt 1 of 3)
        verify(runtime, times(2)).submitCallable(eq(task1), any(), isNull());
    }

    @Test
    void shouldSanitizeSubjectTokens() throws Exception {
        Callable<Void> task = () -> null;

        coordinator.submitInOrder("conv.test 1", task);

        // Dots → dashes, spaces → underscores
        verify(jetStream).publish(eq("eddi.conversation.conv-test_1"), any(byte[].class));
    }

    @Test
    void shouldReportNotInitializedWhenNoConnection() {
        assertFalse(coordinator.isConnected());
        assertEquals("NOT_INITIALIZED", coordinator.getConnectionStatus());
    }

    @Test
    void shouldReportConnectedWhenNatsIsUp() throws Exception {
        Connection mockConnection = mock(Connection.class);
        when(mockConnection.getStatus()).thenReturn(Connection.Status.CONNECTED);

        var connectionField = NatsConversationCoordinator.class.getDeclaredField("natsConnection");
        connectionField.setAccessible(true);
        connectionField.set(coordinator, mockConnection);

        assertTrue(coordinator.isConnected());
        assertEquals("CONNECTED", coordinator.getConnectionStatus());
    }

    // ==================== Dead-Letter Tests ====================

    @Test
    @SuppressWarnings("unchecked")
    void shouldRetryTaskBeforeDeadLettering() throws Exception {
        Callable<Void> failingTask = () -> {
            throw new RuntimeException("fail");
        };

        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> callbackCaptor = ArgumentCaptor
                .forClass(IRuntime.IFinishedExecution.class);

        coordinator.submitInOrder("conv-retry", failingTask);

        // First execution
        verify(runtime, times(1)).submitCallable(eq(failingTask), callbackCaptor.capture(), isNull());

        // Simulate failure (attempt 1)
        callbackCaptor.getValue().onFailure(new RuntimeException("fail"));
        verify(runtime, times(2)).submitCallable(eq(failingTask), callbackCaptor.capture(), isNull());

        // Simulate failure (attempt 2)
        callbackCaptor.getValue().onFailure(new RuntimeException("fail"));
        verify(runtime, times(3)).submitCallable(eq(failingTask), callbackCaptor.capture(), isNull());

        // No dead-letter yet — still have 1 more attempt
        verify(jetStream, never()).publish(startsWith("eddi.deadletter."), any(byte[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldDeadLetterAfterMaxRetries() throws Exception {
        Callable<Void> failingTask = () -> {
            throw new RuntimeException("persistent failure");
        };

        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> callbackCaptor = ArgumentCaptor
                .forClass(IRuntime.IFinishedExecution.class);

        coordinator.submitInOrder("conv-dl", failingTask);

        // Exhaust all retries (maxRetries=3)
        for (int i = 0; i < coordinator.getMaxRetries(); i++) {
            verify(runtime, times(i + 1)).submitCallable(eq(failingTask), callbackCaptor.capture(), isNull());
            callbackCaptor.getValue().onFailure(new RuntimeException("persistent failure"));
        }

        // Should publish to dead-letter subject
        verify(jetStream).publish(eq("eddi.deadletter.conv-dl"), any(byte[].class));
        verify(deadLetterCount).increment();
    }

    @Test
    void shouldIncrementPublishMetricsOnSubmit() throws Exception {
        Callable<Void> task = () -> null;

        coordinator.submitInOrder("conv-metrics", task);

        // Publish metrics should be recorded
        verify(publishCount).increment();
        verify(publishDuration).record(any(java.time.Duration.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncrementConsumeMetricsOnCompletion() throws Exception {
        Callable<Void> task = () -> null;

        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> callbackCaptor = ArgumentCaptor
                .forClass(IRuntime.IFinishedExecution.class);

        coordinator.submitInOrder("conv-consume", task);
        verify(runtime).submitCallable(eq(task), callbackCaptor.capture(), isNull());

        // Simulate successful completion
        callbackCaptor.getValue().onComplete(null);

        verify(consumeCount).increment();
        verify(consumeDuration).record(any(java.time.Duration.class));
    }
}
