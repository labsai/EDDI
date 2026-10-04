/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.events;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.JetStream;
import io.nats.client.api.PublishAck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("JetStreamEventBus")
class JetStreamEventBusTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private NatsConnectionManager connections;
    private JetStreamEventBus bus;

    @BeforeEach
    void setUp() {
        connections = mock(NatsConnectionManager.class);
        when(connections.node()).thenReturn(new NodeIdentity("n1", "boot1"));
        when(connections.config()).thenReturn(ClusterConfig.defaults().withMessagingType("nats").withEventsOutboxSize(3));
        when(connections.scheduler()).thenReturn(mock(ScheduledExecutorService.class));
        when(connections.isConnected()).thenReturn(false);
        bus = new JetStreamEventBus(connections, new SimpleMeterRegistry());
        bus.startCluster();
    }

    private static byte[] event(String type, String node, String boot) throws Exception {
        return JSON.writeValueAsBytes(Map.of("v", 1, "id", "x", "type", type, "originNode", node, "originBoot", boot, "ts", 1,
                "payload", Map.of("k", "v")));
    }

    @Test
    @DisplayName("handles events from other nodes and ignores its own")
    void originFilter() throws Exception {
        List<ClusterEvent> seen = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        bus.subscribe(ClusterEvent.SECRET_CHANGED, e -> {
            seen.add(e);
            latch.countDown();
        });
        bus.receive(event(ClusterEvent.SECRET_CHANGED, "n1", "boot1"), 1);
        bus.receive(event(ClusterEvent.SECRET_CHANGED, "n2", "b2"), 2);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(1, seen.size());
        assertEquals("n2", seen.get(0).originNode());
        assertEquals("v", seen.get(0).getString("k"));
    }

    @Test
    @DisplayName("a gap in the stream sequence triggers a full local resync")
    void gapTriggersResync() throws Exception {
        CountDownLatch flushed = new CountDownLatch(1);
        bus.onResync(flushed::countDown);
        bus.receive(event("x", "n2", "b2"), 10);
        bus.receive(event("x", "n2", "b2"), 11);
        assertEquals(1, flushed.getCount(), "consecutive sequences are no gap");
        bus.receive(event("x", "n2", "b2"), 40);
        assertTrue(flushed.await(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("a resync request from another node flushes this node")
    void resyncRequest() throws Exception {
        CountDownLatch flushed = new CountDownLatch(1);
        bus.onResync(flushed::countDown);
        bus.receive(event(ClusterEvent.RESYNC_ALL, "n2", "b2"), 1);
        assertTrue(flushed.await(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("while disconnected events go to a bounded outbox, never blocking the caller")
    void outboxWhileDisconnected() {
        for (int i = 0; i < 5; i++) {
            bus.publish(ClusterEvent.CONNECTION_CHANGED, Map.of());
        }
        assertEquals(3, bus.outboxDepth(), "bounded by eddi.cluster.events.outbox-size");
    }

    @Test
    @DisplayName("an outbox flush that hits a closing connection keeps the events; flushed events count as published")
    void outboxFlushKeepsEventsOnAnyFailureAndCountsDrained() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        bus = new JetStreamEventBus(connections, registry);
        bus.startCluster();
        bus.publish(ClusterEvent.CONNECTION_CHANGED, Map.of());
        bus.publish(ClusterEvent.CONNECTION_CHANGED, Map.of());
        assertEquals(2, bus.outboxDepth());

        JetStream jetStream = mock(JetStream.class);
        when(connections.jetStream()).thenReturn(jetStream);
        when(jetStream.publish(anyString(), any(byte[].class))).thenThrow(new IllegalStateException("connection closing"));
        bus.flushOutbox();
        assertEquals(2, bus.outboxDepth(), "nothing dropped by an unchecked failure");

        JetStream healthy = mock(JetStream.class);
        when(healthy.publish(anyString(), any(byte[].class))).thenReturn(mock(PublishAck.class));
        when(connections.jetStream()).thenReturn(healthy);
        bus.flushOutbox();
        assertEquals(0, bus.outboxDepth());
        assertEquals(2.0, registry.counter("eddi.cluster.events.published", "type", ClusterEvent.CONNECTION_CHANGED).count(),
                "drained events count as published");
    }
    @Test
    @DisplayName("a connected publish counts on ack and falls back to the outbox when the ack fails")
    void connectedPublishCountsAckOrQueuesFailure() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        bus = new JetStreamEventBus(connections, registry);
        bus.startCluster();
        when(connections.isConnected()).thenReturn(true);
        JetStream jetStream = mock(JetStream.class);
        when(connections.jetStream()).thenReturn(jetStream);

        when(jetStream.publishAsync(anyString(), any(byte[].class))).thenReturn(CompletableFuture.completedFuture(mock(PublishAck.class)));
        bus.publish(ClusterEvent.CONNECTION_CHANGED, Map.of());
        assertEquals(1.0, registry.counter("eddi.cluster.events.published", "type", ClusterEvent.CONNECTION_CHANGED).count());
        assertEquals(0, bus.outboxDepth());

        when(jetStream.publishAsync(anyString(), any(byte[].class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("no ack")));
        bus.publish(ClusterEvent.CONNECTION_CHANGED, Map.of());
        assertEquals(1, bus.outboxDepth(), "a failed ack keeps the event for the next flush");
        assertEquals(1.0, registry.counter("eddi.cluster.events.published", "type", ClusterEvent.CONNECTION_CHANGED).count(),
                "a failed publish is not counted as published");
    }
}
