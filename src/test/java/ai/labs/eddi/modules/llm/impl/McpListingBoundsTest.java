/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager.McpFailureKind;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.secrets.SecretResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.mcp.client.McpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Against a real MCP server on the loopback interface: a {@code tools/list}
 * that answers every page with a fresh cursor is stopped at
 * {@code eddi.mcp.list.max-pages} instead of holding the thread and filling the
 * heap; the discover-tools probe leaves no client behind; and cached clients
 * carry no health-check thread.
 */
@DisplayName("McpToolProviderManager — listing bounds and client lifecycle against a live server")
class McpListingBoundsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final AtomicInteger listCalls = new AtomicInteger();
    /**
     * Pages the server serves before it stops returning a cursor; negative = never
     * stops.
     */
    private volatile int finitePages = -1;
    private McpToolProviderManager manager;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
        manager = new McpToolProviderManager(mock(GlobalVariableResolver.class), mock(SecretResolver.class), false,
                McpToolProviderManager.DEFAULT_MAX_DESCRIPTION_CHARS, McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS);
        manager.maxListPages = 3;
        manager.clientCloseGraceMillis = 0;
    }

    @AfterEach
    void stopServer() {
        manager.shutdown();
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        JsonNode id = request.get("id");
        if (id == null || id.isNull()) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        String method = request.path("method").asText();
        String result;
        if ("initialize".equals(method)) {
            result = "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"pager\",\"version\":\"1\"}}";
        } else if ("tools/list".equals(method)) {
            int page = listCalls.incrementAndGet();
            String cursor = finitePages < 0 || page < finitePages ? ",\"nextCursor\":\"page-" + page + "\"" : "";
            result = "{\"tools\":[{\"name\":\"tool_" + page + "\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]" + cursor + "}";
        } else {
            send(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}");
            return;
        }
        send(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
    }

    private static void send(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private McpServerConfig config() {
        McpServerConfig config = new McpServerConfig();
        config.setName("pager");
        config.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        config.setTimeoutMs(10_000L);
        return config;
    }

    @Test
    @DisplayName("an endless cursor chain stops at max-pages, and the client is dropped")
    void endlessPaginationIsBounded() {
        Exception failure = assertThrows(Exception.class, () -> manager.fetchToolsFromServer(config()));

        boolean bounded = false;
        for (Throwable t = failure; t != null && !bounded; t = t.getCause()) {
            bounded = t instanceof BoundedListingMcpTransport.ListingLimitExceededException;
        }
        assertTrue(bounded, "the failure must come from the listing bound, was: " + failure);
        assertEquals(3, listCalls.get(), "no page past the bound may be requested");
        assertEquals(0, manager.getActiveConnectionCount(), "a client driven to the bound is not kept");
    }

    @Test
    @DisplayName("the agent path reports the bound as a failed server and caches nothing")
    void discoveryReportsTheBound() {
        var result = manager.discoverTools(List.of(config()));

        assertTrue(result.toolSpecs().isEmpty());
        assertEquals(List.of(McpFailureKind.CONNECTION_FAILURE), result.failures().stream().map(f -> f.kind()).toList());
        assertEquals(0, manager.getCachedToolServerCount(), "a stopped listing must not be cached as 'no tools'");
    }

    @Test
    @DisplayName("a finite listing within the bound is served in full")
    void finiteListingWithinBound() {
        finitePages = 3;

        var result = manager.discoverTools(List.of(config()));

        assertEquals(List.of("tool_1", "tool_2", "tool_3"), result.toolSpecs().stream().map(s -> s.name()).toList());
        assertEquals(1, manager.getActiveConnectionCount());
    }

    @Test
    @DisplayName("the discover-tools probe lists the tools and leaves no client and no cached tool list behind")
    void probeLeavesNothingBehind() {
        finitePages = 2;

        var result = manager.probeTools(List.of(config()));

        assertEquals(2, result.toolSpecs().size());
        assertEquals(0, manager.getActiveConnectionCount());
        assertEquals(0, manager.getCachedToolServerCount());
    }

    @Test
    @DisplayName("a cached client runs no health-check scheduler of its own")
    void noHealthCheckThread() throws Exception {
        finitePages = 1;
        McpClient client = manager.getOrCreateClient(config());

        Field scheduler = client.getClass().getDeclaredField("healthCheckScheduler");
        scheduler.setAccessible(true);
        assertNull(scheduler.get(client), "autoHealthCheck must be off: one scheduler thread per cached client otherwise");
    }
}
