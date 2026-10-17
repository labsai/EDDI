/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.engine.httpclient.outbound.OutboundEngine;
import ai.labs.eddi.engine.httpclient.outbound.OutboundEngine.Hop;
import ai.labs.eddi.engine.httpclient.outbound.TestOutbound;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an A2A peer actually receives, driven end to end through the public
 * {@code discoverTools} and the
 * {@link dev.langchain4j.service.tool.ToolExecutor ToolExecutor} it hands back.
 * <p>
 * Deliberately not a test of {@code applyCredential} —
 * {@code A2ACredentialTest} already covers that shared method, and it would
 * have passed throughout the defect this pins. The defect was a
 * <em>caller</em>: the task call had its own copy of the credential block, so a
 * peer configured against a connection discovered its skills perfectly and then
 * sent the literal string {@code Bearer ${connection:…}} on every call it was
 * actually asked to make. Only asserting on the request the task call builds
 * can catch a caller going its own way again.
 * <p>
 * The manager talks to a real {@code OutboundHttp} facade whose engine records
 * every hop and answers it from memory, so nothing here binds a socket and the
 * assertions see exactly what the facade would put on the wire.
 */
@DisplayName("A2AToolProviderManager — what lands on the wire")
class A2ATaskCredentialWiringTest {

    private static final String AGENT_URL = "https://peer.example.com/a2a";
    private static final String CARD_URL = AGENT_URL + "/agent.json";

    /** One skill, so discovery yields a tool whose executor we can then call. */
    private static final String AGENT_CARD = """
            {"name":"peer","skills":[{"id":"lookup","name":"Lookup","description":"Looks things up"}]}""";

    private static final String TASK_RESULT = """
            {"jsonrpc":"2.0","result":{"artifacts":[{"parts":[{"type":"text","text":"done"}]}]}}""";

    private GlobalVariableResolver globalVariableResolver;
    private SecretResolver secretResolver;
    private ConnectionResolver connectionResolver;

    /** Every hop the facade sent, in order. */
    private final List<Hop> sent = new ArrayList<>();

    /** When set, every hop fails with it, as a transport error would. */
    private final AtomicReference<IOException> transportFailure = new AtomicReference<>();

    /**
     * Answers the card fetch with {@link #AGENT_CARD} and the task call with
     * {@link #TASK_RESULT}.
     */
    private final OutboundEngine engine = hop -> {
        sent.add(hop);
        if (transportFailure.get() != null) {
            throw transportFailure.get();
        }
        String body = hop.uri().toString().endsWith("/agent.json") ? AGENT_CARD : TASK_RESULT;
        return new OutboundEngine.Received(200, null, Map.of(), new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    };

    @BeforeEach
    void setUp() throws Exception {
        globalVariableResolver = mock(GlobalVariableResolver.class);
        secretResolver = mock(SecretResolver.class);
        connectionResolver = mock(ConnectionResolver.class);
        sent.clear();
        transportFailure.set(null);
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(secretResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
    }

    private A2AToolProviderManager manager() {
        return new A2AToolProviderManager(globalVariableResolver, secretResolver, false,
                A2AToolProviderManager.DEFAULT_MAX_DESCRIPTION_CHARS, A2AToolProviderManager.DEFAULT_MAX_SCHEMA_BYTES,
                A2AToolProviderManager.DEFAULT_MAX_SCHEMA_BYTES_PER_AGENT, connectionResolver, TestOutbound.facade(engine));
    }

    private static A2AAgentConfig config(String apiKey) {
        var config = new A2AAgentConfig();
        config.setName("peer");
        config.setUrl(AGENT_URL);
        config.setApiKey(apiKey);
        return config;
    }

    private static String header(Hop hop, String name) {
        List<String> values = hop.headers().get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    @Nested
    @DisplayName("the task call")
    class TaskCall {

        @Test
        @DisplayName("carries the per-call resolved credential, not the discovery one and not the literal reference")
        void taskCallCarriesTheResolvedCredential() {
            // Two DIFFERENT tokens on purpose. Identical ones would let a task call
            // that simply reused the cached discovery header pass, and would let a
            // task call that re-resolved through the wrong entry point pass too.
            when(connectionResolver.resolveForDiscovery(eq("${connection:sf}"), any(URI.class)))
                    .thenReturn(Optional.of(new ResolvedCredential("Authorization", "Bearer discovery-token")));
            when(connectionResolver.resolve(eq("${connection:sf}"), any(URI.class), eq(null)))
                    .thenReturn(new ResolvedCredential("Authorization", "Bearer task-token"));

            var executors = manager().discoverTools(List.of(config("${connection:sf}"))).executors();
            var executor = executors.get("peer_lookup");
            assertNotNull(executor, () -> "discovery produced no tool to call: " + executors.keySet());

            String answer = executor.execute(
                    ToolExecutionRequest.builder().name("peer_lookup").arguments("{\"message\":\"hi\"}").build(), null);

            assertEquals("done", answer, "the task call did not complete, so the header assertions below would prove nothing");
            assertEquals(2, sent.size(), "expected exactly the card fetch and the task call");
            assertEquals(CARD_URL, sent.get(0).uri().toString());
            assertEquals("Bearer discovery-token", header(sent.get(0), "Authorization"));
            assertEquals("POST", sent.get(1).method());
            assertEquals(AGENT_URL, sent.get(1).uri().toString());
            assertEquals("Bearer task-token", header(sent.get(1), "Authorization"),
                    "this request is the whole defect: it used to go out carrying the literal ${connection:…} text as its bearer token");
        }

        @Test
        @DisplayName("a connection may name its own header on the task call too")
        void taskCallHonoursTheConnectionsHeaderName() {
            when(connectionResolver.resolveForDiscovery(anyString(), any(URI.class)))
                    .thenReturn(Optional.of(new ResolvedCredential("X-Api-Key", "discovery-key")));
            when(connectionResolver.resolve(anyString(), any(URI.class), eq(null)))
                    .thenReturn(new ResolvedCredential("X-Api-Key", "task-key"));

            var executor = manager().discoverTools(List.of(config("${connection:sf}"))).executors().get("peer_lookup");
            executor.execute(ToolExecutionRequest.builder().name("peer_lookup").arguments("{\"message\":\"hi\"}").build(), null);

            Hop taskRequest = sent.get(1);
            assertEquals("task-key", header(taskRequest, "X-Api-Key"));
            assertNull(header(taskRequest, "Authorization"),
                    "a connection supplies the whole value; adding Authorization as well would send two credentials");
        }
    }

    @Nested
    @DisplayName("discovery")
    class Discovery {

        @Test
        @DisplayName("uses the discovery-aware resolution, never the per-call one")
        void discoveryUsesTheDiscoveryAwareResolution() {
            // The card is CACHED for five minutes and served to every conversation
            // after, so resolving it as an ordinary per-call credential would pin the
            // first caller's authority onto everybody behind them.
            when(connectionResolver.resolveForDiscovery(anyString(), any(URI.class)))
                    .thenReturn(Optional.of(new ResolvedCredential("Authorization", "Bearer discovery-token")));

            manager().discoverTools(List.of(config("${connection:sf}")));

            assertEquals(1, sent.size());
            assertEquals("Bearer discovery-token", header(sent.get(0), "Authorization"));
            verify(connectionResolver).resolveForDiscovery(eq("${connection:sf}"), any(URI.class));
            verify(connectionResolver, never()).resolve(anyString(), any(URI.class), any());
        }

        @Test
        @DisplayName("a withheld PER_USER credential still sends the card fetch, unauthenticated")
        void withheldCredentialStillFetchesTheCard() {
            // resolveForDiscovery returning empty means "let the peer decide", not
            // "give up": a peer whose card is public must still be discoverable.
            when(connectionResolver.resolveForDiscovery(anyString(), any(URI.class))).thenReturn(Optional.empty());

            var result = manager().discoverTools(List.of(config("${connection:sf}")));

            assertEquals(1, sent.size(), "the request must still go out");
            assertNull(header(sent.get(0), "Authorization"),
                    "sending a per-user token here would pin one user's authority onto the cached card");
            assertTrue(result.executors().containsKey("peer_lookup"), "and the peer's skills must still be registered");
        }
    }

    @Nested
    @DisplayName("redirects")
    class Redirects {

        @Test
        @DisplayName("a peer's redirect is never followed, so its credential never reaches the Location")
        void redirectIsNotFollowed() {
            when(connectionResolver.resolveForDiscovery(anyString(), any(URI.class)))
                    .thenReturn(Optional.of(new ResolvedCredential("Authorization", "Bearer discovery-token")));
            OutboundEngine redirecting = hop -> {
                sent.add(hop);
                return new OutboundEngine.Received(307, null, Map.of("Location", List.of("https://203.0.113.50/steal")),
                        new ByteArrayInputStream(new byte[0]));
            };
            var manager = new A2AToolProviderManager(globalVariableResolver, secretResolver, false,
                    A2AToolProviderManager.DEFAULT_MAX_DESCRIPTION_CHARS, A2AToolProviderManager.DEFAULT_MAX_SCHEMA_BYTES,
                    A2AToolProviderManager.DEFAULT_MAX_SCHEMA_BYTES_PER_AGENT, connectionResolver, TestOutbound.facade(redirecting));

            assertTrue(manager.discoverTools(List.of(config("${connection:sf}"))).executors().isEmpty());

            assertTrue(sent.stream().noneMatch(hop -> "203.0.113.50".equals(hop.uri().getHost())),
                    "the Location must never be requested: " + sent.stream().map(Hop::uri).toList());
        }
    }

    @Nested
    @DisplayName("the circuit breaker shields a flaky peer, not a missing credential")
    class CircuitBreaker {

        @Test
        @DisplayName("a credential failure during discovery is retried on the next turn rather than opening the circuit")
        void credentialFailureDoesNotOpenTheCircuit() throws Exception {
            // Opening it here suppresses discovery for EVERY user because one of them
            // has no grant, and tells the operator the peer is unreachable when it is
            // perfectly healthy. Nothing about a missing grant is healed by waiting.
            when(connectionResolver.resolveForDiscovery(anyString(), any(URI.class)))
                    .thenThrow(new ConnectionException(ConnectionException.Reason.NOT_CONNECTED, "alice has not connected 'sf' yet"));
            var manager = manager();
            var config = config("${connection:sf}");

            for (int turn = 1; turn <= 4; turn++) {
                assertTrue(manager.discoverTools(List.of(config)).executors().isEmpty(), "turn " + turn);
            }

            // The circuit state is private; the observable consequence of an open
            // circuit is that the fourth turn is skipped before resolution is even
            // attempted. Four attempts means the circuit stayed closed.
            verify(connectionResolver, times(4)).resolveForDiscovery(anyString(), any(URI.class));
            assertTrue(sent.isEmpty(), "nothing may be sent when the credential cannot be resolved");
        }

        @Test
        @DisplayName("a genuine transport failure still opens the circuit after three turns")
        void transportFailureStillOpensTheCircuit() throws Exception {
            // The counterpart. Without it the test above would still pass if the
            // breaker had been switched off entirely.
            transportFailure.set(new IOException("connection refused"));
            var manager = manager();
            var config = config(null);

            for (int turn = 1; turn <= 4; turn++) {
                assertTrue(manager.discoverTools(List.of(config)).executors().isEmpty(), "turn " + turn);
            }

            assertEquals(3, sent.size(), "three failed turns open the circuit; the fourth is skipped");
        }
    }
}
