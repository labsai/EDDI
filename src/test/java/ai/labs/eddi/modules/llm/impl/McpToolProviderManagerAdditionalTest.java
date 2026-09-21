/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.invocation.InvocationContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.*;
import org.mockito.Mock;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Tag;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Additional tests for {@link McpToolProviderManager} — covering branches
 * around server name fallback, API key resolution, timeout defaults,
 * closeClient with cached entry, shutdown with cached entries (including error
 * during close), and discoverTools with name fallback.
 */
@DisplayName("McpToolProviderManager — Additional Branch Coverage")
class McpToolProviderManagerAdditionalTest {

    @Mock
    private GlobalVariableResolver globalVariableResolver;
    @Mock
    private SecretResolver secretResolver;

    private McpToolProviderManager manager;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = openMocks(this);
        manager = new McpToolProviderManager(globalVariableResolver, secretResolver);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    /**
     * Helper to inject a mock client into the clientCache for testing
     * close/shutdown.
     */
    @SuppressWarnings("unchecked")
    private Map<String, McpClient> getClientCache() throws Exception {
        Field cacheField = McpToolProviderManager.class.getDeclaredField("clientCache");
        cacheField.setAccessible(true);
        return (Map<String, McpClient>) cacheField.get(manager);
    }

    // ==================== closeClient ====================

    @Nested
    @DisplayName("closeClient")
    class CloseClientTests {

        @Test
        @DisplayName("closing cached client — removes and closes it")
        void closeCachedClient() throws Exception {
            McpClient mockClient = mock(McpClient.class);
            getClientCache().put("http://test-server:8080|anonymous", mockClient);
            assertEquals(1, manager.getActiveConnectionCount());

            manager.closeClient("http://test-server:8080");

            assertEquals(0, manager.getActiveConnectionCount());
            verify(mockClient).close();
        }

        @Test
        @DisplayName("closing cached client — handles close exception gracefully")
        void closeClientWithException() throws Exception {
            McpClient mockClient = mock(McpClient.class);
            doThrow(new RuntimeException("close error")).when(mockClient).close();
            getClientCache().put("http://error-server:8080|anonymous", mockClient);

            assertDoesNotThrow(() -> manager.closeClient("http://error-server:8080"));
            assertEquals(0, manager.getActiveConnectionCount());
        }
    }

    // ==================== credential isolation ====================

    @Nested
    @DisplayName("client cache keys on the credential, not just the URL")
    class CredentialIsolationTests {

        private McpServerConfig serverWith(String apiKey) {
            var config = new McpServerConfig();
            config.setUrl("http://shared-server:8080");
            config.setApiKey(apiKey);
            return config;
        }

        private String keyFor(McpServerConfig config) throws Exception {
            var method = McpToolProviderManager.class.getDeclaredMethod("cacheKey", McpServerConfig.class);
            method.setAccessible(true);
            return (String) method.invoke(null, config);
        }

        @Test
        @DisplayName("two credentials against one URL do not share a client")
        void differentCredentialsGetDifferentKeys() throws Exception {
            // Keying on the URL alone meant the second agent silently borrowed the
            // first's authorization — a privilege boundary, not a caching detail.
            assertNotEquals(keyFor(serverWith("key-alpha")), keyFor(serverWith("key-beta")));
        }

        @Test
        @DisplayName("the same credential shares one client, so users do not multiply connections")
        void sameCredentialSharesAKey() throws Exception {
            assertEquals(keyFor(serverWith("${caller:token}")), keyFor(serverWith("${caller:token}")));
        }

        @Test
        @DisplayName("the configured credential never appears in the key")
        void keyDoesNotLeakTheCredential() throws Exception {
            String key = keyFor(serverWith("super-secret-literal-key"));
            assertFalse(key.contains("super-secret-literal-key"), "a heap dump or log line must not reveal it: " + key);
            assertTrue(key.startsWith("http://shared-server:8080|"));
        }

        @Test
        void aCredentiallessServerStillGetsAStableKey() throws Exception {
            assertEquals(keyFor(serverWith(null)), keyFor(serverWith(null)));
            assertTrue(keyFor(serverWith(null)).endsWith("|anonymous"));
        }

        @Test
        @DisplayName("the lookup actually uses that key, not the bare URL")
        void getOrCreateClientLooksUpByCredentialAwareKey() throws Exception {
            // Asserting cacheKey() alone proves nothing about the call site: with the
            // lookup reverted to config.getUrl() the key tests still pass. Seeding the
            // credential-aware key and requiring a cache HIT is what pins it — a
            // URL-keyed lookup misses and tries to build a real client instead.
            var config = serverWith("key-alpha");
            McpClient seeded = mock(McpClient.class);
            getClientCache().put(keyFor(config), seeded);

            var method = McpToolProviderManager.class.getDeclaredMethod("getOrCreateClient", McpServerConfig.class);
            method.setAccessible(true);
            assertSame(seeded, method.invoke(manager, config), "must resolve the client cached under the credential-aware key");
            assertEquals(1, manager.getActiveConnectionCount(), "no second client should have been constructed");
        }
    }

    // ==================== caller identity on the wire ====================

    @Nested
    @DisplayName("only a tool call carries the caller")
    class CallerIdentityHeaderTests {

        private final CallerIdentityContext context = new CallerIdentityContext(null, null);

        private McpServerConfig callerBoundServer() {
            var config = new McpServerConfig();
            config.setUrl("https://eddi.example:443/mcp");
            config.setApiKey("Bearer ${caller:token}");
            return config;
        }

        /** A context shaped like a tools/call, i.e. carrying an invocation context. */
        private McpCallContext toolCall() {
            return new McpCallContext(InvocationContext.builder().chatMemoryId(null).build(), null);
        }

        /** A context shaped like tools/list, i.e. no invocation context. */
        private McpCallContext discovery() {
            return new McpCallContext(null, null);
        }

        private Map<String, String> headersFor(McpServerConfig config, McpCallContext callContext) throws Exception {
            var manager = new McpToolProviderManager(globalVariableResolver, secretResolver,
                    new CallerIdentityResolver(context, true), context, false, 1024, 300000L);
            // The connectionBound flag is false throughout this class: these tests are
            // about ${caller:…}, and a connection reference takes a different branch
            // with its own tests.
            var method = McpToolProviderManager.class.getDeclaredMethod("authorizationHeader", String.class, McpServerConfig.class,
                    boolean.class, boolean.class, McpCallContext.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            var headers = (Map<String, String>) method.invoke(manager, config.getApiKey(), config, true, false, callContext);
            return headers;
        }

        @AfterEach
        void unbind() {
            context.clear();
        }

        @Test
        @DisplayName("a tool call sends the caller's token")
        void toolCallCarriesTheCaller() throws Exception {
            context.bind(new CallerIdentity("alice-token", "alice", "https://eddi.example:443"));
            assertEquals("Bearer alice-token", headersFor(callerBoundServer(), toolCall()).get("Authorization"));
        }

        @Test
        @DisplayName("discovery does not, even on a thread that is bound")
        void discoveryNeverCarriesTheCaller() throws Exception {
            // The client is cached: a session established with alice's token would be
            // reused by everyone after her, and a tool list reflecting her permissions
            // would be offered to the next user.
            context.bind(new CallerIdentity("alice-token", "alice", "https://eddi.example:443"));

            assertTrue(headersFor(callerBoundServer(), discovery()).isEmpty(), "tools/list must not be authenticated as alice");
            assertTrue(headersFor(callerBoundServer(), null).isEmpty(), "a transport-internal request has no caller either");
        }

        @Test
        @DisplayName("a bare token gets the Bearer scheme, like the static path")
        void callerTokenIsGivenTheBearerScheme() throws Exception {
            // apiKey is documented as a key/token and the static path prefixes
            // "Bearer ". A caller-bound key must match, or apiKey: "${caller:token}"
            // would send a raw token with no scheme and the server would reject it.
            context.bind(new CallerIdentity("alice-token", "alice", "https://eddi.example:443"));
            var config = new McpServerConfig();
            config.setUrl("https://eddi.example:443/mcp");
            config.setApiKey("${caller:token}");

            assertEquals("Bearer alice-token", headersFor(config, toolCall()).get("Authorization"));
        }

        @Test
        @DisplayName("an author who spells the scheme out is not double-prefixed")
        void anExplicitSchemeIsLeftAlone() throws Exception {
            context.bind(new CallerIdentity("alice-token", "alice", "https://eddi.example:443"));
            assertEquals("Bearer alice-token", headersFor(callerBoundServer(), toolCall()).get("Authorization"));
        }

        @Test
        @DisplayName("an unbound tool call is refused rather than sent as a placeholder")
        void unboundToolCallSendsNothing() throws Exception {
            assertTrue(headersFor(callerBoundServer(), toolCall()).isEmpty(), "must not ship the literal ${caller:token}");
        }

        @Test
        @DisplayName("a static key is unaffected and reaches every request")
        void staticKeyStillWorks() throws Exception {
            var config = new McpServerConfig();
            config.setUrl("https://eddi.example:443/mcp");
            config.setApiKey("static-key");

            var manager = new McpToolProviderManager(globalVariableResolver, secretResolver,
                    new CallerIdentityResolver(context, true), context, false, 1024, 300000L);
            var method = McpToolProviderManager.class.getDeclaredMethod("authorizationHeader", String.class, McpServerConfig.class,
                    boolean.class, boolean.class, McpCallContext.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            var onDiscovery = (Map<String, String>) method.invoke(manager, "static-key", config, false, false, discovery());
            assertEquals("Bearer static-key", onDiscovery.get("Authorization"), "discovery keeps the service credential");
        }
    }

    @Nested
    @DisplayName("the circuit breaker isolates by credential")
    class CircuitIsolationTests {

        private McpServerConfig serverWith(String apiKey) {
            var config = new McpServerConfig();
            config.setUrl("http://shared-server:8080");
            config.setApiKey(apiKey);
            return config;
        }

        @Test
        @DisplayName("one credential failing does not suppress discovery for another")
        void oneCredentialsFailuresDoNotOpenTheCircuitForAnother() throws Exception {
            // Keyed by bare URL, an agent whose key was revoked would take the other
            // agent's working config down with it.
            var failing = serverWith("revoked-key");
            var working = serverWith("valid-key");

            var record = McpToolProviderManager.class.getDeclaredMethod("recordFailure", String.class);
            record.setAccessible(true);
            var key = McpToolProviderManager.class.getDeclaredMethod("cacheKey", McpServerConfig.class);
            key.setAccessible(true);
            for (int i = 0; i < 3; i++) {
                record.invoke(manager, key.invoke(null, failing));
            }

            assertTrue(manager.isCircuitOpen(failing), "the failing credential trips its own circuit");
            assertFalse(manager.isCircuitOpen(working), "the other credential must still be discoverable");
        }
    }

    @Nested
    @DisplayName("discovery metrics")
    class MetricsTests {

        @Test
        @DisplayName("outcomes are counted without leaking a URL or a credential")
        void discoveryOutcomesAreCountedSafely() throws Exception {
            var registry = new SimpleMeterRegistry();
            // A spy with the network seam stubbed: pointing the real client at an
            // unreachable host would make this test depend on DNS and wait out the
            // 30-second default timeout.
            var spied = spy(new McpToolProviderManager(globalVariableResolver, secretResolver));
            doThrow(new RuntimeException("connection refused")).when(spied).fetchToolsFromServer(any());
            spied.meterRegistry = registry;

            var config = new McpServerConfig();
            config.setUrl("http://unreachable-metrics-test:9999/mcp");
            config.setApiKey("super-secret-literal-key");
            spied.discoverTools(List.of(config));

            var tagValues = registry.getMeters().stream().flatMap(m -> m.getId().getTags().stream())
                    .map(Tag::getValue).toList();
            assertFalse(tagValues.contains("super-secret-literal-key"), "a credential must never become a metric tag");
            assertFalse(tagValues.stream().anyMatch(v -> v.contains("unreachable-metrics-test")),
                    "a server URL is unbounded cardinality and must not be a tag: " + tagValues);
            assertFalse(registry.getMeters().isEmpty(), "the outcome should have been counted");
        }
    }

    // ==================== shutdown ====================

    @Nested
    @DisplayName("shutdown")
    class ShutdownTests {

        @Test
        @DisplayName("shutdown with cached clients — closes all and clears")
        void shutdownWithClients() throws Exception {
            McpClient client1 = mock(McpClient.class);
            McpClient client2 = mock(McpClient.class);
            getClientCache().put("http://server1:8080", client1);
            getClientCache().put("http://server2:8080", client2);
            assertEquals(2, manager.getActiveConnectionCount());

            manager.shutdown();

            assertEquals(0, manager.getActiveConnectionCount());
            verify(client1).close();
            verify(client2).close();
        }

        @Test
        @DisplayName("shutdown — error closing one client doesn't prevent others")
        void shutdownWithPartialError() throws Exception {
            McpClient errorClient = mock(McpClient.class);
            doThrow(new RuntimeException("close error")).when(errorClient).close();
            McpClient goodClient = mock(McpClient.class);
            getClientCache().put("http://error-server:8080", errorClient);
            getClientCache().put("http://good-server:8080", goodClient);

            assertDoesNotThrow(() -> manager.shutdown());
            assertEquals(0, manager.getActiveConnectionCount());
        }
    }

    // ==================== discoverTools — name fallback branches
    // ====================

    @Nested
    @DisplayName("discoverTools — server name fallback")
    class DiscoverToolsNameFallbackTests {

        @Test
        @DisplayName("server with null name — falls back to URL in logging")
        void nullNameFallsBackToUrl() {
            var config = new McpServerConfig();
            config.setUrl("http://unreachable-xyz-test:9999/mcp");
            config.setName(null); // null name — triggers fallback branch
            config.setTransport("http");
            config.setTimeoutMs(500L);

            // This will fail to connect but exercises the name==null branch
            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("server with explicit name — uses name in logging")
        void explicitNameUsed() {
            var config = new McpServerConfig();
            config.setUrl("http://unreachable-xyz-test:9999/mcp");
            config.setName("my-server");
            config.setTransport("http");
            config.setTimeoutMs(500L);

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
        }

        @Test
        @DisplayName("server with null timeoutMs — uses default 30000")
        void nullTimeoutUsesDefault() {
            var config = new McpServerConfig();
            config.setUrl("http://unreachable-xyz-test:9999/mcp");
            config.setName("timeout-test");
            config.setTransport("http");
            config.setTimeoutMs(null); // null timeout — triggers default branch

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
        }
    }

    // ==================== discoverTools — API key branches ====================

    @Nested
    @DisplayName("discoverTools — API key resolution")
    class DiscoverToolsApiKeyTests {

        @Test
        @DisplayName("non-empty API key — resolves through global var and secret resolver")
        void apiKeyResolved() {
            var config = new McpServerConfig();
            config.setUrl("http://unreachable-xyz-test:9999/mcp");
            config.setName("api-key-test");
            config.setTransport("http");
            config.setTimeoutMs(500L);
            config.setApiKey("{{vault.my-key}}");

            doReturn("vault-resolved").when(globalVariableResolver).resolveValue("{{vault.my-key}}");
            doReturn("final-key").when(secretResolver).resolveValue("vault-resolved");

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);

            verify(globalVariableResolver).resolveValue("{{vault.my-key}}");
            verify(secretResolver).resolveValue("vault-resolved");
        }

        @Test
        @DisplayName("null API key — skips resolution")
        void nullApiKeySkipsResolution() {
            var config = new McpServerConfig();
            config.setUrl("http://unreachable-xyz-test:9999/mcp");
            config.setName("no-key-test");
            config.setTransport("http");
            config.setTimeoutMs(500L);
            config.setApiKey(null);

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);

            verifyNoInteractions(globalVariableResolver);
            verifyNoInteractions(secretResolver);
        }

        @Test
        @DisplayName("empty API key — skips resolution")
        void emptyApiKeySkipsResolution() {
            var config = new McpServerConfig();
            config.setUrl("http://unreachable-xyz-test:9999/mcp");
            config.setName("empty-key-test");
            config.setTransport("http");
            config.setTimeoutMs(500L);
            config.setApiKey("");

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);

            verifyNoInteractions(globalVariableResolver);
            verifyNoInteractions(secretResolver);
        }
    }

    // ==================== discoverTools — multiple servers ====================

    @Nested
    @DisplayName("discoverTools — multiple servers")
    class DiscoverToolsMultipleServersTests {

        @Test
        @DisplayName("mix of valid URL and empty URL — skips empty, processes valid")
        void mixedServers() {
            var emptyConfig = new McpServerConfig();
            emptyConfig.setUrl("");
            emptyConfig.setName("empty");

            var validConfig = new McpServerConfig();
            validConfig.setUrl("http://unreachable-xyz-test:9999/mcp");
            validConfig.setName("valid");
            validConfig.setTransport("http");
            validConfig.setTimeoutMs(500L);

            var result = manager.discoverTools(List.of(emptyConfig, validConfig));
            assertNotNull(result);
        }

        @Test
        @DisplayName("all servers with empty URLs — returns empty result")
        void allEmptyUrls() {
            var config1 = new McpServerConfig();
            config1.setUrl("");
            var config2 = new McpServerConfig();
            config2.setUrl(null);

            var result = manager.discoverTools(List.of(config1, config2));
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
            assertTrue(result.executors().isEmpty());
        }
    }

    // ==================== F12 — discovered-tools TTL cache ====================

    /**
     * Finding F12: only the CONNECTION was cached, so every conversation turn
     * issued a live {@code tools/list} RPC to every configured MCP server. The TTL
     * cache added for it had no test at all — this pins both halves of the
     * freshness decision.
     * <p>
     * SSRF protection is on and the URL is loopback, so the moment the cache is NOT
     * used the request is rejected before any connection is attempted. That makes
     * "served from cache" and "went to the server" distinguishable with no network
     * and no socket.
     */
    @Nested
    @DisplayName("F12 — discovered-tools cache TTL")
    class ToolCacheTtl {

        private static final String URL = "http://127.0.0.1:9/mcp";

        private McpToolProviderManager sealedManager() {
            return new McpToolProviderManager(globalVariableResolver, secretResolver, true,
                    McpToolProviderManager.DEFAULT_MAX_DESCRIPTION_CHARS,
                    McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS);
        }

        @SuppressWarnings("unchecked")
        /**
         * Seeds the cache under the key production actually looks up, derived by
         * calling {@code cacheKey} itself rather than reconstructing it here.
         * <p>
         * This used to seed the bare URL. The tool cache later became credential scoped
         * ({@code url|<digest of apiKey>}, or {@code url|anonymous}), so the seeded
         * entry no longer matched the lookup — which broke
         * {@code freshEntryIsServedFromCache} loudly and, worse, made
         * {@code staleEntryIsNotServed} pass <em>vacuously</em>: nothing was served
         * because nothing matched, not because the entry was stale. Deriving the key
         * from the production method means the next change to the key shape carries
         * these tests along instead of silently hollowing them out.
         */
        private void seedToolCache(McpToolProviderManager target, long timestamp) throws Exception {
            Field field = McpToolProviderManager.class.getDeclaredField("toolCache");
            field.setAccessible(true);
            var cache = (Map<String, McpToolProviderManager.CachedTools>) field.get(target);
            var spec = ToolSpecification.builder().name("cached_tool").description("from the cache").build();
            ToolExecutor executor = (request, memoryId) -> "cached";
            cache.put(productionCacheKey(config()),
                    new McpToolProviderManager.CachedTools(List.of(spec), Map.of("cached_tool", executor), timestamp));
        }

        private String productionCacheKey(McpServerConfig config) throws Exception {
            var method = McpToolProviderManager.class.getDeclaredMethod("cacheKey", McpServerConfig.class);
            method.setAccessible(true);
            return (String) method.invoke(null, config);
        }

        private McpServerConfig config() {
            var config = new McpServerConfig();
            config.setUrl(URL);
            config.setName("cached-server");
            return config;
        }

        @Test
        @DisplayName("a fresh entry is served from the cache without contacting the server")
        void freshEntryIsServedFromCache() throws Exception {
            var target = sealedManager();
            seedToolCache(target, System.currentTimeMillis());

            var result = target.discoverTools(List.of(config()));

            assertEquals(1, result.toolSpecs().size(), "the cached tool list must be returned");
            assertEquals("cached_tool", result.toolSpecs().get(0).name());
            assertTrue(result.executors().containsKey("cached_tool"));
            assertEquals(1, target.getCachedToolServerCount());
            assertFalse(target.isCircuitOpen(URL), "no connection was attempted, so no failure was recorded");
        }

        @Test
        @DisplayName("an entry older than the TTL is NOT served — discovery goes back to the server")
        void staleEntryIsNotServed() throws Exception {
            var target = sealedManager();
            seedToolCache(target, System.currentTimeMillis() - (2 * McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS));

            var result = target.discoverTools(List.of(config()));

            assertTrue(result.toolSpecs().isEmpty(),
                    "a stale entry must not be served; discovery re-contacted the (rejected) server instead");
        }
    }

    // ==================== McpToolsResult record ====================

    @Nested
    @DisplayName("McpToolsResult record")
    class McpToolsResultTests {

        @Test
        @DisplayName("record accessors work correctly")
        void recordAccessors() {
            var result = new McpToolProviderManager.McpToolsResult(List.of(), Map.of());
            assertNotNull(result.toolSpecs());
            assertNotNull(result.executors());
            assertTrue(result.toolSpecs().isEmpty());
            assertTrue(result.executors().isEmpty());
        }
    }
}
