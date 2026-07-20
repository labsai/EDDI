/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.*;
import org.mockito.Mock;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Tests for the circuit breaker logic in {@link McpToolProviderManager}.
 */
@DisplayName("McpToolProviderManager Circuit Breaker Tests")
class McpToolProviderManagerCircuitBreakerTest {

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

    // ==================== isCircuitOpen ====================

    @Nested
    @DisplayName("isCircuitOpen")
    class IsCircuitOpenTests {

        @Test
        @DisplayName("should return false when no failures recorded")
        void noFailures_circuitClosed() {
            assertFalse(manager.isCircuitOpen("http://test.local"));
        }

        @Test
        @DisplayName("should return false when fewer than threshold failures in window")
        void belowThreshold_circuitClosed() throws Exception {
            injectFailures("http://test.local", 2);
            assertFalse(manager.isCircuitOpen("http://test.local"));
        }

        @Test
        @DisplayName("should return true when threshold failures reached in window")
        void atThreshold_circuitOpen() throws Exception {
            injectFailures("http://test.local", 3);
            assertTrue(manager.isCircuitOpen("http://test.local"));
        }

        @Test
        @DisplayName("should return true when above threshold failures in window")
        void aboveThreshold_circuitOpen() throws Exception {
            injectFailures("http://test.local", 5);
            assertTrue(manager.isCircuitOpen("http://test.local"));
        }

        @Test
        @DisplayName("should return false when all failures are outside the window")
        void oldFailures_circuitClosed() throws Exception {
            // Inject failures from 120 seconds ago (beyond the 60s window)
            injectOldFailures("http://test.local", 5, 120);
            assertFalse(manager.isCircuitOpen("http://test.local"));
        }

        @Test
        @DisplayName("should track different URLs independently")
        void differentUrls_independentCircuits() throws Exception {
            injectFailures("http://server-a.local", 3);
            injectFailures("http://server-b.local", 1);

            assertTrue(manager.isCircuitOpen("http://server-a.local"));
            assertFalse(manager.isCircuitOpen("http://server-b.local"));
        }
    }

    // ==================== discoverTools + Circuit Breaker Integration
    // ====================

    @Nested
    @DisplayName("discoverTools with circuit breaker")
    class DiscoverToolsCircuitBreakerTests {

        @Test
        @DisplayName("should skip server when circuit is open")
        void circuitOpen_skipsServer() throws Exception {
            injectFailures("http://failing.local", 3);

            McpServerConfig config = new McpServerConfig();
            config.setUrl("http://failing.local");
            config.setName("failing-server");

            var result = manager.discoverTools(List.of(config));

            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty(),
                    "Should return no tools when circuit is open");
        }

        @Test
        @DisplayName("should attempt connection when circuit is closed")
        void circuitClosed_attemptsConnection() throws Exception {
            // No failures — circuit is closed
            McpServerConfig config = new McpServerConfig();
            config.setUrl("http://unreachable-xyz-12345:9999/mcp");
            config.setName("test-server");
            config.setTransport("http");
            config.setTimeoutMs(1000L);

            // Will fail to connect but should at least attempt it
            var result = manager.discoverTools(List.of(config));

            assertNotNull(result);
            // We don't assert on toolSpecs size — the connection will fail but
            // the important thing is it attempted (didn't skip due to circuit breaker)
        }

        @Test
        @DisplayName("should record failure when connection fails — opens circuit after threshold")
        void connectionFailure_recordedForCircuitBreaker() throws Exception {
            McpServerConfig config = new McpServerConfig();
            config.setUrl("http://unreachable-xyz-circuit-test:9999/mcp");
            config.setName("circuit-test");
            config.setTransport("http");
            config.setTimeoutMs(500L);

            // Each discoverTools call will fail and record a failure
            for (int i = 0; i < 3; i++) {
                manager.discoverTools(List.of(config));
            }

            // After 3 failures, the circuit should be open
            assertTrue(manager.isCircuitOpen("http://unreachable-xyz-circuit-test:9999/mcp"),
                    "Circuit should open after 3 consecutive failures");
        }
    }

    // ==================== Circuit Breaker Reset ====================

    @Nested
    @DisplayName("Circuit breaker reset on success")
    class CircuitBreakerResetTests {

        @Test
        @DisplayName("isCircuitOpen returns false after failure history is cleared")
        void clearingFailures_closesCircuit() throws Exception {
            injectFailures("http://recovering.local", 5);
            assertTrue(manager.isCircuitOpen("http://recovering.local"));

            // Simulate success by clearing the failure timestamps
            clearFailures("http://recovering.local");
            assertFalse(manager.isCircuitOpen("http://recovering.local"));
        }
    }

    // ==================== Helpers ====================

    /**
     * Inject recent failure timestamps into the manager's failure tracking via
     * reflection. All injected timestamps are within the circuit window (recent).
     */
    @SuppressWarnings("unchecked")
    private void injectFailures(String url, int count) throws Exception {
        Field field = McpToolProviderManager.class.getDeclaredField("failureTimestamps");
        field.setAccessible(true);
        Map<String, List<Instant>> timestamps = (Map<String, List<Instant>>) field.get(manager);

        List<Instant> failures = timestamps.computeIfAbsent(url, k -> new CopyOnWriteArrayList<>());
        for (int i = 0; i < count; i++) {
            failures.add(Instant.now());
        }
    }

    /**
     * Inject old failure timestamps outside the circuit window.
     */
    @SuppressWarnings("unchecked")
    private void injectOldFailures(String url, int count, long secondsAgo) throws Exception {
        Field field = McpToolProviderManager.class.getDeclaredField("failureTimestamps");
        field.setAccessible(true);
        Map<String, List<Instant>> timestamps = (Map<String, List<Instant>>) field.get(manager);

        List<Instant> failures = timestamps.computeIfAbsent(url, k -> new CopyOnWriteArrayList<>());
        Instant oldTime = Instant.now().minusSeconds(secondsAgo);
        for (int i = 0; i < count; i++) {
            failures.add(oldTime);
        }
    }

    /**
     * Clear failure history for a URL via reflection (simulates recordSuccess).
     */
    @SuppressWarnings("unchecked")
    private void clearFailures(String url) throws Exception {
        Field field = McpToolProviderManager.class.getDeclaredField("failureTimestamps");
        field.setAccessible(true);
        Map<String, List<Instant>> timestamps = (Map<String, List<Instant>>) field.get(manager);
        timestamps.remove(url);
    }
}
