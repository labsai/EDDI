/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import ai.labs.eddi.engine.cluster.lease.LocalLeaseManager;
import ai.labs.eddi.engine.cluster.lease.NatsLeaseManager;
import ai.labs.eddi.engine.runtime.internal.ClusterConversationCoordinator;
import ai.labs.eddi.engine.runtime.internal.InMemoryConversationCoordinator;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ClusterProducers / ClusterConfig")
class ClusterProducersTest {

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance(T value) {
        Instance<T> instance = mock(Instance.class);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    @Test
    @DisplayName("in-memory mode resolves only the in-memory beans — no NATS bean is ever instantiated")
    void inMemoryResolvesNoNatsBean() {
        var producers = new ClusterProducers(ClusterConfig.defaults());
        var inMemory = mock(InMemoryConversationCoordinator.class);
        Instance<ClusterConversationCoordinator> cluster = instance(null);
        assertSame(inMemory, producers.coordinator(instance(inMemory), cluster));
        verify(cluster, never()).get();

        Instance<NatsLeaseManager> natsLeases = instance(null);
        var local = new LocalLeaseManager();
        assertSame(local, producers.leaseManager(instance(local), natsLeases));
        verify(natsLeases, never()).get();

        Instance<NatsSharedStateFactory> natsState = instance(null);
        var localState = new LocalSharedStateFactory();
        assertSame(localState, producers.sharedStateFactory(instance(localState), natsState));
        assertFalse(localState.isShared());
        verify(natsState, never()).get();
    }

    @Test
    @DisplayName("nats mode resolves the cluster beans")
    void natsResolvesClusterBeans() {
        var producers = new ClusterProducers(ClusterConfig.defaults().withMessagingType("nats"));
        var cluster = mock(ClusterConversationCoordinator.class);
        Instance<InMemoryConversationCoordinator> inMemory = instance(null);
        assertSame(cluster, producers.coordinator(inMemory, instance(cluster)));
        verify(inMemory, never()).get();
    }

    @Test
    @DisplayName("an unknown messaging type fails the boot instead of silently staying single-node")
    void unknownMessagingTypeFails() {
        var config = ClusterConfig.defaults().withMessagingType("kafka");
        IllegalStateException e = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(e.getMessage().contains("eddi.messaging.type"));
        assertDoesNotThrow(() -> ClusterConfig.defaults().validate());
        assertDoesNotThrow(() -> ClusterConfig.defaults().withMessagingType(" NATS ").validate());
    }

    @Test
    @DisplayName("a heartbeat slower than half the lease TTL is refused")
    void heartbeatMustFitTtl() {
        var config = ClusterConfig.defaults().withMessagingType("nats")
                .withLeaseTimings(Duration.ofSeconds(10), Duration.ofSeconds(6), Duration.ofSeconds(30));
        assertThrows(IllegalStateException.class, config::validate);
    }

    @Test
    @DisplayName("nats.url may list several servers")
    void serverList() {
        var config = ClusterConfig.defaults().withNatsUrl("nats://a:4222, nats://b:4222,,nats://c:4222");
        assertEquals(3, config.natsServers().size());
    }

    @Test
    @DisplayName("the node id falls back to the schedule instance id and is made subject-safe")
    void nodeIdResolution() {
        assertEquals("explicit", NodeIdentity.resolve(Optional.of("explicit"), Optional.of("sched")));
        assertEquals("sched", NodeIdentity.resolve(Optional.empty(), Optional.of("sched")));
        assertEquals("pod_1_ns", new NodeIdentity("pod.1 ns", "b").nodeId());
    }

    @Test
    @DisplayName("KV keys keep plain ids readable and hash everything else")
    void kvKeys() {
        assertEquals("65a1b2c3d4e5f60718293a4b", KvKeys.safe("65a1b2c3d4e5f60718293a4b"));
        String hashed = KvKeys.safe("user@example.com");
        assertTrue(hashed.startsWith("h_"));
        assertFalse(hashed.contains("@"));
        assertNotEquals(KvKeys.safe("h_x"), "h_x", "a value that looks hashed is hashed again, so it cannot collide");
    }
}
