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
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.api.PublishAck;
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
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Additional branch coverage for {@link NatsConversationCoordinator}:
 * <ul>
 * <li>getDeadLetters with metaData non-null → uses streamSequence</li>
 * <li>extractField — end <= start → returns "unknown"</li>
 * <li>shutdown TimeoutException path</li>
 * <li>computeTotalQueueDepth with active queues</li>
 * <li>routeToDeadLetter message escaping (quotes in error)</li>
 * </ul>
 */
@DisplayName("NatsConversationCoordinator — Additional Branch Coverage")
class NatsConversationCoordinatorBranchTest {

    private IRuntime runtime;
    private JetStream jetStream;
    private PublishAck publishAck;
    private NatsConversationCoordinator coordinator;
    private NatsMetrics natsMetrics;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        runtime = mock(IRuntime.class);
        jetStream = mock(JetStream.class);
        publishAck = mock(PublishAck.class);

        natsMetrics = mock(NatsMetrics.class);
        when(natsMetrics.getPublishCount()).thenReturn(mock(Counter.class));
        when(natsMetrics.getPublishDuration()).thenReturn(mock(Timer.class));
        when(natsMetrics.getConsumeCount()).thenReturn(mock(Counter.class));
        when(natsMetrics.getConsumeDuration()).thenReturn(mock(Timer.class));
        when(natsMetrics.getDeadLetterCount()).thenReturn(mock(Counter.class));

        Instance<NatsMetrics> metricsInstance = mock(Instance.class);
        when(metricsInstance.isResolvable()).thenReturn(true);
        when(metricsInstance.get()).thenReturn(natsMetrics);

        coordinator = new NatsConversationCoordinator(runtime, metricsInstance,
                new SimpleMeterRegistry(), "nats://localhost:4222",
                "EDDI_CONVERSATIONS", "EDDI_DEAD_LETTERS", 3, 10000);

        var jsField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
        jsField.setAccessible(true);
        jsField.set(coordinator, jetStream);

        when(jetStream.publish(anyString(), any(byte[].class))).thenReturn(publishAck);
        when(publishAck.getSeqno()).thenReturn(1L);
    }

    // ==================== getDeadLetters with metaData non-null
    // ====================

    @Nested
    @DisplayName("getDeadLetters metaData")
    class GetDeadLettersMetaData {

        @Test
        @DisplayName("metaData non-null — uses streamSequence for entryId")
        void metaDataNonNull() throws Exception {
            Connection mockConn = mock(Connection.class);
            var connField = NatsConversationCoordinator.class.getDeclaredField("natsConnection");
            connField.setAccessible(true);
            connField.set(coordinator, mockConn);

            JetStream dlJs = mock(JetStream.class);
            var jsField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
            jsField.setAccessible(true);
            jsField.set(coordinator, dlJs);

            Message msg = mock(Message.class);
            String payload = "{\"conversationId\":\"conv-1\",\"error\":\"some error\",\"timestamp\":1700000000}";
            when(msg.getData()).thenReturn(payload.getBytes(StandardCharsets.UTF_8));
            when(msg.getSubject()).thenReturn("eddi.deadletter.conv-1");

            io.nats.client.impl.NatsJetStreamMetaData metaData = mock(io.nats.client.impl.NatsJetStreamMetaData.class);
            when(metaData.streamSequence()).thenReturn(42L);
            when(msg.metaData()).thenReturn(metaData);

            JetStreamSubscription sub = mock(JetStreamSubscription.class);
            when(dlJs.subscribe("eddi.deadletter.*")).thenReturn(sub);
            when(sub.nextMessage(any(Duration.class)))
                    .thenReturn(msg)
                    .thenReturn(null);

            var entries = coordinator.getDeadLetters();
            assertEquals(1, entries.size());
            assertEquals("42", entries.getFirst().id());
        }
    }

    // ==================== shutdown TimeoutException ====================

    @Nested
    @DisplayName("shutdown edge cases")
    class ShutdownEdgeCases {

        @Test
        @DisplayName("shutdown — TimeoutException is caught and logged")
        void shutdownTimeoutException() throws Exception {
            Connection mockConn = mock(Connection.class);
            when(mockConn.drain(any(Duration.class))).thenThrow(new TimeoutException("drain timeout"));

            var connField = NatsConversationCoordinator.class.getDeclaredField("natsConnection");
            connField.setAccessible(true);
            connField.set(coordinator, mockConn);

            assertDoesNotThrow(() -> coordinator.shutdown());
        }
    }

    // ==================== extractField — end <= start → "unknown"
    // ====================

    @Nested
    @DisplayName("extractField edge cases")
    class ExtractFieldEdgeCases {

        @Test
        @DisplayName("extractField — value is empty string → returns unknown")
        void emptyValue() throws Exception {
            var method = NatsConversationCoordinator.class
                    .getDeclaredMethod("extractField", String.class, String.class);
            method.setAccessible(true);

            // The JSON has error:"" → end equals start → returns "unknown"
            String json = "{\"error\":\"\"}";
            assertEquals("unknown", method.invoke(coordinator, json, "error"));
        }
    }

    // ==================== computeTotalQueueDepth ====================

    @Test
    @DisplayName("computeTotalQueueDepth — returns sum of queue sizes")
    void computeTotalQueueDepth() throws Exception {
        // Submit tasks to create queue entries
        Callable<Void> task1 = () -> null;
        Callable<Void> task2 = () -> null;

        coordinator.submitInOrder("conv-1", task1);
        coordinator.submitInOrder("conv-1", task2);

        // The queue for conv-1 should have 2 entries
        var method = NatsConversationCoordinator.class
                .getDeclaredMethod("computeTotalQueueDepth", java.util.Map.class);
        method.setAccessible(true);

        var queuesField = NatsConversationCoordinator.class.getDeclaredField("conversationQueues");
        queuesField.setAccessible(true);
        var queues = queuesField.get(coordinator);

        double depth = (double) method.invoke(coordinator, queues);
        assertTrue(depth >= 1, "Queue depth should be at least 1, got: " + depth);
    }

    // ==================== routeToDeadLetter with quotes in error
    // ====================

    @Nested
    @DisplayName("routeToDeadLetter edge cases")
    class RouteToDeadLetterEdgeCases {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("error message with quotes is escaped in dead-letter payload")
        void errorWithQuotes() throws Exception {
            Instance<NatsMetrics> metricsInstance = mock(Instance.class);
            when(metricsInstance.isResolvable()).thenReturn(true);
            when(metricsInstance.get()).thenReturn(natsMetrics);

            var coord = new NatsConversationCoordinator(runtime, metricsInstance,
                    new SimpleMeterRegistry(), "nats://localhost:4222",
                    "EDDI_CONVERSATIONS", "EDDI_DEAD_LETTERS", 1, 10000);

            var jsField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
            jsField.setAccessible(true);
            jsField.set(coord, jetStream);

            Callable<Void> task = () -> null;
            ArgumentCaptor<IRuntime.IFinishedExecution<Void>> cb = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);

            coord.submitInOrder("conv-quotes", task);
            verify(runtime).submitCallable(eq(task), cb.capture(), isNull());

            // Error with quotes in message
            cb.getValue().onFailure(new RuntimeException("fail with \"quotes\""));

            // Verify the payload was published and the quotes are escaped
            ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
            verify(jetStream).publish(eq("eddi.deadletter.conv-quotes"), payloadCaptor.capture());
            String payload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
            assertTrue(payload.contains("\\\"quotes\\\""),
                    "Quotes should be escaped, got: " + payload);
        }
    }

    // ==================== getQueueDepths with non-empty and empty queues
    // ====================

    @Nested
    @DisplayName("getQueueDepths filtering")
    class QueueDepthFiltering {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("completed queue (size 0) — not included in depths")
        void completedQueueNotIncluded() throws Exception {
            Callable<Void> task = () -> null;
            ArgumentCaptor<IRuntime.IFinishedExecution<Void>> cb = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);

            coordinator.submitInOrder("conv-depth", task);
            verify(runtime).submitCallable(eq(task), cb.capture(), isNull());

            // Before completion, queue has the task
            var depthsBefore = coordinator.getQueueDepths();

            // Complete it — queue is removed
            cb.getValue().onComplete(null);

            var depthsAfter = coordinator.getQueueDepths();
            assertFalse(depthsAfter.containsKey("conv-depth"),
                    "Completed queue should be removed from depths");
        }
    }
}
