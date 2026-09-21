/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.configs.connections.model.StaticAuth;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.AccessTokenSupplier;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionRegistry;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.CredentialReferenceResolver;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.engine.security.ResolutionPrincipal;
import ai.labs.eddi.engine.security.ResolutionPrincipalContext;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager.McpFailureKind;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.client.McpCallContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a connection-bound MCP server actually puts on the wire, and how a
 * credential failure is classified once it comes back.
 * <p>
 * These are call-site tests. {@code ConnectionResolver} already has its own
 * suite, and it passed while a connection-bound MCP server registered ZERO
 * tools — because the manager withheld the credential from the
 * {@code initialize}/{@code tools/list} handshake for <em>every</em> binding,
 * the server answered 401, and the agent silently had no tools at all. So the
 * resolver here is the REAL one, driven through a mocked registry: a test that
 * stubbed {@code resolveForDiscovery} would be asserting its own stubbing
 * rather than the binding rule the fix turns on.
 */
@DisplayName("McpToolProviderManager — connection-bound credentials")
class McpConnectionCredentialTest {

    /** The MCP server, and the only origin the test connections allow. */
    private static final String SERVER_URL = "https://mcp.example.com/mcp";
    private static final String SERVER_ORIGIN = "https://mcp.example.com";

    /**
     * {@code authorizationHeader} is private because nothing outside the transport
     * lambda may call it. Reached reflectively rather than by widening production
     * visibility for a test's convenience; a rename surfaces here as an error, not
     * as a quietly-passing test.
     */
    private static final Method AUTHORIZATION_HEADER = authorizationHeaderMethod();

    private static Method authorizationHeaderMethod() {
        try {
            Method method = McpToolProviderManager.class.getDeclaredMethod("authorizationHeader", String.class, McpServerConfig.class,
                    boolean.class, boolean.class, McpCallContext.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("McpToolProviderManager.authorizationHeader(...) has moved — this test pins what it puts "
                    + "on an MCP request and must be pointed at its replacement, not deleted.", e);
        }
    }

    private ConnectionRegistry registry;
    private SecretResolver secretResolver;
    private GlobalVariableResolver globalVariableResolver;
    private AccessTokenSupplier accessTokenSupplier;
    private ResolutionPrincipalContext resolutionPrincipalContext;
    private ConnectionResolver connectionResolver;

    @BeforeEach
    void setUp() {
        registry = mock(ConnectionRegistry.class);
        secretResolver = mock(SecretResolver.class);
        globalVariableResolver = mock(GlobalVariableResolver.class);
        accessTokenSupplier = mock(AccessTokenSupplier.class);
        resolutionPrincipalContext = new ResolutionPrincipalContext();

        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(secretResolver.resolveValue(anyString()))
                .thenAnswer(i -> i.<String>getArgument(0).replace("${vault:mcp-token}", "live-token"));

        connectionResolver = new ConnectionResolver(registry, new CredentialReferenceResolver(secretResolver, globalVariableResolver),
                mock(CallerIdentityContext.class), new SimpleMeterRegistry(), accessTokenSupplier, true);
        injectPrincipalContext(connectionResolver, resolutionPrincipalContext);
    }

    /**
     * The resolver takes its principal context by field injection, and the field is
     * package-private to its own package. Set reflectively rather than by making it
     * public for a test in another package.
     */
    private static void injectPrincipalContext(ConnectionResolver resolver, ResolutionPrincipalContext context) {
        try {
            Field field = ConnectionResolver.class.getDeclaredField("resolutionPrincipalContext");
            field.setAccessible(true);
            field.set(resolver, context);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ConnectionResolver no longer takes its ResolutionPrincipalContext by field injection", e);
        }
    }

    @AfterEach
    void unbindPrincipal() {
        // A ThreadLocal on a thread JUnit reuses: a leaked principal would authorise
        // the next test's PER_USER resolution and turn a refusal into a pass.
        resolutionPrincipalContext.clear();
    }

    private McpToolProviderManager manager() {
        var callerContext = new CallerIdentityContext(null, null);
        return new McpToolProviderManager(globalVariableResolver, secretResolver, new CallerIdentityResolver(callerContext, true), callerContext,
                false, McpToolProviderManager.DEFAULT_MAX_DESCRIPTION_CHARS, McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS,
                connectionResolver);
    }

    private static McpServerConfig config(String apiKey) {
        var config = new McpServerConfig();
        config.setName("crm");
        config.setUrl(SERVER_URL);
        config.setTransport("http");
        config.setApiKey(apiKey);
        return config;
    }

    private void register(ConnectionConfiguration connection) {
        lenient().when(registry.require(any(ConnectionReference.class))).thenReturn(connection);
        lenient().when(registry.find(any(ConnectionReference.class))).thenReturn(Optional.of(connection));
    }

    private static ConnectionConfiguration serviceConnection() {
        var connection = new ConnectionConfiguration();
        connection.setName("crm");
        connection.setAuthType(AuthType.STATIC);
        connection.setBinding(Binding.SERVICE);
        connection.setBaseUrlAllowlist(List.of(SERVER_ORIGIN));
        var auth = new StaticAuth();
        auth.setHeaderName("Authorization");
        auth.setValueTemplate("Bearer ${vault:mcp-token}");
        connection.setStaticAuth(auth);
        return connection;
    }

    private static ConnectionConfiguration perUserConnection() {
        var connection = new ConnectionConfiguration();
        connection.setName("crm");
        connection.setAuthType(AuthType.OAUTH2_AUTHORIZATION_CODE);
        connection.setBinding(Binding.PER_USER);
        connection.setBaseUrlAllowlist(List.of(SERVER_ORIGIN));
        var oauth = new OAuthConfig();
        oauth.setTokenUrl("https://auth.example.com/oauth/token");
        oauth.setAuthorizationUrl("https://auth.example.com/authorize");
        oauth.setClientId("client");
        oauth.setClientSecret("${vault:client-secret}");
        connection.setOauth(oauth);
        return connection;
    }

    /**
     * The handshake: langchain4j delegates {@code initialize} and
     * {@code tools/list} with a null invocation context, which is the ONLY thing
     * distinguishing them from a tool call.
     */
    private static McpCallContext discoveryCall() {
        return new McpCallContext(null, null);
    }

    /** A tool call: {@code McpToolExecutor} always builds an invocation context. */
    private static McpCallContext toolCall() {
        InvocationContext invocation = InvocationContext.builder().interfaceName("Agent").methodName("chat").build();
        return new McpCallContext(invocation, null);
    }

    /**
     * Invokes the header builder with the two flags derived exactly as
     * {@code createTransport} derives them, so the test cannot accidentally assert
     * a branch the production wiring would never take.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> headerFor(McpServerConfig config, McpCallContext callContext) {
        boolean callerBound = CallerIdentityResolver.containsReference(config.getApiKey());
        boolean connectionBound = ConnectionResolver.containsReference(config.getApiKey());
        try {
            return (Map<String, String>) AUTHORIZATION_HEADER.invoke(manager(), config.getApiKey(), config, callerBound, connectionBound,
                    callContext);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @DisplayName("discovery versus tool call")
    class DiscoveryVersusToolCall {

        @Test
        @DisplayName("a SERVICE-bound connection sends its credential on the discovery handshake")
        void serviceBoundDiscoveryIsAuthenticated() {
            // The headline regression. Withholding here made initialize/tools-list go
            // out unauthenticated, the server answered 401, and the agent registered
            // zero tools with nothing anywhere naming the credential.
            register(serviceConnection());

            Map<String, String> headers = headerFor(config("${connection:crm}"), discoveryCall());

            assertEquals(Map.of("Authorization", "Bearer live-token"), headers,
                    "an unauthenticated handshake is answered 401 and the agent ends up with no tools at all");
        }

        @Test
        @DisplayName("a PER_USER connection is withheld from discovery but supplied to the tool call")
        void perUserIsWithheldFromDiscoveryOnly() {
            // Both halves in one test on purpose: an empty map on its own also passes
            // when the code does nothing whatsoever, so the withholding is only
            // meaningful next to proof that the same config DOES produce a credential
            // where one may safely be sent.
            register(perUserConnection());
            resolutionPrincipalContext.bind(new ResolutionPrincipal("alice", ResolutionPrincipal.Provenance.VERIFIED));
            when(accessTokenSupplier.accessToken(any(), eq("alice"))).thenReturn("ya29.alice");
            var config = config("${connection:crm}");

            assertEquals(Map.of(), headerFor(config, discoveryCall()),
                    "the MCP client is cached, so one user's token on the handshake would pin their session onto everybody after them");
            assertEquals(Map.of("Authorization", "Bearer ya29.alice"), headerFor(config, toolCall()),
                    "a tool call belongs to one conversation, so the turn's principal is exactly who it must resolve for");
        }

        @Test
        @DisplayName("the connection owns the header name — nothing hardcodes Authorization")
        void honoursTheConnectionsHeaderName() {
            var connection = serviceConnection();
            connection.getStaticAuth().setHeaderName("X-Api-Key");
            connection.getStaticAuth().setValueTemplate("${vault:mcp-token}");
            register(connection);

            Map<String, String> headers = headerFor(config("${connection:crm}"), discoveryCall());

            assertEquals(Map.of("X-Api-Key", "live-token"), headers,
                    "sending the value under Authorization instead would authenticate nothing and leak the key to a header the server ignores");
        }

        @Test
        @DisplayName("an ordinary static key is still bearer-prefixed on both discovery and tool calls")
        void staticKeyPathIsUnchanged() {
            // The connection branch sits in front of the path every existing agent
            // uses; this is the guard that it did not swallow it. createTransport
            // resolves a vault reference ONCE and hands the resolved value on, so a
            // plain token is exactly what arrives here.
            var config = config("plain-token");

            assertEquals(Map.of("Authorization", "Bearer plain-token"), headerFor(config, discoveryCall()));
            assertEquals(Map.of("Authorization", "Bearer plain-token"), headerFor(config, toolCall()),
                    "a static key is the same credential for everybody, so a tool call carries it too");
        }
    }

    @Nested
    @DisplayName("a value that is not exactly one connection reference")
    class MixedReferences {

        /**
         * A spy whose {@code fetchToolsFromServer} would blow up if it were reached:
         * every case in here must be refused BEFORE the server is contacted, and a
         * silently-successful discovery would otherwise look like a pass.
         */
        private McpToolProviderManager rejectingManager() {
            McpToolProviderManager manager = spy(manager());
            doThrow(new AssertionError("the server must not be contacted with a credential the config cannot supply"))
                    .when(manager).fetchToolsFromServer(any());
            return manager;
        }

        @Test
        @DisplayName("a scheme spelled out in front of the reference is refused, naming the offending text")
        void refusesASchemePrefix() {
            // A connection supplies the WHOLE header value. "Bearer ${connection:crm}"
            // used to drop the word Bearer silently and send a bare token, and the 401
            // that came back named nothing at all.
            var result = rejectingManager().discoverTools(List.of(config("Bearer ${connection:crm}")));

            assertEquals(1, result.failures().size());
            var failure = result.failures().get(0);
            assertEquals(McpFailureKind.INVALID_CONFIGURATION, failure.kind(),
                    "a config an operator must edit is not a connectivity problem and must never reach the breaker");
            assertTrue(failure.message().contains("apiKey"), failure.message());
            assertTrue(failure.message().contains("Bearer"),
                    "the author has to be told WHICH text is in the way, or the message is not actionable: " + failure.message());
            assertTrue(failure.message().contains("valueTemplate"),
                    "and where the scheme belongs instead: " + failure.message());
        }

        @Test
        @DisplayName("two references in one value are refused rather than silently using the first")
        void refusesTwoReferences() {
            var result = rejectingManager().discoverTools(List.of(config("${connection:crm} ${connection:billing}")));

            assertEquals(1, result.failures().size());
            var failure = result.failures().get(0);
            assertEquals(McpFailureKind.INVALID_CONFIGURATION, failure.kind());
            assertTrue(failure.message().contains("a second ${connection:…} reference"),
                    "only the first reference is ever parsed, so the second one vanishing has to be said out loud: " + failure.message());
        }

        @Test
        @DisplayName("a lone reference is accepted and the server IS contacted")
        void aSoleReferenceIsAccepted() {
            // The control. Without it the two refusals above would still pass if the
            // guard rejected every connection-bound config outright. Discovery itself
            // is stubbed out — a null result is the manager's "server exposed nothing"
            // case — so nothing here builds a transport or opens a socket.
            McpToolProviderManager manager = spy(manager());
            doReturn(null).when(manager).fetchToolsFromServer(any());

            var result = manager.discoverTools(List.of(config("${connection:crm}")));

            verify(manager).fetchToolsFromServer(any());
            assertTrue(result.failures().isEmpty(), () -> "a well-formed reference must not be rejected: " + result.failures());
        }
    }

    @Nested
    @DisplayName("failure classification")
    class FailureClassification {

        private McpToolProviderManager failingWith(RuntimeException failure) {
            McpToolProviderManager manager = spy(manager());
            doThrow(failure).when(manager).fetchToolsFromServer(any());
            return manager;
        }

        /**
         * Runs four turns against the same config, asserting the classification each
         * time, and returns the manager so the caller can inspect the breaker.
         */
        private McpToolProviderManager fourTurns(RuntimeException failure, McpFailureKind expected, McpServerConfig config) {
            McpToolProviderManager manager = failingWith(failure);
            for (int turn = 1; turn <= 4; turn++) {
                var result = manager.discoverTools(List.of(config));
                assertEquals(expected, result.failures().get(0).kind(), "turn " + turn + " was classified wrongly");
            }
            return manager;
        }

        @Test
        @DisplayName("a user who has not connected is an auth problem, and waiting does not heal it")
        void notConnectedDoesNotOpenTheBreaker() {
            var config = config("${connection:crm}");
            var failure = new ConnectionException(ConnectionException.Reason.NOT_CONNECTED, "Nobody has connected 'crm' yet.");

            McpToolProviderManager manager = fourTurns(failure, McpFailureKind.AUTHENTICATION_REQUIRED, config);

            assertFalse(manager.isCircuitOpen(config),
                    "opening the breaker here suppresses discovery for EVERY user because one of them has no grant");
            // The positive half: the server is still asked on every turn. A breaker
            // that had opened would have skipped turns 4 (and reported CIRCUIT_OPEN).
            verify(manager, times(4)).fetchToolsFromServer(any());
        }

        @Test
        @DisplayName("a connection pointed at the wrong origin is a config error, not a flaky server")
        void targetNotAllowedIsAConfigurationError() {
            var config = config("${connection:crm}");
            var failure = new ConnectionException(ConnectionException.Reason.TARGET_NOT_ALLOWED, "Connection 'crm' may not be sent there.");

            McpToolProviderManager manager = fourTurns(failure, McpFailureKind.INVALID_CONFIGURATION, config);

            assertFalse(manager.isCircuitOpen(config), "no amount of waiting adds an origin to an allowlist");
            verify(manager, times(4)).fetchToolsFromServer(any());
        }

        @Test
        @DisplayName("a token endpoint that is merely down keeps the connectivity treatment, breaker included")
        void tokenEndpointUnavailableStillTripsTheBreaker() {
            // The one ConnectionException reason that IS transient. Without this
            // counterpart the two tests above would still pass if the breaker had been
            // disabled altogether.
            var config = config("${connection:crm}");
            McpToolProviderManager manager = failingWith(
                    new ConnectionException(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, "token endpoint 503"));

            for (int turn = 1; turn <= 3; turn++) {
                assertEquals(McpFailureKind.CONNECTION_FAILURE, manager.discoverTools(List.of(config)).failures().get(0).kind(),
                        "turn " + turn);
            }

            assertTrue(manager.isCircuitOpen(config), "a genuinely transient failure is exactly what the breaker is for");
            assertEquals(McpFailureKind.CIRCUIT_OPEN, manager.discoverTools(List.of(config)).failures().get(0).kind());
            verify(manager, times(3)).fetchToolsFromServer(any());
        }

        @Test
        @DisplayName("a 401 reported by the transport is an auth challenge and is kept away from the breaker")
        void transportAuthChallengeDoesNotOpenTheBreaker() {
            var config = config(null);
            var failure = new RuntimeException("Unexpected status code: 401");

            McpToolProviderManager manager = fourTurns(failure, McpFailureKind.AUTHENTICATION_REQUIRED, config);

            assertFalse(manager.isCircuitOpen(config),
                    "three attempts used to open the circuit and report the server unreachable, with nothing pointing at credentials");
            verify(manager, times(4)).fetchToolsFromServer(any());
        }

        @Test
        @DisplayName("a message that merely CONTAINS 401 is still a connection failure and still trips the breaker")
        void bareDigitsAreNotAnAuthChallenge() {
            // The substring test this replaced excused any message containing those
            // three digits from recordFailure — so the breaker never opened for a
            // server that was genuinely down and happened to mention a byte count.
            var config = config(null);
            McpToolProviderManager manager = failingWith(new RuntimeException("Connection reset after reading 401 bytes"));

            for (int turn = 1; turn <= 3; turn++) {
                assertEquals(McpFailureKind.CONNECTION_FAILURE, manager.discoverTools(List.of(config)).failures().get(0).kind(),
                        "turn " + turn);
            }

            assertTrue(manager.isCircuitOpen(config), "a down server that quotes a byte count must not be excused from the breaker");
        }
    }

    @Nested
    @DisplayName("isAuthenticationChallenge reads a status code, not a substring")
    class AuthChallengeRecognition {

        @Test
        @DisplayName("the status code the langchain4j transport actually reports is recognised")
        void recognisesReportedStatusCodes() {
            assertTrue(McpToolProviderManager.isAuthenticationChallenge(new RuntimeException("Unexpected status code: 401")));
            // 403 counts too — McpAuthChallengeParser is the single place that decides.
            assertTrue(McpToolProviderManager.isAuthenticationChallenge(new RuntimeException("Unexpected status code: 403")));
            assertFalse(McpToolProviderManager.isAuthenticationChallenge(new RuntimeException("Unexpected status code: 503")),
                    "a 503 is an outage; excusing it from the breaker is how a down server never trips it");
        }

        @Test
        @DisplayName("three digits loose in a message are not a challenge")
        void ignoresBareDigits() {
            assertFalse(McpToolProviderManager.isAuthenticationChallenge(new RuntimeException("Read 401 bytes before EOF")));
            assertFalse(McpToolProviderManager.isAuthenticationChallenge(new RuntimeException("connection refused")));
        }

        @Test
        @DisplayName("the challenge is found through the cause chain, where the transport actually puts it")
        void unwrapsCauses() {
            var wrapped = new RuntimeException("tool discovery failed",
                    new IllegalStateException("Unexpected status code: 401"));
            assertTrue(McpToolProviderManager.isAuthenticationChallenge(wrapped));
            // A WWW-Authenticate header is the same signal by another route.
            assertTrue(McpToolProviderManager.isAuthenticationChallenge(
                    new RuntimeException("server replied WWW-Authenticate: Bearer realm=\"mcp\"")));
        }

        @Test
        @DisplayName("a self-referencing cause chain terminates instead of hanging the discovery loop")
        void survivesACycle() {
            var first = new RuntimeException("outer");
            var second = new RuntimeException("inner", first);
            first.initCause(second);

            assertFalse(McpToolProviderManager.isAuthenticationChallenge(first));
        }
    }
}
