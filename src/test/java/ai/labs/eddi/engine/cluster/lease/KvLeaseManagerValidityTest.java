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
import ai.labs.eddi.engine.cluster.lease.KvLeaseManagerTest.Bus;
import ai.labs.eddi.engine.cluster.lease.KvLeaseManagerTest.FlakyKv;
import ai.labs.eddi.engine.cluster.lease.KvLeaseManagerTest.View;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lease validity (review findings A1, A2) and waiter markers (A11): a lease is
 * only trusted while its renewals are acknowledged, a write the server applied
 * although its reply was lost is recognised as this node's own, and a waiter
 * cleans up after itself.
 */
@DisplayName("KvLeaseManager — lease validity")
class KvLeaseManagerValidityTest {

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
        // ttl 400 ms, heartbeat 100 ms: validity 200 ms
        config = ClusterConfig.defaults().withMessagingType("nats")
                .withLeaseTimings(Duration.ofMillis(400), Duration.ofMillis(100), Duration.ofSeconds(5));
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

    private double counter(String name, String tag, String value) {
        return meters.counter(name, tag, value).count();
    }

    /**
     * A KV whose next write of the given kind is applied on the server and then
     * reported as a timeout, like a reply lost to a NATS leader change.
     */
    static final class LostReplyKv extends FlakyKv {
        final AtomicBoolean loseNextUpdate = new AtomicBoolean();
        final AtomicBoolean loseNextCreate = new AtomicBoolean();

        LostReplyKv(ISharedKv delegate) {
            super(delegate, new AtomicBoolean());
        }

        @Override
        public OptionalLong update(String key, byte[] value, long expectedRevision) {
            OptionalLong result = super.update(key, value, expectedRevision);
            if (result.isPresent() && loseNextUpdate.compareAndSet(true, false)) {
                throw new ClusterUnavailableException("timeout waiting for the reply (the write was applied)");
            }
            return result;
        }

        @Override
        public OptionalLong create(String key, byte[] value) {
            OptionalLong result = super.create(key, value);
            if (result.isPresent() && loseNextCreate.compareAndSet(true, false)) {
                throw new ClusterUnavailableException("timeout waiting for the reply (the create was applied)");
            }
            return result;
        }
    }

    @Nested
    @DisplayName("A1: a holder cut off from NATS stops itself")
    class CutOff {

        @Test
        @DisplayName("with the heartbeat running, the lease is lost (and onLost fires) before the server-side TTL could pass")
        void lostBeforeTheTtl() throws Exception {
            AtomicBoolean down = new AtomicBoolean();
            KvLeaseManager a = manager(new FlakyKv(kv, down), "a", "a1");
            a.start();
            LeaseHandle lease = get(a.acquire("conv1", Duration.ofSeconds(1)));
            AtomicBoolean fired = new AtomicBoolean();
            lease.onLost(() -> fired.set(true));
            Thread.sleep(250); // several renewals acknowledged
            assertFalse(lease.isLost(), "a renewing holder keeps its lease");

            long cutOffAt = System.nanoTime();
            down.set(true);
            long deadline = cutOffAt + TimeUnit.SECONDS.toNanos(2);
            while (!fired.get() && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            long lostAfterMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cutOffAt);
            assertTrue(fired.get(), "onLost must fire although no NATS call ever succeeded or failed with a conflict");
            assertTrue(lease.isLost());
            // The last acknowledged renewal was sent at most one renewal interval before
            // the
            // cut; the server keeps the lease for 400 ms from that renewal, so the holder
            // must
            // have stopped well before another node could take the conversation over.
            assertTrue(lostAfterMillis < 400, "lost " + lostAfterMillis + " ms after the cut, past the server-side TTL");
            assertEquals(1, counter("eddi.cluster.lease.lost", "reason", "unconfirmed"));
        }

        @Test
        @DisplayName("isLost() reports the expiry at once, even before the tick notices it")
        void isLostWithoutTick() throws Exception {
            AtomicBoolean down = new AtomicBoolean();
            KvLeaseManager a = manager(new FlakyKv(kv, down), "a", "a1");
            LeaseHandle lease = get(a.acquire("conv1", Duration.ofSeconds(1)));
            down.set(true);
            Thread.sleep(230);
            assertTrue(lease.isLost(), "no heartbeat started at all: validity counts from the acquire");
        }

        @Test
        @DisplayName("a lease lost by expiry is still deleted on release when the key is still ours, so nobody waits out the TTL")
        void releaseOfAnExpiredLeaseDeletesIt() throws Exception {
            AtomicBoolean down = new AtomicBoolean();
            KvLeaseManager a = manager(new FlakyKv(kv, down), "a", "a1");
            LeaseHandle lease = get(a.acquire("conv1", Duration.ofSeconds(1)));
            down.set(true);
            Thread.sleep(230);
            assertTrue(lease.isLost());
            down.set(false); // NATS is back when the turn finishes
            a.release(lease);
            assertTrue(kv.get("c.conv1").isEmpty(), "the lost lease was still ours and must not block the conversation");
        }

        @Test
        @DisplayName("validity is ttl - 2 x heartbeat, or the midpoint of heartbeat and ttl when that leaves too little")
        void validityFormula() {
            assertEquals(Duration.ofSeconds(10).toNanos(), KvLeaseManager.validityNanos(Duration.ofSeconds(20), Duration.ofSeconds(5)));
            assertEquals(Duration.ofMillis(200).toNanos(), KvLeaseManager.validityNanos(Duration.ofMillis(400), Duration.ofMillis(100)));
            // heartbeat = ttl / 2 (allowed): ttl - 2 x heartbeat would be 0
            assertEquals(Duration.ofSeconds(15).toNanos(), KvLeaseManager.validityNanos(Duration.ofSeconds(20), Duration.ofSeconds(10)));
        }
    }

    @Nested
    @DisplayName("A2: a write applied on the server whose reply was lost")
    class LostReply {

        @Test
        @DisplayName("a renewal applied but reported as a timeout is not a takeover: the next renewal adopts the revision")
        void renewalAdopted() throws Exception {
            LostReplyKv lossy = new LostReplyKv(kv);
            KvLeaseManager a = manager(lossy, "a", "a1");
            LeaseHandle lease = get(a.acquire("conv1", Duration.ofSeconds(1)));
            AtomicBoolean fired = new AtomicBoolean();
            lease.onLost(() -> fired.set(true));

            lossy.loseNextUpdate.set(true);
            a.heartbeatAll(); // applied on the server, the reply "timed out"
            long afterLostReply = kv.get("c.conv1").orElseThrow().revision();
            a.heartbeatAll(); // compare-and-set at the old revision conflicts

            assertFalse(fired.get(), "a healthy turn must not be cancelled");
            assertFalse(lease.isLost());
            assertEquals(1, a.heldCount());
            assertTrue(kv.get("c.conv1").orElseThrow().revision() > afterLostReply, "renewed at the adopted revision");
            assertEquals(1, counter("eddi.cluster.lease.adopted", "op", "renew"));
            a.release(lease);
            assertTrue(kv.get("c.conv1").isEmpty(), "and released normally afterwards");
        }

        @Test
        @DisplayName("an acquire whose create was applied but timed out takes that lease instead of waiting it out")
        void createAdopted() throws Exception {
            LostReplyKv lossy = new LostReplyKv(kv);
            lossy.loseNextCreate.set(true);
            KvLeaseManager a = manager(lossy, "a", "a1");
            long start = System.nanoTime();
            LeaseHandle lease = get(a.acquire("conv1", Duration.ofSeconds(3)));
            long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertNotNull(lease.fence());
            assertTrue(tookMillis < 1_500, "took " + tookMillis + " ms: the node waited for its own lease");
            assertEquals("a", a.peek("conv1").orElseThrow().node());
            assertEquals(1, counter("eddi.cluster.lease.adopted", "op", "create"));
            a.release(lease);
            assertTrue(kv.get("c.conv1").isEmpty());
        }

        @Test
        @DisplayName("a lease of this process that nobody here holds (an orphaned create) is deleted, not waited out")
        void orphanDeleted() throws Exception {
            // What an acquire that gave up after its create's reply was lost leaves behind.
            kv.create("c.conv1", "{\"node\":\"a\",\"boot\":\"a1\",\"since\":1,\"lid\":\"gone\"}".getBytes(StandardCharsets.UTF_8));
            KvLeaseManager a = manager(kv, "a", "a1");
            LeaseHandle lease = get(a.acquire("conv1", Duration.ofSeconds(1)));
            assertNotNull(lease.fence());
            assertEquals(1, meters.counter("eddi.cluster.lease.orphans.deleted").count());
        }

        @Test
        @DisplayName("a leader election finds its own orphan and takes the role")
        void tryAcquireDeletesOrphan() {
            kv.create("leader.x", "{\"node\":\"a\",\"boot\":\"a1\",\"since\":1,\"lid\":\"gone\"}".getBytes(StandardCharsets.UTF_8));
            KvLeaseManager a = manager(kv, "a", "a1");
            assertTrue(a.tryAcquireKey("leader.x").isPresent());
        }

        @Test
        @DisplayName("a renewal conflict on a key that another node holds now is still a takeover")
        void conflictWithForeignHolderIsLost() throws Exception {
            KvLeaseManager a = manager(kv, "a", "a1");
            LeaseHandle lease = get(a.acquire("conv1", Duration.ofSeconds(1)));
            kv.delete("c.conv1");
            kv.create("c.conv1", "{\"node\":\"a\",\"boot\":\"a1\",\"since\":1,\"lid\":\"another\"}".getBytes(StandardCharsets.UTF_8));
            a.heartbeatAll();
            assertTrue(lease.isLost(), "same node and boot but another lease id: not this lease");
            assertEquals(1, counter("eddi.cluster.lease.lost", "reason", "taken_over"));
        }
    }

    @Nested
    @DisplayName("A11: waiter markers and strict peek")
    class Markers {

        @Test
        @DisplayName("a waiter that times out removes its own marker")
        void timedOutWaiterRemovesItsMarker() throws Exception {
            KvLeaseManager a = manager(kv, "a", "a1");
            KvLeaseManager b = manager(kv, "b", "b1");
            get(a.acquire("conv1", Duration.ofSeconds(1)));
            var waiting = b.acquire("conv1", Duration.ofMillis(300)).toCompletableFuture();
            assertThrows(ExecutionException.class, () -> waiting.get(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (kv.get("w.c.conv1").isPresent() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(kv.get("w.c.conv1").isEmpty(), "a stale marker would make the holder yield to a node that left");
        }

        @Test
        @DisplayName("a granted waiter never deletes another node's marker")
        void grantKeepsAnotherNodesMarker() throws Exception {
            KvLeaseManager a = manager(kv, "a", "a1");
            KvLeaseManager b = manager(kv, "b", "b1");
            LeaseHandle first = get(a.acquire("conv1", Duration.ofSeconds(1)));
            var bWaiting = b.acquire("conv1", Duration.ofSeconds(5)).toCompletableFuture();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (kv.get("w.c.conv1").isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            kv.put("w.c.conv1", "c".getBytes(StandardCharsets.UTF_8)); // node c waits now too
            a.release(first);
            assertNotNull(bWaiting.get(3, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertEquals("c", new String(kv.get("w.c.conv1").orElseThrow().value(), StandardCharsets.UTF_8),
                    "c is still waiting: its marker must survive b's grant");
        }

        @Test
        @DisplayName("peekStrict throws when the leases cannot be read; peek reads that as 'no lease'")
        void peekStrictThrows() throws Exception {
            AtomicBoolean down = new AtomicBoolean();
            KvLeaseManager a = manager(new FlakyKv(kv, down), "a", "a1");
            get(a.acquire("conv1", Duration.ofSeconds(1)));
            down.set(true);
            assertTrue(a.peek("conv1").isEmpty());
            assertThrows(ClusterUnavailableException.class, () -> a.peekStrict("conv1"));
            down.set(false);
            assertEquals(Optional.of("a"), a.peekStrict("conv1").map(LeaseInfo::node));
        }
    }

    @Test
    @DisplayName("a lease written by this node, then renewed concurrently by its heartbeat, still releases cleanly")
    void releaseAfterAdoption() throws Exception {
        AtomicInteger updates = new AtomicInteger();
        ISharedKv counting = new FlakyKv(kv, new AtomicBoolean()) {
            @Override
            public OptionalLong update(String key, byte[] value, long expectedRevision) {
                updates.incrementAndGet();
                return super.update(key, value, expectedRevision);
            }
        };
        KvLeaseManager a = manager(counting, "a", "a1");
        LeaseHandle lease = get(a.acquire("conv1", Duration.ofSeconds(1)));
        a.heartbeatAll();
        assertTrue(updates.get() >= 1);
        a.release(lease);
        assertTrue(kv.get("c.conv1").isEmpty());
    }
}
