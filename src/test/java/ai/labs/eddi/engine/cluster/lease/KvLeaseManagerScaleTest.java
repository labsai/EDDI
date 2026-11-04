/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.lease;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ISharedKv;
import ai.labs.eddi.engine.cluster.InMemorySharedKv;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import ai.labs.eddi.engine.cluster.lease.KvLeaseManagerTest.Bus;
import ai.labs.eddi.engine.cluster.lease.KvLeaseManagerTest.FlakyKv;
import ai.labs.eddi.engine.cluster.lease.KvLeaseManagerTest.View;
import io.micrometer.core.instrument.search.RequiredSearch;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round trips and fan-out of the lease protocol (review findings D1, D2, D4,
 * D5, D7, and the eager registration of C12).
 */
@DisplayName("KvLeaseManager — round trips and scale")
class KvLeaseManagerScaleTest {

    private ScheduledExecutorService scheduler;
    private ExecutorService io;
    private InMemorySharedKv kv;
    private Bus bus;
    private View view;
    private ClusterConfig config;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(2);
        io = Executors.newCachedThreadPool();
        kv = new InMemorySharedKv("LEASES", Duration.ofSeconds(20));
        bus = new Bus();
        view = new View();
        meters = new SimpleMeterRegistry();
        config = ClusterConfig.defaults().withMessagingType("nats")
                .withLeaseTimings(Duration.ofSeconds(4), Duration.ofSeconds(1), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        io.shutdownNow();
    }

    private KvLeaseManager manager(ISharedKv store, String node, String boot) {
        view.live.put(node, boot);
        return new KvLeaseManager(store, new NodeIdentity(node, boot), config, scheduler, io, bus, view, meters);
    }

    private static LeaseHandle get(CompletionStage<LeaseHandle> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /** Counts every call to the store by operation. */
    static final class CountingKv extends FlakyKv {
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        CountingKv(ISharedKv delegate) {
            super(delegate, new AtomicBoolean());
        }

        private void count(String op) {
            calls.computeIfAbsent(op, k -> new AtomicInteger()).incrementAndGet();
        }

        int total() {
            return calls.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        void reset() {
            calls.clear();
        }

        @Override
        public OptionalLong create(String key, byte[] value) {
            count("create");
            return super.create(key, value);
        }

        @Override
        public Optional<Versioned> get(String key) {
            count("get");
            return super.get(key);
        }

        @Override
        public Optional<Versioned> getConsistent(String key) {
            count("getConsistent");
            return super.getConsistent(key);
        }

        @Override
        public Latest latest(String key) {
            count("latest");
            return super.latest(key);
        }

        @Override
        public OptionalLong update(String key, byte[] value, long expectedRevision) {
            count("update");
            return super.update(key, value, expectedRevision);
        }

        @Override
        public long put(String key, byte[] value) {
            count("put");
            return super.put(key, value);
        }

        @Override
        public boolean delete(String key, long expectedRevision) {
            count("delete");
            return super.delete(key, expectedRevision);
        }

        @Override
        public OptionalLong deleteReturningRevision(String key, long expectedRevision) {
            count("deleteReturningRevision");
            return super.deleteReturningRevision(key, expectedRevision);
        }

        @Override
        public List<Entry> entries(String prefix, int max) {
            count("entries");
            return super.entries(prefix, max);
        }
    }

    @Test
    @DisplayName("D1: an uncontended turn on a conversation released before costs three KV round trips, none of them a marker read")
    void uncontendedTurnRoundTrips() throws Exception {
        CountingKv counting = new CountingKv(kv);
        KvLeaseManager a = manager(counting, "a", "a1");
        KvLeaseManager b = manager(counting, "b", "b1");
        a.release(get(a.acquire("conv1", Duration.ofSeconds(1)))); // an earlier turn, on another node
        counting.reset();

        LeaseHandle lease = get(b.acquire("conv1", Duration.ofSeconds(1)));
        b.release(lease);

        // acquire: read the delete marker + write at its revision; release: one guarded
        // delete
        assertEquals(3, counting.total(), "calls: " + counting.calls);
        assertNull(counting.calls.get("get"), "no waiter-marker read on release");
        assertTrue(kv.get("c.conv1").isEmpty());
    }

    @Test
    @DisplayName("D1/D7: a waiter woken by the holder's addressed release acquires with a single write")
    void wokenWaiterAcquiresInOneWrite() throws Exception {
        CountingKv counting = new CountingKv(kv);
        KvLeaseManager a = manager(counting, "a", "a1");
        KvLeaseManager b = manager(counting, "b", "b1");
        LeaseHandle first = get(a.acquire("conv1", Duration.ofSeconds(1)));
        var waiting = b.acquire("conv1", Duration.ofSeconds(5)).toCompletableFuture();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (kv.get("w.c.conv1").isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        counting.reset();
        a.release(first);
        assertNotNull(waiting.get(3, TimeUnit.SECONDS));
        // release: 1 guarded delete; b: 1 write at the delete marker's revision (+
        // removing its marker)
        assertEquals(1, counting.calls.get("deleteReturningRevision").get());
        assertEquals(1, counting.calls.get("update").get(), "the hint saved the read: " + counting.calls);
        assertNull(counting.calls.get("latest"), "no read needed: " + counting.calls);
    }

    @Test
    @DisplayName("D7: a release is addressed only to the node waiting for it — nothing is broadcast to the cluster")
    void releaseIsAddressedToTheWaitingNode() throws Exception {
        KvLeaseManager a = manager(kv, "a", "a1");
        KvLeaseManager b = manager(kv, "b", "b1");
        manager(kv, "c", "c1");
        LeaseHandle first = get(a.acquire("conv1", Duration.ofSeconds(1)));
        var waiting = b.acquire("conv1", Duration.ofSeconds(5)).toCompletableFuture();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (kv.get("w.c.conv1").isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        a.release(first);
        assertNotNull(waiting.get(3, TimeUnit.SECONDS));
        assertEquals(0, bus.broadcasts.get(), "a plain release broadcasts nothing");
        assertEquals(1, bus.releasesTo("b"));
        assertEquals(0, bus.releasesTo("c"));

        LeaseHandle second = get(a.acquire("conv2", Duration.ofSeconds(1)));
        a.release(second);
        assertEquals(0, bus.broadcasts.get(), "nobody waited: nothing is sent at all");
    }

    @Test
    @DisplayName("D2: a healthy lease nobody else waits for is handed to the next turn, reset for it")
    void handOverAHealthyLease() throws Exception {
        KvLeaseManager a = manager(kv, "a", "a1");
        LeaseHandle lease = get(a.acquire("conv1", Duration.ofSeconds(1)));
        AtomicBoolean firstTurnCallback = new AtomicBoolean();
        lease.onLost(() -> firstTurnCallback.set(true));

        assertTrue(a.handOver(lease));
        assertEquals(1, a.heldCount(), "still held");
        assertTrue(kv.get("c.conv1").isPresent(), "not released on the server");
        kv.delete("c.conv1");
        kv.create("c.conv1", "{\"node\":\"z\",\"boot\":\"z1\",\"since\":1}".getBytes());
        a.heartbeatAll();
        assertTrue(lease.isLost());
        assertFalse(firstTurnCallback.get(), "the finished turn's callbacks were dropped at the handoff");
        assertFalse(a.handOver(lease), "a lost lease is never handed over");
    }

    @Test
    @DisplayName("D2: no handoff while another node waits for the lease, and none past the limit in a row")
    void handOverRefusals() throws Exception {
        KvLeaseManager a = manager(kv, "a", "a1");
        KvLeaseManager b = manager(kv, "b", "b1");
        LeaseHandle lease = get(a.acquire("conv1", Duration.ofSeconds(1)));
        for (int i = 0; i < KvLeaseManager.MAX_HANDOFFS; i++) {
            assertTrue(a.handOver(lease));
        }
        assertFalse(a.handOver(lease), "a busy node must let the lease go now and then");

        LeaseHandle other = get(a.acquire("conv2", Duration.ofSeconds(1)));
        var waiting = b.acquire("conv2", Duration.ofSeconds(5)).toCompletableFuture();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (kv.get("w.c.conv2").isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertFalse(a.handOver(other), "b is waiting: the lease goes to b, not to a's next turn");
        a.release(other);
        assertNotNull(waiting.get(3, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("D4: renewals are bounded — never more than the limit in flight, however many leases are held")
    void renewalsAreBounded() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        ISharedKv slow = new FlakyKv(kv, new AtomicBoolean()) {
            @Override
            public OptionalLong update(String key, byte[] value, long expectedRevision) {
                int now = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                inFlight.decrementAndGet();
                return super.update(key, value, expectedRevision);
            }
        };
        KvLeaseManager a = manager(slow, "a", "a1");
        List<LeaseHandle> leases = new ArrayList<>();
        int count = KvLeaseManager.MAX_CONCURRENT_RENEWALS * 3;
        for (int i = 0; i < count; i++) {
            leases.add(get(a.acquire("conv" + i, Duration.ofSeconds(1))));
        }
        a.heartbeatAll();
        assertTrue(maxInFlight.get() <= KvLeaseManager.MAX_CONCURRENT_RENEWALS, "in flight at once: " + maxInFlight.get());
        assertTrue(maxInFlight.get() > 1, "still side by side");
        assertTrue(leases.stream().noneMatch(LeaseHandle::isLost));
    }

    @Test
    @DisplayName("D5: the sweep for an earlier boot's leases runs once per process, not on every reconnect")
    void sweepRunsOnce() {
        kv.create("c.old", "{\"node\":\"a\",\"boot\":\"a0\",\"since\":1}".getBytes());
        CountingKv counting = new CountingKv(kv);
        KvLeaseManager a = manager(counting, "a", "a1");
        a.sweepOwnStaleLeases();
        assertTrue(kv.get("c.old").isEmpty());
        assertEquals(1, counting.calls.get("entries").get(), "one listing, not a read per key");
        assertNull(counting.calls.get("get"));
        a.sweepOwnStaleLeases(); // a reconnect
        assertEquals(1, counting.calls.get("entries").get(), "a reconnect cannot add leases of an earlier boot");
    }

    @Test
    @DisplayName("C12: lease meters exist with every tag value before anything happened")
    void metersAreRegisteredEagerly() {
        manager(kv, "a", "a1");
        RequiredSearch timeout = meters.get("eddi.cluster.lease.acquire").tag("outcome", "timeout").tag("contended", "true");
        assertEquals(0, timeout.timer().count());
        for (String reason : KvLeaseManager.LOST_REASONS) {
            assertEquals(0, meters.get("eddi.cluster.lease.lost").tag("reason", reason).counter().count());
        }
        assertEquals(0, meters.get("eddi.cluster.degraded.decisions").tag("action", "reject").counter().count());
        assertNotNull(meters.find("eddi.cluster.lease.handoffs").counter());
    }
}
