/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager.McpFailureKind;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager.McpServerFailure;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Covers {@link McpToolProviderManager#discoverTools(List)} itself: the F12 TTL
 * tool cache, the F15 duplicate-tool-name policy (within one server AND across
 * two servers) and the classification of a rejected configuration versus a
 * genuine connectivity failure.
 * <p>
 * {@code fetchToolsFromServer} is stubbed on a Mockito spy, so no MCP server
 * and no socket are involved.
 */
@DisplayName("McpToolProviderManager — discoverTools (cache, collisions, failure classification)")
class McpToolProviderManagerDiscoveryTest {

    private static final String URL_A = "http://server-a.example.com/mcp";
    private static final String URL_B = "http://server-b.example.com/mcp";

    @Mock
    private GlobalVariableResolver globalVariableResolver;
    @Mock
    private SecretResolver secretResolver;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = openMocks(this);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    private McpToolProviderManager spyManager(long toolCacheTtlMillis) {
        return spy(new McpToolProviderManager(globalVariableResolver, secretResolver, false,
                McpToolProviderManager.DEFAULT_MAX_DESCRIPTION_CHARS, toolCacheTtlMillis));
    }

    private static McpServerConfig config(String name, String url) {
        var config = new McpServerConfig();
        config.setName(name);
        config.setUrl(url);
        config.setTransport("http");
        config.setTimeoutMs(500L);
        return config;
    }

    private static ToolSpecification spec(String name, String description) {
        return ToolSpecification.builder().name(name).description(description).build();
    }

    /** A ToolProviderResult carrying exactly the given (spec → executor) pairs. */
    private static ToolProviderResult resultOf(Map<ToolSpecification, ToolExecutor> tools) {
        ToolProviderResult result = mock(ToolProviderResult.class);
        when(result.tools()).thenReturn(new LinkedHashMap<>(tools));
        return result;
    }

    // ==================== F12 — TTL tool cache ====================

    @Nested
    @DisplayName("F12 — discovered-tools TTL cache")
    class ToolCache {

        @Test
        @DisplayName("a second turn within the TTL serves the cached tools without a new tools/list call")
        void secondCallWithinTtlIsServedFromCache() {
            var manager = spyManager(McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS);
            ToolExecutor executor = (request, memoryId) -> "ok";
            doReturn(resultOf(Map.of(spec("search", "Searches."), executor))).when(manager).fetchToolsFromServer(any());

            var first = manager.discoverTools(List.of(config("a", URL_A)));
            var second = manager.discoverTools(List.of(config("a", URL_A)));

            verify(manager, times(1)).fetchToolsFromServer(any());
            assertEquals(1, first.toolSpecs().size());
            assertEquals(List.of("search"), second.toolSpecs().stream().map(ToolSpecification::name).toList());
            assertSame(executor, second.executors().get("search"), "the cached executor must still be usable");
            assertEquals(1, manager.getCachedToolServerCount());
        }

        @Test
        @DisplayName("an expired cache entry is re-discovered (TTL is honoured, not 'cache forever')")
        void expiredEntryIsRediscovered() {
            var manager = spyManager(0L); // every entry is stale immediately
            doReturn(resultOf(Map.of(spec("search", "Searches."), (ToolExecutor) (request, memoryId) -> "ok")))
                    .when(manager).fetchToolsFromServer(any());

            manager.discoverTools(List.of(config("a", URL_A)));
            manager.discoverTools(List.of(config("a", URL_A)));

            verify(manager, times(2)).fetchToolsFromServer(any());
        }

        @Test
        @DisplayName("closeClient drops the cached tools so the next turn re-discovers")
        void closeClientInvalidatesCachedTools() {
            var manager = spyManager(McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS);
            doReturn(resultOf(Map.of(spec("search", "Searches."), (ToolExecutor) (request, memoryId) -> "ok")))
                    .when(manager).fetchToolsFromServer(any());

            manager.discoverTools(List.of(config("a", URL_A)));
            assertEquals(1, manager.getCachedToolServerCount());

            manager.closeClient(URL_A);
            assertEquals(0, manager.getCachedToolServerCount(), "executors bound to a closed client must not be served");

            manager.discoverTools(List.of(config("a", URL_A)));
            verify(manager, times(2)).fetchToolsFromServer(any());
        }

        @Test
        @DisplayName("shutdown clears the tool cache")
        void shutdownClearsToolCache() {
            var manager = spyManager(McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS);
            doReturn(resultOf(Map.of(spec("search", "Searches."), (ToolExecutor) (request, memoryId) -> "ok")))
                    .when(manager).fetchToolsFromServer(any());

            manager.discoverTools(List.of(config("a", URL_A)));
            manager.shutdown();

            assertEquals(0, manager.getCachedToolServerCount());
        }
    }

    // ==================== F15 — duplicate tool names ====================

    @Nested
    @DisplayName("F15 — duplicate tool names")
    class DuplicateToolNames {

        @Test
        @DisplayName("one server advertising the same tool name twice yields ONE spec and keeps the FIRST executor")
        void duplicateWithinOneServer() {
            var manager = spyManager(McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS);
            ToolExecutor first = (request, memoryId) -> "first";
            ToolExecutor second = (request, memoryId) -> "second";
            var tools = new LinkedHashMap<ToolSpecification, ToolExecutor>();
            tools.put(spec("wire_transfer", "Transfers money."), first);
            tools.put(spec("wire_transfer", "Also transfers money, elsewhere."), second);
            doReturn(resultOf(tools)).when(manager).fetchToolsFromServer(any());

            var result = manager.discoverTools(List.of(config("a", URL_A)));

            assertEquals(1, result.toolSpecs().size(), "the model must not receive two specs with the same name");
            assertEquals("Transfers money.", result.toolSpecs().get(0).description(), "the first definition wins");
            assertSame(first, result.executors().get("wire_transfer"), "the kept executor must match the kept spec");
        }

        @Test
        @DisplayName("two servers advertising the same tool name yield ONE spec dispatched to the FIRST server")
        void duplicateAcrossServers() {
            var manager = spyManager(McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS);
            ToolExecutor fromA = (request, memoryId) -> "A";
            ToolExecutor fromB = (request, memoryId) -> "B";
            var resultA = resultOf(Map.of(spec("search", "Server A search."), fromA));
            var resultB = resultOf(Map.of(spec("search", "Server B search."), fromB));
            doAnswer(invocation -> URL_A.equals(((McpServerConfig) invocation.getArgument(0)).getUrl()) ? resultA : resultB)
                    .when(manager).fetchToolsFromServer(any());

            var result = manager.discoverTools(List.of(config("a", URL_A), config("b", URL_B)));

            assertEquals(1, result.toolSpecs().size(), "server B must not shadow server A with a second 'search' spec");
            assertEquals("Server A search.", result.toolSpecs().get(0).description());
            assertEquals(1, result.executors().size());
            assertSame(fromA, result.executors().get("search"), "calls must still reach the first server's executor");
        }

        @Test
        @DisplayName("distinct tool names from two servers are both kept")
        void distinctNamesAcrossServersAreMerged() {
            var manager = spyManager(McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS);
            var resultA = resultOf(Map.of(spec("search", "A."), (ToolExecutor) (request, memoryId) -> "A"));
            var resultB = resultOf(Map.of(spec("translate", "B."), (ToolExecutor) (request, memoryId) -> "B"));
            doAnswer(invocation -> URL_A.equals(((McpServerConfig) invocation.getArgument(0)).getUrl()) ? resultA : resultB)
                    .when(manager).fetchToolsFromServer(any());

            var result = manager.discoverTools(List.of(config("a", URL_A), config("b", URL_B)));

            assertEquals(2, result.toolSpecs().size());
            assertEquals(2, result.executors().size());
            assertTrue(result.executors().containsKey("search") && result.executors().containsKey("translate"));
            assertTrue(result.failures().isEmpty(), "nothing failed");
        }

        @Test
        @DisplayName("the cross-server collision also holds when the second server is served from cache")
        void cachedSecondServerStillDoesNotShadow() {
            var manager = spyManager(McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS);
            ToolExecutor fromA = (request, memoryId) -> "A";
            ToolExecutor fromB = (request, memoryId) -> "B";
            var resultA = resultOf(Map.of(spec("search", "Server A search."), fromA));
            var resultB = resultOf(Map.of(spec("search", "Server B search."), fromB));
            doAnswer(invocation -> URL_A.equals(((McpServerConfig) invocation.getArgument(0)).getUrl()) ? resultA : resultB)
                    .when(manager).fetchToolsFromServer(any());

            manager.discoverTools(List.of(config("a", URL_A), config("b", URL_B)));
            // second turn: both servers now come out of the TTL cache
            var result = manager.discoverTools(List.of(config("a", URL_A), config("b", URL_B)));

            verify(manager, times(2)).fetchToolsFromServer(any());
            assertEquals(1, result.toolSpecs().size());
            assertSame(fromA, result.executors().get("search"));
        }
    }

    // ============ rejected configuration vs. connectivity failure ============

    @Nested
    @DisplayName("failure classification")
    class FailureClassification {

        @Test
        @DisplayName("a non-http(s) URL is reported as INVALID_CONFIGURATION and never contacted")
        void rejectedUrlIsSurfaced() {
            var manager = spyManager(McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS);

            var result = manager.discoverTools(List.of(config("evil", "ftp://internal/mcp")));

            verify(manager, never()).fetchToolsFromServer(any());
            assertEquals(1, result.failures().size(), "a rejected server must not be indistinguishable from 'no tools'");
            McpServerFailure failure = result.failures().get(0);
            assertEquals(McpFailureKind.INVALID_CONFIGURATION, failure.kind());
            assertEquals("evil", failure.serverName());
            assertTrue(result.hasConfigurationErrors());
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("an unsupported transport is reported as INVALID_CONFIGURATION, naming the offending value")
        void rejectedTransportIsSurfaced() {
            var manager = spyManager(McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS);
            var cfg = config("legacy", URL_A);
            cfg.setTransport("stdio");

            var result = manager.discoverTools(List.of(cfg));

            verify(manager, never()).fetchToolsFromServer(any());
            assertEquals(1, result.failures().size());
            assertEquals(McpFailureKind.INVALID_CONFIGURATION, result.failures().get(0).kind());
            assertTrue(result.failures().get(0).message().contains("stdio"), result.failures().get(0).message());
        }

        @Test
        @DisplayName("a misconfigured server never trips the circuit breaker (a typo is not a flaky server)")
        void configurationErrorDoesNotOpenTheCircuit() {
            var manager = spyManager(McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS);
            var cfg = config("evil", "ftp://internal/mcp");

            for (int i = 0; i < 4; i++) {
                var result = manager.discoverTools(List.of(cfg));
                assertEquals(McpFailureKind.INVALID_CONFIGURATION, result.failures().get(0).kind(),
                        "turn " + i + " must still report the configuration error, not a circuit trip");
            }

            assertFalse(manager.isCircuitOpen("ftp://internal/mcp"));
        }

        @Test
        @DisplayName("a transient connection failure is CONNECTION_FAILURE and still trips the breaker")
        void connectionFailureIsClassifiedAndTripsTheCircuit() {
            var manager = spyManager(McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS);
            doThrow(new RuntimeException("connection refused")).when(manager).fetchToolsFromServer(any());
            var cfg = config("flaky", URL_A);

            for (int i = 0; i < 3; i++) {
                var result = manager.discoverTools(List.of(cfg));
                assertEquals(McpFailureKind.CONNECTION_FAILURE, result.failures().get(0).kind());
                assertFalse(result.hasConfigurationErrors(), "a flaky server is not a config error");
            }

            assertTrue(manager.isCircuitOpen(cfg), "the circuit is keyed per credential, so ask by config");
            var afterTrip = manager.discoverTools(List.of(cfg));
            assertEquals(McpFailureKind.CIRCUIT_OPEN, afterTrip.failures().get(0).kind());
            verify(manager, times(3)).fetchToolsFromServer(any());
        }

        @Test
        @DisplayName("an empty URL is reported as INVALID_CONFIGURATION")
        void emptyUrlIsSurfaced() {
            var manager = spyManager(McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS);
            var cfg = new McpServerConfig();
            cfg.setName("nameless");
            cfg.setUrl("");

            var result = manager.discoverTools(List.of(cfg));

            assertEquals(1, result.failures().size());
            assertEquals(McpFailureKind.INVALID_CONFIGURATION, result.failures().get(0).kind());
        }

        @Test
        @DisplayName("one rejected server does not stop a healthy one, and both outcomes are reported")
        void rejectedServerDoesNotBlockHealthyOne() {
            var manager = spyManager(McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS);
            doReturn(resultOf(Map.of(spec("search", "Searches."), (ToolExecutor) (request, memoryId) -> "ok")))
                    .when(manager).fetchToolsFromServer(any());

            var result = manager.discoverTools(List.of(config("evil", "ftp://internal/mcp"), config("good", URL_A)));

            assertEquals(List.of("search"), result.toolSpecs().stream().map(ToolSpecification::name).toList());
            assertEquals(1, result.failures().size());
            assertEquals(McpFailureKind.INVALID_CONFIGURATION, result.failures().get(0).kind());
            assertTrue(result.hasConfigurationErrors());
        }

        @Test
        @DisplayName("a fully successful discovery reports no failures")
        void successHasNoFailures() {
            var manager = spyManager(McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS);
            doReturn(resultOf(Map.of(spec("search", "Searches."), (ToolExecutor) (request, memoryId) -> "ok")))
                    .when(manager).fetchToolsFromServer(any());

            var result = manager.discoverTools(List.of(config("good", URL_A)));

            assertTrue(result.failures().isEmpty());
            assertFalse(result.hasConfigurationErrors());
            assertEquals(1, result.toolSpecs().size());
        }
    }
}
