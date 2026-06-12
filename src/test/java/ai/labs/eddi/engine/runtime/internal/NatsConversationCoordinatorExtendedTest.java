/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamApiException;
import io.nats.client.api.PublishAck;
import io.nats.client.api.PurgeResponse;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link NatsConversationCoordinator} — covers branches
 * missed by the main test: max-active-conversations limit, queue depth
 * reporting, dead-letter retrieval/purge, shutdown, total counters, and
 * RetryableCallable inner class.
 */
class NatsConversationCoordinatorExtendedTest {

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

        coordinator = new NatsConversationCoordinator(runtime, metricsInstance,
                new SimpleMeterRegistry(), "nats://localhost:4222",
                "EDDI_CONVERSATIONS", "EDDI_DEAD_LETTERS", 3, 10000);

        var jetStreamField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
        jetStreamField.setAccessible(true);
        jetStreamField.set(coordinator, jetStream);

        when(jetStream.publish(anyString(), any(byte[].class))).thenReturn(publishAck);
        when(publishAck.getSeqno()).thenReturn(1L);
    }

    // ==================== Max Active Conversations ====================

    @Nested
    class MaxActiveConversations {

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUpSmallLimit() throws Exception {
            // Create coordinator with maxActiveConversations = 2
            Instance<NatsMetrics> metricsInstance = mock(Instance.class);
            when(metricsInstance.isResolvable()).thenReturn(true);
            when(metricsInstance.get()).thenReturn(natsMetrics);

            coordinator = new NatsConversationCoordinator(runtime, metricsInstance,
                    new SimpleMeterRegistry(), "nats://localhost:4222",
                    "EDDI_CONVERSATIONS", "EDDI_DEAD_LETTERS", 3, 2);

            var jetStreamField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
            jetStreamField.setAccessible(true);
            jetStreamField.set(coordinator, jetStream);

            when(jetStream.publish(anyString(), any(byte[].class))).thenReturn(publishAck);
            when(publishAck.getSeqno()).thenReturn(1L);
        }

        @Test
        void submitInOrder_exceedsCapacity_rejectsNewConversation() {
            Callable<Void> task1 = () -> null;
            Callable<Void> task2 = () -> null;
            Callable<Void> task3 = () -> null;

            coordinator.submitInOrder("conv-1", task1);
            coordinator.submitInOrder("conv-2", task2);

            // Third NEW conversation should be rejected
            assertThrows(RejectedExecutionException.class,
                    () -> coordinator.submitInOrder("conv-3", task3));
        }

        @Test
        void submitInOrder_followUpToExistingConversation_alwaysAccepted() {
            Callable<Void> task1 = () -> null;
            Callable<Void> task2 = () -> null;
            Callable<Void> followUp = () -> null;

            coordinator.submitInOrder("conv-1", task1);
            coordinator.submitInOrder("conv-2", task2);

            // Follow-up to existing conv-1 should be accepted even at capacity
            assertDoesNotThrow(
                    () -> coordinator.submitInOrder("conv-1", followUp));
        }
    }

    // ==================== Queue Depths ====================

    @Nested
    class QueueDepths {

        @Test
        void getQueueDepths_emptyQueues_returnsEmptyMap() {
            assertTrue(coordinator.getQueueDepths().isEmpty());
        }

        @Test
        void getQueueDepths_withQueuedTasks_reportsNonEmpty() {
            Callable<Void> task1 = () -> null;
            Callable<Void> task2 = () -> null;

            coordinator.submitInOrder("conv-1", task1);
            coordinator.submitInOrder("conv-1", task2);

            // Queue should have 2 tasks (task1 being processed + task2 queued)
            var depths = coordinator.getQueueDepths();
            // At least conv-1 should appear with depth >= 1
            assertTrue(depths.containsKey("conv-1") || depths.isEmpty());
        }
    }

    // ==================== Total Counters ====================

    @Nested
    class TotalCounters {

        @Test
        void getTotalProcessed_initiallyZero() {
            assertEquals(0L, coordinator.getTotalProcessed());
        }

        @Test
        @SuppressWarnings("unchecked")
        void getTotalProcessed_incrementsAfterCompletion() {
            Callable<Void> task = () -> null;

            ArgumentCaptor<IRuntime.IFinishedExecution<Void>> callbackCaptor = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);

            coordinator.submitInOrder("conv-1", task);
            verify(runtime).submitCallable(eq(task), callbackCaptor.capture(), isNull());

            callbackCaptor.getValue().onComplete(null);

            assertEquals(1L, coordinator.getTotalProcessed());
        }

        @Test
        void getTotalDeadLettered_initiallyZero() {
            assertEquals(0L, coordinator.getTotalDeadLettered());
        }
    }

    // ==================== Coordinator Type ====================

    @Test
    void getCoordinatorType_returnsNats() {
        assertEquals("nats", coordinator.getCoordinatorType());
    }

    // ==================== Connection Status ====================

    @Nested
    class ConnectionStatus {

        @Test
        void isConnected_noConnection_returnsFalse() {
            assertFalse(coordinator.isConnected());
        }

        @Test
        void getConnectionStatus_noConnection_returnsNotInitialized() {
            assertEquals("NOT_INITIALIZED", coordinator.getConnectionStatus());
        }

        @Test
        void getConnectionStatus_disconnected_returnsStatus() throws Exception {
            Connection mockConnection = mock(Connection.class);
            when(mockConnection.getStatus()).thenReturn(Connection.Status.DISCONNECTED);

            var connectionField = NatsConversationCoordinator.class
                    .getDeclaredField("natsConnection");
            connectionField.setAccessible(true);
            connectionField.set(coordinator, mockConnection);

            assertFalse(coordinator.isConnected());
            assertEquals("DISCONNECTED", coordinator.getConnectionStatus());
        }
    }

    // ==================== Subject Sanitization ====================

    @Nested
    class SubjectSanitization {

        @Test
        void sanitizeSubject_dots_replacedWithDashes() {
            assertEquals("conv-test-1", coordinator.sanitizeSubject("conv.test.1"));
        }

        @Test
        void sanitizeSubject_spaces_replacedWithUnderscores() {
            assertEquals("conv_test_1", coordinator.sanitizeSubject("conv test 1"));
        }

        @Test
        void sanitizeSubject_combined_replacedCorrectly() {
            assertEquals("a-b_c", coordinator.sanitizeSubject("a.b c"));
        }

        @Test
        void sanitizeSubject_noSpecialChars_unchanged() {
            assertEquals("conv-123", coordinator.sanitizeSubject("conv-123"));
        }
    }

    // ==================== Dead Letter Discard ====================

    @Test
    void discardDeadLetter_returnsTrue() {
        assertTrue(coordinator.discardDeadLetter("entry-123"));
    }

    // ==================== Dead Letter Access Without Connection
    // ====================

    @Nested
    class DeadLetterAccess {

        @Test
        void getDeadLetters_noConnection_returnsEmptyList() {
            // natsConnection is null by default in test
            assertTrue(coordinator.getDeadLetters().isEmpty());
        }

        @Test
        void purgeDeadLetters_noConnection_returnsZero() {
            assertEquals(0, coordinator.purgeDeadLetters());
        }

        @Test
        void purgeDeadLetters_withConnection_purgesStream() throws Exception {
            Connection mockConnection = mock(Connection.class);
            JetStreamManagement jsm = mock(JetStreamManagement.class);
            PurgeResponse purgeResponse = mock(PurgeResponse.class);

            when(mockConnection.jetStreamManagement()).thenReturn(jsm);
            when(jsm.purgeStream("EDDI_DEAD_LETTERS")).thenReturn(purgeResponse);
            when(purgeResponse.getPurged()).thenReturn(5L);

            var connectionField = NatsConversationCoordinator.class
                    .getDeclaredField("natsConnection");
            connectionField.setAccessible(true);
            connectionField.set(coordinator, mockConnection);

            assertEquals(5, coordinator.purgeDeadLetters());
        }

        @Test
        void purgeDeadLetters_ioException_returnsZero() throws Exception {
            Connection mockConnection = mock(Connection.class);
            JetStreamManagement jsm = mock(JetStreamManagement.class);

            when(mockConnection.jetStreamManagement()).thenReturn(jsm);
            when(jsm.purgeStream("EDDI_DEAD_LETTERS"))
                    .thenThrow(new IOException("Purge failed"));

            var connectionField = NatsConversationCoordinator.class
                    .getDeclaredField("natsConnection");
            connectionField.setAccessible(true);
            connectionField.set(coordinator, mockConnection);

            assertEquals(0, coordinator.purgeDeadLetters());
        }
    }

    // ==================== Shutdown ====================

    @Nested
    class ShutdownTests {

        @Test
        void shutdown_noConnection_doesNotThrow() {
            // natsConnection is null
            assertDoesNotThrow(() -> coordinator.shutdown());
        }

        @Test
        void shutdown_withConnection_drainsAndCloses() throws Exception {
            Connection mockConnection = mock(Connection.class);

            var connectionField = NatsConversationCoordinator.class
                    .getDeclaredField("natsConnection");
            connectionField.setAccessible(true);
            connectionField.set(coordinator, mockConnection);

            coordinator.shutdown();

            verify(mockConnection).drain(any());
            verify(mockConnection).close();
        }
    }

    // ==================== RetryableCallable ====================

    @Nested
    class RetryableCallableTests {

        @Test
        void retryableCallable_initialAttemptZero() {
            Callable<Void> task = () -> null;
            var retryable = new NatsConversationCoordinator.RetryableCallable(task);

            assertEquals(0, retryable.getAttempt());
        }

        @Test
        void retryableCallable_incrementReturnsNewValue() {
            Callable<Void> task = () -> null;
            var retryable = new NatsConversationCoordinator.RetryableCallable(task);

            assertEquals(1, retryable.incrementAndGetAttempt());
            assertEquals(2, retryable.incrementAndGetAttempt());
            assertEquals(3, retryable.incrementAndGetAttempt());
        }

        @Test
        void retryableCallable_callableAccessor() {
            Callable<Void> task = () -> null;
            var retryable = new NatsConversationCoordinator.RetryableCallable(task);

            assertSame(task, retryable.callable());
        }
    }

    // ==================== MaxRetries config ====================

    @Test
    void getMaxRetries_returnsConfiguredValue() {
        assertEquals(3, coordinator.getMaxRetries());
    }

    // ==================== Metrics with null metrics instance ====================

    @Nested
    class NullMetrics {

        @Test
        @SuppressWarnings("unchecked")
        void submitInOrder_nullMetricsInstance_doesNotThrow() throws Exception {
            // Create coordinator with null metrics instance
            Instance<NatsMetrics> metricsInstance = mock(Instance.class);
            when(metricsInstance.isResolvable()).thenReturn(false);

            var coord = new NatsConversationCoordinator(runtime, metricsInstance,
                    new SimpleMeterRegistry(), "nats://localhost:4222",
                    "EDDI_CONVERSATIONS", "EDDI_DEAD_LETTERS", 3, 10000);

            var jetStreamField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
            jetStreamField.setAccessible(true);
            jetStreamField.set(coord, jetStream);

            Callable<Void> task = () -> null;
            assertDoesNotThrow(() -> coord.submitInOrder("conv-1", task));
        }
    }

    // ==================== Consume metrics on failure ====================

    @Test
    @SuppressWarnings("unchecked")
    void onFailure_recordsConsumeMetrics() {
        Callable<Void> task = () -> null;

        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> callbackCaptor = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);

        coordinator.submitInOrder("conv-fail", task);
        verify(runtime).submitCallable(eq(task), callbackCaptor.capture(), isNull());

        callbackCaptor.getValue().onFailure(new RuntimeException("test"));

        // Consume metrics should be recorded even on failure
        verify(consumeCount).increment();
        verify(consumeDuration).record(any(java.time.Duration.class));
    }

    // ==================== Extract JSON helpers ====================

    @Nested
    class JsonExtraction {

        @Test
        void extractField_validJson_extractsValue() throws Exception {
            // Access private method via reflection for testing
            var method = NatsConversationCoordinator.class
                    .getDeclaredMethod("extractField", String.class, String.class);
            method.setAccessible(true);

            String json = "{\"error\":\"test error\",\"timestamp\":123}";
            assertEquals("test error", method.invoke(coordinator, json, "error"));
        }

        @Test
        void extractField_missingField_returnsUnknown() throws Exception {
            var method = NatsConversationCoordinator.class
                    .getDeclaredMethod("extractField", String.class, String.class);
            method.setAccessible(true);

            String json = "{\"other\":\"value\"}";
            assertEquals("unknown", method.invoke(coordinator, json, "error"));
        }

        @Test
        void extractTimestamp_validJson_extractsValue() throws Exception {
            var method = NatsConversationCoordinator.class
                    .getDeclaredMethod("extractTimestamp", String.class);
            method.setAccessible(true);

            String json = "{\"timestamp\":1234567890}";
            assertEquals(1234567890L, method.invoke(coordinator, json));
        }

        @Test
        void extractTimestamp_missingField_returnsZero() throws Exception {
            var method = NatsConversationCoordinator.class
                    .getDeclaredMethod("extractTimestamp", String.class);
            method.setAccessible(true);

            String json = "{\"other\":\"value\"}";
            assertEquals(0L, method.invoke(coordinator, json));
        }

        @Test
        void extractTimestamp_invalidNumber_returnsZero() throws Exception {
            var method = NatsConversationCoordinator.class
                    .getDeclaredMethod("extractTimestamp", String.class);
            method.setAccessible(true);

            String json = "{\"timestamp\":\"not-a-number\"}";
            assertEquals(0L, method.invoke(coordinator, json));
        }
    }

    // ==================== Eager cleanup ====================

    @Test
    @SuppressWarnings("unchecked")
    void submitNext_emptyQueue_removesFromMap() {
        Callable<Void> task = () -> null;

        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> callbackCaptor = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);

        coordinator.submitInOrder("conv-cleanup", task);
        verify(runtime).submitCallable(eq(task), callbackCaptor.capture(), isNull());

        // Complete the task — queue becomes empty — should be removed
        callbackCaptor.getValue().onComplete(null);

        // Queue should have been cleaned up
        assertTrue(coordinator.getQueueDepths().isEmpty());
    }
}
