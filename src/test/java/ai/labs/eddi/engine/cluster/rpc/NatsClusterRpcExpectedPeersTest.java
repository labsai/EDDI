/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.rpc;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ClusterPresence;
import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("NatsClusterRpc.expectedPeers")
class NatsClusterRpcExpectedPeersTest {

    private ClusterPresence presence;
    private NatsClusterRpc rpc;

    @BeforeEach
    void setUp() {
        NatsConnectionManager connections = mock(NatsConnectionManager.class);
        ClusterConfig config = mock(ClusterConfig.class);
        when(config.natsPrefix()).thenReturn("eddi");
        when(config.natsRequestTimeout()).thenReturn(Duration.ofSeconds(1));
        when(connections.config()).thenReturn(config);
        NodeIdentity node = mock(NodeIdentity.class);
        when(node.nodeId()).thenReturn("self");
        when(connections.node()).thenReturn(node);
        presence = mock(ClusterPresence.class);
        rpc = new NatsClusterRpc(connections, presence, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("counts the remote nodes, leaving this node's own record out")
    void countsRemoteNodes() {
        when(presence.currentMembers()).thenReturn(List.of(Map.of("node", "a"), Map.of("node", "self"), Map.of("node", "b")));
        assertEquals(2, rpc.expectedPeers());
    }

    @Test
    @DisplayName("does not subtract this node when its own record is absent")
    void ownRecordAbsent() {
        when(presence.currentMembers()).thenReturn(List.of(Map.of("node", "a"), Map.of("node", "b")));
        assertEquals(2, rpc.expectedPeers());
    }

    @Test
    @DisplayName("an unreadable presence bucket fails instead of answering no peers")
    void unavailablePresenceFails() {
        when(presence.currentMembers()).thenThrow(new ClusterUnavailableException("presence down"));
        assertThrows(ClusterUnavailableException.class, () -> rpc.expectedPeers());
    }
}
