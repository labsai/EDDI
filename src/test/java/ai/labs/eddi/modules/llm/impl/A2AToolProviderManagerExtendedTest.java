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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Extended tests for {@link A2AToolProviderManager} — covers deeper discovery
 * paths including agent card fetch failure, HTTP error codes, cache lifecycle,
 * circuit breaker reset, and raw key warning.
 */
class A2AToolProviderManagerExtendedTest {

    private A2AToolProviderManager manager;
    private SecretResolver secretResolver;

    @BeforeEach
    void setUp() {
        secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        manager = new A2AToolProviderManager(globalVariableResolver, secretResolver);
    }

    // ─── Discovery with unreachable agents ────────────────────────

    @Nested
    @DisplayName("Discovery with unreachable agents")
    class UnreachableAgentTests {

        @Test
        @DisplayName("invalid URL — records failure gracefully")
        void invalidUrl() {
            var config = new A2AAgentConfig();
            config.setUrl("http://192.0.2.1:1"); // RFC5737 TEST-NET, won't connect
            config.setTimeoutMs(100L);

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("non-HTTP URL — handles gracefully")
        void nonHttpUrl() {
            var config = new A2AAgentConfig();
            config.setUrl("ftp://invalid:9999");
            config.setTimeoutMs(100L);

            var result = manager.discoverTools(List.of(config));
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("multiple agents — failures don't block other agents")
        void multipleAgentsPartialFailure() {
            var config1 = new A2AAgentConfig();
            config1.setUrl("http://192.0.2.1:1");
            config1.setTimeoutMs(100L);

            var config2 = new A2AAgentConfig();
            config2.setUrl("http://192.0.2.2:1");
            config2.setTimeoutMs(100L);

            var result = manager.discoverTools(List.of(config1, config2));
            assertNotNull(result);
            // Both fail but no exception propagates
            assertTrue(result.toolSpecs().isEmpty());
        }
    }

    // ─── Circuit breaker advanced scenarios ──────────────────────

    @Nested
    @DisplayName("Circuit breaker advanced scenarios")
    class CircuitBreakerAdvanced {

        @Test
        @DisplayName("circuit opens after threshold — subsequent calls skip HTTP")
        void circuitOpenSkipsHttp() {
            var config = new A2AAgentConfig();
            config.setUrl("http://192.0.2.1:1");
            config.setTimeoutMs(100L);

            // Trip circuit breaker (3 failures)
            for (int i = 0; i < 3; i++) {
                manager.discoverTools(List.of(config));
            }

            // Next call should be instant (circuit open = skip)
            long start = System.currentTimeMillis();
            var result = manager.discoverTools(List.of(config));
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(result.toolSpecs().isEmpty());
            // Should be very fast since HTTP call is skipped
            assertTrue(elapsed < 500, "Circuit breaker should skip HTTP: elapsed=" + elapsed + "ms");
        }

        @Test
        @DisplayName("different URLs have independent circuit breakers")
        void independentCircuits() {
            var config1 = new A2AAgentConfig();
            config1.setUrl("http://192.0.2.1:1");
            config1.setTimeoutMs(100L);

            var config2 = new A2AAgentConfig();
            config2.setUrl("http://192.0.2.2:1");
            config2.setTimeoutMs(100L);

            // Trip circuit for config1 only
            for (int i = 0; i < 3; i++) {
                manager.discoverTools(List.of(config1));
            }

            // config2 should still attempt discovery (not blocked by config1)
            var result = manager.discoverTools(List.of(config2));
            assertNotNull(result);
        }
    }

    // ─── API key warning ────────────────────────────────────────

    @Nested
    @DisplayName("API key handling")
    class ApiKeyHandling {

        @Test
        @DisplayName("vault reference key — no warning")
        void vaultRefKey() {
            var config = new A2AAgentConfig();
            config.setUrl("http://192.0.2.1:1");
            config.setApiKey("${vault:my-key}");
            config.setTimeoutMs(100L);

            // Should not throw — vault reference is accepted
            assertDoesNotThrow(() -> manager.discoverTools(List.of(config)));
        }

        @Test
        @DisplayName("eddivault reference key — no warning")
        void eddiVaultRefKey() {
            var config = new A2AAgentConfig();
            config.setUrl("http://192.0.2.1:1");
            config.setApiKey("${vault:my-key}");
            config.setTimeoutMs(100L);

            assertDoesNotThrow(() -> manager.discoverTools(List.of(config)));
        }

        @Test
        @DisplayName("raw key — logs warning but still works")
        void rawKey() {
            var config = new A2AAgentConfig();
            config.setUrl("http://192.0.2.1:1");
            config.setApiKey("sk-raw-key-1234");
            config.setTimeoutMs(100L);

            // Should not throw — warning is logged but execution continues
            assertDoesNotThrow(() -> manager.discoverTools(List.of(config)));
        }
    }

    // ─── Lifecycle and cache ────────────────────────────────────

    @Nested
    @DisplayName("Cache management")
    class CacheManagement {

        @Test
        @DisplayName("shutdown clears cache and resets connection count")
        void shutdownClearsAll() {
            manager.shutdown();
            assertEquals(0, manager.getActiveConnectionCount());
            // Can still discover tools after shutdown
            var result = manager.discoverTools(null);
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("connection count reflects cache state")
        void connectionCountAccurate() {
            assertEquals(0, manager.getActiveConnectionCount());
            // After failed discovery, no cache entry is added
            var config = new A2AAgentConfig();
            config.setUrl("http://192.0.2.1:1");
            config.setTimeoutMs(100L);
            manager.discoverTools(List.of(config));
            assertEquals(0, manager.getActiveConnectionCount());
        }
    }

    // ─── CachedAgentInfo and CircuitState records ───────────────

    @Nested
    @DisplayName("Internal record types")
    class InternalRecords {

        @Test
        @DisplayName("CachedAgentInfo stores card and timestamp")
        void cachedAgentInfo() {
            var info = new A2AToolProviderManager.CachedAgentInfo(
                    java.util.Map.of("name", "agent"), 123456L);
            assertEquals("agent", info.agentCard().get("name"));
            assertEquals(123456L, info.timestamp());
        }

        @Test
        @DisplayName("CircuitState stores failures and lastFailure")
        void circuitState() {
            var state = new A2AToolProviderManager.CircuitState(3, 999L);
            assertEquals(3, state.failures());
            assertEquals(999L, state.lastFailure());
        }

        @Test
        @DisplayName("A2AToolsResult stores specs and executors")
        void a2aToolsResult() {
            var result = new A2AToolProviderManager.A2AToolsResult(
                    List.of(), java.util.Map.of());
            assertTrue(result.toolSpecs().isEmpty());
            assertTrue(result.executors().isEmpty());
        }
    }

    // ─── sanitizeToolName edge cases (via reflection) ────────────

    @Nested
    @DisplayName("sanitizeToolName edge cases")
    class SanitizeToolNameEdgeCases {

        private String invokeSanitize(String input) {
            try {
                var method = A2AToolProviderManager.class.getDeclaredMethod("sanitizeToolName", String.class);
                method.setAccessible(true);
                return (String) method.invoke(manager, input);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("special characters replaced with underscores")
        void specialCharsReplaced() {
            String result = invokeSanitize("my-agent!@#v2");
            // Should keep alphanumeric and underscores, collapse multiples
            assertFalse(result.contains("-"));
            assertFalse(result.contains("!"));
            assertFalse(result.contains("@"));
            assertFalse(result.contains("#"));
            assertTrue(result.matches("[a-z0-9_]+"));
        }

        @Test
        @DisplayName("leading underscores are stripped")
        void leadingUnderscoresStripped() {
            String result = invokeSanitize("___leading");
            assertFalse(result.startsWith("_"));
            assertEquals("leading", result);
        }

        @Test
        @DisplayName("trailing underscores are stripped")
        void trailingUnderscoresStripped() {
            String result = invokeSanitize("trailing___");
            assertFalse(result.endsWith("_"));
            assertEquals("trailing", result);
        }

        @Test
        @DisplayName("consecutive underscores collapsed to single")
        void consecutiveUnderscoresCollapsed() {
            String result = invokeSanitize("foo___bar");
            assertEquals("foo_bar", result);
        }

        @Test
        @DisplayName("mixed special chars, leading/trailing, and consecutive underscores")
        void mixedEdgeCases() {
            String result = invokeSanitize("--Hello World!! Test--");
            // lowercase, specials→_, collapse, trim leading/trailing
            assertEquals("hello_world_test", result);
        }

        @Test
        @DisplayName("uppercase is lowercased")
        void uppercaseLowered() {
            String result = invokeSanitize("MyAgent");
            assertEquals("myagent", result);
        }

        @Test
        @DisplayName("already valid name is unchanged")
        void alreadyValid() {
            String result = invokeSanitize("valid_name_123");
            assertEquals("valid_name_123", result);
        }
    }

    // ─── Discovery with empty/blank URL config ──────────────────

    @Nested
    @DisplayName("Discovery with empty URL config")
    class EmptyUrlConfigTests {

        @Test
        @DisplayName("blank URL string is skipped — returns empty result")
        void blankUrlSkipped() {
            var config = new A2AAgentConfig();
            config.setUrl(""); // blank string — isNullOrEmpty returns true
            config.setTimeoutMs(100L);

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
            assertTrue(result.executors().isEmpty());
        }

        @Test
        @DisplayName("null URL is skipped — returns empty result")
        void nullUrlSkipped() {
            var config = new A2AAgentConfig();
            config.setUrl(null);
            config.setTimeoutMs(100L);

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("mix of blank and valid URL — blank is skipped")
        void mixedBlankAndValidUrl() {
            var blankConfig = new A2AAgentConfig();
            blankConfig.setUrl("");
            blankConfig.setTimeoutMs(100L);

            var validConfig = new A2AAgentConfig();
            validConfig.setUrl("http://192.0.2.1:1"); // will fail to connect but not skipped
            validConfig.setTimeoutMs(100L);

            var result = manager.discoverTools(List.of(blankConfig, validConfig));
            assertNotNull(result);
            // Both return no tools (blank skipped, valid fails to connect)
            assertTrue(result.toolSpecs().isEmpty());
        }
    }

    // ─── warnIfRawKey with vars reference ────────────────────────

    @Nested
    @DisplayName("warnIfRawKey with vars reference")
    class VarsReferenceTests {

        @Test
        @DisplayName("${vars:key} reference — no warning (treated like vault)")
        void varsRefKey() {
            var config = new A2AAgentConfig();
            config.setUrl("http://192.0.2.1:1");
            config.setApiKey("${vars:my-api-key}");
            config.setTimeoutMs(100L);

            // Should not throw — vars reference is accepted just like vault
            assertDoesNotThrow(() -> manager.discoverTools(List.of(config)));
        }

        @Test
        @DisplayName("${eddivault:key} reference — no warning")
        void eddiVaultRefKey() {
            var config = new A2AAgentConfig();
            config.setUrl("http://192.0.2.1:1");
            config.setApiKey("${eddivault:secret-key}");
            config.setTimeoutMs(100L);

            assertDoesNotThrow(() -> manager.discoverTools(List.of(config)));
        }

        @Test
        @DisplayName("null API key — no warning, no exception")
        void nullApiKey() {
            var config = new A2AAgentConfig();
            config.setUrl("http://192.0.2.1:1");
            config.setApiKey(null);
            config.setTimeoutMs(100L);

            assertDoesNotThrow(() -> manager.discoverTools(List.of(config)));
        }

        @Test
        @DisplayName("empty API key — no warning, no exception")
        void emptyApiKey() {
            var config = new A2AAgentConfig();
            config.setUrl("http://192.0.2.1:1");
            config.setApiKey("");
            config.setTimeoutMs(100L);

            assertDoesNotThrow(() -> manager.discoverTools(List.of(config)));
        }
    }
}
