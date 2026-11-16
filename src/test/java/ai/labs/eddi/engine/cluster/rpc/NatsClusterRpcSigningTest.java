/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.rpc;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ClusterMessageSigner;
import ai.labs.eddi.engine.cluster.ClusterPresence;
import ai.labs.eddi.engine.cluster.ClusterSigningConfig;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.impl.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C4: node RPCs are signed; a forged, tampered, stale or replayed request is
 * refused without a reply, and a forged reply is dropped by the caller.
 */
@DisplayName("NatsClusterRpc — signed requests and replies")
class NatsClusterRpcSigningTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "fixturefixturefixturefixturefixture-rpc-key";
    private static final String SUBJECT = "eddi.EDDI.rpc.all.admin-drain";

    private NatsConnectionManager connections;
    private Connection connection;
    private ClusterMessageSigner signer;
    private final AtomicInteger handled = new AtomicInteger();

    @BeforeEach
    void setUp() {
        connections = mock(NatsConnectionManager.class);
        when(connections.node()).thenReturn(new NodeIdentity("self", "b1"));
        when(connections.config()).thenReturn(ClusterConfig.defaults().withMessagingType("nats"));
        connection = mock(Connection.class);
        when(connections.requireConnected()).thenReturn(connection);
        signer = signer(false);
    }

    private static ClusterMessageSigner signer(boolean required) {
        return new ClusterMessageSigner(ClusterSigningConfig.of(KEY, List.of(), required, Duration.ofSeconds(60)), new SimpleMeterRegistry());
    }

    private NatsClusterRpc rpc(ClusterMessageSigner s) {
        NatsClusterRpc rpc = new NatsClusterRpc(connections, mock(ClusterPresence.class), new SimpleMeterRegistry(), s);
        Function<Map<String, Object>, Map<String, Object>> handler = request -> {
            handled.incrementAndGet();
            return Map.of("drained", true);
        };
        rpc.handle("admin-drain", handler);
        return rpc;
    }

    @Test
    @DisplayName("a request signed with the cluster key is served once; its replay, a tampered copy and a forgery are refused")
    void signedRequests() throws Exception {
        NatsClusterRpc rpc = rpc(signer);
        byte[] body = JSON.writeValueAsBytes(Map.of("reason", "maintenance"));
        Headers headers = signer.sign(SUBJECT, body, null, NatsClusterRpc.PAYLOAD_VERSION);

        assertTrue(rpc.handleRequest(SUBJECT, "_INBOX.1", headers, body));
        verify(connection, timeout(2000)).publish(anyString(), any(Headers.class), any(byte[].class));
        assertFalse(rpc.handleRequest(SUBJECT, "_INBOX.1", headers, body), "a captured request replayed is refused");
        assertFalse(rpc.handleRequest(SUBJECT, "_INBOX.1", headers, JSON.writeValueAsBytes(Map.of("reason", "x"))),
                "a body changed under a valid signature is refused");
        assertFalse(rpc.handleRequest("eddi.EDDI.rpc.all.gdpr-stop", "_INBOX.1", signer.sign(SUBJECT, body, null, 1), body),
                "a signature cannot be moved to another operation");
        ClusterMessageSigner attacker = new ClusterMessageSigner(
                ClusterSigningConfig.of("an-attacker-key-of-sufficient-length-000", List.of(), false, Duration.ofSeconds(60)),
                new SimpleMeterRegistry());
        assertFalse(rpc.handleRequest(SUBJECT, "_INBOX.1", attacker.sign(SUBJECT, body, null, 1), body));
        Thread.sleep(200);
        assertEquals(1, handled.get());
    }

    @Test
    @DisplayName("a request older than the window is refused")
    void staleRequest() throws Exception {
        ClusterMessageSigner past = signer(false);
        past.withClock(() -> System.currentTimeMillis() - 120_000);
        byte[] body = JSON.writeValueAsBytes(Map.of());
        assertFalse(rpc(signer).handleRequest(SUBJECT, null, past.sign(SUBJECT, body, null, 1), body));
    }

    @Test
    @DisplayName("rolling upgrade: an unsigned request from an older node is served unless signing is required")
    void unsignedRequests() throws Exception {
        byte[] body = JSON.writeValueAsBytes(Map.of());
        assertTrue(rpc(signer).handleRequest(SUBJECT, null, null, body));
        assertFalse(rpc(signer(true)).handleRequest(SUBJECT, null, null, body));
        assertTrue(rpc(ClusterMessageSigner.unsigned()).handleRequest(SUBJECT, null, signer.sign(SUBJECT, body, null, 1), body),
                "a node without a key yet accepts the signed messages of nodes that already have one");
    }

    @Test
    @DisplayName("a reply that is not signed over the request's nonce is dropped by the caller")
    void forgedReplyIsDropped() throws Exception {
        NatsClusterRpc rpc = rpc(signer);
        byte[] genuine = JSON.writeValueAsBytes(Map.of("node", "n2", "stopped", 1));
        when(connection.request(anyString(), any(Headers.class), any(byte[].class), any(Duration.class))).thenAnswer(inv -> {
            Headers request = inv.getArgument(1);
            return message(genuine,
                    signer.sign(ClusterMessageSigner.replySubject(ClusterMessageSigner.nonceOf(request)), genuine, null, 1));
        });
        Optional<Map<String, Object>> answer = rpc.call("n2", "gdpr-stop", Map.of());
        assertEquals(1, answer.orElseThrow().get("stopped"));

        // A reply signed for another request (a replayed earlier answer) is not this
        // answer.
        Headers other = signer.sign(ClusterMessageSigner.replySubject("some-other-nonce"), genuine, null, 1);
        when(connection.request(anyString(), any(Headers.class), any(byte[].class), any(Duration.class)))
                .thenAnswer(inv -> message(genuine, other));
        assertTrue(rpc.call("n2", "gdpr-stop", Map.of()).isEmpty());
    }

    private static Message message(byte[] data, Headers headers) {
        Message message = mock(Message.class);
        when(message.getData()).thenReturn(data);
        when(message.hasHeaders()).thenReturn(headers != null);
        when(message.getHeaders()).thenReturn(headers);
        return message;
    }
}
