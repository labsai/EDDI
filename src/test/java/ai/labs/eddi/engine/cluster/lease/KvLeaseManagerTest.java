/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.lease;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.ISharedKv;
import ai.labs.eddi.engine.cluster.InMemorySharedKv;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KvLeaseManager")
class KvLeaseManagerTest {

    /**
     * Delivers release notifications to every manager sharing it, like core NATS.
     */
    static final class Bus implements KvLeaseManager.LeaseNotifier {
        final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
        volatile boolean deliver = true;

        @Override
        public void publishReleased(String key) {
            if (deliver) {
                listeners.forEach(l -> l.accept(key));
            }
        }

        @Override
        public void onReleased(Consumer<String> listener) {
            listeners.add(listener);
        }
    }

    static final class View implements KvLeaseManager.ClusterView {
        volatile boolean connected = true;
        volatile boolean degraded;
        volatile boolean presenceDown;
        final Map<String, String> live = new ConcurrentHashMap<>();

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public boolean isDegraded() {
            return degraded;
        }

        @Override
        public Optional<String> liveBoot(String nodeId) {
            if (presenceDown) {
                throw new ClusterUnavailableException("presence bucket unreachable");
            }
            return Optional.ofNullable(live.get(nodeId));
        }
    }

    private ScheduledExecutorService scheduler;
    private ExecutorService io;
    private InMemorySharedKv kv;
    private Bus bus;
    private View view;
    private ClusterConfig config;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(2);
        io = Executors.newCachedThreadPool();
        kv = new InMemorySharedKv("LEASES", Duration.ofSeconds(20));
        bus = new Bus();
        view = new View();
        config = ClusterConfig.defaults().withMessagingType("nats")
                .withLeaseTimings(Duration.ofMillis(400), Duration.ofMillis(100), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        io.shutdownNow();
    }

    private KvLeaseManager manager(String node, String boot) {
        return manager(kv, node, boot);
    }

    private KvLeaseManager manager(ISharedKv store, String node, String boot) {
        view.live.put(node, boot);
        return new KvLeaseManager(store, new NodeIdentity(node, boot), config, scheduler, io, bus, view, new SimpleMeterRegistry());
    }

    private static LeaseHandle get(CompletionStage<LeaseHandle> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a free lease is granted with the KV revision as fencing token")
    void freeLeaseIsGrantedWithFence() throws Exception {
        KvLeaseManager a = manager("a", "a1");
        LeaseHandle lease = get(a.acquire("conv1", Duration.ofSeconds(1)));
        assertNotNull(lease.fence());
        assertEquals("conv1", lease.conversationId());
        assertEquals(1, a.heldCount());
        assertEquals("a", a.peek("conv1").orElseThrow().node());
        a.release(lease);
        assertEquals(0, a.heldCount());
        assertTrue(a.peek("conv1").isEmpty());
    }

    @Test
    @DisplayName("a held lease parks the second node, which wakes on the release notification with a larger fence")
    void contendedLeaseWakesOnRelease() throws Exception {
        KvLeaseManager a = manager("a", "a1");
        KvLeaseManager b = manager("b", "b1");
        LeaseHandle first = get(a.acquire("conv1", Duration.ofSeconds(1)));
        var waiting = b.acquire("conv1", Duration.ofSeconds(5)).toCompletableFuture();
        Thread.sleep(50);
        assertFalse(waiting.isDone(), "b must wait while a holds the lease");
        long releasedAt = System.nanoTime();
        a.release(first);
        LeaseHandle second = waiting.get(5, TimeUnit.SECONDS);
        // The poll interval is ttl/4 = 100 ms ± jitter; the notification makes it
        // immediate.
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - releasedAt) < 80, "woken by the notification, not the poll");
        assertTrue(second.fence() > first.fence(), "fencing tokens only grow");
    }

    @Test
    @DisplayName("without a notification the waiter still gets the lease once it is released (poll)")
    void pollWakesWithoutNotification() throws Exception {
        bus.deliver = false;
        KvLeaseManager a = manager("a", "a1");
        KvLeaseManager b = manager("b", "b1");
        LeaseHandle first = get(a.acquire("conv1", Duration.ofSeconds(1)));
        var waiting = b.acquire("conv1", Duration.ofSeconds(5)).toCompletableFuture();
        a.release(first);
        assertNotNull(waiting.get(2, TimeUnit.SECONDS).fence());
    }

    @Test
    @DisplayName("a crashed holder's lease expires on the server and the waiter takes it")
    void crashedHolderExpires() throws Exception {
        AtomicReference<Long> now = new AtomicReference<>(0L);
        InMemorySharedKv clocked = new InMemorySharedKv("LEASES", Duration.ofMillis(400), now::get);
        KvLeaseManager a = manager(clocked, "a", "a1");
        KvLeaseManager b = manager(clocked, "b", "b1");
        get(a.acquire("conv1", Duration.ofSeconds(1)));
        // a "crashes": it never heartbeats and never releases (start() is not called)
        var waiting = b.acquire("conv1", Duration.ofSeconds(5)).toCompletableFuture();
        Thread.sleep(150);
        assertFalse(waiting.isDone());
        now.set(1_000L); // server-side TTL passed
        assertNotNull(waiting.get(3, TimeUnit.SECONDS).fence());
    }

    @Test
    @DisplayName("a holder whose presence is gone (dead node) is taken over at once")
    void deadHolderIsTakenOver() throws Exception {
        // A lease written long ago by a node that is not present any more.
        String stale = "{\"node\":\"ghost\",\"boot\":\"g1\",\"since\":1}";
        OptionalLong rev = kv.create("c.conv1", stale.getBytes(StandardCharsets.UTF_8));
        assertTrue(rev.isPresent());
        KvLeaseManager b = manager("b", "b1");
        LeaseHandle lease = get(b.acquire("conv1", Duration.ofSeconds(2)));
        assertTrue(lease.wasTakenOver());
        assertTrue(lease.fence() > rev.getAsLong());
    }

    @Test
    @DisplayName("a live holder with the boot it registered in presence is never taken over")
    void liveHolderIsNotTakenOver() throws Exception {
        KvLeaseManager a = manager("a", "a1");
        KvLeaseManager b = manager("b", "b1");
        get(a.acquire("conv1", Duration.ofSeconds(1)));
        var waiting = b.acquire("conv1", Duration.ofMillis(300)).toCompletableFuture();
        ExecutionException e = assertThrows(ExecutionException.class, () -> waiting.get(3, TimeUnit.SECONDS));
        LeaseUnavailableException cause = assertInstanceOf(LeaseUnavailableException.class, e.getCause());
        assertEquals(LeaseUnavailableException.Reason.TIMEOUT, cause.reason());
        assertEquals("a", cause.holderNode());
    }

    @Test
    @DisplayName("a lease taken over while held fires onLost at the next heartbeat")
    void heartbeatConflictFiresOnLost() throws Exception {
        KvLeaseManager a = manager("a", "a1");
        LeaseHandle lease = get(a.acquire("conv1", Duration.ofSeconds(1)));
        AtomicBoolean lost = new AtomicBoolean();
        lease.onLost(() -> lost.set(true));
        // Somebody else rewrote the key (e.g. it expired during a long GC pause and was
        // re-acquired).
        kv.delete("c.conv1");
        kv.create("c.conv1", "{\"node\":\"b\",\"boot\":\"b1\",\"since\":1}".getBytes(StandardCharsets.UTF_8));
        a.heartbeatAll();
        assertTrue(lost.get());
        assertTrue(lease.isLost());
        assertEquals(0, a.heldCount());
    }

    @Test
    @DisplayName("a heartbeat that cannot reach NATS keeps the lease (the DB fence decides)")
    void heartbeatIoFailureKeepsLease() throws Exception {
        InMemorySharedKv backing = new InMemorySharedKv("LEASES", Duration.ofSeconds(20));
        AtomicBoolean down = new AtomicBoolean();
        ISharedKv flaky = new FlakyKv(backing, down);
        KvLeaseManager a = manager(flaky, "a", "a1");
        LeaseHandle lease = get(a.acquire("conv1", Duration.ofSeconds(1)));
        down.set(true);
        a.heartbeatAll();
        assertFalse(lease.isLost());
        assertEquals(1, a.heldCount());
    }

    @Test
    @DisplayName("the boot sweep deletes only this node's leases from an earlier boot")
    void bootSweepDeletesOnlyOwnStaleBoots() {
        kv.create("c.mine-old", "{\"node\":\"a\",\"boot\":\"old\",\"since\":1}".getBytes(StandardCharsets.UTF_8));
        kv.create("c.other", "{\"node\":\"b\",\"boot\":\"b1\",\"since\":1}".getBytes(StandardCharsets.UTF_8));
        KvLeaseManager a = manager("a", "new");
        kv.create("c.mine-current", "{\"node\":\"a\",\"boot\":\"new\",\"since\":1}".getBytes(StandardCharsets.UTF_8));
        a.sweepOwnStaleLeases();
        assertTrue(kv.get("c.mine-old").isEmpty());
        assertTrue(kv.get("c.other").isPresent());
        assertTrue(kv.get("c.mine-current").isPresent());
    }

    @Test
    @DisplayName("after releasing to another node's waiter, the holder yields its next acquire for the handoff grace")
    void handoffGraceYieldsToOtherNode() throws Exception {
        config = ClusterConfig.defaults().withMessagingType("nats")
                .withLeaseTimings(Duration.ofSeconds(2), Duration.ofMillis(500), Duration.ofSeconds(5));
        KvLeaseManager a = manager("a", "a1");
        KvLeaseManager b = manager("b", "b1");
        LeaseHandle first = get(a.acquire("conv1", Duration.ofSeconds(1)));
        var bWaiting = b.acquire("conv1", Duration.ofSeconds(5)).toCompletableFuture();
        // wait until b has registered its waiter marker
        long deadline = System.currentTimeMillis() + 2000;
        while (kv.get("w.c.conv1").isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(kv.get("w.c.conv1").isPresent());
        bus.deliver = false; // let b win only through fairness, not luck
        a.release(first);
        var aAgain = a.acquire("conv1", Duration.ofSeconds(5)).toCompletableFuture();
        bus.deliver = true;
        bus.publishReleased("c.conv1");
        LeaseHandle bLease = bWaiting.get(3, TimeUnit.SECONDS);
        assertNotNull(bLease);
        assertFalse(aAgain.isDone(), "a must not re-acquire ahead of the waiting node");
        b.release(bLease);
        assertNotNull(aAgain.get(3, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("acquire never touches the KV on the caller's thread")
    void acquireDoesNoIoOnCallerThread() throws Exception {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> ioThread = new AtomicReference<>();
        ISharedKv recording = new FlakyKv(kv, new AtomicBoolean()) {
            @Override
            public OptionalLong create(String key, byte[] value) {
                ioThread.set(Thread.currentThread());
                return super.create(key, value);
            }
        };
        KvLeaseManager a = manager(recording, "a", "a1");
        get(a.acquire("conv1", Duration.ofSeconds(1)));
        assertNotNull(ioThread.get());
        assertNotSame(caller, ioThread.get());
    }

    @Test
    @DisplayName("degraded with turns=local: an unfenced lease is granted at once")
    void degradedLocalGrantsUnfenced() throws Exception {
        view.connected = false;
        view.degraded = true;
        KvLeaseManager a = manager("a", "a1");
        LeaseHandle lease = get(a.acquire("conv1", Duration.ofSeconds(1)));
        assertNull(lease.fence());
    }

    @Test
    @DisplayName("degraded with turns=reject: the acquire fails with DEGRADED")
    void degradedRejectFails() {
        config = config.withDegradedTurns(ClusterConfig.REJECT);
        view.connected = false;
        view.degraded = true;
        KvLeaseManager a = manager("a", "a1");
        CompletableFuture<LeaseHandle> f = a.acquire("conv1", Duration.ofSeconds(1)).toCompletableFuture();
        ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(3, TimeUnit.SECONDS));
        assertEquals(LeaseUnavailableException.Reason.DEGRADED, ((LeaseUnavailableException) e.getCause()).reason());
    }

    @Test
    @DisplayName("a short NATS blip within the grace is ridden out — the lease stays fenced")
    void blipWithinGraceStaysFenced() throws Exception {
        view.connected = false;
        KvLeaseManager a = manager("a", "a1");
        var pending = a.acquire("conv1", Duration.ofSeconds(3)).toCompletableFuture();
        Thread.sleep(300);
        assertFalse(pending.isDone());
        view.connected = true;
        assertNotNull(pending.get(3, TimeUnit.SECONDS).fence());
    }

    @Test
    @DisplayName("releaseAll on shutdown releases held leases and fails pending waiters")
    void releaseAllOnShutdown() throws Exception {
        KvLeaseManager a = manager("a", "a1");
        KvLeaseManager b = manager("b", "b1");
        get(b.acquire("conv1", Duration.ofSeconds(1)));
        get(a.acquire("conv2", Duration.ofSeconds(1)));
        var waiting = a.acquire("conv1", Duration.ofSeconds(5)).toCompletableFuture();
        a.releaseAll();
        assertEquals(0, a.heldCount());
        assertTrue(kv.get("c.conv2").isEmpty());
        ExecutionException e = assertThrows(ExecutionException.class, () -> waiting.get(3, TimeUnit.SECONDS));
        assertEquals(LeaseUnavailableException.Reason.SHUTTING_DOWN, ((LeaseUnavailableException) e.getCause()).reason());
        assertThrows(ExecutionException.class,
                () -> a.acquire("conv3", Duration.ofSeconds(1)).toCompletableFuture().get(1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("stopAcquiring at the start of a drain fails waiters at once but keeps the leases of running turns")
    void stopAcquiringKeepsHeldLeases() throws Exception {
        KvLeaseManager a = manager("a", "a1");
        KvLeaseManager b = manager("b", "b1");
        get(b.acquire("conv1", Duration.ofSeconds(1)));
        LeaseHandle running = get(a.acquire("conv2", Duration.ofSeconds(1)));
        var waiting = a.acquire("conv1", Duration.ofSeconds(30)).toCompletableFuture();

        a.stopAcquiring();

        ExecutionException e = assertThrows(ExecutionException.class, () -> waiting.get(3, TimeUnit.SECONDS),
                "a turn waiting for its lease must be answered now, not after its 30 s acquire timeout");
        assertEquals(LeaseUnavailableException.Reason.SHUTTING_DOWN, ((LeaseUnavailableException) e.getCause()).reason());
        assertEquals(1, a.heldCount(), "the running turn keeps its lease until it finishes");
        assertTrue(kv.get("c.conv2").isPresent());
        assertThrows(ExecutionException.class,
                () -> a.acquire("conv3", Duration.ofSeconds(1)).toCompletableFuture().get(1, TimeUnit.SECONDS));

        a.release(running);
        assertEquals(0, a.heldCount());
    }

    @Test
    @DisplayName("tryAcquireKey is a single non-waiting attempt (leader election)")
    void tryAcquireForLeaders() {
        KvLeaseManager a = manager("a", "a1");
        KvLeaseManager b = manager("b", "b1");
        assertTrue(a.tryAcquireKey("leader.x").isPresent());
        assertTrue(b.tryAcquireKey("leader.x").isEmpty());
        assertTrue(a.tryAcquireKey("leader.x").isPresent(), "re-entrant for the holder");
    }

    @Test
    @DisplayName("the timeout outcome is reported while another node keeps the lease")
    void timeoutWhileHeld() throws TimeoutException, InterruptedException {
        KvLeaseManager a = manager("a", "a1");
        KvLeaseManager b = manager("b", "b1");
        a.tryAcquireKey("c.conv9");
        var f = b.acquire("conv9", Duration.ofMillis(200)).toCompletableFuture();
        assertThrows(ExecutionException.class, () -> f.get(3, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("a holder missing from presence is taken over only after its lease revision sat still for three heartbeats")
    void missingPresenceNeedsAnUnchangedRevisionForThreeHeartbeats() throws Exception {
        // heartbeat 100 ms in this test config, so the window is 300 ms
        kv.create("c.conv1", "{\"node\":\"ghost\",\"boot\":\"g1\",\"since\":1}".getBytes(StandardCharsets.UTF_8));
        KvLeaseManager b = manager("b", "b1");
        long start = System.nanoTime();
        LeaseHandle lease = get(b.acquire("conv1", Duration.ofSeconds(3)));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(lease.wasTakenOver());
        assertTrue(elapsedMillis >= 280, "taken over after " + elapsedMillis + " ms, before the observation window had passed");
    }

    @Test
    @DisplayName("a holder is not robbed because the presence lookup failed")
    void unreadablePresenceIsNotAbandonment() throws Exception {
        // A lease from a node the presence bucket cannot be asked about: the lookup
        // failing says nothing about the holder, however old the lease looks.
        OptionalLong rev = kv.create("c.conv1", "{\"node\":\"ghost\",\"boot\":\"g1\",\"since\":1}".getBytes(StandardCharsets.UTF_8));
        assertTrue(rev.isPresent());
        view.presenceDown = true;
        KvLeaseManager b = manager("b", "b1");
        var waiting = b.acquire("conv1", Duration.ofMillis(900)).toCompletableFuture();
        ExecutionException e = assertThrows(ExecutionException.class, () -> waiting.get(5, TimeUnit.SECONDS));
        assertEquals(LeaseUnavailableException.Reason.TIMEOUT, assertInstanceOf(LeaseUnavailableException.class, e.getCause()).reason());
        assertTrue(kv.get("c.conv1").isPresent(), "the lease is untouched");
    }

    @Test
    @DisplayName("a holder missing from presence that keeps renewing its lease is not robbed, whatever its clock says")
    void renewingHolderWithoutPresenceIsNotRobbed() throws Exception {
        // "since" is the holder's own clock, here decades behind ours.
        String value = "{\"node\":\"ghost\",\"boot\":\"g1\",\"since\":1}";
        long[] revision = {kv.create("c.conv1", value.getBytes(StandardCharsets.UTF_8)).getAsLong()};
        AtomicBoolean renew = new AtomicBoolean(true);
        Thread renewer = Thread.ofPlatform().daemon().start(() -> {
            while (renew.get()) {
                // a live holder rewrites its lease every heartbeat interval (100 ms here)
                revision[0] = kv.update("c.conv1", value.getBytes(StandardCharsets.UTF_8), revision[0]).orElse(revision[0]);
                try {
                    Thread.sleep(40);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        });
        try {
            KvLeaseManager b = manager("b", "b1");
            var waiting = b.acquire("conv1", Duration.ofMillis(1200)).toCompletableFuture();
            ExecutionException e = assertThrows(ExecutionException.class, () -> waiting.get(5, TimeUnit.SECONDS));
            assertEquals(LeaseUnavailableException.Reason.TIMEOUT, assertInstanceOf(LeaseUnavailableException.class, e.getCause()).reason());
        } finally {
            renew.set(false);
            renewer.join(1000);
        }
    }

    @Test
    @DisplayName("a release that races the heartbeat's renewal still frees the lease")
    void releaseAfterRenewalFreesTheKey() throws Exception {
        KvLeaseManager a = manager("a", "a1");
        LeaseHandle lease = get(a.acquire("conv1", Duration.ofSeconds(1)));
        // The heartbeat renewed the key on the server, but its new revision had not
        // been recorded yet when the turn finished and the lease was released.
        ISharedKv.Versioned current = kv.get("c.conv1").orElseThrow();
        assertTrue(kv.update("c.conv1", current.value(), current.revision()).isPresent());
        a.release(lease);
        assertTrue(kv.get("c.conv1").isEmpty(), "the key must not be left to expire after the TTL");
    }

    @Test
    @DisplayName("a release never deletes a lease another node holds now")
    void releaseDoesNotDeleteTheirLease() throws Exception {
        KvLeaseManager a = manager("a", "a1");
        LeaseHandle lease = get(a.acquire("conv1", Duration.ofSeconds(1)));
        kv.delete("c.conv1");
        kv.create("c.conv1", "{\"node\":\"b\",\"boot\":\"b1\",\"since\":1}".getBytes(StandardCharsets.UTF_8));
        a.release(lease);
        assertTrue(kv.get("c.conv1").isPresent(), "b's lease survives a's late release");
    }

    /**
     * Delegates to a backing KV but throws ClusterUnavailableException while down.
     */
    static class FlakyKv implements ISharedKv {
        private final ISharedKv delegate;
        private final AtomicBoolean down;

        FlakyKv(ISharedKv delegate, AtomicBoolean down) {
            this.delegate = delegate;
            this.down = down;
        }

        private void check() {
            if (down.get()) {
                throw new ClusterUnavailableException("down");
            }
        }

        @Override
        public String bucket() {
            return delegate.bucket();
        }

        @Override
        public OptionalLong create(String key, byte[] value) {
            check();
            return delegate.create(key, value);
        }

        @Override
        public Optional<Versioned> get(String key) {
            check();
            return delegate.get(key);
        }

        @Override
        public OptionalLong update(String key, byte[] value, long expectedRevision) {
            check();
            return delegate.update(key, value, expectedRevision);
        }

        @Override
        public long put(String key, byte[] value) {
            check();
            return delegate.put(key, value);
        }

        @Override
        public boolean delete(String key, long expectedRevision) {
            check();
            return delegate.delete(key, expectedRevision);
        }

        @Override
        public void delete(String key) {
            check();
            delegate.delete(key);
        }

        @Override
        public List<String> keys() {
            check();
            return delegate.keys();
        }
    }
}
