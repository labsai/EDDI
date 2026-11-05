/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C6 (duplicate node ids), D6 (members from a watch) and C15 (mixed versions).
 */
@DisplayName("ClusterPresence")
class ClusterPresenceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private InMemorySharedKv bucket;

    @BeforeEach
    void setUp() {
        bucket = new InMemorySharedKv("NODES", Duration.ofSeconds(30));
    }

    private static NatsConnectionManager connections(String nodeId, String boot) {
        NatsConnectionManager connections = mock(NatsConnectionManager.class);
        when(connections.node()).thenReturn(new NodeIdentity(nodeId, boot));
        when(connections.isConnected()).thenReturn(true);
        when(connections.config()).thenReturn(ClusterConfig.defaults().withMessagingType("nats"));
        return connections;
    }

    private ClusterPresence presence(String nodeId, String boot) {
        return new ClusterPresence(connections(nodeId, boot), bucket, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("C6: a second live JVM with the same node id is detected; the first keeps its record and stays ready")
    void liveTwinIsDetected() {
        ClusterPresence first = presence("node-a", "boot1");
        ClusterPresence twin = presence("node-a", "boot2");

        first.publishSafely();
        twin.publishSafely(); // sees boot1, waits one interval
        first.publishSafely(); // the live first node rewrites its record
        twin.publishSafely(); // ... which the twin now sees changed

        assertTrue(twin.isDuplicate());
        assertFalse(first.isDuplicate());
        first.publishSafely();
        assertFalse(first.isDuplicate(), "the twin no longer writes, so the first node is undisturbed");

        ClusterConfig nats = ClusterConfig.defaults().withMessagingType("nats");
        NodeIdentityHealthCheck twinCheck = new NodeIdentityHealthCheck(nats, instance(twin));
        NodeIdentityHealthCheck firstCheck = new NodeIdentityHealthCheck(nats, instance(first));
        assertEquals(HealthCheckResponse.Status.DOWN, twinCheck.call().getStatus());
        assertEquals(HealthCheckResponse.Status.UP, firstCheck.call().getStatus());
    }

    @Test
    @DisplayName("C6: the id is confirmed (and listeners told once) only after the takeover, never on a twin's first connect")
    void identityIsConfirmedOnlyOnceOwned() {
        ClusterPresence first = presence("node-a", "boot1");
        first.publishSafely();
        assertTrue(first.isIdentityConfirmed(), "a fresh record is this node's own");

        ClusterPresence twin = presence("node-a", "boot2");
        AtomicInteger twinConfirmed = new AtomicInteger();
        twin.onIdentityConfirmed(twinConfirmed::incrementAndGet);
        twin.publishSafely(); // first connect: the live record is not yet told from a dead one
        assertFalse(twin.isIdentityConfirmed());
        first.publishSafely();
        twin.publishSafely();
        assertTrue(twin.isDuplicate());
        assertFalse(twin.isIdentityConfirmed());
        assertEquals(0, twinConfirmed.get(), "a twin never runs the boot sweep");

        ClusterPresence restarted = presence("node-b", "boot2");
        presence("node-b", "boot1").publishSafely(); // a predecessor that then crashed
        AtomicInteger confirmed = new AtomicInteger();
        restarted.onIdentityConfirmed(confirmed::incrementAndGet);
        restarted.publishSafely();
        assertEquals(0, confirmed.get(), "not confirmed on the first connect");
        restarted.publishSafely(); // unchanged for one interval: taken over
        restarted.publishSafely();
        assertTrue(restarted.isIdentityConfirmed());
        assertEquals(1, confirmed.get(), "told once, not on every write");
    }

    @Test
    @DisplayName("C6: the record of a crashed previous boot (unchanged for one interval) is taken over")
    void deadPredecessorIsTakenOver() throws Exception {
        ClusterPresence previousBoot = presence("node-a", "boot1");
        previousBoot.publishSafely(); // then the pod crashes

        ClusterPresence restarted = presence("node-a", "boot2");
        restarted.publishSafely();
        restarted.publishSafely();

        assertFalse(restarted.isDuplicate());
        Map<?, ?> record = JSON.readValue(bucket.get("n.node-a").orElseThrow().value(), Map.class);
        assertEquals("boot2", record.get("boot"));
    }

    @Test
    @DisplayName("C6: an older node that overwrites blindly is detected by the node whose record it overwrote")
    void blindOverwriteIsDetected() {
        ClusterPresence mine = presence("node-a", "boot1");
        mine.publishSafely();
        bucket.put("n.node-a", "{\"node\":\"node-a\",\"boot\":\"boot9\"}".getBytes());
        mine.publishSafely();
        assertTrue(mine.isDuplicate());
    }

    @Test
    @DisplayName("C6: a flagged node clears itself once the other record is gone")
    void duplicateClearsWhenTheOtherIsGone() {
        ClusterPresence mine = presence("node-a", "boot1");
        mine.publishSafely();
        bucket.put("n.node-a", "{\"node\":\"node-a\",\"boot\":\"boot9\"}".getBytes());
        mine.publishSafely();
        assertTrue(mine.isDuplicate());
        bucket.delete("n.node-a");
        mine.publishSafely();
        assertFalse(mine.isDuplicate());
    }

    @Test
    @DisplayName("D6: with the watch running, members are answered without reading the bucket, and stale records age out")
    void membersFromTheWatch() throws Exception {
        ISharedKv nodes = spy(bucket);
        ClusterPresence presence = new ClusterPresence(connections("self", "b"), nodes, new SimpleMeterRegistry(), null,
                Duration.ofSeconds(30));
        long now = System.currentTimeMillis();
        presence.onWatched("n.a", JSON.writeValueAsBytes(Map.of("node", "a", "version", "6.5.0", "protocol", 1)), true, now);
        presence.onWatched("n.b", JSON.writeValueAsBytes(Map.of("node", "b", "version", "6.5.0", "protocol", 1)), true, now);
        presence.onWatched("n.dead", JSON.writeValueAsBytes(Map.of("node", "dead")), true, now - 31_000);
        presence.watchReady(true);

        List<Map<String, Object>> members = presence.currentMembers();
        assertEquals(List.of("a", "b"), members.stream().map(m -> m.get("node")).toList(), "a record older than the TTL is gone");
        assertEquals(2, presence.watchedRecords(), "an expired record is dropped, not only skipped");
        presence.onWatched("n.b", null, false, now);
        assertEquals(1, presence.members().size(), "a deleted record leaves at once");
        verify(nodes, never()).keys();
    }

    @Test
    @DisplayName("C15: members on different versions or protocols are counted")
    void mixedVersions() {
        ClusterPresence presence = presence("self", "b");
        presence.checkVersions(List.of(Map.of("node", "a", "version", "6.5.0", "protocol", 1), Map.of("node", "b", "version", "6.5.0")));
        assertEquals(1, presence.distinctVersions(), "a record without a protocol is protocol 1");
        presence.checkVersions(List.of(Map.of("node", "a", "version", "6.5.0", "protocol", 1),
                Map.of("node", "b", "version", "6.6.0", "protocol", 2)));
        assertEquals(2, presence.distinctVersions());
    }

    @SuppressWarnings("unchecked")
    private static Instance<ClusterPresence> instance(ClusterPresence presence) {
        Instance<ClusterPresence> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(presence);
        return instance;
    }
}
