/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.engine.httpclient.outbound.TestOutbound;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Slack client over a real socket to a loopback address — the path a test
 * double on the same machine takes ({@code eddi.slack.api-base-url} with plain
 * http to {@code 127.0.0.1}). The client sends under
 * {@code SsrfPolicy.METADATA_ONLY}: loopback must stay reachable while the
 * cloud metadata service is refused.
 */
@DisplayName("Slack client over loopback http")
class SlackWebApiClientLoopbackTest {

    private HttpServer server;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"ok\":true,\"ts\":\"1700000000.000001\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a post to a loopback base URL is sent and answered")
    void loopbackBaseUrlIsReachable() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/";
        var config = new SlackConfig(60, 300, 1, 10L).withApiBaseUrl(base);
        var client = new SlackWebApiClient(new ObjectMapper(), TestOutbound.standalone(), config);

        assertEquals("1700000000.000001", client.postMessage("Bearer xoxb-loop", "C1", null, "hello"));
        assertEquals(List.of("Bearer xoxb-loop"), authorizations);
    }
}
