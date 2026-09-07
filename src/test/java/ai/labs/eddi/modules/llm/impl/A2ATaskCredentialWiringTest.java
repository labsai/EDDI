/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
 * The outbound client is a mock installed into the manager's field, so nothing
 * here binds a socket: the manager builds its {@code HttpClient} lazily and the
 * double-checked read returns whatever is already there.
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
    private HttpClient httpClient;

    /** Every request the manager handed to the client, in order. */
    private final List<HttpRequest> sent = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        globalVariableResolver = mock(GlobalVariableResolver.class);
        secretResolver = mock(SecretResolver.class);
        connectionResolver = mock(ConnectionResolver.class);
        httpClient = mock(HttpClient.class);
        sent.clear();
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(secretResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        // doAnswer rather than when(...): the stubbed call takes matchers for both
        // arguments and its answer has to READ the request that was passed.
        doAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            sent.add(request);
            return response(request.uri().toString().endsWith("/agent.json") ? AGENT_CARD : TASK_RESULT);
        }).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        return response;
    }

    private A2AToolProviderManager manager() {
        var manager = new A2AToolProviderManager(globalVariableResolver, secretResolver, false, connectionResolver);
        installHttpClient(manager, httpClient);
        return manager;
    }

    /**
     * The manager creates its client lazily precisely so an injected bean that
     * never talks to a peer does not pay for one; that same laziness is what lets a
     * test put a mock there instead of standing up a server.
     */
    private static void installHttpClient(A2AToolProviderManager manager, HttpClient client) {
        try {
            Field field = A2AToolProviderManager.class.getDeclaredField("httpClient");
            field.setAccessible(true);
            field.set(manager, client);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("A2AToolProviderManager no longer keeps its outbound client in a 'httpClient' field — this test "
                    + "asserts what actually reaches a peer and must be re-pointed rather than deleted.", e);
        }
    }

    private static A2AAgentConfig config(String apiKey) {
        var config = new A2AAgentConfig();
        config.setName("peer");
        config.setUrl(AGENT_URL);
        config.setApiKey(apiKey);
        return config;
    }

    private static String header(HttpRequest request, String name) {
        return request.headers().firstValue(name).orElse(null);
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

            HttpRequest taskRequest = sent.get(1);
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
            verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }

        @Test
        @DisplayName("a genuine transport failure still opens the circuit after three turns")
        void transportFailureStillOpensTheCircuit() throws Exception {
            // The counterpart. Without it the test above would still pass if the
            // breaker had been switched off entirely.
            doThrow(new IOException("connection refused")).when(httpClient)
                    .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
            var manager = manager();
            var config = config(null);

            for (int turn = 1; turn <= 4; turn++) {
                assertTrue(manager.discoverTools(List.of(config)).executors().isEmpty(), "turn " + turn);
            }

            verify(httpClient, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }
}
