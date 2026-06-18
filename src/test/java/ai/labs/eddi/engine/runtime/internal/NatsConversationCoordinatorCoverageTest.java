/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.*;
import io.nats.client.api.PublishAck;
import io.nats.client.api.PurgeResponse;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Coverage tests for {@link NatsConversationCoordinator} targeting remaining
 * uncovered instructions and branches.
 */
@DisplayName("NatsConversationCoordinator — Coverage Tests")
class NatsConversationCoordinatorCoverageTest {

    private IRuntime runtime;
    private JetStream jetStream;
    private PublishAck publishAck;
    private NatsConversationCoordinator coordinator;
    private NatsMetrics natsMetrics;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        runtime = mock(IRuntime.class);
        jetStream = mock(JetStream.class);
        publishAck = mock(PublishAck.class);

        natsMetrics = mock(NatsMetrics.class);
        doReturn(mock(Counter.class)).when(natsMetrics).getPublishCount();
        doReturn(mock(Timer.class)).when(natsMetrics).getPublishDuration();
        doReturn(mock(Counter.class)).when(natsMetrics).getConsumeCount();
        doReturn(mock(Timer.class)).when(natsMetrics).getConsumeDuration();
        doReturn(mock(Counter.class)).when(natsMetrics).getDeadLetterCount();

        Instance<NatsMetrics> metricsInstance = mock(Instance.class);
        doReturn(true).when(metricsInstance).isResolvable();
        doReturn(natsMetrics).when(metricsInstance).get();

        coordinator = new NatsConversationCoordinator(runtime, metricsInstance,
                new SimpleMeterRegistry(), "nats://localhost:4222",
                "EDDI_CONVERSATIONS", "EDDI_DEAD_LETTERS", 3, 10000);

        var jsField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
        jsField.setAccessible(true);
        jsField.set(coordinator, jetStream);

        doReturn(publishAck).when(jetStream).publish(anyString(), any(byte[].class));
        doReturn(1L).when(publishAck).getSeqno();
    }

    private void setNatsConnection(Connection conn) throws Exception {
        var connField = NatsConversationCoordinator.class.getDeclaredField("natsConnection");
        connField.setAccessible(true);
        connField.set(coordinator, conn);
    }

    // ==================== isConnected ====================

    @Nested
    @DisplayName("isConnected")
    class IsConnectedTests {

        @Test
        @DisplayName("returns false when natsConnection is null")
        void nullConnection() throws Exception {
            setNatsConnection(null);
            assertFalse(coordinator.isConnected());
        }

        @Test
        @DisplayName("returns true when status is CONNECTED")
        void connectedStatus() throws Exception {
            Connection conn = mock(Connection.class);
            doReturn(Connection.Status.CONNECTED).when(conn).getStatus();
            setNatsConnection(conn);
            assertTrue(coordinator.isConnected());
        }

        @Test
        @DisplayName("returns false when status is not CONNECTED")
        void disconnectedStatus() throws Exception {
            Connection conn = mock(Connection.class);
            doReturn(Connection.Status.CLOSED).when(conn).getStatus();
            setNatsConnection(conn);
            assertFalse(coordinator.isConnected());
        }
    }

    // ==================== getConnectionStatus ====================

    @Nested
    @DisplayName("getConnectionStatus")
    class GetConnectionStatusTests {

        @Test
        @DisplayName("returns NOT_INITIALIZED when natsConnection is null")
        void nullConnectionStatus() throws Exception {
            setNatsConnection(null);
            assertEquals("NOT_INITIALIZED", coordinator.getConnectionStatus());
        }

        @Test
        @DisplayName("returns status name when connection exists")
        void connectedStatusName() throws Exception {
            Connection conn = mock(Connection.class);
            doReturn(Connection.Status.CONNECTED).when(conn).getStatus();
            setNatsConnection(conn);
            assertEquals("CONNECTED", coordinator.getConnectionStatus());
        }
    }

    // ==================== getCoordinatorType ====================

    @Test
    @DisplayName("getCoordinatorType returns 'nats'")
    void getCoordinatorType() throws Exception {
        assertEquals("nats", coordinator.getCoordinatorType());
    }

    // ==================== getMaxRetries ====================

    @Test
    @DisplayName("getMaxRetries returns configured value")
    void getMaxRetries() throws Exception {
        assertEquals(3, coordinator.getMaxRetries());
    }

    // ==================== getTotalProcessed / getTotalDeadLettered
    // ====================

    @Test
    @DisplayName("getTotalProcessed starts at 0")
    void getTotalProcessed() throws Exception {
        assertEquals(0, coordinator.getTotalProcessed());
    }

    @Test
    @DisplayName("getTotalDeadLettered starts at 0")
    void getTotalDeadLettered() throws Exception {
        assertEquals(0, coordinator.getTotalDeadLettered());
    }

    // ==================== sanitizeSubject ====================

    @Test
    @DisplayName("sanitizeSubject replaces dots with dashes and spaces with underscores")
    void sanitizeSubject() throws Exception {
        assertEquals("conv-1-2", coordinator.sanitizeSubject("conv.1.2"));
        assertEquals("conv_1", coordinator.sanitizeSubject("conv 1"));
        assertEquals("conv-1_2", coordinator.sanitizeSubject("conv.1 2"));
    }

    // ==================== shutdown ====================

    @Test
    @DisplayName("shutdown with null natsConnection does nothing")
    void shutdownNullConnection() throws Exception {
        setNatsConnection(null);
        assertDoesNotThrow(() -> coordinator.shutdown());
    }

    // ==================== discardDeadLetter ====================

    @Test
    @DisplayName("discardDeadLetter always returns true")
    void discardDeadLetter() throws Exception {
        assertTrue(coordinator.discardDeadLetter("entry-1"));
    }

    // ==================== getDeadLetters ====================

    @Test
    @DisplayName("getDeadLetters returns empty list when natsConnection is null")
    void getDeadLettersNullConnection() throws Exception {
        setNatsConnection(null);
        var entries = coordinator.getDeadLetters();
        assertTrue(entries.isEmpty());
    }

    @Test
    @DisplayName("getDeadLetters returns empty list when jetStream is null")
    void getDeadLettersNullJetStream() throws Exception {
        Connection conn = mock(Connection.class);
        setNatsConnection(conn);
        var jsField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
        jsField.setAccessible(true);
        jsField.set(coordinator, null);

        var entries = coordinator.getDeadLetters();
        assertTrue(entries.isEmpty());
    }

    // ==================== purgeDeadLetters ====================

    @Nested
    @DisplayName("purgeDeadLetters")
    class PurgeDeadLettersTests {

        @Test
        @DisplayName("returns 0 when natsConnection is null")
        void nullConnection() throws Exception {
            setNatsConnection(null);
            assertEquals(0, coordinator.purgeDeadLetters());
        }

        @Test
        @DisplayName("returns purge count on success")
        void successfulPurge() throws Exception {
            Connection conn = mock(Connection.class);
            setNatsConnection(conn);

            JetStreamManagement jsm = mock(JetStreamManagement.class);
            doReturn(jsm).when(conn).jetStreamManagement();

            PurgeResponse response = mock(PurgeResponse.class);
            doReturn(5L).when(response).getPurged();
            doReturn(response).when(jsm).purgeStream("EDDI_DEAD_LETTERS");

            assertEquals(5, coordinator.purgeDeadLetters());
        }

        @Test
        @DisplayName("returns 0 when IOException occurs")
        void ioExceptionReturnsZero() throws Exception {
            Connection conn = mock(Connection.class);
            setNatsConnection(conn);

            doThrow(new IOException("purge fail")).when(conn).jetStreamManagement();

            assertEquals(0, coordinator.purgeDeadLetters());
        }
    }

    // ==================== submitInOrder — capacity exceeded ====================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("submitInOrder rejects new conversation when capacity exceeded")
    void submitInOrderCapacityExceeded() throws Exception {
        Instance<NatsMetrics> metricsInstance = mock(Instance.class);
        doReturn(true).when(metricsInstance).isResolvable();
        doReturn(natsMetrics).when(metricsInstance).get();

        // maxActiveConversations=1
        var coord = new NatsConversationCoordinator(runtime, metricsInstance,
                new SimpleMeterRegistry(), "nats://localhost:4222",
                "EDDI_CONVERSATIONS", "EDDI_DEAD_LETTERS", 3, 1);

        var jsField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
        jsField.setAccessible(true);
        jsField.set(coord, jetStream);

        // First submission fills the slot
        coord.submitInOrder("conv-first", () -> null);

        // Second new conversation should be rejected
        assertThrows(RejectedExecutionException.class,
                () -> coord.submitInOrder("conv-second", () -> null));
    }

    // ==================== extractTimestamp edge cases ====================

    @Nested
    @DisplayName("extractTimestamp edge cases")
    class ExtractTimestampTests {

        @Test
        @DisplayName("returns 0 when timestamp key is missing")
        void missingTimestamp() throws Exception {
            var method = NatsConversationCoordinator.class
                    .getDeclaredMethod("extractTimestamp", String.class);
            method.setAccessible(true);

            String json = "{\"error\":\"some error\"}";
            assertEquals(0L, method.invoke(coordinator, json));
        }

        @Test
        @DisplayName("returns 0 when timestamp value is not a number")
        void nonNumericTimestamp() throws Exception {
            var method = NatsConversationCoordinator.class
                    .getDeclaredMethod("extractTimestamp", String.class);
            method.setAccessible(true);

            String json = "{\"timestamp\":abc}";
            assertEquals(0L, method.invoke(coordinator, json));
        }

        @Test
        @DisplayName("parses valid timestamp")
        void validTimestamp() throws Exception {
            var method = NatsConversationCoordinator.class
                    .getDeclaredMethod("extractTimestamp", String.class);
            method.setAccessible(true);

            String json = "{\"timestamp\":1700000000}";
            assertEquals(1700000000L, method.invoke(coordinator, json));
        }
    }

    // ==================== extractField — missing key ====================

    @Test
    @DisplayName("extractField returns 'unknown' when key is not present")
    void extractFieldMissingKey() throws Exception {
        var method = NatsConversationCoordinator.class
                .getDeclaredMethod("extractField", String.class, String.class);
        method.setAccessible(true);

        String json = "{\"other\":\"value\"}";
        assertEquals("unknown", method.invoke(coordinator, json, "error"));
    }

    // ==================== getMetrics — not resolvable ====================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("getMetrics returns empty when metricsInstance is not resolvable")
    void getMetricsNotResolvable() throws Exception {
        Instance<NatsMetrics> metricsInstance = mock(Instance.class);
        doReturn(false).when(metricsInstance).isResolvable();

        var coord = new NatsConversationCoordinator(runtime, metricsInstance,
                new SimpleMeterRegistry(), "nats://localhost:4222",
                "EDDI_CONVERSATIONS", "EDDI_DEAD_LETTERS", 3, 10000);

        var jsField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
        jsField.setAccessible(true);
        jsField.set(coord, jetStream);

        // Submit and trigger onComplete — if metrics are not resolvable,
        // recordConsumeMetrics should not throw
        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> cb = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);

        coord.submitInOrder("conv-metrics", () -> null);
        verify(runtime).submitCallable(any(Callable.class), cb.capture(), isNull());
        assertDoesNotThrow(() -> cb.getValue().onComplete(null));
    }

    // ==================== routeToDeadLetter — null message ====================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("routeToDeadLetter handles null error message")
    void routeToDeadLetterNullMessage() throws Exception {
        Instance<NatsMetrics> metricsInstance = mock(Instance.class);
        doReturn(true).when(metricsInstance).isResolvable();
        doReturn(natsMetrics).when(metricsInstance).get();

        var coord = new NatsConversationCoordinator(runtime, metricsInstance,
                new SimpleMeterRegistry(), "nats://localhost:4222",
                "EDDI_CONVERSATIONS", "EDDI_DEAD_LETTERS", 1, 10000);

        var jsField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
        jsField.setAccessible(true);
        jsField.set(coord, jetStream);

        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> cb = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);

        coord.submitInOrder("conv-null-msg", () -> null);
        verify(runtime).submitCallable(any(Callable.class), cb.capture(), isNull());

        // Throwable with null message
        cb.getValue().onFailure(new RuntimeException((String) null));

        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(jetStream).publish(eq("eddi.deadletter.conv-null-msg"), payloadCaptor.capture());
        String payload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
        assertTrue(payload.contains("unknown"),
                "Null error message should be replaced with 'unknown', got: " + payload);
    }

    // ==================== routeToDeadLetter — IOException path
    // ====================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("routeToDeadLetter catches IOException during publish")
    void routeToDeadLetterIOException() throws Exception {
        Instance<NatsMetrics> metricsInstance = mock(Instance.class);
        doReturn(true).when(metricsInstance).isResolvable();
        doReturn(natsMetrics).when(metricsInstance).get();

        JetStream failingJs = mock(JetStream.class);
        // First call for publish succeeds, second call for dead letter fails
        doReturn(publishAck).when(failingJs).publish(startsWith("eddi.conversation."), any(byte[].class));
        doThrow(new IOException("dead letter fail")).when(failingJs).publish(startsWith("eddi.deadletter."), any(byte[].class));

        var coord = new NatsConversationCoordinator(runtime, metricsInstance,
                new SimpleMeterRegistry(), "nats://localhost:4222",
                "EDDI_CONVERSATIONS", "EDDI_DEAD_LETTERS", 1, 10000);

        var jsField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
        jsField.setAccessible(true);
        jsField.set(coord, failingJs);

        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> cb = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);

        coord.submitInOrder("conv-dl-fail", () -> null);
        verify(runtime).submitCallable(any(Callable.class), cb.capture(), isNull());

        // Should not throw even though dead letter publish fails
        assertDoesNotThrow(() -> cb.getValue().onFailure(new RuntimeException("test")));
    }

    // ==================== RetryableCallable ====================

    @Test
    @DisplayName("RetryableCallable tracks attempt count")
    void retryableCallable() throws Exception {
        var rc = new NatsConversationCoordinator.RetryableCallable(() -> null);
        assertEquals(0, rc.getAttempt());
        assertEquals(1, rc.incrementAndGetAttempt());
        assertEquals(1, rc.getAttempt());
        assertEquals(2, rc.incrementAndGetAttempt());
        assertNotNull(rc.callable());
    }

    // ==================== getDeadLetters — exception during subscribe
    // ====================

    @Test
    @DisplayName("getDeadLetters handles subscription exception gracefully")
    void getDeadLettersSubscriptionException() throws Exception {
        Connection conn = mock(Connection.class);
        setNatsConnection(conn);

        JetStream failingJs = mock(JetStream.class);
        doThrow(new IOException("subscribe fail")).when(failingJs).subscribe(anyString());

        var jsField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
        jsField.setAccessible(true);
        jsField.set(coordinator, failingJs);

        var entries = coordinator.getDeadLetters();
        assertTrue(entries.isEmpty());
    }

    // ==================== getDeadLetters — null metaData ====================

    @Test
    @DisplayName("getDeadLetters uses fallback ID when metaData is null")
    void getDeadLettersNullMetaData() throws Exception {
        Connection conn = mock(Connection.class);
        setNatsConnection(conn);

        Message msg = mock(Message.class);
        String payload = "{\"conversationId\":\"conv-1\",\"error\":\"err\",\"timestamp\":1700000000}";
        doReturn(payload.getBytes(StandardCharsets.UTF_8)).when(msg).getData();
        doReturn("eddi.deadletter.conv-1").when(msg).getSubject();
        doReturn(null).when(msg).metaData();

        JetStreamSubscription sub = mock(JetStreamSubscription.class);
        doReturn(sub).when(jetStream).subscribe("eddi.deadletter.*");
        doReturn(msg).doReturn(null).when(sub).nextMessage(any(Duration.class));

        var entries = coordinator.getDeadLetters();
        assertEquals(1, entries.size());
        assertEquals("0", entries.getFirst().id()); // fallback: entries.size() = 0 at time of creation
    }

    // ==================== publishAndExecute — NATS publish IOException
    // ====================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("submitInOrder proceeds even when NATS publish throws IOException")
    void publishIOExceptionFallsThrough() throws Exception {
        JetStream failingJs = mock(JetStream.class);
        doThrow(new IOException("publish fail")).when(failingJs).publish(anyString(), any(byte[].class));

        var jsField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
        jsField.setAccessible(true);
        jsField.set(coordinator, failingJs);

        // Should not throw — falls through to local execution
        coordinator.submitInOrder("conv-pub-fail", () -> null);
        verify(runtime).submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), isNull());
    }
}
