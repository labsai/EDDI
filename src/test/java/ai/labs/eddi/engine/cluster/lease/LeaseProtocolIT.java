/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.lease;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ClusterSubjects;
import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.ISharedKv;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.NatsSharedStateFactory;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import ai.labs.eddi.engine.cluster.SharedBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Dispatcher;
import io.nats.client.KeyValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The lease protocol against a real NATS JetStream server: what the in-memory
 * bucket of the unit tests cannot prove.
 * <ul>
 * <li>The delete marker this version writes itself (to learn its revision) is
 * the one jnats writes: a node of an earlier version, whose acquire is a jnats
 * {@code create}, takes the key after it — and the single compare-and-set this
 * version acquires with refuses a key such a node took in between (rolling
 * upgrade: mutual exclusion across versions).</li>
 * <li>A renewal and a create that the server applied but whose reply the client
 * never saw (review A2) are recognised as this node's own.</li>
 * <li>Release notifications addressed to the waiting node arrive over real core
 * NATS, and nothing is broadcast.</li>
 * </ul>
 */
@DisplayName("Lease protocol against real NATS")
class LeaseProtocolIT {

    @SuppressWarnings("resource")
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.11-alpine").withExposedPorts(4222)
            .withCommand("--jetstream", "--store_dir=/data").waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    @BeforeAll
    static void startNats() {
        NATS.start();
    }

    @AfterAll
    static void stopNats() {
        NATS.stop();
    }

    /** One simulated node: a connection, the LEASES bucket and a lease manager. */
    final class Node {
        final NodeIdentity identity;
        final NatsConnectionManager connections;
        final ISharedKv bucket;
        final NatsSharedStateFactory shared;
        KvLeaseManager leases;

        Node(String id) {
            identity = new NodeIdentity(id, UUID.randomUUID().toString().substring(0, 8));
            ClusterConfig config = ClusterConfig.defaults().withMessagingType("nats")
                    .withNatsUrl("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222)).withNatsPrefix(prefix).withNodeId(id)
                    .withLeaseTimings(Duration.ofSeconds(4), Duration.ofSeconds(1), Duration.ofSeconds(30));
            var meters = new SimpleMeterRegistry();
            connections = new NatsConnectionManager(config, identity, meters);
            shared = new NatsSharedStateFactory(connections, meters);
            connections.start();
            bucket = shared.bucket(SharedBucket.LEASES.withTtl(config.leaseTtl()));
        }

        KvLeaseManager start(ISharedKv store) {
            leases = new KvLeaseManager(store, identity, connections.config(), scheduler, io, new NatsLeaseManager.NatsNotifier(connections),
                    new KvLeaseManager.ClusterView() {
                        @Override
                        public boolean isConnected() {
                            return connections.isConnected();
                        }

                        @Override
                        public boolean isDegraded() {
                            return false;
                        }

                        @Override
                        public Optional<String> liveBoot(String nodeId) {
                            // every node of this test is alive with the boot it registered
                            return nodes.stream().filter(n -> n.identity.nodeId().equals(nodeId)).map(n -> n.identity.bootId())
                                    .findFirst();
                        }
                    }, new SimpleMeterRegistry());
            return leases;
        }

        void awaitConnected() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 15_000;
            while (!connections.isConnected() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(connections.isConnected(), identity.nodeId() + " did not connect");
        }

        /** The raw jnats handle, as a node of an earlier version uses it. */
        KeyValue jnats() {
            return connections.keyValue(prefix + "_LEASES");
        }
    }

    /**
     * Applies the next write of a kind on the real server, then reports a timeout:
     * the reply the client never saw.
     */
    static final class LostReplyKv extends KvLeaseManagerTest.FlakyKv {
        final AtomicBoolean loseNextUpdate = new AtomicBoolean();
        final AtomicBoolean loseNextWrite = new AtomicBoolean();

        LostReplyKv(ISharedKv delegate) {
            super(delegate, new AtomicBoolean());
        }

        @Override
        public OptionalLong update(String key, byte[] value, long expectedRevision) {
            OptionalLong result = super.update(key, value, expectedRevision);
            if (result.isPresent() && (loseNextUpdate.compareAndSet(true, false) || loseNextWrite.compareAndSet(true, false))) {
                throw new ClusterUnavailableException("timeout (applied on the server)");
            }
            return result;
        }

        @Override
        public OptionalLong create(String key, byte[] value) {
            OptionalLong result = super.create(key, value);
            if (result.isPresent() && loseNextWrite.compareAndSet(true, false)) {
                throw new ClusterUnavailableException("timeout (applied on the server)");
            }
            return result;
        }
    }

    private String prefix;
    private final List<Node> nodes = new ArrayList<>();
    private ScheduledExecutorService scheduler;
    private ExecutorService io;

    @BeforeEach
    void setUp() throws Exception {
        prefix = "LP" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        scheduler = Executors.newScheduledThreadPool(2);
        io = Executors.newVirtualThreadPerTaskExecutor();
        nodes.add(new Node("a"));
        nodes.add(new Node("b"));
        for (Node n : nodes) {
            n.awaitConnected();
            // The bucket was requested before the connection was up: provisioned by the
            // connect hook, asynchronously. Provision it now so no test races that hook.
            n.shared.provisionAll();
        }
    }

    @AfterEach
    void tearDown() {
        for (Node n : nodes) {
            n.connections.shutdown();
        }
        nodes.clear();
        scheduler.shutdownNow();
        io.shutdownNow();
    }

    private static LeaseHandle get(CompletionStage<LeaseHandle> stage) throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("the delete marker written with its revision is a real KV delete: latest() reads it, one update recreates the key")
    void deleteMarkerRoundTrip() {
        ISharedKv kv = nodes.get(0).bucket;
        String key = "c.marker-" + UUID.randomUUID();
        long created = kv.create(key, "v1".getBytes()).orElseThrow();
        assertTrue(kv.deleteReturningRevision(key, created - 1).isEmpty(), "guarded by the expected revision");
        long marker = kv.deleteReturningRevision(key, created).orElseThrow();
        assertTrue(marker > created);
        assertTrue(kv.get(key).isEmpty(), "jnats reads the key as deleted");
        ISharedKv.Latest latest = kv.latest(key);
        assertFalse(latest.isLive());
        assertEquals(marker, latest.revision());
        long recreated = kv.update(key, "v2".getBytes(), marker).orElseThrow();
        assertEquals("v2", new String(kv.get(key).orElseThrow().value()));
        assertTrue(kv.update(key, "v3".getBytes(), marker).isEmpty(), "a second writer at the same marker loses");
        assertEquals(recreated, kv.latest(key).revision());
        assertEquals(new ISharedKv.Latest(null, 0), kv.latest("c.never-" + UUID.randomUUID()));
    }

    @Test
    @DisplayName("rolling upgrade: an earlier version's jnats create takes a key this version released, and the reverse")
    void earlierVersionInteroperates() throws Exception {
        Node newer = nodes.get(0);
        KvLeaseManager leases = newer.start(newer.bucket);
        KeyValue older = nodes.get(1).jnats();
        String conversation = "upgrade-" + UUID.randomUUID();
        String key = "c." + conversation;

        LeaseHandle lease = get(leases.acquire(conversation, Duration.ofSeconds(5)));
        leases.release(lease); // this version's own delete marker
        // An earlier version's lease value: no lease id. Node b is alive with this
        // boot.
        String olderValue = "{\"node\":\"b\",\"boot\":\"" + nodes.get(1).identity.bootId() + "\",\"since\":1}";
        long olderRevision = older.create(key, olderValue.getBytes(StandardCharsets.UTF_8));
        assertTrue(olderRevision > lease.fence(), "the earlier version acquired after this version's release");

        // While the earlier version holds it, this version waits — and takes it once
        // the
        // earlier version releases with its own (jnats) delete.
        var waiting = leases.acquire(conversation, Duration.ofSeconds(10)).toCompletableFuture();
        Thread.sleep(300);
        assertFalse(waiting.isDone(), "mutual exclusion across versions");
        older.delete(key, olderRevision);
        LeaseHandle next = waiting.get(10, TimeUnit.SECONDS);
        assertTrue(next.fence() > olderRevision, "fencing tokens only grow across versions");
    }

    @Test
    @DisplayName("A2: a renewal applied on the server whose reply was lost keeps the lease")
    void lostRenewalReplyKeepsTheLease() throws Exception {
        Node a = nodes.get(0);
        LostReplyKv lossy = new LostReplyKv(a.bucket);
        KvLeaseManager leases = a.start(lossy);
        String conversation = "renew-" + UUID.randomUUID();
        LeaseHandle lease = get(leases.acquire(conversation, Duration.ofSeconds(5)));
        AtomicBoolean lost = new AtomicBoolean();
        lease.onLost(() -> lost.set(true));

        lossy.loseNextUpdate.set(true);
        leases.heartbeatAll();
        long applied = a.bucket.get("c." + conversation).orElseThrow().revision();
        assertTrue(applied > lease.fence(), "the renewal reached the server");
        leases.heartbeatAll();

        assertFalse(lost.get(), "a healthy turn must not be cancelled by a lost reply");
        assertFalse(lease.isLost());
        assertTrue(a.bucket.get("c." + conversation).orElseThrow().revision() > applied, "renewed again at the adopted revision");
        leases.release(lease);
        assertTrue(a.bucket.get("c." + conversation).isEmpty(), "and released without waiting out the TTL");
    }

    @Test
    @DisplayName("A2: an acquire whose write was applied but timed out takes its own lease at once")
    void lostCreateReplyIsAdopted() throws Exception {
        Node a = nodes.get(0);
        LostReplyKv lossy = new LostReplyKv(a.bucket);
        KvLeaseManager leases = a.start(lossy);
        String conversation = "create-" + UUID.randomUUID();
        lossy.loseNextWrite.set(true);
        long start = System.nanoTime();
        LeaseHandle lease = get(leases.acquire(conversation, Duration.ofSeconds(10)));
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMillis < 2_000, "took " + tookMillis + " ms — the node waited for its own lease");
        assertEquals("a", leases.peek(conversation).orElseThrow().node());
        leases.release(lease);
    }

    @Test
    @DisplayName("D7: the release reaches the waiting node over core NATS at once, addressed to it — nothing is broadcast")
    void addressedReleaseOverRealNats() throws Exception {
        KvLeaseManager a = nodes.get(0).start(nodes.get(0).bucket);
        KvLeaseManager b = nodes.get(1).start(nodes.get(1).bucket);
        AtomicInteger broadcasts = new AtomicInteger();
        Dispatcher spy = nodes.get(0).connections.requireConnected().createDispatcher(m -> broadcasts.incrementAndGet());
        spy.subscribe(new ClusterSubjects(prefix).leaseReleasedWildcard());
        String conversation = "notify-" + UUID.randomUUID();

        LeaseHandle first = get(a.acquire(conversation, Duration.ofSeconds(5)));
        CompletableFuture<LeaseHandle> waiting = b.acquire(conversation, Duration.ofSeconds(10)).toCompletableFuture();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (nodes.get(0).bucket.get("w.c." + conversation).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        Thread.sleep(50); // the "waiting" notice travels just ahead of the marker
        long releasedAt = System.nanoTime();
        a.release(first);
        LeaseHandle second = waiting.get(10, TimeUnit.SECONDS);
        long wokeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - releasedAt);
        assertTrue(second.fence() > first.fence());
        // b's poll is at least 250 ms (ttl/4 capped at 500 ms, jittered from half).
        assertTrue(wokeMillis < 200, "woke after " + wokeMillis + " ms: by the poll, not the notification");
        Thread.sleep(100);
        assertEquals(0, broadcasts.get(), "a plain release is not broadcast to the cluster");
    }
}
