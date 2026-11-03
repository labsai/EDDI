/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.events;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ClusterMessageSigner;
import ai.labs.eddi.engine.cluster.ClusterSigningConfig;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import io.nats.client.impl.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2 (resync after the first subscription, recreated stream), B3 (bounded,
 * split dispatch), D7 (provision only on drift, resync jitter) and the signing
 * of events (C4).
 */
@DisplayName("JetStreamEventBus — hardening")
class JetStreamEventBusHardeningTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "0123456789abcdef0123456789abcdef-test-key";

    private NatsConnectionManager connections;
    private JetStreamManagement jsm;
    private JetStream jetStream;
    private ScheduledExecutorService scheduler;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        connections = mock(NatsConnectionManager.class);
        when(connections.node()).thenReturn(new NodeIdentity("n1", "boot1"));
        when(connections.config()).thenReturn(ClusterConfig.defaults().withMessagingType("nats"));
        scheduler = mock(ScheduledExecutorService.class);
        when(connections.scheduler()).thenReturn(scheduler);
        when(connections.isConnected()).thenReturn(true);
        Connection connection = mock(Connection.class);
        when(connection.createDispatcher()).thenReturn(mock(Dispatcher.class));
        when(connections.requireConnected()).thenReturn(connection);
        jetStream = mock(JetStream.class);
        when(connections.jetStream()).thenReturn(jetStream);
        jsm = mock(JetStreamManagement.class);
        when(connections.jetStreamManagement()).thenReturn(jsm);
        registry = new SimpleMeterRegistry();
    }

    private Runnable connectHook(JetStreamEventBus bus) {
        bus.startCluster();
        ArgumentCaptor<Runnable> hook = ArgumentCaptor.forClass(Runnable.class);
        verify(connections).onConnected(hook.capture());
        return hook.getValue();
    }

    private StreamInfo streamInfo(StreamConfiguration configuration, ZonedDateTime created) {
        StreamInfo info = mock(StreamInfo.class);
        when(info.getConfiguration()).thenReturn(configuration);
        when(info.getCreateTime()).thenReturn(created);
        return info;
    }

    private static byte[] event(String type, String node) throws Exception {
        return JSON.writeValueAsBytes(Map.of("v", 1, "id", "x", "type", type, "originNode", node, "originBoot", "b", "ts", 1,
                "payload", Map.of("k", "v")));
    }

    @Test
    @DisplayName("B2: the first subscription of a boot resyncs once; later reconnects do not")
    void firstSubscriptionResyncs() throws Exception {
        JetStreamEventBus bus = new JetStreamEventBus(connections, registry);
        StreamInfo info = streamInfo(bus.desiredStream(), ZonedDateTime.parse("2026-11-01T00:00:00Z"));
        when(jsm.getStreamInfo(anyString())).thenReturn(info);
        AtomicInteger flushes = new AtomicInteger();
        CountDownLatch flushed = new CountDownLatch(1);
        bus.onResync(() -> {
            flushes.incrementAndGet();
            flushed.countDown();
        });
        Runnable onConnected = connectHook(bus);

        onConnected.run();
        assertTrue(flushed.await(3, TimeUnit.SECONDS), "events published before the first subscription are unknown");
        onConnected.run();
        Thread.sleep(200);
        assertEquals(1, flushes.get(), "a reconnect within the retention does not flush again");
    }

    @Test
    @DisplayName("D7: the stream is updated only when its configuration differs")
    void provisionOnlyOnDrift() throws Exception {
        JetStreamEventBus bus = new JetStreamEventBus(connections, registry);
        ZonedDateTime created = ZonedDateTime.parse("2026-11-01T00:00:00Z");
        StreamInfo same = streamInfo(bus.desiredStream(), created);
        when(jsm.getStreamInfo(anyString())).thenReturn(same);
        Runnable onConnected = connectHook(bus);
        onConnected.run();
        onConnected.run();
        verify(jsm, never()).updateStream(any());

        StreamConfiguration drifted = StreamConfiguration.builder(bus.desiredStream()).maxAge(Duration.ofMinutes(5)).build();
        StreamInfo driftedInfo = streamInfo(drifted, created);
        when(jsm.getStreamInfo(anyString())).thenReturn(driftedInfo);
        onConnected.run();
        verify(jsm, times(1)).updateStream(any());
    }

    @Test
    @DisplayName("B2: a stream recreated under a connected node is re-subscribed and resynced")
    void recreatedStreamIsDetected() throws Exception {
        JetStreamEventBus bus = new JetStreamEventBus(connections, registry);
        StreamInfo original = streamInfo(bus.desiredStream(), ZonedDateTime.parse("2026-11-01T00:00:00Z"));
        when(jsm.getStreamInfo(anyString())).thenReturn(original);
        AtomicInteger flushes = new AtomicInteger();
        bus.onResync(flushes::incrementAndGet);
        connectHook(bus).run();
        waitFor(() -> flushes.get() == 1);

        StreamInfo recreated = streamInfo(bus.desiredStream(), ZonedDateTime.parse("2026-11-02T00:00:00Z"));
        when(jsm.getStreamInfo(anyString())).thenReturn(recreated);
        bus.checkStreamIdentity();

        waitFor(() -> flushes.get() == 2);
        assertEquals(2, flushes.get());
        verify(jetStream, times(2)).subscribe(anyString(), any(Dispatcher.class), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("B2: a stream sequence that goes back means the stream was recreated")
    void sequenceGoingBackResyncs() throws Exception {
        JetStreamEventBus bus = new JetStreamEventBus(connections, registry);
        bus.startCluster();
        CountDownLatch flushed = new CountDownLatch(1);
        bus.onResync(flushed::countDown);
        bus.receive(event("x", "n2"), 10);
        bus.receive(event("x", "n2"), 11);
        assertEquals(1, flushed.getCount());
        bus.receive(event("x", "n2"), 1);
        assertTrue(flushed.await(3, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("B3: a burst of cache evictions neither delays a secret change nor grows without bound")
    void dispatchIsSplitAndBounded() throws Exception {
        JetStreamEventBus bus = new JetStreamEventBus(connections, registry);
        bus.startCluster();
        CountDownLatch release = new CountDownLatch(1);
        bus.subscribe(ClusterEvent.CACHE_EVICT, e -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        CountDownLatch secret = new CountDownLatch(1);
        bus.subscribe(ClusterEvent.SECRET_CHANGED, e -> secret.countDown());
        CountDownLatch flushed = new CountDownLatch(1);
        bus.onResync(flushed::countDown);

        byte[] evict = event(ClusterEvent.CACHE_EVICT, "n2");
        for (int i = 0; i < JetStreamEventBus.LANE_CAPACITY + 5; i++) {
            bus.receive(evict, 0);
        }
        bus.receive(event(ClusterEvent.SECRET_CHANGED, "n2"), 0);

        assertTrue(secret.await(3, TimeUnit.SECONDS), "a secret change must not wait behind cache evictions");
        assertTrue(registry.counter("eddi.cluster.events.dropped").count() > 0, "the full lane drops instead of growing");
        assertTrue(flushed.await(3, TimeUnit.SECONDS), "a dropped eviction is repaired by a resync");
        release.countDown();
    }

    @Test
    @DisplayName("D7: a cluster-wide resync starts after a random delay; an administrator's own request runs at once")
    void resyncJitter() throws Exception {
        JetStreamEventBus bus = new JetStreamEventBus(connections, registry, ClusterMessageSigner.unsigned(), Duration.ofSeconds(5));
        bus.startCluster();
        AtomicInteger flushes = new AtomicInteger();
        bus.onResync(flushes::incrementAndGet);

        // A delay of 0 is possible but has a chance of 1 in 5001; retry a few times.
        boolean scheduled = false;
        for (int i = 0; i < 5 && !scheduled; i++) {
            bus.resync("cluster-wide " + i);
            try {
                verify(scheduler).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
                scheduled = true;
            } catch (AssertionError notYet) {
                waitFor(() -> flushes.get() > 0);
            }
        }
        assertTrue(scheduled, "a jittered resync goes through the scheduler");

        JetStreamEventBus admin = new JetStreamEventBus(connections, registry, ClusterMessageSigner.unsigned(), Duration.ofSeconds(5));
        AtomicInteger adminFlushes = new AtomicInteger();
        admin.onResync(adminFlushes::incrementAndGet);
        when(jetStream.publishAsync(anyString(), any(Headers.class), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        admin.requestResyncAll("admin");
        waitFor(() -> adminFlushes.get() == 1);
        assertEquals(1, adminFlushes.get());
    }

    @Test
    @DisplayName("C4: signed events are accepted once; a forged, tampered, replayed or (when required) unsigned one is dropped")
    void signedEvents() throws Exception {
        ClusterMessageSigner signer = new ClusterMessageSigner(ClusterSigningConfig.of(KEY, List.of(), false, Duration.ofSeconds(60)),
                registry);
        JetStreamEventBus bus = new JetStreamEventBus(connections, registry, signer, Duration.ZERO);
        bus.startCluster();
        List<ClusterEvent> seen = new CopyOnWriteArrayList<>();
        bus.subscribe(ClusterEvent.SECRET_CHANGED, seen::add);
        String subject = "eddi.EDDI.evt." + ClusterEvent.SECRET_CHANGED;
        byte[] data = event(ClusterEvent.SECRET_CHANGED, "n2");
        Headers headers = signer.sign(subject, data, null, ClusterEvent.VERSION);

        bus.receive(subject, headers, data, 0);
        waitFor(() -> seen.size() == 1);
        bus.receive(subject, headers, data, 0); // captured and replayed
        byte[] tampered = event(ClusterEvent.SECRET_CHANGED, "n3");
        bus.receive(subject, headers, tampered, 0);
        ClusterMessageSigner attacker = new ClusterMessageSigner(
                ClusterSigningConfig.of("another-key-that-is-long-enough-to-pass", List.of(), false, Duration.ofSeconds(60)), registry);
        bus.receive(subject, attacker.sign(subject, data, null, 1), data, 0);
        Thread.sleep(200);
        assertEquals(1, seen.size(), "only the genuine event is handled");

        bus.receive(subject, null, event(ClusterEvent.SECRET_CHANGED, "old-node"), 0);
        waitFor(() -> seen.size() == 2);
        assertEquals(2, seen.size(), "an unsigned event from an older node is accepted while signing is not required");

        ClusterMessageSigner strict = new ClusterMessageSigner(ClusterSigningConfig.of(KEY, List.of(), true, Duration.ofSeconds(60)),
                registry);
        JetStreamEventBus strictBus = new JetStreamEventBus(connections, registry, strict, Duration.ZERO);
        List<ClusterEvent> strictSeen = new CopyOnWriteArrayList<>();
        strictBus.subscribe(ClusterEvent.SECRET_CHANGED, strictSeen::add);
        strictBus.receive(subject, null, event(ClusterEvent.SECRET_CHANGED, "old-node"), 0);
        Thread.sleep(200);
        assertTrue(strictSeen.isEmpty(), "eddi.cluster.signing.required=true refuses unsigned events");
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
