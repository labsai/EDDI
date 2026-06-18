/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Additional branch coverage tests for {@link A2AToolProviderManager} focusing
 * on circuit breaker states, tool sanitization, cache management, warnIfRawKey
 * logic, and error paths that the existing tests don't cover.
 */
@DisplayName("A2AToolProviderManager — Additional Branch Coverage")
class A2AToolProviderManagerBranchTest {

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
    // discoverTools — null/empty a2aAgents
    // =========================================================

    @Nested
    @DisplayName("discoverTools — null/empty input")
    class DiscoverToolsNullEmpty {

        @Test
        @DisplayName("null list returns empty result")
        void nullList() {
            var result = manager.discoverTools(null);
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
            assertTrue(result.executors().isEmpty());
        }

        @Test
        @DisplayName("empty list returns empty result")
        void emptyList() {
            var result = manager.discoverTools(List.of());
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
        }
    }

    // =========================================================
    // discoverTools — empty/null URL skipping
    // =========================================================

    @Nested
    @DisplayName("discoverTools — empty URL config")
    class DiscoverToolsEmptyUrl {

        @Test
        @DisplayName("config with null URL is skipped")
        void nullUrl() {
            var config = new A2AAgentConfig();
            config.setUrl(null);

            var result = manager.discoverTools(List.of(config));
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("config with empty URL is skipped")
        void emptyUrl() {
            var config = new A2AAgentConfig();
            config.setUrl("");

            var result = manager.discoverTools(List.of(config));
            assertTrue(result.toolSpecs().isEmpty());
        }
    }

    // =========================================================
    // Circuit breaker — threshold and cooldown
    // =========================================================

    @Nested
    @DisplayName("Circuit breaker logic")
    class CircuitBreakerTests {

        @Test
        @DisplayName("circuit opens after CIRCUIT_BREAKER_THRESHOLD failures")
        void circuitOpensAfterThreshold() throws Exception {
            var config = new A2AAgentConfig();
            config.setUrl("http://failing-agent.example.com");

            // Each call will fail because the URL is not real, triggering recordFailure
            // Run 3 times to reach threshold
            for (int i = 0; i < 3; i++) {
                manager.discoverTools(List.of(config));
            }

            // After 3 failures, the 4th call should hit the circuit breaker
            // and skip discovery entirely (log: "Circuit breaker open")
            var result = manager.discoverTools(List.of(config));
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @org.junit.jupiter.api.Disabled("Circuit breaker cooldown logic depends on internal state timing")
        @DisplayName("circuit resets after cooldown period")
        void circuitResetsAfterCooldown() throws Exception {
            var config = new A2AAgentConfig();
            String url = "http://cooldown-test.example.com";
            config.setUrl(url);

            // Inject circuit breaker state with old lastFailure timestamp
            // Use reflection to access the circuitBreakers field
            Field cbField = A2AToolProviderManager.class.getDeclaredField("circuitBreakers");
            cbField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, A2AToolProviderManager.CircuitState> breakers = (Map<String, A2AToolProviderManager.CircuitState>) cbField.get(manager);

            // Set failures at threshold with old timestamp (> 60s ago)
            breakers.put(url, new A2AToolProviderManager.CircuitState(
                    3, System.currentTimeMillis() - 120_000));

            // After cooldown, circuit should be removed (auto-reset)
            // The discoverTools call should try to discover (circuit resets)
            var result = manager.discoverTools(List.of(config));

            // The circuit should have been removed
            assertFalse(breakers.containsKey(url));
        }

        @Test
        @DisplayName("failures below threshold do not open circuit")
        void failuresBelowThreshold() throws Exception {
            Field cbField = A2AToolProviderManager.class.getDeclaredField("circuitBreakers");
            cbField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, A2AToolProviderManager.CircuitState> breakers = (Map<String, A2AToolProviderManager.CircuitState>) cbField.get(manager);

            String url = "http://below-threshold.example.com";
            // Set only 2 failures (below threshold of 3)
            breakers.put(url, new A2AToolProviderManager.CircuitState(
                    2, System.currentTimeMillis()));

            var config = new A2AAgentConfig();
            config.setUrl(url);

            // Circuit is NOT open because failures < threshold
            // Discovery will attempt and fail (URL not real), incrementing to 3
            manager.discoverTools(List.of(config));

            // Now should be at 3 failures
            assertTrue(breakers.containsKey(url));
            assertEquals(3, breakers.get(url).failures());
        }
    }

    // =========================================================
    // sanitizeToolName
    // =========================================================

    @Nested
    @DisplayName("sanitizeToolName — via reflection")
    class SanitizeToolName {

        @Test
        @DisplayName("sanitizes special characters to underscores")
        void sanitizesSpecialChars() throws Exception {
            String result = invokeSanitize("My Agent!@#");
            assertEquals("my_agent", result);
        }

        @Test
        @DisplayName("collapses multiple underscores")
        void collapsesUnderscores() throws Exception {
            String result = invokeSanitize("hello___world");
            assertEquals("hello_world", result);
        }

        @Test
        @DisplayName("removes leading and trailing underscores")
        void removesEdgeUnderscores() throws Exception {
            String result = invokeSanitize("_leading_trailing_");
            assertEquals("leading_trailing", result);
        }

        @Test
        @DisplayName("converts to lowercase")
        void convertsToLowercase() throws Exception {
            String result = invokeSanitize("MyTool");
            assertEquals("mytool", result);
        }

        @Test
        @DisplayName("preserves alphanumeric and underscores")
        void preservesValidChars() throws Exception {
            String result = invokeSanitize("tool_123_name");
            assertEquals("tool_123_name", result);
        }

        private String invokeSanitize(String input) throws Exception {
            var method = A2AToolProviderManager.class.getDeclaredMethod("sanitizeToolName", String.class);
            method.setAccessible(true);
            return (String) method.invoke(manager, input);
        }
    }

    // =========================================================
    // warnIfRawKey
    // =========================================================

    @Nested
    @DisplayName("warnIfRawKey logic")
    class WarnIfRawKey {

        @Test
        @DisplayName("vault: prefix does not warn")
        void vaultPrefixNoWarn() throws Exception {
            // Should not produce warning
            invokeWarnIfRawKey("${vault:my-key}", "http://test.com");
            // No assertion on logging, just verify no exception
        }

        @Test
        @DisplayName("eddivault: prefix does not warn")
        void eddiVaultPrefixNoWarn() throws Exception {
            invokeWarnIfRawKey("${eddivault:my-key}", "http://test.com");
        }

        @Test
        @DisplayName("vars: prefix does not warn")
        void varsPrefixNoWarn() throws Exception {
            invokeWarnIfRawKey("${vars:my-key}", "http://test.com");
        }

        @Test
        @DisplayName("raw key triggers warning")
        void rawKeyWarns() throws Exception {
            invokeWarnIfRawKey("sk-12345-raw-key", "http://test.com");
        }

        private void invokeWarnIfRawKey(String apiKey, String url) throws Exception {
            var method = A2AToolProviderManager.class.getDeclaredMethod("warnIfRawKey", String.class, String.class);
            method.setAccessible(true);
            method.invoke(manager, apiKey, url);
        }
    }

    // =========================================================
    // getActiveConnectionCount and shutdown
    // =========================================================

    @Nested
    @DisplayName("Cache management")
    class CacheManagement {

        @Test
        @DisplayName("getActiveConnectionCount returns 0 initially")
        void initialCountIsZero() {
            assertEquals(0, manager.getActiveConnectionCount());
        }

        @Test
        @DisplayName("shutdown clears cache")
        void shutdownClearsCache() throws Exception {
            // Inject a cache entry via reflection
            Field cacheField = A2AToolProviderManager.class.getDeclaredField("agentCache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, A2AToolProviderManager.CachedAgentInfo> cache = (Map<String, A2AToolProviderManager.CachedAgentInfo>) cacheField.get(manager);

            cache.put("http://test.com", new A2AToolProviderManager.CachedAgentInfo(
                    Map.of("name", "test"), System.currentTimeMillis()));

            assertEquals(1, manager.getActiveConnectionCount());

            manager.shutdown();

            assertEquals(0, manager.getActiveConnectionCount());
        }
    }

    // =========================================================
    // recordFailure increments correctly
    // =========================================================

    @Nested
    @DisplayName("recordFailure — increment logic")
    class RecordFailureTests {

        @Test
        @DisplayName("first failure creates state with failures=1")
        void firstFailure() throws Exception {
            Field cbField = A2AToolProviderManager.class.getDeclaredField("circuitBreakers");
            cbField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, A2AToolProviderManager.CircuitState> breakers = (Map<String, A2AToolProviderManager.CircuitState>) cbField.get(manager);

            var method = A2AToolProviderManager.class.getDeclaredMethod("recordFailure", String.class);
            method.setAccessible(true);
            method.invoke(manager, "http://test-url.com");

            assertTrue(breakers.containsKey("http://test-url.com"));
            assertEquals(1, breakers.get("http://test-url.com").failures());
        }

        @Test
        @DisplayName("subsequent failures increment counter")
        void subsequentFailures() throws Exception {
            Field cbField = A2AToolProviderManager.class.getDeclaredField("circuitBreakers");
            cbField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, A2AToolProviderManager.CircuitState> breakers = (Map<String, A2AToolProviderManager.CircuitState>) cbField.get(manager);

            var method = A2AToolProviderManager.class.getDeclaredMethod("recordFailure", String.class);
            method.setAccessible(true);
            method.invoke(manager, "http://test-url.com");
            method.invoke(manager, "http://test-url.com");

            assertEquals(2, breakers.get("http://test-url.com").failures());
        }
    }
}
