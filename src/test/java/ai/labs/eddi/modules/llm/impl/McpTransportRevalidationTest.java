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
 * DNS rebinding (MCP part): the server URL was validated when the transport was
 * built, and the cached transport then resolved the host again on every new
 * connection without asking. The transport's per-request header hook now
 * validates the URL before each request is sent.
 * <p>
 * A real loopback server stands in for the MCP endpoint, and the manager's URL
 * check is made to refuse from its second call on — the shape of a name that
 * passed once and then rebound to a private address.
 */
@DisplayName("McpToolProviderManager — the cached transport validates the URL on every request")
class McpTransportRevalidationTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger validations = new AtomicInteger();
    private McpToolProviderManager manager;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        manager = new McpToolProviderManager(mock(GlobalVariableResolver.class), mock(SecretResolver.class), false,
                McpToolProviderManager.DEFAULT_MAX_DESCRIPTION_CHARS, McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS) {
            @Override
            void validateServerUrl(String url) {
                if (validations.incrementAndGet() > 1) {
                    throw new IllegalArgumentException("URL resolves to a private/internal address which is not allowed: " + url);
                }
                super.validateServerUrl(url);
            }
        };
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a URL that no longer passes is refused before the request leaves the process")
    void requestRefusedWhenUrlNoLongerPasses() {
        McpServerConfig config = new McpServerConfig();
        config.setName("rebinding");
        config.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        config.setTimeoutMs(5000L);

        assertThrows(Exception.class, () -> manager.fetchToolsFromServer(config));

        assertTrue(validations.get() >= 2, "validated when the transport was built and again for the request");
        assertEquals(0, requests.get(), "nothing may reach the server once its URL is refused");
    }
}
