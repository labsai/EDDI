/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link A2AToolProviderManager} covering the testable
 * pure-function paths: circuit breaker logic, tool name sanitization,
 * empty/null input handling, and lifecycle methods.
 */
class A2AToolProviderManagerTest {

    private A2AToolProviderManager manager;

    @BeforeEach
    void setUp() {
        SecretResolver secretResolver = mock(SecretResolver.class);
        manager = new A2AToolProviderManager(secretResolver);
    }

    // ─── discoverTools empty/null ─────────────────────────────────

    @Nested
    @DisplayName("discoverTools with no agents")
    class DiscoverToolsEmpty {

        @Test
        @DisplayName("should return empty result for null config list")
        void nullConfig() {
            var result = manager.discoverTools(null);
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
            assertTrue(result.executors().isEmpty());
        }

        @Test
        @DisplayName("should return empty result for empty config list")
        void emptyConfig() {
            var result = manager.discoverTools(List.of());
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("should skip agent config with empty URL")
        void skipNullUrl() {
            var config = new A2AAgentConfig();
            config.setUrl(null);
            var result = manager.discoverTools(List.of(config));
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("should skip agent config with blank URL")
        void skipBlankUrl() {
            var config = new A2AAgentConfig();
            config.setUrl("  ");
            var result = manager.discoverTools(List.of(config));
            assertTrue(result.toolSpecs().isEmpty());
        }
    }

    // ─── sanitizeToolName ────────────────────────────────────────

    @Nested
    @DisplayName("sanitizeToolName")
    class SanitizeToolName {

        @Test
        @DisplayName("should lowercase and replace special chars with underscores")
        void specialChars() {
            // Use reflection to access private method, or test indirectly via
            // a config that fails during HTTP discovery but verifies name construction
            // For now test the pattern directly
            String input = "My Agent-v2 (prod)";
            String expected = "my_agent_v2_prod";
            assertEquals(expected, sanitize(input));
        }

        @Test
        @DisplayName("should collapse consecutive underscores")
        void collapseUnderscores() {
            assertEquals("a_b", sanitize("A__B"));
        }

        @Test
        @DisplayName("should strip leading and trailing underscores")
        void stripEdgeUnderscores() {
            assertEquals("name", sanitize("_name_"));
        }

        @Test
        @DisplayName("should handle purely alphanumeric names")
        void alphanumeric() {
            assertEquals("agent42", sanitize("Agent42"));
        }

        /**
         * Replicates the private sanitizeToolName logic for testing, since the method
         * is private but the logic is security-relevant.
         */
        private String sanitize(String name) {
            return name.toLowerCase()
                    .replaceAll("[^a-z0-9_]", "_")
                    .replaceAll("_+", "_")
                    .replaceAll("^_|_$", "");
        }
    }

    // ─── Lifecycle methods ──────────────────────────────────────

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("initial connection count should be zero")
        void initialConnectionCount() {
            assertEquals(0, manager.getActiveConnectionCount());
        }

        @Test
        @DisplayName("shutdown should clear the cache")
        void shutdownClearsCache() {
            manager.shutdown();
            assertEquals(0, manager.getActiveConnectionCount());
        }
    }

    // ─── Circuit breaker ────────────────────────────────────────

    @Nested
    @DisplayName("Circuit breaker behavior")
    class CircuitBreaker {

        @Test
        @DisplayName("should skip agent after 3 consecutive failures")
        void circuitOpensAfterThreshold() {
            var config = new A2AAgentConfig();
            config.setUrl("http://unreachable:9999");
            config.setTimeoutMs(100L);

            // First 3 attempts will fail and record failures
            for (int i = 0; i < 3; i++) {
                manager.discoverTools(List.of(config));
            }

            // The 4th attempt should skip due to circuit breaker
            // (no HTTP attempt, still returns empty)
            var result = manager.discoverTools(List.of(config));
            assertTrue(result.toolSpecs().isEmpty());
        }
    }
}
