/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.admin;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ClusterPresence;
import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import ai.labs.eddi.engine.cluster.events.JetStreamEventBus;
import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.cluster.lease.KvLeaseManager.ForceRelease;
import ai.labs.eddi.engine.cluster.lease.KvLeaseManager.ForceReleaseOutcome;
import ai.labs.eddi.engine.cluster.lease.KvLeaseManager.LeaseSnapshot;
import ai.labs.eddi.engine.cluster.lease.LeaseInfo;
import ai.labs.eddi.engine.cluster.lease.NatsLeaseManager;
import ai.labs.eddi.engine.cluster.rpc.IClusterRpc;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.ClusterAdminModels.ActionResult;
import ai.labs.eddi.engine.model.ClusterAdminModels.BulkResult;
import ai.labs.eddi.engine.model.ClusterAdminModels.BucketView;
import ai.labs.eddi.engine.model.ClusterAdminModels.ClusterOverview;
import ai.labs.eddi.engine.model.ClusterAdminModels.DeadLetterPage;
import ai.labs.eddi.engine.model.ClusterAdminModels.DeadLetterSummary;
import ai.labs.eddi.engine.model.ClusterAdminModels.Diagnosis;
import ai.labs.eddi.engine.model.ClusterAdminModels.LeasePage;
import ai.labs.eddi.engine.model.ClusterAdminModels.LeaseView;
import ai.labs.eddi.engine.model.ClusterAdminModels.NatsView;
import ai.labs.eddi.engine.model.ClusterAdminModels.PeerView;
import ai.labs.eddi.engine.model.ClusterAdminModels.StreamView;
import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgentDeploymentManagement;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.internal.ClusterConversationCoordinator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.labs.eddi.engine.cluster.LocalSharedStateFactory;
import ai.labs.eddi.engine.model.ClusterAdminModels.ItemOutcome;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@DisplayName("ClusterAdminService")
class ClusterAdminServiceTest {

    /**
     * Reset per test: the first test of a fresh JVM can start many seconds after
     * class loading.
     */
    private static long NOW;

    private ClusterConfig config;
    private ClusterActivityLog activity;
    private ClusterWatcher watcher;
    private ClusterConversationCoordinator coordinator;
    private IConversationLeaseManager leaseManager;
    private IClusterRpc rpc;
    private NatsConnectionManager connections;
    private ClusterPresence presence;
    private NatsLeaseManager natsLeases;
    private JetStreamEventBus eventBus;
    private IConversationMemoryStore memoryStore;
    private IConversationService conversationService;
    private IAgentDeploymentManagement deployments;
    private AuditLedgerService audit;
    private ClusterAdminService service;
    /**
     * One shared bucket per test: two services built on it are two nodes of one
     * cluster.
     */
    private LocalSharedStateFactory shared;
    private final List<Map<String, Object>> members = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance(T value) {
        Instance<T> instance = mock(Instance.class);
        when(instance.get()).thenReturn(value);
        when(instance.isResolvable()).thenReturn(value != null);
        return instance;
    }

    private static Map<String, Object> member(String node, String boot, long updatedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node", node);
        m.put("boot", boot);
        m.put("updatedAt", updatedAt);
        m.put("startedAt", NOW - 60_000);
        m.put("leasesHeld", 1);
        return m;
    }

    @BeforeEach
    void setUp() {
        NOW = System.currentTimeMillis();
        config = ClusterConfig.defaults().withMessagingType("nats");
        activity = mock(ClusterActivityLog.class);
        watcher = mock(ClusterWatcher.class);
        when(watcher.goneNodes()).thenReturn(Map.of());
        coordinator = mock(ClusterConversationCoordinator.class);
        leaseManager = mock(IConversationLeaseManager.class);
        rpc = mock(IClusterRpc.class);
        connections = mock(NatsConnectionManager.class);
        when(connections.node()).thenReturn(new NodeIdentity("n1", "b1"));
        when(connections.isConnected()).thenReturn(true);
        when(connections.status()).thenReturn(NatsConnectionManager.Status.CONNECTED);
        presence = mock(ClusterPresence.class);
        members.clear();
        members.add(member("n1", "b1", NOW));
        members.add(member("n2", "b2", NOW));
        members.add(member("n3", "b3", NOW));
        when(presence.members()).thenAnswer(i -> List.copyOf(members));
        when(presence.selfRecord()).thenAnswer(i -> members.get(0));
        natsLeases = mock(NatsLeaseManager.class);
        eventBus = mock(JetStreamEventBus.class);
        memoryStore = mock(IConversationMemoryStore.class);
        conversationService = mock(IConversationService.class);
        deployments = mock(IAgentDeploymentManagement.class);
        audit = mock(AuditLedgerService.class);
        shared = new LocalSharedStateFactory();
        service = newService(config);
    }

    private ClusterAdminService newService(ClusterConfig cfg) {
        return new ClusterAdminService(cfg, activity, watcher, coordinator, leaseManager, rpc, instance(connections), instance(presence),
                instance(natsLeases), instance(eventBus), memoryStore, conversationService, deployments, audit, new SimpleMeterRegistry(),
                shared, instance(null));
    }

    @Test
    @DisplayName("the cluster coordinator is found by its type, not by casting the injected proxy (forwarding used to report 0)")
    void clusterCoordinatorBehindAProxy() {
        IConversationCoordinator proxy = mock(IConversationCoordinator.class);
        when(coordinator.forwardLocalDeadLetters()).thenReturn(3);
        when(rpc.callAll(ClusterAdminService.RPC_FORWARD, Map.of())).thenReturn(Map.of());
        ClusterAdminService behindProxy = new ClusterAdminService(config, activity, watcher, proxy, leaseManager, rpc, instance(connections),
                instance(presence), instance(natsLeases), instance(eventBus), memoryStore, conversationService, deployments, audit,
                new SimpleMeterRegistry(), shared, instance(coordinator));
        assertEquals(3, behindProxy.forwardLocalDeadLetters("a").details().get("total"));
    }

    private static DeadLetterEntry entry(String id, String reason, String node, Map<String, Object> turn) {
        return new DeadLetterEntry(id, "conv-" + id, "boom " + id, NOW - 1000, "{}", turn, reason, node, null);
    }

    private static Map<String, Object> turn(String agent, String input) {
        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("agentId", agent);
        turn.put("agentVersion", 2);
        if (input != null) {
            turn.put("input", input);
        }
        return turn;
    }

    @Nested
    @DisplayName("overview")
    class Overview {

        @Test
        @DisplayName("single node: SINGLE_NODE, one card, no NATS section")
        void singleNode() {
            IConversationCoordinator inMemory = mock(IConversationCoordinator.class);
            when(inMemory.getQueueDepths()).thenReturn(Map.of("c1", 2));
            when(inMemory.getDeadLetters()).thenReturn(List.of());
            ClusterAdminService single = new ClusterAdminService(ClusterConfig.defaults(), activity, watcher, inMemory, leaseManager, rpc,
                    instance(null), instance(null), instance(null), instance(null), memoryStore, conversationService, deployments, audit,
                    new SimpleMeterRegistry(), new LocalSharedStateFactory(), instance(null));
            ClusterOverview overview = single.overview();
            assertEquals("SINGLE_NODE", overview.verdict());
            assertEquals("single-node", overview.mode());
            assertEquals(1, overview.nodes().size());
            assertEquals(2, overview.nodes().get(0).queueDepthTotal());
            assertNull(overview.nats());
        }

        @Test
        @DisplayName("NATS unreachable from this node: DEGRADED with NATS_UNREACHABLE and the degraded-since time")
        void natsUnreachable() {
            when(connections.isConnected()).thenReturn(false);
            when(connections.isDegraded()).thenReturn(true);
            when(connections.unavailableSinceMillis()).thenReturn(NOW - 30_000);
            when(presence.selfRecord()).thenReturn(member("n1", "b1", NOW));
            ClusterOverview overview = service.overview();
            assertEquals("DEGRADED", overview.verdict());
            assertTrue(overview.reasons().contains("NATS_UNREACHABLE"));
            assertEquals(NOW - 30_000, overview.degradedSince());
            assertEquals("local", overview.degradedTurnsPolicy());
            // Cut off, this node cannot tell whether the others still run: unknown, not
            // late.
            assertEquals("UNKNOWN", overview.nodes().stream().filter(n -> n.nodeId().equals("n2")).findFirst().orElseThrow().state());
            assertFalse(overview.reasons().contains("NODE_STALE"));
        }

        @Test
        @DisplayName("this node's card shows its live numbers, not its last published record (dead letters kept while NATS was down)")
        void selfCardIsLive() {
            Map<String, Object> live = member("n1", "b1", NOW);
            live.put("localDeadLetters", 2);
            when(presence.selfRecord()).thenReturn(live);
            ClusterOverview overview = service.overview();
            assertEquals(2, overview.nodes().stream().filter(n -> n.nodeId().equals("n1")).findFirst().orElseThrow().localDeadLetters());
            assertTrue(overview.reasons().contains("LOCAL_DEAD_LETTERS"));
            assertEquals(2, overview.deadLetters().local());
        }

        @Test
        @DisplayName("a node that stopped heartbeating without leaving: DEGRADED, its card is LOST")
        void lostNode() {
            members.removeIf(m -> m.get("node").equals("n3"));
            when(watcher.goneNodes()).thenReturn(Map.of("n3", new ClusterWatcher.GoneNode(member("n3", "b3", NOW - 40_000), NOW, false)));
            ClusterOverview overview = service.overview();
            assertEquals("DEGRADED", overview.verdict());
            assertTrue(overview.reasons().contains("NODE_LOST"));
            assertEquals("LOST", overview.nodes().stream().filter(n -> n.nodeId().equals("n3")).findFirst().orElseThrow().state());
        }

        @Test
        @DisplayName("a node that shut down cleanly is LEFT and does not degrade the verdict")
        void leftNode() {
            members.removeIf(m -> m.get("node").equals("n3"));
            when(watcher.goneNodes()).thenReturn(Map.of("n3", new ClusterWatcher.GoneNode(member("n3", "b3", NOW - 2_000), NOW, true)));
            ClusterOverview overview = service.overview();
            assertEquals("HEALTHY", overview.verdict());
            assertEquals("LEFT", overview.nodes().stream().filter(n -> n.nodeId().equals("n3")).findFirst().orElseThrow().state());
        }

        @Test
        @DisplayName("a member whose heartbeat is late is STALE and degrades the verdict")
        void staleMember() {
            members.set(1, member("n2", "b2", NOW - 25_000));
            ClusterOverview overview = service.overview();
            assertEquals("DEGRADED", overview.verdict());
            assertEquals("STALE", overview.nodes().stream().filter(n -> n.nodeId().equals("n2")).findFirst().orElseThrow().state());
        }

        private ClusterAdminService withReplicas(boolean offline, boolean current) {
            ClusterAdminService spied = spy(service);
            List<PeerView> peers = List.of(new PeerView("nats-1", true, false, 0, 0L), new PeerView("nats-2", current, offline, 0, null));
            doReturn(new NatsView("CONNECTED", "nats://nats-1:4222", "nats-1", "2.11", "c1", List.of(), 0, 1,
                    List.of(new StreamView("EDDI_EVENTS", "events", 3, 0, 0, 0, 0, 0, "nats-1", peers, List.of())),
                    List.of(new BucketView("EDDI_LEASES", "LEASES", 3, 0, 0, null, "nats-1", peers)), null, null, null)).when(spied)
                    .natsView(any());
            return spied;
        }

        @Test
        @DisplayName("a JetStream peer that is offline makes the verdict PARTITIONED, with NATS_PEER_OFFLINE")
        void offlinePeerIsPartitioned() {
            ClusterOverview overview = withReplicas(true, false).overview();
            assertEquals("PARTITIONED", overview.verdict());
            assertTrue(overview.reasons().contains("NATS_PEER_OFFLINE"));
        }

        @Test
        @DisplayName("a replica that is only behind (not offline) is DEGRADED with NATS_REPLICA_BEHIND, never PARTITIONED")
        void behindReplicaIsDegradedNotPartitioned() {
            ClusterOverview overview = withReplicas(false, false).overview();
            assertEquals("DEGRADED", overview.verdict());
            assertTrue(overview.reasons().contains("NATS_REPLICA_BEHIND"));
            assertFalse(overview.reasons().contains("NATS_PEER_OFFLINE"));
        }

        @Test
        @DisplayName("every replica current: HEALTHY")
        void currentReplicasAreHealthy() {
            assertEquals("HEALTHY", withReplicas(false, true).overview().verdict());
        }

        @Test
        @DisplayName("the HITL recovery leader is marked on its card")
        void hitlLeader() {
            when(leaseManager.peekKey("leader.hitl-recovery")).thenReturn(Optional.of(new LeaseInfo("n2", "b2", 5, NOW)));
            ClusterOverview overview = service.overview();
            assertEquals("n2", overview.hitlLeader());
            assertTrue(overview.nodes().stream().filter(n -> n.nodeId().equals("n2")).findFirst().orElseThrow().hitlLeader());
        }
    }

    @Nested
    @DisplayName("leases")
    class Leases {

        @Test
        @DisplayName("flags a gone holder, a missed renewal and a waiting node; suspicious first; agents read in one call")
        void flags() throws Exception {
            long renewedLongAgo = NOW - 60_000;
            when(natsLeases.snapshot("", ClusterAdminService.MAX_LEASE_SCAN)).thenReturn(List.of(
                    new LeaseSnapshot("c.healthy", new LeaseInfo("n2", "b2", 10, NOW - 1_000), NOW - 500, null),
                    new LeaseSnapshot("c.orphan", new LeaseInfo("n9", "b9", 11, NOW - 90_000), renewedLongAgo, "n1"),
                    new LeaseSnapshot("leader.hitl-recovery", new LeaseInfo("n1", "b1", 12, NOW - 5_000), NOW - 100, null)));
            when(memoryStore.loadListingSummaries(anyCollection())).thenReturn(Map.of("orphan",
                    new ConversationListingSummary("orphan", "u", null, ConversationState.IN_PROGRESS, "agent-7", 1, 3)));
            LeasePage page = service.leases(null, false, 100);
            assertEquals(3, page.total());
            assertEquals(1, page.suspicious());
            LeaseView first = page.leases().get(0);
            assertEquals("orphan", first.conversationId());
            assertEquals("agent-7", first.agentId());
            assertEquals("IN_PROGRESS", first.conversationState());
            assertTrue(first.flags().containsAll(List.of("HOLDER_GONE", "NOT_RENEWED", "LONG_RUNNING", "CONTENDED")), first.flags().toString());
            assertEquals("GONE", first.holderStatus());
            verify(memoryStore, times(1)).loadListingSummaries(anyCollection());

            assertEquals(1, service.leases(null, true, 100).leases().size());
            assertEquals(1, service.leases("agent-7", false, 100).leases().size());
            assertEquals("leader", service.leases("hitl", false, 100).leases().get(0).kind());
        }

        @Test
        @DisplayName("a holder that came back with a new boot is RESTARTED")
        void restartedHolder() {
            when(natsLeases.snapshot(anyString(), anyInt()))
                    .thenReturn(List.of(new LeaseSnapshot("c.x", new LeaseInfo("n2", "old-boot", 10, NOW), NOW, null)));
            LeaseView v = service.leases(null, false, 10).leases().get(0);
            assertEquals("RESTARTED", v.holderStatus());
            assertTrue(v.flags().contains("HOLDER_RESTARTED"));
        }

        @Test
        @DisplayName("a single node has no leases to list")
        void singleNodeEmpty() {
            assertEquals(0, newService(ClusterConfig.defaults()).leases(null, false, 10).total());
        }
    }

    @Nested
    @DisplayName("dead letters")
    class DeadLetters {

        @BeforeEach
        void entries() {
            List<DeadLetterEntry> all = List.of(entry("1", "fenced", "n1", turn("a1", "hello")), entry("2", "failed", "n2", turn("a2", "x")),
                    entry("3", "fenced", "n2", turn("a1", "y")), entry("local-1", "timeout", "n3", turn("a1", "z")));
            when(coordinator.getDeadLetters(anyInt(), any())).thenAnswer(i -> {
                int limit = i.getArgument(0);
                String after = i.getArgument(1);
                int start = 0;
                if (after != null) {
                    for (int k = 0; k < all.size(); k++) {
                        if (all.get(k).id().equals(after)) {
                            start = k + 1;
                        }
                    }
                }
                return all.subList(Math.min(start, all.size()), Math.min(all.size(), start + limit));
            });
        }

        @Test
        @DisplayName("filters by reason and node, and pages with a cursor")
        void filtersAndPages() {
            DeadLetterPage fenced = service.deadLetters(50, null, new ClusterAdminService.DeadLetterFilter("fenced", null, null, null, null, null),
                    true);
            assertEquals(List.of("1", "3"), fenced.entries().stream().map(e -> e.id()).toList());
            assertNull(fenced.nextCursor());
            assertEquals("hello", fenced.entries().get(0).input());

            DeadLetterPage byNode = service.deadLetters(1, null, new ClusterAdminService.DeadLetterFilter(null, "n2", null, null, null, null),
                    true);
            assertEquals(List.of("2"), byNode.entries().stream().map(e -> e.id()).toList());
            assertEquals("2", byNode.nextCursor());
            DeadLetterPage next = service.deadLetters(1, byNode.nextCursor(),
                    new ClusterAdminService.DeadLetterFilter(null, "n2", null, null, null, null), true);
            assertEquals(List.of("3"), next.entries().stream().map(e -> e.id()).toList());
            assertTrue(service.deadLetters(50, null, new ClusterAdminService.DeadLetterFilter(null, null, null, null, null, null), true)
                    .entries().stream().anyMatch(e -> e.local()));
        }

        @Test
        @DisplayName("the summary counts by reason, node and agent and never carries input")
        void summaryWithoutInput() {
            DeadLetterSummary summary = service.deadLetterSummary();
            assertEquals(4, summary.total());
            assertEquals(1, summary.local());
            assertEquals(2L, summary.byReason().get("fenced"));
            assertEquals(2L, summary.byNode().get("n2"));
            assertEquals(3L, summary.byAgent().get("a1"));
            assertTrue(summary.recent().stream().allMatch(e -> e.input() == null));
            assertTrue(summary.recent().stream().allMatch(e -> e.error() == null), "an exception message can quote user content");
        }
    }

    @Nested
    @DisplayName("bulk replay and discard")
    class Bulk {

        @Test
        @DisplayName("reports every id's outcome; a secret entry is NOT_REPLAYABLE with its reason; one audit entry for the batch")
        void replayOutcomes() throws Exception {
            Map<String, Object> secret = turn("a1", null);
            secret.put("secretInput", true);
            when(coordinator.getDeadLetter("ok")).thenReturn(Optional.of(entry("ok", "fenced", "n1", turn("a1", "hi"))));
            when(coordinator.getDeadLetter("secret")).thenReturn(Optional.of(entry("secret", "failed", "n1", secret)));
            when(coordinator.getDeadLetter("gone")).thenReturn(Optional.empty());
            when(coordinator.getDeadLetter("busy")).thenReturn(Optional.of(entry("busy", "failed", "n1", turn("a1", "x"))));
            when(coordinator.getDeadLetter("down")).thenThrow(new ClusterUnavailableException("down"));
            doThrow(new IllegalStateException("conversation ended")).when(conversationService).say(eq("conv-busy"), any(), any(), any(),
                    any(), anyBoolean(), any());

            BulkResult result = service.replay(List.of("ok", "secret", "gone", "busy", "down", "ok"), "admin-1");
            Map<String, String> outcomes = new LinkedHashMap<>();
            result.results().forEach(r -> outcomes.put(r.id(), r.outcome()));
            assertEquals(Map.of("ok", "REPLAYED", "secret", "NOT_REPLAYABLE", "gone", "NOT_FOUND", "busy", "REJECTED", "down", "UNAVAILABLE"),
                    outcomes);
            assertEquals("SECRET_INPUT", result.results().get(1).message());
            assertEquals(1, result.succeeded());
            assertEquals(4, result.failed());

            ArgumentCaptor<InputData> input = ArgumentCaptor.forClass(InputData.class);
            verify(conversationService).say(eq("conv-ok"), any(), any(), any(), input.capture(), eq(false), any());
            assertEquals("hi", input.getValue().getInput());
            assertEquals("ok", input.getValue().getContext().get("replayOf").getValue());
            verify(coordinator).replayDeadLetter("ok");
            verify(coordinator, never()).replayDeadLetter("busy");

            ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
            verify(audit, times(1)).submit(entry.capture());
            assertEquals(ClusterAdminService.AUDIT_TRAIL, entry.getValue().conversationId());
            assertEquals("admin-1", entry.getValue().userId());
            assertEquals(List.of("deadletters.replay"), entry.getValue().actions());
        }

        @Test
        @DisplayName("two administrators replaying one entry at the same moment: the turn runs once, the other is told IN_PROGRESS")
        void concurrentReplayRunsOnce() throws Exception {
            AtomicBoolean present = new AtomicBoolean(true);
            when(coordinator.getDeadLetter("7")).thenAnswer(i -> present.get()
                    ? Optional.of(entry("7", "fenced", "n1", turn("a1", "hi")))
                    : Optional.empty());
            when(coordinator.replayDeadLetter("7")).thenAnswer(i -> {
                present.set(false);
                return true;
            });
            CountDownLatch inSay = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(i -> {
                inSay.countDown();
                release.await(5, TimeUnit.SECONDS);
                return null;
            }).when(conversationService).say(eq("conv-7"), any(), any(), any(), any(), anyBoolean(), any());
            ClusterAdminService other = newService(config);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<ItemOutcome> a = pool.submit(() -> service.replayOne("7"));
                assertTrue(inSay.await(5, TimeUnit.SECONDS));
                ItemOutcome b = pool.submit(() -> other.replayOne("7")).get(5, TimeUnit.SECONDS);
                release.countDown();
                assertEquals("REPLAYED", a.get(5, TimeUnit.SECONDS).outcome());
                assertEquals("IN_PROGRESS", b.outcome());
                // the claim is gone and so is the entry: a late third replay finds nothing to
                // run
                assertEquals("NOT_FOUND", other.replayOne("7").outcome());
                verify(conversationService, times(1)).say(eq("conv-7"), any(), any(), any(), any(), anyBoolean(), any());
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("a rejected replay keeps the entry and releases the claim, so it can be replayed once the cause is fixed")
        void rejectedReplayCanBeRetried() throws Exception {
            when(coordinator.getDeadLetter("8")).thenReturn(Optional.of(entry("8", "failed", "n1", turn("a1", "x"))));
            doThrow(new IllegalStateException("conversation ended")).doNothing().when(conversationService).say(eq("conv-8"), any(), any(),
                    any(), any(), anyBoolean(), any());
            assertEquals("REJECTED", service.replayOne("8").outcome());
            verify(coordinator, never()).replayDeadLetter("8");
            assertEquals("REPLAYED", service.replayOne("8").outcome());
        }

        @Test
        @DisplayName("discard reports DISCARDED and NOT_FOUND per id")
        void discardOutcomes() {
            when(coordinator.discardDeadLetter("1")).thenReturn(true);
            when(coordinator.discardDeadLetter("2")).thenReturn(false);
            BulkResult result = service.discard(List.of("1", "2"), "admin-1");
            assertEquals("DISCARDED", result.results().get(0).outcome());
            assertEquals("NOT_FOUND", result.results().get(1).outcome());
            verify(audit).submit(any());
        }

        @Test
        @DisplayName("more than 100 ids, or none, is refused before anything happens")
        void bounded() {
            List<String> many = IntStream.range(0, 101).mapToObj(String::valueOf).toList();
            assertThrows(IllegalArgumentException.class, () -> service.discard(many, "a"));
            assertThrows(IllegalArgumentException.class, () -> service.replay(List.of(), "a"));
            verifyNoInteractions(audit);
        }
    }

    @Nested
    @DisplayName("recovery actions")
    class Actions {

        @Test
        @DisplayName("force-release is audited and recorded with its outcome")
        void forceRelease() {
            when(natsLeases.forceRelease("c.conv1", 42L))
                    .thenReturn(new ForceRelease(ForceReleaseOutcome.RELEASED, new LeaseInfo("n2", "b2", 42, NOW), 42));
            ActionResult result = service.forceRelease("conv1", 42L, "admin-1");
            assertEquals("RELEASED", result.outcome());
            assertEquals("n2", result.details().get("holderNode"));
            verify(audit).submit(argThat(e -> e.actions().equals(List.of("lease.release")) && "admin-1".equals(e.userId())));
            verify(activity).record(eq("admin.lease.release"), anyString(), anyMap(), isNull());
        }

        @Test
        @DisplayName("a renewed lease is reported as RENEWED, not released")
        void renewed() {
            when(natsLeases.forceRelease("c.conv1", 1L))
                    .thenReturn(new ForceRelease(ForceReleaseOutcome.RENEWED, new LeaseInfo("n2", "b2", 5, NOW), 5));
            ActionResult result = service.forceRelease("conv1", 1L, "admin-1");
            assertEquals("RENEWED", result.outcome());
            assertEquals("5", result.details().get("currentRevision"), "tokens travel as strings: a browser would round them");
        }

        @Test
        @DisplayName("cluster actions are refused on a single node")
        void refusedOnSingleNode() {
            ClusterAdminService single = newService(ClusterConfig.defaults());
            var e = assertThrows(ClusterAdminService.ActionRefusedException.class, () -> single.forceRelease("c", null, "a"));
            assertEquals(409, e.status());
            assertThrows(ClusterAdminService.ActionRefusedException.class, () -> single.drain("n1", true, "a"));
            assertThrows(ClusterAdminService.ActionRefusedException.class, () -> single.resyncCaches("a"));
            verifyNoInteractions(audit);
        }

        @Test
        @DisplayName("draining the last node that takes turns is refused")
        void lastNodeNotDrained() {
            drainKey("n2");
            drainKey("n3");
            var e = assertThrows(ClusterAdminService.ActionRefusedException.class, () -> service.drain("n1", true, "a"));
            assertEquals("LAST_NODE", e.code());
            verify(natsLeases, never()).setDraining(anyBoolean());
        }

        private void drainKey(String node) {
            shared.bucket(ClusterAdminService.ADMIN_BUCKET).put(ClusterAdminService.DRAIN_PREFIX + node, new byte[]{1});
        }

        @Test
        @DisplayName("the guard counts drains from the shared drain keys, not from presence records that may be 5 s old")
        void lastNodeGuardDoesNotTrustStalePresence() {
            when(rpc.call(anyString(), eq(ClusterAdminService.RPC_DRAIN), anyMap())).thenReturn(Optional.of(Map.of("draining", true)));
            members.removeIf(m -> m.get("node").equals("n1")); // n1 answers but is not a member: two nodes take turns
            // Another admin drained n2 a moment ago; no presence record says so yet.
            assertEquals("DRAINED", newService(config).drain("n2", true, "admin-b").outcome());
            var e = assertThrows(ClusterAdminService.ActionRefusedException.class, () -> service.drain("n3", true, "admin-a"));
            assertEquals("LAST_NODE", e.code());
        }

        @Test
        @DisplayName("two administrators draining the last two nodes at the same moment: exactly one succeeds")
        void concurrentDrainOfTheLastTwoNodes() throws Exception {
            drainKey("n1");
            CountDownLatch inRpc = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(rpc.call(anyString(), eq(ClusterAdminService.RPC_DRAIN), anyMap())).thenAnswer(i -> {
                inRpc.countDown();
                release.await(5, TimeUnit.SECONDS);
                return Optional.of(Map.of("draining", true));
            });
            ClusterAdminService other = newService(config);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<String> a = pool.submit(() -> outcome(() -> service.drain("n2", true, "admin-a")));
                assertTrue(inRpc.await(5, TimeUnit.SECONDS));
                Future<String> b = pool.submit(() -> outcome(() -> other.drain("n3", true, "admin-b")));
                String second = b.get(5, TimeUnit.SECONDS); // decided while the first is still applying
                release.countDown();
                assertEquals("DRAINED", a.get(5, TimeUnit.SECONDS));
                assertEquals("BUSY", second);
                // and once the first finished, the second is refused for the right reason
                assertEquals("LAST_NODE", outcome(() -> other.drain("n3", true, "admin-b")));
            } finally {
                pool.shutdownNow();
            }
        }

        private String outcome(Supplier<ActionResult> action) {
            try {
                return action.get().outcome();
            } catch (ClusterAdminService.ActionRefusedException e) {
                return e.code();
            }
        }

        @Test
        @DisplayName("draining this node applies locally; another node is asked over RPC; an unknown node is 404")
        void drainRouting() {
            ActionResult self = service.drain("n1", true, "a");
            assertEquals("DRAINED", self.outcome());
            verify(natsLeases).setDraining(true);
            verify(presence).publishNow();

            when(rpc.call("n2", ClusterAdminService.RPC_DRAIN, Map.of("drain", true))).thenReturn(Optional.of(Map.of("draining", true)));
            assertEquals("DRAINED", service.drain("n2", true, "a").outcome());

            when(rpc.call("n3", ClusterAdminService.RPC_DRAIN, Map.of("drain", false))).thenReturn(Optional.empty());
            var unreachable = assertThrows(ClusterAdminService.ActionRefusedException.class, () -> service.drain("n3", false, "a"));
            assertEquals(409, unreachable.status());

            var missing = assertThrows(ClusterAdminService.ActionRefusedException.class, () -> service.drain("n9", true, "a"));
            assertEquals(404, missing.status());
        }

        @Test
        @DisplayName("resync asks every node through the event bus; reconcile runs here and on every other node")
        void resyncAndReconcile() {
            when(eventBus.requestResyncAll(anyString())).thenReturn(true);
            assertEquals("DONE", service.resyncCaches("a").outcome());
            verify(eventBus).requestResyncAll(anyString());
            when(rpc.callAll(ClusterAdminService.RPC_RECONCILE, Map.of())).thenReturn(Map.of("n2", Map.of(), "n3", Map.of()));
            ActionResult result = service.reconcileDeployments("a");
            verify(deployments).reconcileNow();
            assertEquals("DONE", result.outcome());
            assertEquals(3, ((List<?>) result.details().get("nodes")).size());
        }

        @Test
        @DisplayName("a resync that only reached the outbox (NATS down) is QUEUED, not DONE — and audited as such")
        void resyncQueuedWhileDisconnected() {
            when(eventBus.requestResyncAll(anyString())).thenReturn(false);
            ActionResult result = service.resyncCaches("a");
            assertEquals("QUEUED", result.outcome());
            assertTrue(result.message().contains("outbox"));
            verify(audit).submit(argThat(e -> "QUEUED".equals(e.output().get("outcome"))));
        }

        @Test
        @DisplayName("a reconcile some members did not answer is PARTIAL and names them")
        void reconcilePartial() {
            when(rpc.callAll(ClusterAdminService.RPC_RECONCILE, Map.of())).thenReturn(Map.of("n2", Map.of()));
            ActionResult result = service.reconcileDeployments("a");
            assertEquals("PARTIAL", result.outcome());
            assertEquals(List.of("n3"), result.details().get("missing"));
            // a reply carrying an error does not count as having run
            when(rpc.callAll(ClusterAdminService.RPC_RECONCILE, Map.of()))
                    .thenReturn(Map.of("n2", Map.of(), "n3", Map.of("error", "no handler for admin-reconcile")));
            assertEquals("PARTIAL", service.reconcileDeployments("a").outcome());
        }

        @Test
        @DisplayName("forwarding that leaves entries local, or misses a node, is PARTIAL")
        void forwardPartial() {
            when(coordinator.forwardLocalDeadLetters()).thenReturn(1);
            when(coordinator.localDeadLetterCount()).thenReturn(2);
            when(rpc.callAll(ClusterAdminService.RPC_FORWARD, Map.of()))
                    .thenReturn(Map.of("n2", Map.of("forwarded", 0, "remaining", 0), "n3", Map.of("forwarded", 0, "remaining", 0)));
            ActionResult stuck = service.forwardLocalDeadLetters("a");
            assertEquals("PARTIAL", stuck.outcome());
            assertTrue(stuck.message().contains("2 are still kept locally"));
            when(coordinator.localDeadLetterCount()).thenReturn(0);
            when(rpc.callAll(ClusterAdminService.RPC_FORWARD, Map.of())).thenReturn(Map.of("n2", Map.of("forwarded", 0, "remaining", 0)));
            ActionResult missing = service.forwardLocalDeadLetters("a");
            assertEquals("PARTIAL", missing.outcome());
            assertEquals(List.of("n3"), missing.details().get("missing"));
        }

        @Test
        @DisplayName("forwarding local dead letters sums every node's count; refused while this node has no NATS")
        void forward() {
            when(coordinator.forwardLocalDeadLetters()).thenReturn(2);
            when(rpc.callAll(ClusterAdminService.RPC_FORWARD, Map.of()))
                    .thenReturn(Map.of("n2", Map.of("forwarded", 3, "remaining", 0), "n3", Map.of("forwarded", 0, "remaining", 0)));
            ActionResult done = service.forwardLocalDeadLetters("a");
            assertEquals(5, done.details().get("total"));
            assertEquals("DONE", done.outcome());
            when(connections.isConnected()).thenReturn(false);
            assertThrows(ClusterAdminService.ActionRefusedException.class, () -> service.forwardLocalDeadLetters("a"));
        }
    }

    @Nested
    @DisplayName("diagnosis")
    class Diagnose {

        @Test
        @DisplayName("an orphaned lease makes the conversation STUCK with FORCE_RELEASE suggested")
        void orphanedLease() throws Exception {
            when(memoryStore.loadListingSummaries(anyCollection())).thenReturn(
                    Map.of("conv1", new ConversationListingSummary("conv1", "u", null, ConversationState.IN_PROGRESS, "agent-1", 3, 4)));
            when(natsLeases.peekSnapshot("c.conv1"))
                    .thenReturn(Optional.of(new LeaseSnapshot("c.conv1", new LeaseInfo("n9", "b9", 7, NOW - 70_000), NOW - 60_000, null)));
            when(rpc.callAll(IClusterRpc.COORDINATOR_STATUS, Map.of())).thenReturn(Map.of("n2", Map.of("queueDepths", Map.of("conv1", 2))));
            when(coordinator.getDeadLettersOf("conv1", 20)).thenReturn(List.of(entry("9", "fenced", "n9", turn("agent-1", "hi"))));
            Diagnosis d = service.diagnose("conv1");
            assertEquals("STUCK", d.verdict());
            assertEquals("agent-1", d.agentId());
            assertEquals(2, d.queuedOn().get("n2"));
            assertTrue(d.findings().stream().anyMatch(f -> f.code().equals("LEASE_ORPHANED") && f.action().equals("FORCE_RELEASE")));
            assertTrue(d.findings().stream().anyMatch(f -> f.code().equals("DEAD_LETTERED") && f.action().equals("REPLAY")));
            assertNull(d.deadLetters().get(0).input(), "the diagnosis is readable by eddi-viewer and must not carry input");
        }

        @Test
        @DisplayName("an unknown conversation is NOT_FOUND")
        void notFound() throws Exception {
            when(memoryStore.loadListingSummaries(anyCollection())).thenReturn(Map.of());
            when(natsLeases.peekSnapshot(anyString())).thenReturn(Optional.empty());
            when(rpc.callAll(anyString(), anyMap())).thenReturn(Map.of());
            when(coordinator.getDeadLettersOf(anyString(), anyInt())).thenReturn(List.of());
            assertEquals("NOT_FOUND", service.diagnose("nope").verdict());
        }

        @Test
        @DisplayName("a live, renewing holder is BUSY with WAIT")
        void running() throws Exception {
            when(memoryStore.loadListingSummaries(anyCollection())).thenReturn(
                    Map.of("conv1", new ConversationListingSummary("conv1", "u", null, ConversationState.IN_PROGRESS, "agent-1", 3, 4)));
            when(natsLeases.peekSnapshot("c.conv1"))
                    .thenReturn(Optional.of(new LeaseSnapshot("c.conv1", new LeaseInfo("n2", "b2", 7, NOW - 2_000), NOW - 1_000, null)));
            when(rpc.callAll(anyString(), anyMap())).thenReturn(Map.of());
            when(coordinator.getDeadLettersOf(anyString(), anyInt())).thenReturn(List.of());
            Diagnosis d = service.diagnose("conv1");
            assertEquals("BUSY", d.verdict());
            assertEquals("WAIT", d.findings().get(0).action());
        }
    }
}
