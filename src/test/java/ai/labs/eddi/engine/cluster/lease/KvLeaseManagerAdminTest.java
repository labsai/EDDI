/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.lease;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.InMemorySharedKv;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import ai.labs.eddi.engine.cluster.lease.KvLeaseManager.ForceReleaseOutcome;
import ai.labs.eddi.engine.cluster.lease.KvLeaseManager.LeaseSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The administrative side of the lease manager: force-release, drain, the
 * takeover listener and the listing snapshot.
 */
@DisplayName("KvLeaseManager — admin operations")
class KvLeaseManagerAdminTest {

    private ScheduledExecutorService scheduler;
    private ExecutorService io;
    private InMemorySharedKv kv;
    private KvLeaseManagerTest.Bus bus;
    private KvLeaseManagerTest.View view;
    private ClusterConfig config;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(2);
        io = Executors.newCachedThreadPool();
        kv = new InMemorySharedKv("LEASES", Duration.ofSeconds(20));
        bus = new KvLeaseManagerTest.Bus();
        view = new KvLeaseManagerTest.View();
        config = ClusterConfig.defaults().withMessagingType("nats")
                .withLeaseTimings(Duration.ofMillis(400), Duration.ofMillis(100), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        io.shutdownNow();
    }

    private KvLeaseManager manager(String node, String boot) {
        view.live.put(node, boot);
        return new KvLeaseManager(kv, new NodeIdentity(node, boot), config, scheduler, io, bus, view, new SimpleMeterRegistry());
    }

    private static LeaseHandle get(CompletionStage<LeaseHandle> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("force-release deletes another node's lease; the next holder gets a higher fence, and the old holder learns it lost it")
    void forceReleaseHandsOverWithAHigherFence() throws Exception {
        KvLeaseManager a = manager("a", "a1");
        KvLeaseManager b = manager("b", "b1");
        a.start();
        LeaseHandle held = get(a.acquire("conv1", Duration.ofSeconds(1)));
        AtomicBoolean lost = new AtomicBoolean();
        held.onLost(() -> lost.set(true));

        KvLeaseManager.ForceRelease result = b.forceRelease("c.conv1", null);
        assertEquals(ForceReleaseOutcome.RELEASED, result.outcome());
        assertEquals("a", result.holder().node());
        assertTrue(kv.get("c.conv1").isEmpty(), "the key must be gone from the bucket");

        LeaseHandle next = get(b.acquire("conv1", Duration.ofSeconds(1)));
        assertTrue(next.fence() > held.fence(), "the next holder's fencing token must be higher");
        // a's heartbeat (100 ms) finds its revision gone and fires onLost
        long deadline = System.currentTimeMillis() + 3_000;
        while (!lost.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(lost.get(), "the former holder must learn it lost the lease");
    }

    @Test
    @DisplayName("force-release of a lease held by this very node fires its onLost at once")
    void forceReleaseOfOwnLeaseFiresLostImmediately() throws Exception {
        KvLeaseManager a = manager("a", "a1");
        LeaseHandle held = get(a.acquire("conv1", Duration.ofSeconds(1)));
        AtomicBoolean lost = new AtomicBoolean();
        held.onLost(() -> lost.set(true));
        assertEquals(ForceReleaseOutcome.RELEASED, a.forceRelease("c.conv1", null).outcome());
        assertTrue(lost.get());
        assertEquals(0, a.heldCount());
    }

    @Test
    @DisplayName("force-release is idempotent: an absent lease is ALREADY_RELEASED and nothing changes")
    void forceReleaseOfAbsentLease() {
        KvLeaseManager a = manager("a", "a1");
        KvLeaseManager.ForceRelease result = a.forceRelease("c.nobody", null);
        assertEquals(ForceReleaseOutcome.ALREADY_RELEASED, result.outcome());
        assertNull(result.holder());
    }

    @Test
    @DisplayName("force-release with a stale expected revision is refused as RENEWED and keeps the lease")
    void forceReleaseRefusedWhenRenewed() throws Exception {
        KvLeaseManager a = manager("a", "a1");
        LeaseHandle held = get(a.acquire("conv1", Duration.ofSeconds(1)));
        long seen = held.fence();
        // the holder renews (what its heartbeat does)
        a.heartbeatAll();
        KvLeaseManager.ForceRelease result = manager("b", "b1").forceRelease("c.conv1", seen);
        assertEquals(ForceReleaseOutcome.RENEWED, result.outcome());
        assertTrue(result.currentRevision() > seen);
        assertTrue(kv.get("c.conv1").isPresent(), "a renewed lease must not be deleted");
        assertFalse(held.isLost());
    }

    @Test
    @DisplayName("a holder that renews between the administrator's read and the delete keeps its lease: the delete is compare-and-set, not unconditional")
    void forceReleaseLosesTheRaceToARenewal() throws Exception {
        AtomicBoolean renewAfterNextRead = new AtomicBoolean();
        InMemorySharedKv racing = new InMemorySharedKv("LEASES", Duration.ofSeconds(20)) {
            @Override
            public synchronized Optional<Versioned> getConsistent(String key) {
                Optional<Versioned> read = super.getConsistent(key);
                if (read.isPresent() && renewAfterNextRead.getAndSet(false)) {
                    // the holder's heartbeat lands right after the administrator read the lease
                    update(key, read.get().value(), read.get().revision());
                }
                return read;
            }
        };
        view.live.put("a", "a1");
        view.live.put("b", "b1");
        KvLeaseManager holder = new KvLeaseManager(racing, new NodeIdentity("a", "a1"), config, scheduler, io, bus, view, new SimpleMeterRegistry());
        KvLeaseManager admin = new KvLeaseManager(racing, new NodeIdentity("b", "b1"), config, scheduler, io, bus, view, new SimpleMeterRegistry());
        LeaseHandle held = get(holder.acquire("conv1", Duration.ofSeconds(1)));

        renewAfterNextRead.set(true);
        KvLeaseManager.ForceRelease result = admin.forceRelease("c.conv1", null);

        assertEquals(ForceReleaseOutcome.RENEWED, result.outcome());
        assertTrue(racing.get("c.conv1").isPresent(), "the renewed lease must survive the release attempt");
        assertFalse(held.isLost());
    }

    @Test
    @DisplayName("a force-release wakes a waiting node at once instead of after the TTL")
    void forceReleaseWakesWaiters() throws Exception {
        KvLeaseManager a = manager("a", "a1");
        KvLeaseManager b = manager("b", "b1");
        get(a.acquire("conv1", Duration.ofSeconds(1)));
        CompletionStage<LeaseHandle> waiting = b.acquire("conv1", Duration.ofSeconds(3));
        Thread.sleep(50);
        assertFalse(waiting.toCompletableFuture().isDone());
        a.forceRelease("c.conv1", null);
        assertNotNull(waiting.toCompletableFuture().get(1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("a drained node refuses new leases with DRAINING, fails its waiters, takes no leader role; undrain restores it")
    void drainRefusesAndUndrainRestores() throws Exception {
        KvLeaseManager a = manager("a", "a1");
        KvLeaseManager b = manager("b", "b1");
        get(b.acquire("conv1", Duration.ofSeconds(1)));
        CompletionStage<LeaseHandle> waiter = a.acquire("conv1", Duration.ofSeconds(5));
        Thread.sleep(50);

        a.setDraining(true);
        assertTrue(a.isDraining());
        ExecutionException pending = assertThrows(ExecutionException.class, () -> waiter.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertEquals(LeaseUnavailableException.Reason.DRAINING, ((LeaseUnavailableException) pending.getCause()).reason());

        ExecutionException fresh = assertThrows(ExecutionException.class, () -> get(a.acquire("conv2", Duration.ofSeconds(1))));
        assertEquals(LeaseUnavailableException.Reason.DRAINING, ((LeaseUnavailableException) fresh.getCause()).reason());
        assertTrue(a.tryAcquireKey("leader.hitl-recovery").isEmpty(), "a drained node must not lead");
        assertTrue(kv.get("c.conv2").isEmpty());

        a.setDraining(false);
        assertNotNull(get(a.acquire("conv2", Duration.ofSeconds(1))));
    }

    @Test
    @DisplayName("a waiter that registered just after the drain swept the waiters is refused by its attempt, and takes no lease")
    void waiterMissedByTheDrainSweepIsRefused() throws Exception {
        List<Runnable> queued = new CopyOnWriteArrayList<>();
        view.live.put("a", "a1");
        KvLeaseManager a = new KvLeaseManager(kv, new NodeIdentity("a", "a1"), config, scheduler, queued::add, bus, view,
                new SimpleMeterRegistry());
        // acquireKey read draining=false and registered the waiter; its attempt is
        // queued.
        CompletionStage<LeaseHandle> waiter = a.acquire("conv9", Duration.ofSeconds(2));
        // setDraining(true) wrote the flag, but its sweep over the waiters ran before
        // this
        // waiter was in the set — the interleaving the sweep alone cannot catch.
        Field draining = KvLeaseManager.class.getDeclaredField("draining");
        draining.setAccessible(true);
        draining.setBoolean(a, true);
        queued.forEach(Runnable::run);

        ExecutionException refused = assertThrows(ExecutionException.class, () -> waiter.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertEquals(LeaseUnavailableException.Reason.DRAINING, ((LeaseUnavailableException) refused.getCause()).reason());
        assertTrue(kv.get("c.conv9").isEmpty(), "a drained node must not take the lease");
        assertEquals(0, a.heldCount());
    }

    @Test
    @DisplayName("a takeover from a dead holder notifies the listener with the previous holder")
    void takeoverNotifiesListener() throws Exception {
        KvLeaseManager a = manager("a", "a1");
        get(a.acquire("conv1", Duration.ofSeconds(1)));
        view.live.remove("a"); // a's presence record is gone
        view.live.put("a", "a2"); // ...and it came back with a new boot
        KvLeaseManager b = manager("b", "b1");
        List<LeaseInfo> previous = new CopyOnWriteArrayList<>();
        b.onTakeover((key, info) -> previous.add(info));
        assertNotNull(get(b.acquire("conv1", Duration.ofSeconds(2))));
        assertEquals(1, previous.size());
        assertEquals("a", previous.get(0).node());
        assertEquals("a1", previous.get(0).boot());
    }

    @Test
    @DisplayName("a restarted node clearing its previous boot's leases reports each as a takeover")
    void ownStaleSweepNotifies() throws Exception {
        get(manager("a", "a1").acquire("conv1", Duration.ofSeconds(1)));
        KvLeaseManager restarted = manager("a", "a2");
        List<LeaseInfo> previous = new CopyOnWriteArrayList<>();
        restarted.onTakeover((key, info) -> previous.add(info));
        restarted.sweepOwnStaleLeases();
        assertEquals(1, previous.size());
        assertEquals("a1", previous.get(0).boot());
        assertTrue(kv.get("c.conv1").isEmpty());
    }

    @Test
    @DisplayName("the snapshot lists leases with holder, renewal time and the node waiting for it; waiter markers are not leases")
    void snapshotListsLeasesAndWaiters() throws Exception {
        KvLeaseManager a = manager("a", "a1");
        KvLeaseManager b = manager("b", "b1");
        get(a.acquire("conv1", Duration.ofSeconds(1)));
        get(a.acquire("conv2", Duration.ofSeconds(1)));
        b.acquire("conv1", Duration.ofSeconds(2));
        long deadline = System.currentTimeMillis() + 2_000;
        while (kv.get("w.c.conv1").isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        List<LeaseSnapshot> leases = a.snapshot("", 100);
        assertEquals(2, leases.size(), "waiter markers must not be listed as leases: " + leases);
        LeaseSnapshot conv1 = leases.stream().filter(l -> l.key().equals("c.conv1")).findFirst().orElseThrow();
        assertEquals("a", conv1.holder().node());
        assertEquals("b", conv1.waitingNode());
        assertTrue(conv1.renewedAt() > 0);
        assertEquals(1, a.snapshot("c.conv2", 100).size());
        assertEquals("b", a.peekSnapshot("c.conv1").orElseThrow().waitingNode());
        assertTrue(a.peekSnapshot("c.none").isEmpty());
        assertEquals("b", new String(kv.get("w.c.conv1").orElseThrow().value(), StandardCharsets.UTF_8));
    }
}
