/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.engine.cluster.events.JetStreamEventBus;
import ai.labs.eddi.engine.cluster.lease.LeaseHandle;
import ai.labs.eddi.engine.cluster.lease.NatsLeaseManager;
import ai.labs.eddi.engine.cluster.rpc.NatsClusterRpc;
import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.runtime.IDescribedTask;
import ai.labs.eddi.engine.runtime.IDiscardableTask;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.ClusterConversationCoordinator;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.nats.client.api.StreamConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import ai.labs.eddi.engine.cluster.lease.KvLeaseManager;

/**
 * Three in-JVM "nodes" — each with its own NATS connection, presence, lease
 * manager, event bus, RPC and coordinator — against one real NATS JetStream
 * server. Proves the properties the cluster mode exists for: turns of one
 * conversation never overlap across nodes, a dead holder's lease is taken over,
 * a NATS outage degrades to local and recovers, events and RPCs reach the other
 * nodes, and dead letters are shared.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Cluster coordinator against real NATS")
class ClusterCoordinatorIT {

    /**
     * Fixed for the whole class — the outage test stops and restarts the container
     * and the clients must find it on the same port — but picked free at load, so a
     * busy port on a developer machine or CI runner does not fail the class.
     */
    private static final int NATS_HOST_PORT = freePort();

    /**
     * The {@code n} of a turn id {@code <nodeId>-<n>}; fails the test, not the
     * parse, on anything else.
     */
    private static int turnNumber(String turnId, String nodeId) {
        String digits = turnId.substring(nodeId.length() + 1);
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw new AssertionError("turn id " + turnId + " does not end in a turn number after " + nodeId + "-", e);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("no free local port for NATS", e);
        }
    }

    @SuppressWarnings("resource")
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.11-alpine").withExposedPorts(4222)
            .withCommand("--jetstream", "--store_dir=/data")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withPortBindings(new PortBinding(Ports.Binding.bindPort(NATS_HOST_PORT), new ExposedPort(4222))))
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    @BeforeAll
    static void startNats() {
        NATS.start();
    }

    @AfterAll
    static void stopNats() {
        NATS.stop();
    }

    /** One simulated EDDI node. */
    static final class Node {
        final String id;
        final NatsConnectionManager connections;
        final ClusterPresence presence;
        final NatsLeaseManager leases;
        final NatsSharedStateFactory shared;
        final JetStreamDeadLetterStore deadLetters;
        final JetStreamEventBus events;
        final NatsClusterRpc rpc;
        final ClusterConversationCoordinator coordinator;

        Node(String id, String prefix, IRuntime runtime) {
            this.id = id;
            ClusterConfig config = ClusterConfig.defaults().withMessagingType("nats")
                    .withNatsUrl("nats://127.0.0.1:" + NATS_HOST_PORT).withNatsPrefix(prefix).withNodeId(id)
                    .withLeaseTimings(Duration.ofSeconds(4), Duration.ofSeconds(1), Duration.ofSeconds(30))
                    .withPresenceInterval(Duration.ofSeconds(1)).withDegradedGrace(Duration.ofSeconds(2));
            var meters = new SimpleMeterRegistry();
            connections = new NatsConnectionManager(config, new NodeIdentity(id, UUID.randomUUID().toString().substring(0, 8)), meters);
            shared = new NatsSharedStateFactory(connections, meters);
            presence = new ClusterPresence(connections, shared, meters);
            leases = new NatsLeaseManager(connections, shared, presence, meters);
            deadLetters = new JetStreamDeadLetterStore(connections);
            events = new JetStreamEventBus(connections, meters);
            rpc = new NatsClusterRpc(connections, presence, meters);
            coordinator = new ClusterConversationCoordinator(runtime, meters, leases, deadLetters, connections, presence, 10_000, 1000,
                    Duration.ofSeconds(30));
            connections.start();
            presence.start();
            leases.start();
            events.startCluster();
            rpc.startCluster();
        }

        void awaitConnected() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 15_000;
            while (!connections.isConnected() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(connections.isConnected(), id + " did not connect");
        }

        void stop() {
            connections.shutdown();
        }
    }

    /** A runtime that runs callables on a pool, like BaseRuntime. */
    static final class PoolRuntime implements IRuntime {
        final ExecutorService pool = Executors.newCachedThreadPool();

        @Override
        public void init() {
        }

        @Override
        public String getVersion() {
            return "it";
        }

        @Override
        public ExecutorService getExecutorService() {
            return pool;
        }

        @Override
        public ScheduledExecutorService getScheduledExecutorService() {
            return null;
        }

        @Override
        public void logVersion() {
        }

        @Override
        public <T> Future<T> submitCallable(Callable<T> callable, Map<Object, Object> threadBindings) {
            return pool.submit(callable);
        }

        @Override
        public <T> Future<T> submitCallable(Callable<T> callable, IFinishedExecution<T> done, Map<Object, Object> threadBindings) {
            return pool.submit(() -> {
                try {
                    T result = callable.call();
                    done.onComplete(result);
                    return result;
                } catch (Throwable t) {
                    done.onFailure(t);
                    return null;
                }
            });
        }
    }

    private PoolRuntime runtime;
    private final List<Node> nodes = new ArrayList<>();
    private String prefix;

    @BeforeEach
    void setUp() throws Exception {
        runtime = new PoolRuntime();
        prefix = "IT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        for (int i = 1; i <= 3; i++) {
            nodes.add(new Node("n" + i, prefix, runtime));
        }
        for (Node n : nodes) {
            n.awaitConnected();
        }
    }

    @AfterEach
    void tearDown() {
        for (Node n : nodes) {
            n.stop();
        }
        nodes.clear();
        runtime.pool.shutdownNow();
    }

    /**
     * A turn that checks no other turn of the conversation runs at the same time.
     */
    static final class OverlapTurn implements IDiscardableTask, IDescribedTask {
        final AtomicInteger running;
        final AtomicInteger maxRunning;
        final List<String> order;
        final String name;
        final CountDownLatch done;
        final long workMillis;

        OverlapTurn(String name, AtomicInteger running, AtomicInteger maxRunning, List<String> order, CountDownLatch done, long workMillis) {
            this.name = name;
            this.running = running;
            this.maxRunning = maxRunning;
            this.order = order;
            this.done = done;
            this.workMillis = workMillis;
        }

        @Override
        public Void call() throws Exception {
            try {
                int now = running.incrementAndGet();
                maxRunning.accumulateAndGet(now, Math::max);
                order.add(name);
                Thread.sleep(workMillis);
                return null;
            } finally {
                running.decrementAndGet();
                done.countDown();
            }
        }

        @Override
        public void onDiscarded(Throwable cause) {
            order.add("DISCARDED " + name + ": " + cause);
            done.countDown();
        }

        @Override
        public Map<String, Object> describe() {
            return Map.of("input", name, "agentId", "a");
        }
    }

    @Test
    @Order(1)
    @DisplayName("3 × 50 concurrent turns of one conversation over 3 nodes never overlap and none is lost")
    void turnsNeverOverlapAcrossNodes() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        int perNode = 50;
        CountDownLatch done = new CountDownLatch(3 * perNode);
        String conversation = "65a1b2c3d4e5f60718293a4b";
        ExecutorService submitters = Executors.newFixedThreadPool(3);
        for (Node node : nodes) {
            submitters.execute(() -> {
                for (int i = 0; i < perNode; i++) {
                    node.coordinator.submitInOrder(conversation, new OverlapTurn(node.id + "-" + i, running, maxRunning, order, done, 2));
                }
            });
        }
        assertTrue(done.await(120, TimeUnit.SECONDS), "all turns ran; ran so far: " + order.size());
        submitters.shutdownNow();
        assertEquals(1, maxRunning.get(), "turns of one conversation overlapped across nodes");
        assertEquals(3 * perNode, order.size());
        assertTrue(order.stream().noneMatch(s -> s.startsWith("DISCARDED")), "no turn was discarded: " + order);
        // FIFO per node: each node's turns ran in its own submission order
        for (Node node : nodes) {
            List<Integer> mine = order.stream().filter(s -> s.startsWith(node.id + "-"))
                    .map(s -> turnNumber(s, node.id)).toList();
            List<Integer> sorted = new ArrayList<>(mine);
            Collections.sort(sorted);
            assertEquals(sorted, mine, node.id + " ran its own turns out of order");
        }
    }

    @Test
    @Order(2)
    @DisplayName("a crashed holder's lease is taken over within the TTL")
    void crashedHolderIsTakenOver() throws Exception {
        Node a = nodes.get(0);
        Node b = nodes.get(1);
        LeaseHandle held = a.leases.acquire("conv-crash", Duration.ofSeconds(5)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNotNull(held.fence());
        // "kill -9": the connection drops without releasing anything.
        a.connections.requireConnected().close();
        long start = System.currentTimeMillis();
        LeaseHandle taken = b.leases.acquire("conv-crash", Duration.ofSeconds(20)).toCompletableFuture().get(25, TimeUnit.SECONDS);
        long waited = System.currentTimeMillis() - start;
        assertTrue(taken.fence() > held.fence(), "the new holder's fence is larger");
        assertTrue(waited < 9_000, "taken over within TTL + poll, took " + waited + " ms");
    }

    @Test
    @Order(3)
    @DisplayName("events published on one node reach the others, not the origin")
    void eventsReachOtherNodes() throws Exception {
        Map<String, AtomicReference<ClusterEvent>> seen = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(2);
        for (Node n : nodes) {
            n.events.subscribe(ClusterEvent.SECRET_CHANGED, e -> {
                seen.computeIfAbsent(n.id, k -> new AtomicReference<>()).set(e);
                latch.countDown();
            });
        }
        Thread.sleep(500); // let the ordered consumers start
        nodes.get(0).events.publish(ClusterEvent.SECRET_CHANGED, Map.of("tenantId", "t", "keyName", "k"));
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        Thread.sleep(300);
        assertNull(seen.get("n1"), "the origin ignores its own event");
        assertEquals("k", seen.get("n2").get().getString("keyName"));
        assertEquals("n1", seen.get("n3").get().originNode());
    }

    @Test
    @Order(4)
    @DisplayName("an RPC reaches the addressed node and a scatter reaches every other node")
    void rpcReachesNodes() throws Exception {
        for (Node n : nodes) {
            n.rpc.handle("echo", req -> Map.of("echo", req.get("x"), "on", n.id));
        }
        Thread.sleep(1500); // presence records written
        var reply = nodes.get(0).rpc.call("n3", "echo", Map.of("x", "hello"));
        assertTrue(reply.isPresent());
        assertEquals("n3", reply.get().get("on"));
        var all = nodes.get(0).rpc.callAll("echo", Map.of("x", "all"));
        assertEquals(Set.of("n2", "n3"), all.keySet());
    }

    @Test
    @Order(5)
    @DisplayName("dead letters are shared: written on one node, listed, discarded and purged from another")
    void deadLettersAreShared() throws Exception {
        Thread.sleep(500);
        String id = nodes.get(0).deadLetters.append("conv-dl", "LLM said 500", 1L, Map.of("input", "hi", "agentId", "a"));
        List<DeadLetterEntry> listed = nodes.get(2).coordinator.getDeadLetters();
        assertTrue(listed.stream().anyMatch(e -> e.id().equals(id) && e.isReplayable()), "listed on another node: " + listed);
        assertTrue(nodes.get(1).coordinator.discardDeadLetter(id));
        assertFalse(nodes.get(2).coordinator.discardDeadLetter(id), "a discarded entry is gone everywhere");
        nodes.get(0).deadLetters.append("conv-dl2", "x", 2L, null);
        assertEquals(1, nodes.get(2).coordinator.purgeDeadLetters("conv-dl2"));
    }

    @Test
    @Order(6)
    @DisplayName("NATS down: turns run degraded (local, unfenced); NATS back: fenced again")
    void natsOutageDegradesAndRecovers() throws Exception {
        Node a = nodes.get(0);
        var docker = NATS.getDockerClient();
        String containerId = NATS.getContainerId();
        docker.stopContainerCmd(containerId).withTimeout(1).exec();
        try {
            long deadline = System.currentTimeMillis() + 15_000;
            while (!a.connections.isDegraded() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(a.connections.isDegraded(), "degraded after the grace");
            LeaseHandle local = a.leases.acquire("conv-degraded", Duration.ofSeconds(5)).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertNull(local.fence(), "degraded turns run unfenced (eddi.cluster.degraded.turns=local)");
        } finally {
            docker.startContainerCmd(containerId).exec();
        }
        long deadline = System.currentTimeMillis() + 30_000;
        while (!a.connections.isConnected() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(a.connections.isConnected(), "reconnected");
        LeaseHandle fenced = a.leases.acquire("conv-degraded", Duration.ofSeconds(10)).toCompletableFuture().get(15, TimeUnit.SECONDS);
        assertNotNull(fenced.fence(), "fenced again after recovery");
    }

    /**
     * Fencing tokens are lease revisions, and the stores keep the highest one seen.
     * A leases bucket recreated after NATS lost its data must not start below the
     * fences the stores already hold, or every write to those conversations is
     * refused for good: a new bucket starts at its creation time in microseconds.
     */
    @Test
    @Order(7)
    @DisplayName("a recreated leases bucket hands out fences above every earlier one")
    void recreatedLeasesBucketStaysAboveEarlierFences() throws Exception {
        long before = System.currentTimeMillis();
        Node a = nodes.get(0);
        LeaseHandle first = a.leases.acquire("conv-epoch", Duration.ofSeconds(5)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(first.fence() >= NatsSharedStateFactory.firstRevisionAt(before - 60_000),
                "a new leases bucket starts at its creation time, not at 1: " + first.fence());
        a.leases.release(first);

        a.connections.keyValueManagement().delete(prefix + "_LEASES");
        Thread.sleep(5);
        a.shared.provisionAll();

        LeaseHandle second = a.leases.acquire("conv-epoch", Duration.ofSeconds(5)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(second.fence() > first.fence(), "after recreation " + second.fence() + " must exceed " + first.fence());
        a.leases.release(second);
    }

    @Test
    @Order(8)
    @DisplayName("admin: a lease listed with its renewal time on another node, force-released there, re-acquired above the old fence")
    void adminForceReleaseAcrossNodes() throws Exception {
        Node a = nodes.get(0);
        Node b = nodes.get(1);
        Node c = nodes.get(2);
        LeaseHandle held = a.leases.acquire("conv-admin", Duration.ofSeconds(5)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        AtomicReference<Boolean> lost = new AtomicReference<>(false);
        held.onLost(() -> lost.set(true));

        // The listing is one KV watch; its renewal time is the server's write time.
        List<KvLeaseManager.LeaseSnapshot> listed = c.leases.snapshot("c.", 100);
        KvLeaseManager.LeaseSnapshot snapshot = listed.stream().filter(s -> s.key().equals("c.conv-admin")).findFirst().orElseThrow();
        assertEquals("n1", snapshot.holder().node());
        assertTrue(Math.abs(System.currentTimeMillis() - snapshot.renewedAt()) < 10_000, "renewedAt from the server: " + snapshot.renewedAt());

        // A stale expected revision is refused while the holder renews (heartbeat 1 s).
        long seen = snapshot.holder().revision();
        Thread.sleep(1_500);
        assertEquals(KvLeaseManager.ForceReleaseOutcome.RENEWED, c.leases.forceRelease("c.conv-admin", seen).outcome());

        KvLeaseManager.ForceRelease released = c.leases.forceRelease("c.conv-admin", null);
        assertEquals(KvLeaseManager.ForceReleaseOutcome.RELEASED, released.outcome());
        assertTrue(c.leases.peekSnapshot("c.conv-admin").isEmpty(), "gone from the bucket");
        assertEquals(KvLeaseManager.ForceReleaseOutcome.ALREADY_RELEASED, c.leases.forceRelease("c.conv-admin", null).outcome());

        LeaseHandle next = b.leases.acquire("conv-admin", Duration.ofSeconds(5)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(next.fence() > held.fence(), "the next holder's token is above the released one");
        long deadline = System.currentTimeMillis() + 5_000;
        while (!lost.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(lost.get(), "the former holder's heartbeat finds its lease gone");
        b.leases.release(next);
    }

    @Test
    @Order(9)
    @DisplayName("admin: a dead letter keeps its reason, node and fence; it is counted and found by conversation on another node")
    void deadLetterReasonRoundTrip() throws Exception {
        Thread.sleep(500);
        String id = nodes.get(0).deadLetters.append("conv-reason", "fenced", 3L, Map.of("input", "hi", "agentId", "a"), "fenced",
                Map.of("token", 5, "storedFence", 9));
        nodes.get(0).deadLetters.appendAudit("{}".getBytes(StandardCharsets.UTF_8));
        DeadLetterEntry read = nodes.get(2).deadLetters.listConversation("conv-reason", 10).get(0);
        assertEquals(id, read.id());
        assertEquals("fenced", read.reason());
        assertEquals("n1", read.nodeId());
        assertEquals(9, ((Number) read.fence().get("storedFence")).intValue());
        assertEquals(1, nodes.get(2).deadLetters.countTurns(), "audit entries parked in the same stream are not counted as turns");
    }

    @Test
    @Order(10)
    @DisplayName("a bucket an older build left with direct get is switched to leader reads in place; open handles recover on their next call")
    void directGetIsSwitchedOffInPlace() throws Exception {
        Node a = nodes.get(0);
        Node b = nodes.get(1);
        String bucket = prefix + "_LEASES";
        String stream = "KV_" + bucket;
        LeaseHandle before = a.leases.acquire("conv-direct", Duration.ofSeconds(5)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        a.leases.release(before);

        var jsm = a.connections.jetStreamManagement();
        List<String> buckets = jsm.getStreamNames().stream().filter(n -> n.startsWith("KV_" + prefix + "_")).toList();
        assertFalse(buckets.isEmpty());
        for (String name : buckets) {
            assertFalse(jsm.getStreamInfo(name).getConfiguration().getAllowDirect(), name + " was created with direct get on");
        }

        // What a build without leader reads leaves behind: the same bucket, direct get
        // on.
        StreamConfiguration created = jsm.getStreamInfo(stream).getConfiguration();
        jsm.updateStream(StreamConfiguration.builder(created).allowDirect(true).build());
        // A handle opened now reads with direct get, as an older node's would.
        NatsSharedKv older = new NatsSharedKv(b.connections, bucket, null);
        older.put("probe", "1".getBytes());
        assertTrue(older.get("probe").isPresent());

        a.shared.provisionAll();

        StreamConfiguration switched = jsm.getStreamInfo(stream).getConfiguration();
        assertFalse(switched.getAllowDirect(), "reads must be answered by the stream leader only");
        assertEquals(created.getFirstSequence(), switched.getFirstSequence(), "the fenced bucket keeps its first sequence");
        Optional<ISharedKv.Versioned> read = Optional.empty();
        for (int attempt = 0; attempt < 2 && read.isEmpty(); attempt++) {
            try {
                read = older.get("probe");
            } catch (ClusterUnavailableException expectedOnce) {
                // the direct-get handle gets no answer once; the next call reopens it
            }
        }
        assertTrue(read.isPresent(), "a handle opened before the switch must recover on its next call, not at the next reconnect");
        LeaseHandle after = a.leases.acquire("conv-direct", Duration.ofSeconds(5)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(after.fence() > before.fence(), "leases keep working and fences keep rising");
        a.leases.release(after);
    }

    @Test
    @Order(9)
    @DisplayName("concurrent creates of one lease key from three nodes: exactly one wins, every loser sees a conflict, none an outage")
    void concurrentCreatesOfOneKeyAreConflictsNotOutages() throws Exception {
        String key = "c.race-" + UUID.randomUUID();
        List<NatsSharedKv> handles = nodes.stream().map(n -> new NatsSharedKv(n.connections, prefix + "_LEASES", null)).toList();
        int perNode = 10;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        List<Throwable> outages = new CopyOnWriteArrayList<>();
        ExecutorService racers = Executors.newFixedThreadPool(handles.size() * perNode);
        try {
            List<Future<?>> all = new ArrayList<>();
            for (NatsSharedKv kv : handles) {
                for (int i = 0; i < perNode; i++) {
                    all.add(racers.submit(() -> {
                        start.await();
                        try {
                            if (kv.create(key, "x".getBytes()).isPresent()) {
                                won.incrementAndGet();
                            } else {
                                lost.incrementAndGet();
                            }
                        } catch (ClusterUnavailableException e) {
                            outages.add(e);
                        }
                        return null;
                    }));
                }
            }
            start.countDown();
            for (Future<?> f : all) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            racers.shutdownNow();
        }
        assertEquals(List.of(), outages.stream().map(Throwable::getMessage).toList(), "a lost race is not NATS being unreachable");
        assertEquals(1, won.get(), "exactly one create wins");
        assertEquals(handles.size() * perNode - 1, lost.get());
    }
}
