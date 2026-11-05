/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.caching;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ClusterMessageSigner;
import ai.labs.eddi.engine.cluster.ClusterSigningConfig;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B4 (webhook de-duplication fails closed) and B5 (eviction keys travel as
 * keyed hashes when a signing key is set).
 */
@DisplayName("CacheFactory — cluster replay protection and keyed eviction hashes")
class ClusterCacheProtectionTest {

    private static final String KEY = "fixturefixturefixturefixturefixture-seal-key";

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance(T value) {
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(value != null);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    private static CacheFactory clusteredFactory(RecordingEventBus bus, ISharedKv kv, ClusterMessageSigner signer) {
        CacheFactory factory = new CacheFactory();
        factory.clusterConfig = ClusterConfig.defaults().withMessagingType("nats");
        NatsSharedStateFactory shared = mock(NatsSharedStateFactory.class);
        when(shared.bucket(any(SharedBucket.class))).thenReturn(kv);
        factory.natsSharedState = instance(shared);
        factory.clusterEvents = instance((IClusterEventBus) bus);
        factory.clusterSigner = instance(signer);
        return factory;
    }

    @Test
    @DisplayName("B4: with NATS down, the webhook de-duplication refuses instead of checking node-locally")
    void webhookDedupFailsClosed() {
        CacheFactory factory = clusteredFactory(new RecordingEventBus(), new DownSharedKv(), null);
        ICache<String, String> dedup = factory.getCache("webhook-event-dedup", Duration.ofMinutes(10));
        assertThrows(ClusterUnavailableException.class, () -> dedup.putIfAbsent("k", ""));

        ICache<String, String> slack = factory.getCache("slack-event-dedup", Duration.ofMinutes(10));
        assertNull(slack.putIfAbsent("k", ""), "Slack's de-duplication keeps its node-local fallback");
    }

    @Test
    @DisplayName("B5: with a signing key, eviction keys travel as keyed hashes and still match on the receiving node")
    void keyedEvictionHashes() {
        RecordingEventBus bus = new RecordingEventBus();
        ClusterMessageSigner signer = new ClusterMessageSigner(ClusterSigningConfig.of(KEY, List.of(), false, Duration.ofSeconds(60)),
                new SimpleMeterRegistry());
        CacheFactory factory = clusteredFactory(bus, new InMemorySharedKv("X", Duration.ofMinutes(5)), signer);
        ICache<String, String> users = factory.getCache("userConversations");

        users.put("intent::alice@example.com", "conv-1");
        String sent = String.valueOf(bus.ofType(ClusterEvent.CACHE_EVICT).get(0).payload().get("key"));
        assertTrue(sent.startsWith("k_"));
        assertNotEquals(KvKeys.hashed("intent::alice@example.com"), sent, "no plain, dictionary-reversible hash on the wire");

        users.fill("intent::alice@example.com", "conv-1");
        bus.deliver(ClusterEvent.CACHE_EVICT, Map.of("cache", "userConversations", "key", sent));
        assertNull(users.get("intent::alice@example.com"));

        users.fill("intent::bob@example.com", "conv-2");
        bus.deliver(ClusterEvent.CACHE_EVICT, Map.of("cache", "userConversations", "key", KvKeys.hashed("intent::bob@example.com")));
        assertNull(users.get("intent::bob@example.com"), "a plain hash from an older node still matches");
    }
}
