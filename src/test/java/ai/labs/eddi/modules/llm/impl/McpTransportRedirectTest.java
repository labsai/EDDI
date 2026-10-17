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

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Security review 2026-10-15, finding M2 (MCP part): only the configured server
 * URL is validated, so the transport must never follow a {@code 3xx} from it —
 * the {@code Location} is a host nobody checked, and every POST (handshake,
 * {@code tools/list}, each tool call with its {@code Authorization} header)
 * would be replayed there.
 * <p>
 * Measured against a real redirecting server on the loopback interface rather
 * than read off the builder: the langchain4j default has changed between
 * releases ({@code Redirect.NORMAL} before 1.22), which is exactly why the
 * manager pins {@code followRedirects(false)} itself.
 */
@DisplayName("McpToolProviderManager — a 3xx from the MCP URL is not followed")
class McpTransportRedirectTest {

    private HttpServer server;
    private final AtomicInteger mcpHits = new AtomicInteger();
    private final AtomicInteger redirectTargetHits = new AtomicInteger();
    private McpToolProviderManager manager;

    @BeforeEach
    void startRedirectingServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The configured endpoint: answers every request with a 307 to a sibling
        // path on the same host. Same host on purpose — a cross-origin hop would
        // also drop the Authorization header in the JDK client, and this test is
        // about the hop itself, not the header.
        server.createContext("/mcp", exchange -> {
            mcpHits.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/internal");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        // The unvalidated target. Reaching it at all is the finding.
        server.createContext("/internal", exchange -> {
            redirectTargetHits.incrementAndGet();
            byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        // SSRF protection off, so the loopback server URL itself is admitted and the
        // only thing under test is the redirect.
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
    @DisplayName("the handshake fails on the 307 and the Location is never requested")
    void redirectIsRefusedNotFollowed() {
        McpServerConfig config = new McpServerConfig();
        config.setName("redirecting");
        config.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        config.setTimeoutMs(5000L);

        assertThrows(Exception.class, () -> manager.fetchToolsFromServer(config),
                "a 3xx answer to the initialize request must fail the discovery, not be followed");

        assertTrue(mcpHits.get() >= 1, "the configured URL must have been contacted");
        assertEquals(0, redirectTargetHits.get(), "the redirect target must never be requested: it was not validated");
    }
}
