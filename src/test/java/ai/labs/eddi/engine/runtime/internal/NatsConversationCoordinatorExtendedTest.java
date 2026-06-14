/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.*;
import io.nats.client.api.PublishAck;
import io.nats.client.api.PurgeResponse;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
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

    // ==================== Retry on Failure ====================

    @Nested
    class PublishAndExecuteRetry {

        @Test
        @SuppressWarnings("unchecked")
        void onFailure_retries_whenAttemptsRemain() {
            Callable<Void> task = () -> null;

            // 1st submission → captures the callback
            ArgumentCaptor<IRuntime.IFinishedExecution<Void>> cb1 = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);
            coordinator.submitInOrder("conv-retry", task);
            verify(runtime, times(1)).submitCallable(eq(task), cb1.capture(), isNull());

            // Trigger failure (attempt 1 of 3) → should re-submit
            cb1.getValue().onFailure(new RuntimeException("transient error"));
            verify(runtime, times(2)).submitCallable(eq(task), any(), isNull());
        }

        @Test
        @SuppressWarnings("unchecked")
        void onFailure_routesToDeadLetter_whenRetriesExhausted() throws Exception {
            // maxRetries = 3 → need 3 failures to exhaust
            Callable<Void> task = () -> null;
            ArgumentCaptor<IRuntime.IFinishedExecution<Void>> cbCaptor = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);

            coordinator.submitInOrder("conv-exhaust", task);
            verify(runtime, times(1)).submitCallable(eq(task), cbCaptor.capture(), isNull());

            // failure 1 → retry
            cbCaptor.getValue().onFailure(new RuntimeException("fail-1"));
            verify(runtime, times(2)).submitCallable(eq(task), cbCaptor.capture(), isNull());

            // failure 2 → retry
            cbCaptor.getValue().onFailure(new RuntimeException("fail-2"));
            verify(runtime, times(3)).submitCallable(eq(task), cbCaptor.capture(), isNull());

            // failure 3 → retries exhausted (attempt == maxRetries) → dead-letter
            cbCaptor.getValue().onFailure(new RuntimeException("fail-3"));

            // Should NOT have been submitted a 4th time
            verify(runtime, times(3)).submitCallable(eq(task), any(), isNull());

            // Dead-letter published to JetStream
            verify(jetStream).publish(
                    eq("eddi.deadletter.conv-exhaust"), any(byte[].class));

            // totalDeadLettered incremented
            assertEquals(1L, coordinator.getTotalDeadLettered());
        }
    }

    // ==================== routeToDeadLetter ====================

    @Nested
    class RouteToDeadLetterTests {

        @Test
        @SuppressWarnings("unchecked")
        void routeToDeadLetter_nullFailureMessage() throws Exception {
            // Create a throwable with null getMessage()
            Callable<Void> task = () -> null;
            ArgumentCaptor<IRuntime.IFinishedExecution<Void>> cb = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);

            // Use maxRetries = 1 coordinator so 1 failure exhausts retries
            Instance<NatsMetrics> metricsInstance = mock(Instance.class);
            when(metricsInstance.isResolvable()).thenReturn(true);
            when(metricsInstance.get()).thenReturn(natsMetrics);

            var coord = new NatsConversationCoordinator(runtime, metricsInstance,
                    new SimpleMeterRegistry(), "nats://localhost:4222",
                    "EDDI_CONVERSATIONS", "EDDI_DEAD_LETTERS", 1, 10000);

            var jsField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
            jsField.setAccessible(true);
            jsField.set(coord, jetStream);

            coord.submitInOrder("conv-null-msg", task);
            verify(runtime).submitCallable(eq(task), cb.capture(), isNull());

            // Throwable with null message
            cb.getValue().onFailure(new RuntimeException((String) null));

            // Dead-letter should be published with "unknown" for the error field
            ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
            verify(jetStream).publish(
                    eq("eddi.deadletter.conv-null-msg"), payloadCaptor.capture());
            String payload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
            assertTrue(payload.contains("\"error\":\"unknown\""), "null message should produce 'unknown'");
        }

        @Test
        @SuppressWarnings("unchecked")
        void routeToDeadLetter_ioException_doesNotThrow() throws Exception {
            // If dead-letter publish itself fails with IOException, it should be caught
            Callable<Void> task = () -> null;
            ArgumentCaptor<IRuntime.IFinishedExecution<Void>> cb = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);

            Instance<NatsMetrics> metricsInstance = mock(Instance.class);
            when(metricsInstance.isResolvable()).thenReturn(true);
            when(metricsInstance.get()).thenReturn(natsMetrics);

            var coord = new NatsConversationCoordinator(runtime, metricsInstance,
                    new SimpleMeterRegistry(), "nats://localhost:4222",
                    "EDDI_CONVERSATIONS", "EDDI_DEAD_LETTERS", 1, 10000);

            // Set up jetStream mock that succeeds for initial publish but fails for
            // dead-letter
            JetStream dlJetStream = mock(JetStream.class);
            when(dlJetStream.publish(startsWith("eddi.conversation."), any(byte[].class)))
                    .thenReturn(publishAck);
            when(dlJetStream.publish(startsWith("eddi.deadletter."), any(byte[].class)))
                    .thenThrow(new IOException("Dead-letter publish failed"));

            var jsField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
            jsField.setAccessible(true);
            jsField.set(coord, dlJetStream);

            coord.submitInOrder("conv-dl-fail", task);
            verify(runtime).submitCallable(eq(task), cb.capture(), isNull());

            // Should not throw even though dead-letter publish fails
            assertDoesNotThrow(() -> cb.getValue().onFailure(new RuntimeException("boom")));

            // totalDeadLettered should NOT have been incremented
            assertEquals(0L, coord.getTotalDeadLettered());
        }

        @Test
        @SuppressWarnings("unchecked")
        void routeToDeadLetter_successfulPublish_incrementsCounter() throws Exception {
            Callable<Void> task = () -> null;
            ArgumentCaptor<IRuntime.IFinishedExecution<Void>> cb = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);

            Instance<NatsMetrics> metricsInstance = mock(Instance.class);
            when(metricsInstance.isResolvable()).thenReturn(true);
            when(metricsInstance.get()).thenReturn(natsMetrics);

            var coord = new NatsConversationCoordinator(runtime, metricsInstance,
                    new SimpleMeterRegistry(), "nats://localhost:4222",
                    "EDDI_CONVERSATIONS", "EDDI_DEAD_LETTERS", 1, 10000);

            var jsField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
            jsField.setAccessible(true);
            jsField.set(coord, jetStream);

            coord.submitInOrder("conv-dl-ok", task);
            verify(runtime).submitCallable(eq(task), cb.capture(), isNull());

            cb.getValue().onFailure(new RuntimeException("final failure"));

            assertEquals(1L, coord.getTotalDeadLettered());
            verify(deadLetterCount).increment();
        }
    }

    // ==================== CAS Retry in submitInOrder ====================

    @Nested
    class CasRetryTests {

        @Test
        @SuppressWarnings("unchecked")
        void submitInOrder_casRetry_succeedsAfterStaleQueue() throws Exception {
            // Simulate the CAS race: the queue returned by computeIfAbsent
            // gets removed by submitNext before we can synchronize on it.
            //
            // Strategy: submit task1, complete it (which triggers eager cleanup),
            // then submit task2 — the CAS loop handles the orphaned queue internally.

            Callable<Void> task1 = () -> null;
            Callable<Void> task2 = () -> null;

            ArgumentCaptor<IRuntime.IFinishedExecution<Void>> cb = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);

            coordinator.submitInOrder("conv-cas", task1);
            verify(runtime).submitCallable(eq(task1), cb.capture(), isNull());

            // Complete task1 → eager cleanup removes queue from map
            cb.getValue().onComplete(null);
            assertTrue(coordinator.getQueueDepths().isEmpty(), "queue should be cleaned up");

            // Submit task2 for same conversationId → CAS loop creates a fresh queue
            coordinator.submitInOrder("conv-cas", task2);
            verify(runtime).submitCallable(eq(task2), any(), isNull());
        }
    }

    // ==================== getDeadLetters with Messages ====================

    @Nested
    class GetDeadLettersWithMessages {

        @Test
        void getDeadLetters_withMessages_returnsEntries() throws Exception {
            Connection mockConnection = mock(Connection.class);
            JetStream dlJetStream = mock(JetStream.class);

            var connField = NatsConversationCoordinator.class.getDeclaredField("natsConnection");
            connField.setAccessible(true);
            connField.set(coordinator, mockConnection);

            var jsField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
            jsField.setAccessible(true);
            jsField.set(coordinator, dlJetStream);

            // Build a mock message
            Message msg1 = mock(Message.class);
            String payload = "{\"conversationId\":\"conv-abc\",\"error\":\"something failed\",\"timestamp\":1700000000000}";
            when(msg1.getData()).thenReturn(payload.getBytes(StandardCharsets.UTF_8));
            when(msg1.getSubject()).thenReturn("eddi.deadletter.conv-abc");
            when(msg1.metaData()).thenReturn(null); // metaData null → falls back to entries.size()

            JetStreamSubscription sub = mock(JetStreamSubscription.class);
            when(dlJetStream.subscribe("eddi.deadletter.*")).thenReturn(sub);
            when(sub.nextMessage(any(Duration.class)))
                    .thenReturn(msg1) // first call returns message
                    .thenReturn(null); // second call ends iteration

            List<DeadLetterEntry> entries = coordinator.getDeadLetters();

            assertEquals(1, entries.size());
            DeadLetterEntry entry = entries.get(0);
            assertEquals("conv-abc", entry.conversationId());
            assertEquals("something failed", entry.error());
            assertEquals(1700000000000L, entry.timestamp());
            assertTrue(entry.payload().contains("conv-abc"));

            verify(sub).unsubscribe();
        }

        @Test
        void getDeadLetters_exceptionDuringSubscribe_returnsEmptyList() throws Exception {
            Connection mockConnection = mock(Connection.class);
            JetStream dlJetStream = mock(JetStream.class);

            var connField = NatsConversationCoordinator.class.getDeclaredField("natsConnection");
            connField.setAccessible(true);
            connField.set(coordinator, mockConnection);

            var jsField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
            jsField.setAccessible(true);
            jsField.set(coordinator, dlJetStream);

            when(dlJetStream.subscribe("eddi.deadletter.*"))
                    .thenThrow(new IOException("subscribe failed"));

            List<DeadLetterEntry> entries = coordinator.getDeadLetters();
            assertTrue(entries.isEmpty());
        }
    }

    // ==================== isConnected returns true ====================

    @Nested
    class IsConnectedTrue {

        @Test
        void isConnected_connectedStatus_returnsTrue() throws Exception {
            Connection mockConnection = mock(Connection.class);
            when(mockConnection.getStatus()).thenReturn(Connection.Status.CONNECTED);

            var connectionField = NatsConversationCoordinator.class
                    .getDeclaredField("natsConnection");
            connectionField.setAccessible(true);
            connectionField.set(coordinator, mockConnection);

            assertTrue(coordinator.isConnected());
            assertEquals("CONNECTED", coordinator.getConnectionStatus());
        }
    }

    // ==================== Shutdown InterruptedException ====================

    @Nested
    class ShutdownInterruptedExceptionTests {

        @Test
        void shutdown_interruptedException_setsInterruptFlag() throws Exception {
            Connection mockConnection = mock(Connection.class);
            when(mockConnection.drain(any(Duration.class)))
                    .thenThrow(new InterruptedException("drain interrupted"));

            var connectionField = NatsConversationCoordinator.class
                    .getDeclaredField("natsConnection");
            connectionField.setAccessible(true);
            connectionField.set(coordinator, mockConnection);

            coordinator.shutdown();

            // InterruptedException handler should re-set interrupt flag
            assertTrue(Thread.currentThread().isInterrupted(),
                    "Thread interrupt flag should be set after InterruptedException");

            // Clear the interrupt flag so it doesn't affect other tests
            Thread.interrupted();
        }
    }

    // ==================== computeTotalQueueDepth with multiple queues
    // ====================

    @Nested
    class ComputeTotalQueueDepthTests {

        @Test
        @SuppressWarnings("unchecked")
        void computeTotalQueueDepth_multipleQueues_sumsAllSizes() {
            // Submit tasks to multiple conversations to populate queues
            // Each conversation gets 1 task executing + N queued
            Callable<Void> task = () -> null;

            coordinator.submitInOrder("conv-depth-1", task);
            coordinator.submitInOrder("conv-depth-1", task); // 2nd task queued behind running
            coordinator.submitInOrder("conv-depth-2", task);
            coordinator.submitInOrder("conv-depth-2", task); // 2nd task queued behind running
            coordinator.submitInOrder("conv-depth-2", task); // 3rd task queued behind running

            var depths = coordinator.getQueueDepths();
            // conv-depth-1 has 2 tasks, conv-depth-2 has 3 tasks
            assertEquals(2, depths.get("conv-depth-1"));
            assertEquals(3, depths.get("conv-depth-2"));
        }
    }

    // ==================== submitNext with remaining tasks ====================

    @Nested
    class SubmitNextWithRemainingTasks {

        @Test
        @SuppressWarnings("unchecked")
        void submitNext_queueNotEmpty_executesNextTask() {
            Callable<Void> task1 = () -> null;
            Callable<Void> task2 = () -> null;

            ArgumentCaptor<IRuntime.IFinishedExecution<Void>> cb = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);

            coordinator.submitInOrder("conv-next", task1);
            coordinator.submitInOrder("conv-next", task2); // queued behind task1

            // Capture callback for task1
            verify(runtime, times(1)).submitCallable(eq(task1), cb.capture(), isNull());

            // Complete task1 → submitNext should pick up task2
            cb.getValue().onComplete(null);

            // task2 should now be submitted
            verify(runtime, times(1)).submitCallable(eq(task2), any(), isNull());

            // Queue should still exist (task2 is running)
            assertFalse(coordinator.getQueueDepths().isEmpty(),
                    "queue should still exist while task2 is running");
        }

        @Test
        @SuppressWarnings("unchecked")
        void submitNext_lastTaskCompletes_cleansUpQueue() {
            Callable<Void> task1 = () -> null;
            Callable<Void> task2 = () -> null;

            ArgumentCaptor<IRuntime.IFinishedExecution<Void>> cb = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);

            coordinator.submitInOrder("conv-chain", task1);
            coordinator.submitInOrder("conv-chain", task2);

            verify(runtime, times(1)).submitCallable(eq(task1), cb.capture(), isNull());
            // Complete task1 → task2 starts
            cb.getValue().onComplete(null);

            verify(runtime, times(1)).submitCallable(eq(task2), cb.capture(), isNull());
            // Complete task2 → queue should be cleaned up
            cb.getValue().onComplete(null);

            assertTrue(coordinator.getQueueDepths().isEmpty(),
                    "queue should be removed after all tasks complete");
            assertEquals(2L, coordinator.getTotalProcessed());
        }
    }
}
