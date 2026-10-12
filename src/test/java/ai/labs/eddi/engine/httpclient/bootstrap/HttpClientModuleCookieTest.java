/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.bootstrap;

import ai.labs.eddi.engine.httpclient.impl.VertxHttpClient;
import com.sun.net.httpserver.HttpServer;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The outbound client behind every agent's httpcalls is shared by all agents,
 * conversations and users, so it must hold no cookie jar: a {@code Set-Cookie}
 * returned to one call may never be replayed on a later call to the same host.
 * Runs the real producer against a real loopback server.
 */
@DisplayName("HttpClientModule does not persist cookies across calls")
class HttpClientModuleCookieTest {

    private Vertx vertx;
    private HttpServer server;
    private final List<String> cookieHeaderPerRequest = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            cookieHeaderPerRequest.add(String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")));
            exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=TENANT-A-SECRET; Path=/; HttpOnly");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a Set-Cookie from one request is not sent on the next request to the same host")
    void setCookieIsNotReplayed() throws Exception {
        HttpClientModule module = new HttpClientModule();
        module.vertx = vertx;
        VertxHttpClient client = module.provideHttpClient(5, 5, 0, 5000);

        int port = server.getAddress().getPort();
        for (int i = 0; i < 2; i++) {
            int status = client.getWebClient().get(port, "127.0.0.1", "/ping").send().toCompletionStage().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).statusCode();
            assertEquals(200, status);
        }

        assertEquals(2, cookieHeaderPerRequest.size());
        assertEquals("null", cookieHeaderPerRequest.get(0), "first call has no cookie");
        assertEquals("null", cookieHeaderPerRequest.get(1),
                "Set-Cookie from the first call was replayed on the second");
    }
}
