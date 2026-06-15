/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Tests targeting uncovered branches in {@link A2AToolProviderManager} focusing
 * on:
 * <ul>
 * <li>{@code sanitizeToolName} — edge cases for regex replacements</li>
 * <li>{@code warnIfRawKey} — vault, eddivault, vars detection</li>
 * <li>{@code discoverTools} — null/empty config lists, null/empty URLs</li>
 * <li>{@code isCircuitOpen} — boundary value testing</li>
 * <li>{@code recordFailure} — increment logic</li>
 * <li>{@code CachedAgentInfo} — TTL behavior</li>
 * <li>{@code A2AToolsResult} — immutability</li>
 * <li>{@code getActiveConnectionCount} — count accuracy</li>
 * <li>{@code shutdown} — clears cache</li>
 * </ul>
 */
@DisplayName("A2AToolProviderManager — Uncovered Branch Coverage")
class A2AToolProviderManagerUncoveredBranchTest {

    @Mock
    private GlobalVariableResolver globalVariableResolver;
    @Mock
    private SecretResolver secretResolver;

    private A2AToolProviderManager manager;

    @BeforeEach
    void setUp() {
        openMocks(this);
        manager = new A2AToolProviderManager(globalVariableResolver, secretResolver);
    }

    // =========================================================
    // sanitizeToolName — comprehensive edge cases
    // =========================================================

    @Nested
    @DisplayName("sanitizeToolName — comprehensive edge cases")
    class SanitizeToolName {

        @Test
        @DisplayName("simple name lowercased")
        void simpleName() throws Exception {
            assertEquals("agent", invokeSanitize("Agent"));
        }

        @Test
        @DisplayName("hyphens converted to underscores, then deduped and trimmed")
        void hyphens() throws Exception {
            // "my-agent-tool" → "my_agent_tool"
            assertEquals("my_agent_tool", invokeSanitize("my-agent-tool"));
        }

        @Test
        @DisplayName("periods converted to underscores")
        void periods() throws Exception {
            assertEquals("agent_tool_v2", invokeSanitize("agent.tool.v2"));
        }

        @Test
        @DisplayName("spaces converted to underscores")
        void spaces() throws Exception {
            assertEquals("my_agent", invokeSanitize("My Agent"));
        }

        @Test
        @DisplayName("special chars removed, underscore dedup")
        void specialChars() throws Exception {
            assertEquals("test_name", invokeSanitize("test@#$name"));
        }

        @Test
        @DisplayName("leading underscore stripped")
        void leadingUnderscore() throws Exception {
            // "_leading" → leading underscore should be stripped by ^_ regex
            assertEquals("leading", invokeSanitize("_leading"));
        }

        @Test
        @DisplayName("trailing underscore stripped")
        void trailingUnderscore() throws Exception {
            assertEquals("trailing", invokeSanitize("trailing_"));
        }

        @Test
        @DisplayName("multiple consecutive underscores reduced to one")
        void multipleUnderscores() throws Exception {
            assertEquals("a_b", invokeSanitize("a___b"));
        }

        @Test
        @DisplayName("numbers preserved")
        void numbers() throws Exception {
            assertEquals("agent123", invokeSanitize("agent123"));
        }

        @Test
        @DisplayName("all-uppercase lowercased")
        void allUppercase() throws Exception {
            assertEquals("myagent", invokeSanitize("MYAGENT"));
        }

        @Test
        @DisplayName("empty string returns empty")
        void emptyString() throws Exception {
            assertEquals("", invokeSanitize(""));
        }

        @Test
        @DisplayName("all special chars returns empty")
        void allSpecialChars() throws Exception {
            assertEquals("", invokeSanitize("@#$%^&*"));
        }

        @Test
        @DisplayName("Unicode chars stripped")
        void unicodeChars() throws Exception {
            String result = invokeSanitize("Agentéàü");
            // Only ASCII [a-z0-9_] survive
            assertEquals("agent", result);
        }

        private String invokeSanitize(String input) throws Exception {
            Method method = A2AToolProviderManager.class.getDeclaredMethod("sanitizeToolName", String.class);
            method.setAccessible(true);
            return (String) method.invoke(manager, input);
        }
    }

    // =========================================================
    // warnIfRawKey — all prefix checks
    // =========================================================

    @Nested
    @DisplayName("warnIfRawKey — prefix detection")
    class WarnIfRawKey {

        @Test
        @DisplayName("vault reference does not warn (no exception)")
        void vaultRef() throws Exception {
            invokeWarnIfRawKey("${vault:my-key}", "http://test.com");
            // No exception = test passes (we can't easily verify log output)
        }

        @Test
        @DisplayName("eddivault reference does not warn")
        void eddivaultRef() throws Exception {
            invokeWarnIfRawKey("${eddivault:legacy-key}", "http://test.com");
        }

        @Test
        @DisplayName("vars reference does not warn")
        void varsRef() throws Exception {
            invokeWarnIfRawKey("${vars:env-key}", "http://test.com");
        }

        @Test
        @DisplayName("raw key triggers warning (no exception)")
        void rawKey() throws Exception {
            invokeWarnIfRawKey("sk-1234567890abcdef", "http://test.com");
        }

        @Test
        @DisplayName("Bearer token is a raw key — triggers warning")
        void bearerToken() throws Exception {
            invokeWarnIfRawKey("Bearer eyJhbGciOiJIUzI1NiJ9", "http://test.com");
        }

        @Test
        @DisplayName("key starting with 'vault' but not '${vault:' is raw")
        void vaultWithoutDollarBrace() throws Exception {
            invokeWarnIfRawKey("vault:my-key", "http://test.com");
            // No ${...} wrapper → treated as raw key
        }

        private void invokeWarnIfRawKey(String apiKey, String url) throws Exception {
            Method method = A2AToolProviderManager.class.getDeclaredMethod("warnIfRawKey", String.class, String.class);
            method.setAccessible(true);
            method.invoke(manager, apiKey, url);
        }
    }

    // =========================================================
    // discoverTools — edge cases
    // =========================================================

    @Nested
    @DisplayName("discoverTools — edge cases")
    class DiscoverToolsEdge {

        @Test
        @DisplayName("null config list returns empty result")
        void nullConfigList() {
            var result = manager.discoverTools(null);
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
            assertTrue(result.executors().isEmpty());
        }

        @Test
        @DisplayName("empty config list returns empty result")
        void emptyConfigList() {
            var result = manager.discoverTools(List.of());
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
            assertTrue(result.executors().isEmpty());
        }

        @Test
        @DisplayName("config with null URL is skipped")
        void nullUrlConfig() {
            var config = new A2AAgentConfig();
            config.setUrl(null);
            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("config with empty URL is skipped")
        void emptyUrlConfig() {
            var config = new A2AAgentConfig();
            config.setUrl("");
            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("config with open circuit is skipped")
        void openCircuitConfig() throws Exception {
            var breakers = getCircuitBreakers();
            breakers.put("http://broken.com",
                    new A2AToolProviderManager.CircuitState(3, System.currentTimeMillis()));

            var config = new A2AAgentConfig();
            config.setUrl("http://broken.com");
            var result = manager.discoverTools(List.of(config));
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("multiple configs with mixed open/closed circuits")
        void mixedCircuits() throws Exception {
            var breakers = getCircuitBreakers();
            breakers.put("http://broken.com",
                    new A2AToolProviderManager.CircuitState(5, System.currentTimeMillis()));

            var config1 = new A2AAgentConfig();
            config1.setUrl("http://broken.com");
            var config2 = new A2AAgentConfig();
            config2.setUrl("http://also-unreachable.com");

            // config2 will fail with connection refused (not a circuit break)
            var result = manager.discoverTools(List.of(config1, config2));
            // config1 is circuit-broken, config2 fails at HTTP level
            assertTrue(result.toolSpecs().isEmpty());
        }
    }

    // =========================================================
    // isCircuitOpen — boundary conditions
    // =========================================================

    @Nested
    @DisplayName("isCircuitOpen — boundary conditions")
    class IsCircuitOpen {

        @Test
        @DisplayName("no state → circuit closed")
        void noState() throws Exception {
            assertFalse(invokeIsCircuitOpen("http://unknown.com"));
        }

        @Test
        @DisplayName("failures=0 → circuit closed")
        void zeroFailures() throws Exception {
            getCircuitBreakers().put("http://t.com",
                    new A2AToolProviderManager.CircuitState(0, System.currentTimeMillis()));
            assertFalse(invokeIsCircuitOpen("http://t.com"));
        }

        @Test
        @DisplayName("failures=2 → circuit closed (below threshold)")
        void belowThreshold() throws Exception {
            getCircuitBreakers().put("http://t.com",
                    new A2AToolProviderManager.CircuitState(2, System.currentTimeMillis()));
            assertFalse(invokeIsCircuitOpen("http://t.com"));
        }

        @Test
        @DisplayName("failures=3 recent → circuit open")
        void atThresholdRecent() throws Exception {
            getCircuitBreakers().put("http://t.com",
                    new A2AToolProviderManager.CircuitState(3, System.currentTimeMillis()));
            assertTrue(invokeIsCircuitOpen("http://t.com"));
        }

        @Test
        @DisplayName("failures=3 old → circuit auto-resets")
        void atThresholdOld() throws Exception {
            var breakers = getCircuitBreakers();
            breakers.put("http://t.com",
                    new A2AToolProviderManager.CircuitState(3, System.currentTimeMillis() - 120_000));
            assertFalse(invokeIsCircuitOpen("http://t.com"));
            // Should have been removed
            assertFalse(breakers.containsKey("http://t.com"));
        }

        @Test
        @DisplayName("failures=5 recent → circuit open")
        void aboveThresholdRecent() throws Exception {
            getCircuitBreakers().put("http://t.com",
                    new A2AToolProviderManager.CircuitState(5, System.currentTimeMillis()));
            assertTrue(invokeIsCircuitOpen("http://t.com"));
        }

        @Test
        @DisplayName("failures=10 old → auto-reset")
        void highFailuresOld() throws Exception {
            var breakers = getCircuitBreakers();
            breakers.put("http://t.com",
                    new A2AToolProviderManager.CircuitState(10, System.currentTimeMillis() - 90_000));
            assertFalse(invokeIsCircuitOpen("http://t.com"));
            assertFalse(breakers.containsKey("http://t.com"));
        }

        private boolean invokeIsCircuitOpen(String url) throws Exception {
            Method method = A2AToolProviderManager.class.getDeclaredMethod("isCircuitOpen", String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(manager, url);
        }
    }

    // =========================================================
    // recordFailure — increment logic
    // =========================================================

    @Nested
    @DisplayName("recordFailure — increment logic")
    class RecordFailure {

        @Test
        @DisplayName("first failure creates state with failures=1")
        void firstFailure() throws Exception {
            invokeRecordFailure("http://test.com");
            var state = getCircuitBreakers().get("http://test.com");
            assertNotNull(state);
            assertEquals(1, state.failures());
        }

        @Test
        @DisplayName("consecutive failures increment counter")
        void consecutiveFailures() throws Exception {
            invokeRecordFailure("http://test.com");
            invokeRecordFailure("http://test.com");
            invokeRecordFailure("http://test.com");
            var state = getCircuitBreakers().get("http://test.com");
            assertEquals(3, state.failures());
        }

        @Test
        @DisplayName("different URLs have independent counters")
        void independentCounters() throws Exception {
            invokeRecordFailure("http://a.com");
            invokeRecordFailure("http://a.com");
            invokeRecordFailure("http://b.com");
            assertEquals(2, getCircuitBreakers().get("http://a.com").failures());
            assertEquals(1, getCircuitBreakers().get("http://b.com").failures());
        }

        private void invokeRecordFailure(String url) throws Exception {
            Method method = A2AToolProviderManager.class.getDeclaredMethod("recordFailure", String.class);
            method.setAccessible(true);
            method.invoke(manager, url);
        }
    }

    // =========================================================
    // CachedAgentInfo — record structure
    // =========================================================

    @Nested
    @DisplayName("CachedAgentInfo — record structure")
    class CachedAgentInfoTests {

        @Test
        @DisplayName("fresh cache entry has valid timestamp")
        void freshEntry() {
            long before = System.currentTimeMillis();
            var info = new A2AToolProviderManager.CachedAgentInfo(
                    Map.of("name", "test"), System.currentTimeMillis());
            assertTrue(info.timestamp() >= before);
        }

        @Test
        @DisplayName("agent card is accessible")
        void agentCard() {
            Map<String, Object> card = Map.of("name", "Agent", "description", "A test agent");
            var info = new A2AToolProviderManager.CachedAgentInfo(card, System.currentTimeMillis());
            assertEquals("Agent", info.agentCard().get("name"));
            assertEquals("A test agent", info.agentCard().get("description"));
        }

        @Test
        @DisplayName("empty agent card")
        void emptyCard() {
            var info = new A2AToolProviderManager.CachedAgentInfo(Map.of(), System.currentTimeMillis());
            assertTrue(info.agentCard().isEmpty());
        }
    }

    // =========================================================
    // A2AToolsResult — record structure
    // =========================================================

    @Nested
    @DisplayName("A2AToolsResult — record structure")
    class A2AToolsResultTests {

        @Test
        @DisplayName("empty result has empty collections")
        void emptyResult() {
            var result = new A2AToolProviderManager.A2AToolsResult(List.of(), Map.of());
            assertTrue(result.toolSpecs().isEmpty());
            assertTrue(result.executors().isEmpty());
        }

        @Test
        @DisplayName("result preserves tool specs and executors")
        void preservesData() {
            var spec = ToolSpecification.builder()
                    .name("test_tool")
                    .description("A test tool")
                    .build();
            ToolExecutor executor = (request, memoryId) -> "result";

            var result = new A2AToolProviderManager.A2AToolsResult(List.of(spec), Map.of("test_tool", executor));
            assertEquals(1, result.toolSpecs().size());
            assertEquals(1, result.executors().size());
            assertEquals("test_tool", result.toolSpecs().getFirst().name());
        }
    }

    // =========================================================
    // getActiveConnectionCount — cache counting
    // =========================================================

    @Nested
    @DisplayName("getActiveConnectionCount — cache counting")
    class ActiveConnectionCount {

        @Test
        @DisplayName("initially zero")
        void initiallyZero() {
            assertEquals(0, manager.getActiveConnectionCount());
        }

        @Test
        @DisplayName("reflects cache entries")
        void reflectsCache() throws Exception {
            var cacheField = A2AToolProviderManager.class.getDeclaredField("agentCache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var cache = (Map<String, A2AToolProviderManager.CachedAgentInfo>) cacheField.get(manager);
            cache.put("http://a.com", new A2AToolProviderManager.CachedAgentInfo(Map.of("name", "A"), System.currentTimeMillis()));
            cache.put("http://b.com", new A2AToolProviderManager.CachedAgentInfo(Map.of("name", "B"), System.currentTimeMillis()));
            assertEquals(2, manager.getActiveConnectionCount());
        }
    }

    // =========================================================
    // shutdown — clears cache
    // =========================================================

    @Nested
    @DisplayName("shutdown — clears cache")
    class Shutdown {

        @Test
        @DisplayName("shutdown clears agent cache")
        void shutdownClearsCache() throws Exception {
            var cacheField = A2AToolProviderManager.class.getDeclaredField("agentCache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var cache = (Map<String, A2AToolProviderManager.CachedAgentInfo>) cacheField.get(manager);
            cache.put("http://x.com", new A2AToolProviderManager.CachedAgentInfo(Map.of("name", "X"), System.currentTimeMillis()));
            assertEquals(1, manager.getActiveConnectionCount());

            manager.shutdown();
            assertEquals(0, manager.getActiveConnectionCount());
        }
    }

    // =========================================================
    // CircuitState — record structure
    // =========================================================

    @Nested
    @DisplayName("CircuitState — record structure")
    class CircuitStateTests {

        @Test
        @DisplayName("record holds failures and lastFailure")
        void recordHoldsValues() {
            long now = System.currentTimeMillis();
            var state = new A2AToolProviderManager.CircuitState(5, now);
            assertEquals(5, state.failures());
            assertEquals(now, state.lastFailure());
        }

        @Test
        @DisplayName("zero failures zero timestamp")
        void zeroValues() {
            var state = new A2AToolProviderManager.CircuitState(0, 0);
            assertEquals(0, state.failures());
            assertEquals(0, state.lastFailure());
        }
    }

    // =========================================================
    // Helpers
    // =========================================================

    @SuppressWarnings("unchecked")
    private Map<String, A2AToolProviderManager.CircuitState> getCircuitBreakers() throws Exception {
        Field field = A2AToolProviderManager.class.getDeclaredField("circuitBreakers");
        field.setAccessible(true);
        return (Map<String, A2AToolProviderManager.CircuitState>) field.get(manager);
    }
}
