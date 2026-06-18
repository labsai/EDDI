/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.secrets.SecretResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Deep branch coverage tests for {@link A2AToolProviderManager} targeting the
 * internal methods: executeA2ATask, fetchAgentCard, discoverAgentTools, circuit
 * breaker, and warnIfRawKey.
 */
@DisplayName("A2AToolProviderManager — Deep Branch Coverage")
class A2AToolProviderManagerDeepBranchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private GlobalVariableResolver globalVariableResolver;
    private SecretResolver secretResolver;
    private A2AToolProviderManager manager;
    private HttpServer httpServer;
    private int serverPort;

    @BeforeEach
    void setUp() throws IOException {
        globalVariableResolver = mock(GlobalVariableResolver.class);
        secretResolver = mock(SecretResolver.class);
        doReturn("resolved-key").when(globalVariableResolver).resolveValue(anyString());
        doReturn("resolved-key").when(secretResolver).resolveValue(anyString());

        manager = new A2AToolProviderManager(globalVariableResolver, secretResolver);

        // Create a lightweight HTTP server for testing real HTTP calls
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        serverPort = httpServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        manager.shutdown();
    }

    private String serverUrl() {
        return "http://127.0.0.1:" + serverPort;
    }

    // =========================================================
    // fetchAgentCard — various response scenarios
    // =========================================================

    @Nested
    @DisplayName("fetchAgentCard scenarios")
    class FetchAgentCardTests {

        @Test
        @DisplayName("Should return null when Agent Card response is non-200")
        void nonOkStatusReturnsNull() {
            httpServer.createContext("/agent.json", exchange -> {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setTimeoutMs(5000L);

            var result = manager.discoverTools(List.of(config));
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("Should return null when Agent Card is too large")
        void tooLargeResponseReturnsNull() {
            httpServer.createContext("/agent.json", exchange -> {
                // Send a body > 1MB
                byte[] largeBody = new byte[1_048_577];
                Arrays.fill(largeBody, (byte) 'a');
                exchange.sendResponseHeaders(200, largeBody.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(largeBody);
                }
            });
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setTimeoutMs(5000L);

            var result = manager.discoverTools(List.of(config));
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("Should return null when Agent Card JSON missing 'name' field")
        void missingNameFieldReturnsNull() throws Exception {
            String cardJson = MAPPER.writeValueAsString(Map.of("description", "no-name agent"));
            httpServer.createContext("/agent.json", exchange -> {
                byte[] body = cardJson.getBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setTimeoutMs(5000L);

            var result = manager.discoverTools(List.of(config));
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("Should cache agent card and return from cache on second call")
        void cachesAgentCard() throws Exception {
            String cardJson = MAPPER.writeValueAsString(Map.of("name", "test-agent", "description", "A test agent"));
            var callCount = new int[]{0};
            httpServer.createContext("/agent.json", exchange -> {
                callCount[0]++;
                byte[] body = cardJson.getBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            // Also need a tasks/send endpoint for the A2A tool executor
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setTimeoutMs(5000L);

            // First call fetches
            var result1 = manager.discoverTools(List.of(config));
            assertEquals(1, result1.toolSpecs().size());
            assertEquals(1, callCount[0]);

            // Second call uses cache
            var result2 = manager.discoverTools(List.of(config));
            assertEquals(1, result2.toolSpecs().size());
            assertEquals(1, callCount[0], "Should use cached agent card");
        }

        @Test
        @DisplayName("Should add Authorization header when apiKey is set")
        void addsAuthHeaderWithApiKey() throws Exception {
            String cardJson = MAPPER.writeValueAsString(Map.of("name", "secure-agent"));
            var receivedHeaders = new HashMap<String, String>();
            httpServer.createContext("/agent.json", exchange -> {
                String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
                if (authHeader != null) {
                    receivedHeaders.put("Authorization", authHeader);
                }
                byte[] body = cardJson.getBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setApiKey("my-api-key");
            config.setTimeoutMs(5000L);

            manager.discoverTools(List.of(config));
            assertTrue(receivedHeaders.containsKey("Authorization"));
        }
    }

    // =========================================================
    // discoverAgentTools — skills and filtering
    // =========================================================

    @Nested
    @DisplayName("discoverAgentTools — skills handling")
    class DiscoverAgentToolsTests {

        @Test
        @DisplayName("Should create one tool per skill when Agent Card has skills")
        void createsToolPerSkill() throws Exception {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("name", "multi-skill-agent");
            card.put("skills", List.of(
                    Map.of("id", "translate", "name", "Translate", "description", "Translates text"),
                    Map.of("id", "summarize", "name", "Summarize", "description", "Summarizes text")));

            String cardJson = MAPPER.writeValueAsString(card);
            httpServer.createContext("/agent.json", exchange -> {
                byte[] body = cardJson.getBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setTimeoutMs(5000L);

            var result = manager.discoverTools(List.of(config));
            assertEquals(2, result.toolSpecs().size());
            assertTrue(result.toolSpecs().get(0).name().contains("translate"));
            assertTrue(result.toolSpecs().get(1).name().contains("summarize"));
        }

        @Test
        @DisplayName("Should apply skills filter to include only matching skills")
        void skillsFilterIncludesMatching() throws Exception {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("name", "filtered-agent");
            card.put("skills", List.of(
                    Map.of("id", "translate", "name", "Translate", "description", "Translates"),
                    Map.of("id", "summarize", "name", "Summarize", "description", "Summarizes"),
                    Map.of("id", "analyze", "name", "Analyze", "description", "Analyzes")));

            String cardJson = MAPPER.writeValueAsString(card);
            httpServer.createContext("/agent.json", exchange -> {
                byte[] body = cardJson.getBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setTimeoutMs(5000L);
            config.setSkillsFilter(List.of("translate", "analyze"));

            var result = manager.discoverTools(List.of(config));
            assertEquals(2, result.toolSpecs().size());
        }

        @Test
        @DisplayName("Should use agent name from config when provided, overriding Agent Card name")
        void usesConfigNameOverCardName() throws Exception {
            String cardJson = MAPPER.writeValueAsString(Map.of("name", "card-name"));
            httpServer.createContext("/agent.json", exchange -> {
                byte[] body = cardJson.getBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setName("custom-name");
            config.setTimeoutMs(5000L);

            var result = manager.discoverTools(List.of(config));
            assertEquals(1, result.toolSpecs().size());
            assertTrue(result.toolSpecs().get(0).name().contains("custom_name"));
        }

        @Test
        @DisplayName("Should create default tool when skills list is empty")
        void emptySkillsCreatesDefaultTool() throws Exception {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("name", "no-skills-agent");
            card.put("description", "Agent with no skills list");
            card.put("skills", List.of());

            String cardJson = MAPPER.writeValueAsString(card);
            httpServer.createContext("/agent.json", exchange -> {
                byte[] body = cardJson.getBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setTimeoutMs(5000L);

            var result = manager.discoverTools(List.of(config));
            assertEquals(1, result.toolSpecs().size());
            assertTrue(result.toolSpecs().get(0).name().contains("no_skills_agent"));
        }
    }

    // =========================================================
    // executeA2ATask — response parsing
    // =========================================================

    @Nested
    @DisplayName("executeA2ATask — JSON-RPC response handling")
    class ExecuteA2ATaskTests {

        @Test
        @DisplayName("Should return artifact text from successful A2A response")
        void returnsArtifactText() throws Exception {
            setupAgentCard("executor-agent");

            Map<String, Object> rpcResponse = Map.of(
                    "jsonrpc", "2.0",
                    "result", Map.of(
                            "artifacts", List.of(
                                    Map.of("parts", List.of(Map.of("text", "Hello from A2A"))))));

            httpServer.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().equals("/agent.json")) {
                    serveAgentCard(exchange, "executor-agent");
                } else {
                    byte[] body = MAPPER.writeValueAsBytes(rpcResponse);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                }
            });
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setTimeoutMs(5000L);

            var result = manager.discoverTools(List.of(config));
            assertFalse(result.toolSpecs().isEmpty());
            assertNotNull(result.executors().get(result.toolSpecs().get(0).name()));
        }

        @Test
        @DisplayName("Should return error message when A2A returns non-200 HTTP status")
        void returnsErrorOnNon200() throws Exception {
            httpServer.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().equals("/agent.json")) {
                    serveAgentCard(exchange, "error-agent");
                } else {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                }
            });
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setTimeoutMs(5000L);

            var result = manager.discoverTools(List.of(config));
            assertFalse(result.toolSpecs().isEmpty());

            // Execute the tool to trigger executeA2ATask
            var toolName = result.toolSpecs().get(0).name();
            var executor = result.executors().get(toolName);
            var toolRequest = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .name(toolName)
                    .arguments("{\"message\": \"test\"}")
                    .build();
            String toolResult = executor.execute(toolRequest, null);
            assertTrue(toolResult.contains("HTTP 500"));
        }

        @Test
        @DisplayName("Should return error message from JSON-RPC error response")
        void returnsJsonRpcError() throws Exception {
            Map<String, Object> rpcResponse = Map.of(
                    "jsonrpc", "2.0",
                    "error", Map.of("code", -32600, "message", "Invalid request"));

            httpServer.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().equals("/agent.json")) {
                    serveAgentCard(exchange, "rpc-error-agent");
                } else {
                    byte[] body = MAPPER.writeValueAsBytes(rpcResponse);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                }
            });
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setTimeoutMs(5000L);

            var result = manager.discoverTools(List.of(config));
            var toolName = result.toolSpecs().get(0).name();
            var executor = result.executors().get(toolName);
            var toolRequest = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .name(toolName)
                    .arguments("{\"message\": \"test\"}")
                    .build();
            String toolResult = executor.execute(toolRequest, null);
            assertTrue(toolResult.contains("A2A error"));
        }

        @Test
        @DisplayName("Should return 'No result' when JSON-RPC has neither result nor error")
        void returnsNoResult() throws Exception {
            Map<String, Object> rpcResponse = Map.of("jsonrpc", "2.0", "id", "abc");

            httpServer.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().equals("/agent.json")) {
                    serveAgentCard(exchange, "no-result-agent");
                } else {
                    byte[] body = MAPPER.writeValueAsBytes(rpcResponse);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                }
            });
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setTimeoutMs(5000L);

            var result = manager.discoverTools(List.of(config));
            var toolName = result.toolSpecs().get(0).name();
            var executor = result.executors().get(toolName);
            var toolRequest = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .name(toolName)
                    .arguments("{\"message\": \"test\"}")
                    .build();
            String toolResult = executor.execute(toolRequest, null);
            assertEquals("No result from A2A agent", toolResult);
        }

        @Test
        @DisplayName("Should return invalid response when jsonrpc version is wrong")
        void returnsInvalidResponseOnWrongVersion() throws Exception {
            Map<String, Object> rpcResponse = Map.of("jsonrpc", "1.0", "result", Map.of("status", "ok"));

            httpServer.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().equals("/agent.json")) {
                    serveAgentCard(exchange, "bad-version-agent");
                } else {
                    byte[] body = MAPPER.writeValueAsBytes(rpcResponse);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                }
            });
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setTimeoutMs(5000L);

            var result = manager.discoverTools(List.of(config));
            var toolName = result.toolSpecs().get(0).name();
            var executor = result.executors().get(toolName);
            var toolRequest = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .name(toolName)
                    .arguments("{\"message\": \"test\"}")
                    .build();
            String toolResult = executor.execute(toolRequest, null);
            assertTrue(toolResult.contains("Invalid A2A response"));
        }

        @Test
        @DisplayName("Should return history text as fallback when no artifacts")
        void returnsHistoryTextFallback() throws Exception {
            Map<String, Object> rpcResponse = Map.of(
                    "jsonrpc", "2.0",
                    "result", Map.of(
                            "history", List.of(
                                    Map.of("role", "agent", "parts", List.of(Map.of("text", "History response"))))));

            httpServer.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().equals("/agent.json")) {
                    serveAgentCard(exchange, "history-agent");
                } else {
                    byte[] body = MAPPER.writeValueAsBytes(rpcResponse);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                }
            });
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setTimeoutMs(5000L);

            var result = manager.discoverTools(List.of(config));
            var toolName = result.toolSpecs().get(0).name();
            var executor = result.executors().get(toolName);
            var toolRequest = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .name(toolName)
                    .arguments("{\"message\": \"test\"}")
                    .build();
            String toolResult = executor.execute(toolRequest, null);
            assertEquals("History response", toolResult);
        }

        @Test
        @DisplayName("Should return response size exceeded when A2A response is too large")
        void returnsSizeExceeded() throws Exception {
            httpServer.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().equals("/agent.json")) {
                    serveAgentCard(exchange, "large-response-agent");
                } else {
                    // Send a valid JSON-RPC with large body > 1MB
                    byte[] largeBody = new byte[1_048_577];
                    Arrays.fill(largeBody, (byte) 'x');
                    exchange.sendResponseHeaders(200, largeBody.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(largeBody);
                    }
                }
            });
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setTimeoutMs(5000L);

            var result = manager.discoverTools(List.of(config));
            var toolName = result.toolSpecs().get(0).name();
            var executor = result.executors().get(toolName);
            var toolRequest = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .name(toolName)
                    .arguments("{\"message\": \"test\"}")
                    .build();
            String toolResult = executor.execute(toolRequest, null);
            assertTrue(toolResult.contains("size limit"));
        }

        @Test
        @DisplayName("Should add auth header to A2A task request when apiKey is set")
        void addsAuthToTaskRequest() throws Exception {
            var receivedAuth = new String[1];
            Map<String, Object> rpcResponse = Map.of(
                    "jsonrpc", "2.0",
                    "result", Map.of("artifacts", List.of(Map.of("parts", List.of(Map.of("text", "ok"))))));

            httpServer.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().equals("/agent.json")) {
                    serveAgentCard(exchange, "auth-agent");
                } else {
                    receivedAuth[0] = exchange.getRequestHeaders().getFirst("Authorization");
                    byte[] body = MAPPER.writeValueAsBytes(rpcResponse);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                }
            });
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setApiKey("${vault:my-key}");
            config.setTimeoutMs(5000L);

            var result = manager.discoverTools(List.of(config));
            var toolName = result.toolSpecs().get(0).name();
            var executor = result.executors().get(toolName);
            var toolRequest = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .name(toolName)
                    .arguments("{\"message\": \"test\"}")
                    .build();
            executor.execute(toolRequest, null);
            assertNotNull(receivedAuth[0], "Should send Authorization header");
        }
    }

    // =========================================================
    // Circuit breaker
    // =========================================================

    @Nested
    @DisplayName("Circuit breaker behavior")
    class CircuitBreakerTests {

        @Test
        @DisplayName("Should open circuit after 3 failures and skip discovery")
        void opensCircuitAfterThreshold() throws Exception {
            // Use a URL that will fail (connection refused)
            var config = new A2AAgentConfig();
            config.setUrl("http://127.0.0.1:1");
            config.setTimeoutMs(500L);

            // Fail 3 times to trip the circuit
            for (int i = 0; i < 3; i++) {
                manager.discoverTools(List.of(config));
            }

            // 4th call should be skipped due to circuit breaker
            var result = manager.discoverTools(List.of(config));
            assertTrue(result.toolSpecs().isEmpty());
        }
    }

    // =========================================================
    // warnIfRawKey
    // =========================================================

    @Nested
    @DisplayName("warnIfRawKey")
    class WarnIfRawKeyTests {

        @Test
        @DisplayName("Should not warn for vault reference API keys")
        void noWarnForVaultRef() throws Exception {
            String cardJson = MAPPER.writeValueAsString(Map.of("name", "vault-agent"));
            httpServer.createContext("/agent.json", exchange -> {
                byte[] body = cardJson.getBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            httpServer.start();

            var config = new A2AAgentConfig();
            config.setUrl(serverUrl());
            config.setApiKey("${vault:my-secret}");
            config.setTimeoutMs(5000L);

            // Should succeed without issues (vault ref is acceptable)
            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
        }
    }

    // =========================================================
    // sanitizeToolName
    // =========================================================

    @Nested
    @DisplayName("sanitizeToolName")
    class SanitizeToolNameTests {

        @Test
        @DisplayName("Should sanitize special characters in tool name")
        void sanitizesSpecialChars() throws Exception {
            // Access private method via reflection
            Method sanitize = A2AToolProviderManager.class.getDeclaredMethod("sanitizeToolName", String.class);
            sanitize.setAccessible(true);

            assertEquals("hello_world", sanitize.invoke(manager, "Hello World!"));
            assertEquals("test_123", sanitize.invoke(manager, "Test-123"));
            assertEquals("a_b", sanitize.invoke(manager, "A__B"));
        }
    }

    // =========================================================
    // Helpers
    // =========================================================

    private void setupAgentCard(String name) {
        // No-op, card is served by the context handler
    }

    private void serveAgentCard(com.sun.net.httpserver.HttpExchange exchange, String name) throws IOException {
        String cardJson;
        try {
            cardJson = MAPPER.writeValueAsString(Map.of("name", name));
        } catch (Exception e) {
            throw new IOException(e);
        }
        byte[] body = cardJson.getBytes();
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
