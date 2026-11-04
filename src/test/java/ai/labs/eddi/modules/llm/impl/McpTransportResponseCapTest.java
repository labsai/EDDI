/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.secrets.SecretResolver;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * An MCP server answers through EDDI's {@code BoundedJdkHttpClient}, so a
 * response past {@code eddi.llm.max-response-bytes} (32 MB by default) fails
 * the call instead of being read into the heap whole. Measured against a real
 * server on the loopback interface that answers the handshake with a valid
 * JSON-RPC message padded past the cap: the transport's own client (before
 * langchain4j 1.23) read it to the end.
 */
@DisplayName("McpToolProviderManager — an MCP response past the cap is cut off")
class McpTransportResponseCapTest {

    private static final long PADDING = 40L * 1024 * 1024;

    private HttpServer server;
    private McpToolProviderManager manager;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0); // chunked: no length to refuse up front
            byte[] spaces = new byte[64 * 1024];
            Arrays.fill(spaces, (byte) ' ');
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{".getBytes(StandardCharsets.UTF_8));
                for (long sent = 0; sent < PADDING; sent += spaces.length) {
                    out.write(spaces);
                }
                out.write("}}".getBytes(StandardCharsets.UTF_8));
            } catch (Exception clientWentAway) {
                // the capped client cancelled the body: expected
            }
        });
        server.start();

        manager = new McpToolProviderManager(mock(GlobalVariableResolver.class), mock(SecretResolver.class), false,
                McpToolProviderManager.DEFAULT_MAX_DESCRIPTION_CHARS, McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("the handshake fails with the cap's message")
    void oversizedResponseFails() {
        McpServerConfig config = new McpServerConfig();
        config.setName("oversized");
        config.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        config.setTimeoutMs(20_000L);

        Exception failure = assertThrows(Exception.class, () -> manager.fetchToolsFromServer(config));

        boolean capped = false;
        for (Throwable t = failure; t != null && !capped; t = t.getCause()) {
            capped = t.getMessage() != null && t.getMessage().contains("eddi.llm.max-response-bytes");
        }
        assertTrue(capped, "the failure must come from the response cap, was: " + failure);
    }
}
