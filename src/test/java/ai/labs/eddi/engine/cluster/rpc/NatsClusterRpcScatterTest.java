/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.rpc;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ClusterPresence;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("NatsClusterRpc — a scatter that waits for particular nodes")
class NatsClusterRpcScatterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Deque<Message> inbox = new ArrayDeque<>();
    private NatsClusterRpc rpc;

    @BeforeEach
    void setUp() throws Exception {
        NatsConnectionManager connections = mock(NatsConnectionManager.class);
        when(connections.node()).thenReturn(new NodeIdentity("self", "b1"));
        when(connections.config()).thenReturn(ClusterConfig.defaults().withMessagingType("nats"));
        Connection connection = mock(Connection.class);
        when(connections.requireConnected()).thenReturn(connection);
        when(connection.createInbox()).thenReturn("_INBOX.test");
        Subscription subscription = mock(Subscription.class);
        when(connection.subscribe("_INBOX.test")).thenReturn(subscription);
        when(subscription.nextMessage(any(Duration.class))).thenAnswer(inv -> inbox.poll());
        rpc = new NatsClusterRpc(connections, mock(ClusterPresence.class), new SimpleMeterRegistry());
        // n4 joined after the caller read its peers, and answers first.
        for (String node : List.of("n4", "n2", "n3")) {
            inbox.add(reply(node));
        }
    }

    private static Message reply(String node) throws Exception {
        Message message = mock(Message.class);
        when(message.hasHeaders()).thenReturn(false);
        when(message.getData()).thenReturn(JSON.writeValueAsBytes(Map.of("node", node, "drained", true)));
        return message;
    }

    @Test
    @DisplayName("an extra node's reply does not end the wait: every expected node is waited for")
    void waitsForEveryExpectedNode() {
        Map<String, Map<String, Object>> replies = rpc.callAllAwaiting(IClusterRpc.ERASURE_DRAIN, Map.of(), Duration.ofSeconds(5),
                Set.of("n2", "n3"));
        assertEquals(Set.of("n2", "n3", "n4"), replies.keySet());
        assertEquals(0, inbox.size());
    }

    @Test
    @DisplayName("once every expected node answered, the wait ends")
    void stopsOnceTheExpectedNodesAnswered() throws Exception {
        inbox.add(reply("n5"));
        Map<String, Map<String, Object>> replies = rpc.callAllAwaiting(IClusterRpc.ERASURE_DRAIN, Map.of(), Duration.ofSeconds(5),
                Set.of("n2", "n3"));
        assertEquals(Set.of("n4", "n2", "n3"), replies.keySet());
        assertEquals(1, inbox.size(), "the reply after the last expected node is not waited for");
    }

    @Test
    @DisplayName("by count, any two replies end the wait — the reason a caller that needs particular nodes waits by id")
    void byCountAnyRepliesEndTheWait() {
        Map<String, Map<String, Object>> replies = rpc.callAll(IClusterRpc.ERASURE_DRAIN, Map.of(), Duration.ofSeconds(5), 2);
        assertEquals(Set.of("n4", "n2"), replies.keySet());
    }
}
