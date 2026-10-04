/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.labs.eddi.engine.cluster.lease.NatsLeaseManager;

@DisplayName("ClusterHealthCheck")
class ClusterHealthCheckTest {

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance(T value) {
        Instance<T> instance = mock(Instance.class);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    private static ClusterConfig requireNats(ClusterConfig config) throws Exception {
        Field f = ClusterConfig.class.getDeclaredField("readinessRequireNats");
        f.setAccessible(true);
        f.setBoolean(config, true);
        return config;
    }

    @Test
    @DisplayName("in-memory: UP, and no NATS bean is touched")
    void inMemoryIsUp() {
        Instance<NatsConnectionManager> connections = instance(null);
        var check = new ClusterHealthCheck(ClusterConfig.defaults(), connections, instance(null), instance(null));
        HealthCheckResponse response = check.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        verify(connections, never()).get();
    }

    @Test
    @DisplayName("nats degraded: still UP by default — a NATS blip must never empty the load balancer")
    void degradedStaysUpByDefault() {
        NatsConnectionManager manager = mock(NatsConnectionManager.class);
        when(manager.isDegraded()).thenReturn(true);
        when(manager.isConnected()).thenReturn(false);
        when(manager.node()).thenReturn(new NodeIdentity("n1", "b1"));
        var check = new ClusterHealthCheck(ClusterConfig.defaults().withMessagingType("nats"), instance(manager), instance(null),
                instance(null));
        HealthCheckResponse response = check.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals(Boolean.TRUE, response.getData().orElseThrow().get("degraded"));
    }

    @Test
    @DisplayName("nats degraded with require-nats=true: DOWN")
    void degradedDownWhenRequired() throws Exception {
        NatsConnectionManager manager = mock(NatsConnectionManager.class);
        when(manager.isDegraded()).thenReturn(true);
        when(manager.node()).thenReturn(new NodeIdentity("n1", "b1"));
        var check = new ClusterHealthCheck(requireNats(ClusterConfig.defaults().withMessagingType("nats")), instance(manager),
                instance(null), instance(null));
        assertEquals(HealthCheckResponse.Status.DOWN, check.call().getStatus());
    }

    @Test
    @DisplayName("nats connected: UP with the member count")
    void connectedReportsMembers() {
        NatsConnectionManager manager = mock(NatsConnectionManager.class);
        when(manager.isConnected()).thenReturn(true);
        when(manager.node()).thenReturn(new NodeIdentity("n1", "b1"));
        ClusterPresence presence = mock(ClusterPresence.class);
        when(presence.members()).thenReturn(List.of(Map.of("node", "n1"), Map.of("node", "n2")));
        var check = new ClusterHealthCheck(ClusterConfig.defaults().withMessagingType("nats"), instance(manager), instance(presence),
                instance(null));
        HealthCheckResponse response = check.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals(2L, ((Number) response.getData().orElseThrow().get("members")).longValue());
    }

    @Test
    @DisplayName("drained by an administrator: DOWN, whatever NATS says, so the load balancer stops sending turns")
    void drainedIsDown() {
        NatsConnectionManager manager = mock(NatsConnectionManager.class);
        when(manager.isConnected()).thenReturn(true);
        when(manager.node()).thenReturn(new NodeIdentity("n1", "b1"));
        ClusterPresence presence = mock(ClusterPresence.class);
        when(presence.members()).thenReturn(List.of(Map.of("node", "n1")));
        NatsLeaseManager leases = mock(NatsLeaseManager.class);
        when(leases.isDraining()).thenReturn(true);
        Instance<NatsLeaseManager> leaseInstance = instance(leases);
        when(leaseInstance.isResolvable()).thenReturn(true);
        var check = new ClusterHealthCheck(ClusterConfig.defaults().withMessagingType("nats"), instance(manager), instance(presence),
                leaseInstance);
        HealthCheckResponse response = check.call();
        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        assertEquals(Boolean.TRUE, response.getData().orElseThrow().get("draining"));
    }
}
