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
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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

    private static final long INTERVAL = ClusterConfig.defaults().presenceInterval().toMillis();

    private InMemorySharedKv bucket;
    private final AtomicLong clock = new AtomicLong(1_000_000L);

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
        return new ClusterPresence(connections(nodeId, boot), bucket, new SimpleMeterRegistry()).withClock(clock::get);
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
        clock.addAndGet(INTERVAL);
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
        clock.addAndGet(INTERVAL);
        restarted.publishSafely();

        assertFalse(restarted.isDuplicate());
        Map<?, ?> record = JSON.readValue(bucket.get("n.node-a").orElseThrow().value(), Map.class);
        assertEquals("boot2", record.get("boot"));
    }

    @Test
    @DisplayName("C6: two writes in quick succession (a reconnect and a tick) are not an interval: no takeover, no confirmation")
    void takeoverNeedsAFullInterval() throws Exception {
        presence("node-a", "boot1").publishSafely(); // possibly a live JVM between its writes

        ClusterPresence restarted = presence("node-a", "boot2");
        AtomicInteger confirmed = new AtomicInteger();
        restarted.onIdentityConfirmed(confirmed::incrementAndGet);
        restarted.publishSafely();
        clock.addAndGet(INTERVAL - 1);
        restarted.publishSafely();
        restarted.publishSafely();

        assertFalse(restarted.isIdentityConfirmed());
        assertEquals(0, confirmed.get());
        Map<?, ?> record = JSON.readValue(bucket.get("n.node-a").orElseThrow().value(), Map.class);
        assertEquals("boot1", record.get("boot"), "the other record is left alone");

        clock.addAndGet(1);
        restarted.publishSafely();
        assertTrue(restarted.isIdentityConfirmed());
        assertEquals(1, confirmed.get());
    }

    @Test
    @DisplayName("C6: a lost create after the own record expired leaves the node unconfirmed, not holding a stale revision")
    void lostCreateResetsTheOwnRevision() {
        ISharedKv nodes = spy(bucket);
        ClusterPresence mine = new ClusterPresence(connections("node-a", "boot1"), nodes, new SimpleMeterRegistry())
                .withClock(clock::get);
        AtomicInteger confirmed = new AtomicInteger();
        mine.onIdentityConfirmed(confirmed::incrementAndGet);
        mine.publishSafely();
        assertTrue(mine.isIdentityConfirmed());
        assertEquals(1, confirmed.get());

        nodes.delete("n.node-a"); // the record expired (e.g. a long NATS outage)
        doAnswer(inv -> {
            nodes.put("n.node-a", "{\"node\":\"node-a\",\"boot\":\"boot9\"}".getBytes());
            return OptionalLong.empty(); // another JVM won the create
        }).when(nodes).create(anyString(), any());
        mine.publishSafely();

        assertFalse(mine.isIdentityConfirmed(), "the record now belongs to whoever won the create");
        assertEquals(1, confirmed.get(), "listeners are not told again");
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
                Duration.ofSeconds(30)).withClock(clock::get);
        presence.onWatched("n.dead", JSON.writeValueAsBytes(Map.of("node", "dead")), true, 1);
        clock.addAndGet(1_000);
        presence.onWatched("n.a", JSON.writeValueAsBytes(Map.of("node", "a", "version", "6.5.0", "protocol", 1)), true, 2);
        presence.onWatched("n.b", JSON.writeValueAsBytes(Map.of("node", "b", "version", "6.5.0", "protocol", 1)), true, 3);
        presence.watchReady(true);
        clock.addAndGet(29_000); // "dead" was last seen 30 s ago, a and b 29 s ago

        List<Map<String, Object>> members = presence.currentMembers();
        assertEquals(List.of("a", "b"), members.stream().map(m -> m.get("node")).toList(), "a record not rewritten for the TTL is gone");
        assertEquals(2, presence.watchedRecords(), "an expired record is dropped, not only skipped");
        presence.onWatched("n.b", null, false, 4);
        assertEquals(1, presence.members().size(), "a deleted record leaves at once");
        verify(nodes, never()).keys();
    }

    @Test
    @DisplayName("OPS-1: a watched record expires on this node's clock alone, whatever the writer's clock says")
    void watchedRecordExpiresOnThisNodesClock() throws Exception {
        ClusterPresence presence = new ClusterPresence(connections("self", "b"), bucket, new SimpleMeterRegistry(), null,
                Duration.ofSeconds(30)).withClock(clock::get);
        presence.watchReady(true);
        // The writer's clock runs a minute behind this node's: its own timestamp
        // claims the record is already older than the TTL.
        long writerClock = System.currentTimeMillis() - 60_000;
        presence.onWatched("n.a", JSON.writeValueAsBytes(Map.of("node", "a", "updatedAt", writerClock)), true, 7);

        clock.addAndGet(29_000);
        assertEquals(List.of("a"), presence.members().stream().map(m -> m.get("node")).toList(), "seen 29 s ago: still a member");
        clock.addAndGet(2_000);
        assertTrue(presence.members().isEmpty(), "not rewritten for 31 s: gone");
    }

    @Test
    @DisplayName("OPS-1: the heartbeat age is the time since this node saw the record change, and resets with a new revision")
    void heartbeatAgeFromTheWatch() throws Exception {
        ClusterPresence presence = new ClusterPresence(connections("self", "b"), bucket, new SimpleMeterRegistry(), null,
                Duration.ofSeconds(30)).withClock(clock::get);
        presence.watchReady(true);
        presence.onWatched("n.a", JSON.writeValueAsBytes(Map.of("node", "a", "updatedAt", 1L)), true, 7);
        clock.addAndGet(12_000);
        assertEquals(OptionalLong.of(12_000), presence.heartbeatAgeMillis("a"));
        presence.onWatched("n.a", JSON.writeValueAsBytes(Map.of("node", "a", "updatedAt", 2L)), true, 8);
        clock.addAndGet(500);
        assertEquals(OptionalLong.of(500), presence.heartbeatAgeMillis("a"));
        assertEquals(OptionalLong.of(0), presence.heartbeatAgeMillis("self"), "this node's own record is always current");
        assertTrue(presence.heartbeatAgeMillis("nobody").isEmpty());
    }

    @Test
    @DisplayName("OPS-1: a record delivered with the watch's initial values has no known age until it is rewritten")
    void initialValuesHaveNoKnownAge() throws Exception {
        ClusterPresence presence = new ClusterPresence(connections("self", "b"), bucket, new SimpleMeterRegistry(), null,
                Duration.ofSeconds(30)).withClock(clock::get);
        // Delivered before the end of the initial data: written at some unknown time
        // within the bucket TTL.
        presence.onWatched("n.a", JSON.writeValueAsBytes(Map.of("node", "a")), true, 7);
        presence.watchReady(true);
        assertEquals(1, presence.members().size(), "listed as a member");
        assertTrue(presence.heartbeatAgeMillis("a").isEmpty(), "but how old it is cannot be told");
        clock.addAndGet(3_000);
        presence.onWatched("n.a", JSON.writeValueAsBytes(Map.of("node", "a")), true, 8);
        assertEquals(OptionalLong.of(0), presence.heartbeatAgeMillis("a"), "a rewrite seen live is dated");
    }

    @Test
    @DisplayName("OPS-1: without a watch, the age is known once a bucket read sees the revision change, and only then")
    void heartbeatAgeFromBucketReads() {
        ClusterPresence presence = presence("self", "b");
        bucket.put("n.a", "{\"node\":\"a\"}".getBytes());
        presence.currentMembers();
        assertTrue(presence.heartbeatAgeMillis("a").isEmpty(), "the first read cannot tell when the record was written");
        clock.addAndGet(4_000);
        presence.currentMembers();
        assertTrue(presence.heartbeatAgeMillis("a").isEmpty(), "an unchanged revision still says nothing");
        bucket.put("n.a", "{\"node\":\"a\"}".getBytes());
        presence.currentMembers();
        clock.addAndGet(2_500);
        assertEquals(OptionalLong.of(2_500), presence.heartbeatAgeMillis("a"));
        bucket.put("n.c", "{\"node\":\"c\"}".getBytes());
        presence.currentMembers();
        assertEquals(OptionalLong.of(0), presence.heartbeatAgeMillis("c"), "a key that appeared between two reads was just written");

        clock.addAndGet(31_000); // a read longer than the TTL ago brackets nothing
        bucket.put("n.c", "{\"node\":\"c\"}".getBytes());
        presence.currentMembers();
        assertTrue(presence.heartbeatAgeMillis("c").isEmpty());
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
