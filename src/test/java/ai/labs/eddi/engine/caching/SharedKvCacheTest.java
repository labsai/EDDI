/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.caching;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.DownSharedKv;
import ai.labs.eddi.engine.cluster.ISharedKv;
import ai.labs.eddi.engine.cluster.InMemorySharedKv;
import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.engine.cluster.NatsSharedStateFactory;
import ai.labs.eddi.engine.cluster.SharedBucket;
import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.engine.cluster.events.IClusterEventBus;
import ai.labs.eddi.engine.cluster.events.RecordingEventBus;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Cluster-shared and cluster-invalidated caches")
class SharedKvCacheTest {

    private static <K, V> ICache<K, V> local(String name) {
        return new CacheFactory().getCache(name, Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("putIfAbsent is atomic across nodes: the second node sees a replay")
    void putIfAbsentAcrossNodes() {
        var kv = new InMemorySharedKv("NONCES", Duration.ofMinutes(5));
        ICache<String, Boolean> nodeA = new SharedKvCache<>(local("a"), kv, true);
        ICache<String, Boolean> nodeB = new SharedKvCache<>(local("b"), kv, true);
        assertNull(nodeA.putIfAbsent("nonce-1", Boolean.TRUE));
        assertEquals(Boolean.TRUE, nodeB.putIfAbsent("nonce-1", Boolean.TRUE), "a nonce used on A is a replay on B");
        assertTrue(kv.keys().stream().allMatch(k -> k.startsWith("h_")), "keys are hashed, never in clear");
    }

    @Test
    @DisplayName("values written on one node are read on another, with their type")
    void valuesTravel() {
        var kv = new InMemorySharedKv("A2A_TASKS", Duration.ofMinutes(5));
        ICache<String, String> nodeA = new SharedKvCache<>(local("a"), kv, false);
        ICache<String, String> nodeB = new SharedKvCache<>(local("b"), kv, false);
        nodeA.put("task-1", "conv-1");
        assertEquals("conv-1", nodeB.get("task-1"));
        nodeB.remove("task-1");
        assertNull(nodeA.get("task-1"));
    }

    @Test
    @DisplayName("fail-closed caches throw while the cluster is unreachable; others answer node-locally")
    void degradedPolicies() {
        ICache<String, Boolean> nonces = new SharedKvCache<>(local("n"), new DownSharedKv(), true);
        assertThrows(ClusterUnavailableException.class, () -> nonces.putIfAbsent("x", Boolean.TRUE));
        ICache<String, Boolean> dedup = new SharedKvCache<>(local("d"), new DownSharedKv(), false);
        assertNull(dedup.putIfAbsent("evt", Boolean.TRUE));
        assertEquals(Boolean.TRUE, dedup.putIfAbsent("evt", Boolean.TRUE), "node-local de-duplication still works");
    }

    @Test
    @DisplayName("a value the bucket refuses (larger than the NATS payload limit) is still read back on the node that stored it, and only there")
    void refusedValueStaysReadableLocally() {
        ISharedKv refusing = mock(ISharedKv.class);
        when(refusing.bucket()).thenReturn("TOOL_PAGES");
        when(refusing.get(any())).thenReturn(Optional.empty());
        when(refusing.put(any(), any())).thenThrow(new ClusterUnavailableException("payload exceeds the server limit (test)"));
        ICache<String, String> node = new SharedKvCache<>(local("big"), refusing, false);

        node.put("response-1", "x".repeat(10));

        assertEquals("x".repeat(10), node.get("response-1"), "paginated tool responses over 1 MiB must still page on this node");
        assertNull(node.get("never-stored"), "a key nobody stored stays absent");
        node.remove("response-1");
        assertNull(node.get("response-1"), "a removed value is gone, not resurrected from the local copy");
        ICache<String, String> other = new SharedKvCache<>(local("other"), refusing, false);
        assertNull(other.get("response-1"), "another node has no copy: the page fetch fails there, as documented");
    }

    @SuppressWarnings("unchecked")
    private static CacheFactory clusteredFactory(RecordingEventBus bus, ISharedKv kv) {
        var factory = new CacheFactory();
        factory.clusterConfig = ClusterConfig.defaults().withMessagingType("nats");
        NatsSharedStateFactory shared = mock(NatsSharedStateFactory.class);
        when(shared.bucket(any(SharedBucket.class))).thenReturn(kv);
        Instance<NatsSharedStateFactory> sharedInstance = mock(Instance.class);
        when(sharedInstance.get()).thenReturn(shared);
        factory.natsSharedState = sharedInstance;
        Instance<IClusterEventBus> events = mock(Instance.class);
        when(events.get()).thenReturn(bus);
        factory.clusterEvents = events;
        return factory;
    }

    @Test
    @DisplayName("in-memory mode: every cache is exactly the node-local one")
    void inMemoryUnchanged() {
        var factory = new CacheFactory();
        assertInstanceOf(CacheImpl.class, factory.getCache("nonce-replay-protection", Duration.ofMinutes(1)));
        assertInstanceOf(CacheImpl.class, factory.getCache("agentTriggers"));
    }

    @Test
    @DisplayName("cluster mode: state caches are shared, copy caches announce evictions by hashed key")
    void clusterModeWrapping() {
        var bus = new RecordingEventBus();
        var factory = clusteredFactory(bus, new InMemorySharedKv("X", Duration.ofMinutes(5)));
        assertInstanceOf(SharedKvCache.class, factory.getCache("nonce-replay-protection", Duration.ofMinutes(1)));
        assertInstanceOf(SharedKvCache.class, factory.getCache("a2aTaskMapping"));
        ICache<String, String> triggers = factory.getCache("agentTriggers");
        assertInstanceOf(ClusterInvalidatingCache.class, triggers);
        assertInstanceOf(CacheImpl.class, factory.getCache("tool-results", Duration.ofMinutes(1)), "a pure cache stays local");

        triggers.put("support", "agent-1");
        var evictions = bus.ofType(ClusterEvent.CACHE_EVICT);
        assertEquals(1, evictions.size());
        assertEquals(KvKeys.hashed("support"), evictions.get(0).payload().get("key"));

        // another node changed the trigger: this node drops its copy
        bus.deliver(ClusterEvent.CACHE_EVICT, Map.of("cache", "agentTriggers", "key", KvKeys.hashed("support")));
        assertNull(triggers.get("support"));
        assertEquals(1, bus.ofType(ClusterEvent.CACHE_EVICT).size(), "a received eviction is never re-published");
    }

    @Test
    @DisplayName("a resync clears every invalidated cache")
    void resyncClears() {
        var bus = new RecordingEventBus();
        var factory = clusteredFactory(bus, new InMemorySharedKv("X", Duration.ofMinutes(5)));
        ICache<String, String> users = factory.getCache("userConversations");
        users.put("k", "v");
        bus.resyncs.forEach(Runnable::run);
        assertNull(users.get("k"));
    }
}
