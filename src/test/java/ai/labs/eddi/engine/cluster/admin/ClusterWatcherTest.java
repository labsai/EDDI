/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.admin;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ClusterPresence;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.engine.cluster.events.IClusterEventBus;
import ai.labs.eddi.engine.cluster.lease.LeaseInfo;
import ai.labs.eddi.engine.cluster.lease.NatsLeaseManager;
import ai.labs.eddi.engine.model.ClusterAdminModels.ActivityEvent;
import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.runtime.internal.ClusterConversationCoordinator;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ClusterWatcher")
class ClusterWatcherTest {

    private ClusterActivityLog activity;
    private NatsConnectionManager connections;
    private ClusterPresence presence;
    private ClusterWatcher watcher;
    private final List<Map<String, Object>> members = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance(T value) {
        Instance<T> instance = mock(Instance.class);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    private static Map<String, Object> member(String node, String boot, long updatedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node", node);
        m.put("boot", boot);
        m.put("updatedAt", updatedAt);
        return m;
    }

    @BeforeEach
    void setUp() {
        activity = new ClusterActivityLog(ClusterConfig.defaults(), new NodeIdentity("n1", "b1"), instance(null), Duration.ofHours(1));
        connections = mock(NatsConnectionManager.class);
        when(connections.isConnected()).thenReturn(true);
        when(connections.node()).thenReturn(new NodeIdentity("n1", "b1"));
        presence = mock(ClusterPresence.class);
        when(presence.members()).thenAnswer(i -> List.copyOf(members));
        watcher = new ClusterWatcher(ClusterConfig.defaults().withMessagingType("nats").withPresenceInterval(Duration.ofSeconds(10)),
                activity, instance(connections), instance(presence), instance(mock(NatsLeaseManager.class)),
                instance(mock(ClusterConversationCoordinator.class)), mock(IClusterEventBus.class));
    }

    private List<String> types() {
        return activity.recent(100, List.of()).stream().map(ActivityEvent::type).toList();
    }

    @Test
    @DisplayName("the first poll learns the members silently; a later new member or a new boot is node.joined")
    void joined() {
        long now = System.currentTimeMillis();
        members.add(member("n1", "b1", now));
        members.add(member("n2", "b2", now));
        watcher.pollMembers();
        assertTrue(types().isEmpty());
        members.add(member("n3", "b3", now));
        members.set(1, member("n2", "b2-restarted", now));
        watcher.pollMembers();
        assertEquals(2, types().stream().filter("node.joined"::equals).count());
    }

    @Test
    @DisplayName("a record that disappears while fresh is node.left (clean); one that expired is node.lost — and stays listed as gone")
    void leftVersusLost() {
        long now = System.currentTimeMillis();
        members.add(member("n1", "b1", now));
        members.add(member("n2", "b2", now - 1_000));
        members.add(member("n3", "b3", now - 35_000));
        watcher.pollMembers();
        members.removeIf(m -> !m.get("node").equals("n1"));
        watcher.pollMembers();
        List<ActivityEvent> events = activity.recent(100, List.of());
        ActivityEvent left = events.stream().filter(e -> e.type().equals("node.left")).findFirst().orElseThrow();
        ActivityEvent lost = events.stream().filter(e -> e.type().equals("node.lost")).findFirst().orElseThrow();
        assertEquals("n2", left.payload().get("nodeId"));
        assertEquals("n3", lost.payload().get("nodeId"));
        assertEquals(ClusterActivityLog.ERROR, lost.severity());
        assertEquals("node.lost:n3:b3", lost.id(), "the id is deterministic so every node's report collapses into one");
        assertTrue(watcher.goneNodes().get("n2").clean());
        assertFalse(watcher.goneNodes().get("n3").clean());
    }

    @Test
    @DisplayName("a late heartbeat is reported once as node.stale")
    void stale() {
        long now = System.currentTimeMillis();
        members.add(member("n1", "b1", now));
        members.add(member("n2", "b2", now));
        watcher.pollMembers();
        members.set(1, member("n2", "b2", now - 25_000));
        watcher.pollMembers();
        watcher.pollMembers();
        assertEquals(1, types().stream().filter("node.stale"::equals).count());
    }

    @Test
    @DisplayName("nothing is concluded about members while this node cannot read presence")
    void noConclusionsWhileDisconnected() {
        long now = System.currentTimeMillis();
        members.add(member("n2", "b2", now - 35_000));
        watcher.pollMembers();
        members.clear();
        when(connections.isConnected()).thenReturn(false);
        watcher.pollMembers();
        assertTrue(types().isEmpty());
    }

    @Test
    @DisplayName("degraded mode on and off are recorded once each, with the turns policy")
    void degradedTransitions() {
        when(connections.isDegraded()).thenReturn(true);
        when(connections.unavailableSinceMillis()).thenReturn(1_000L);
        watcher.pollDegraded();
        watcher.pollDegraded();
        when(connections.isDegraded()).thenReturn(false);
        watcher.pollDegraded();
        assertEquals(List.of("degraded.on", "degraded.off"), types());
        assertEquals("local", activity.recent(10, List.of("degraded.on")).get(0).payload().get("turnsPolicy"));
    }

    @Test
    @DisplayName("a fenced dead letter is fence.rejected with its tokens; others are deadletter.created")
    void deadLetters() {
        watcher.onDeadLetter(new DeadLetterEntry("7", "conv1", "fenced", 1, null, Map.of("agentId", "a1"), "fenced", "n1",
                Map.of("token", 5L, "storedFence", 9L)));
        watcher.onDeadLetter(new DeadLetterEntry("8", "conv2", "boom", 1, null, null, "failed", "n1", null));
        List<ActivityEvent> events = activity.recent(10, List.of());
        assertEquals("fence.rejected", events.get(0).type());
        assertEquals(Map.of("token", 5L, "storedFence", 9L), events.get(0).payload().get("fence"));
        assertEquals("a1", events.get(0).payload().get("agentId"));
        assertEquals("deadletter.created", events.get(1).type());
    }

    @Test
    @DisplayName("a takeover is recorded with the previous holder")
    void takeover() {
        watcher.onTakeover("c.conv1", new LeaseInfo("n2", "b2", 17, 0));
        ActivityEvent e = activity.recent(10, List.of()).get(0);
        assertEquals("lease.takeover", e.type());
        assertEquals("conv1", e.payload().get("conversationId"));
        assertEquals("n2", e.payload().get("previousNode"));
    }

    @Test
    @DisplayName("deployment changes are recorded per receiving node with the delay; cache events are summarised")
    void deploymentAndCaches() {
        watcher.onDeployment(new ClusterEvent(1, "evt-1", ClusterEvent.DEPLOYMENT_CHANGED, "n2", "b2", System.currentTimeMillis() - 30, null,
                Map.of("agentId", "a1", "version", 3, "env", "production", "status", "UNDEPLOYED")));
        ActivityEvent dep = activity.recent(10, List.of("deployment.")).get(0);
        assertEquals("n2", dep.payload().get("originNode"));
        assertEquals("UNDEPLOYED", dep.payload().get("status"));
        assertTrue(((Number) dep.payload().get("latencyMs")).longValue() >= 0);

        watcher.flushCacheCounts();
        assertTrue(activity.recent(10, List.of("cache.")).isEmpty(), "nothing to summarise, nothing recorded");
        for (int i = 0; i < 3; i++) {
            watcher.onCacheEvent(new ClusterEvent(1, "c" + i, ClusterEvent.CACHE_EVICT, "n2", "b2", 0, null, Map.of("cache", "agents")));
        }
        watcher.onCacheEvent(new ClusterEvent(1, "c9", ClusterEvent.CACHE_CLEAR, "n2", "b2", 0, null, Map.of("cache", "secrets")));
        watcher.flushCacheCounts();
        ActivityEvent summary = activity.recent(10, List.of("cache.")).get(0);
        assertEquals(4, summary.payload().get("total"));
        assertEquals(Map.of("agents", 3, "secrets", 1), summary.payload().get("counts"));
    }
}
