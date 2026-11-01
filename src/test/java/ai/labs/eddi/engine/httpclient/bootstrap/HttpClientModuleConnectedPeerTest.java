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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DNS rebinding: {@code ApiCallExecutor} validates the httpcall's host by
 * resolving it, and the Vert.x client resolves it again to connect. The client
 * now checks the address the connection actually reached before the request is
 * written, so the two lookups can no longer disagree to the attacker's
 * advantage.
 * <p>
 * The end-to-end half connects to a real loopback server by literal address:
 * what the guard judges is the peer of the connection, so how the name was
 * resolved does not matter — exactly the property a rebinding name exploits.
 */
@DisplayName("HttpClientModule checks the connected peer before sending")
class HttpClientModuleConnectedPeerTest {

    @Nested
    @DisplayName("the peer rule")
    class Rule {

        @Test
        @DisplayName("protection on: a loopback or private peer is refused, a public one is not")
        void privatePeerRefusedWithProtectionOn() {
            for (String peer : new String[]{"127.0.0.1", "10.0.0.5", "192.168.1.1", "0:0:0:0:0:0:0:1", "fd00:0:0:0:0:0:0:1"}) {
                String refusal = HttpClientModule.connectedPeerRefusal(peer, "rebind.example", 443, true, true, Set.of());
                assertNotNull(refusal, peer);
                assertTrue(refusal.contains("private/internal"), refusal);
            }
            assertNull(HttpClientModule.connectedPeerRefusal("93.184.216.34", "api.example.com", 443, true, true, Set.of()));
        }

        @Test
        @DisplayName("protection on: an allow-listed host may reach its private address, by bare host or host:port")
        void allowListedHostMayBePrivate() {
            assertNull(HttpClientModule.connectedPeerRefusal("10.0.0.5", "billing.internal", 443, true, true, Set.of("billing.internal")));
            assertNull(HttpClientModule.connectedPeerRefusal("10.0.0.5", "billing.internal", -1, true, true, Set.of("billing.internal:443")));
            assertNotNull(HttpClientModule.connectedPeerRefusal("10.0.0.5", "billing.internal", -1, false, true, Set.of("billing.internal:443")));
        }

        @Test
        @DisplayName("protection off: private peers are reachable, as configured internal APIs are")
        void privatePeerAllowedWithProtectionOff() {
            assertNull(HttpClientModule.connectedPeerRefusal("127.0.0.1", "localhost", 8080, false, false, Set.of()));
            assertNull(HttpClientModule.connectedPeerRefusal("10.0.0.5", "internal-api", 80, false, false, Set.of()));
        }

        @Test
        @DisplayName("a metadata or link-local peer is refused always — protection off, allow-listed, zoned IPv6")
        void metadataPeerRefusedAlways() {
            for (String peer : new String[]{"169.254.169.254", "169.254.170.2", "fe80:0:0:0:0:0:0:1%eth0", "fd00:ec2:0:0:0:0:0:254",
                    "100.100.100.200"}) {
                String refusal = HttpClientModule.connectedPeerRefusal(peer, "rebind.example", 80, false, false, Set.of("rebind.example"));
                assertNotNull(refusal, peer);
                assertTrue(refusal.contains("metadata"), refusal);
            }
        }

        @Test
        @DisplayName("an unreadable peer is refused with protection on and let through with it off")
        void unreadablePeer() {
            assertNotNull(HttpClientModule.connectedPeerRefusal(null, "rebind.example", 80, false, true, Set.of()));
            assertNotNull(HttpClientModule.connectedPeerRefusal("not-an-ip", "rebind.example", 80, false, true, Set.of()));
            assertNull(HttpClientModule.connectedPeerRefusal(null, "rebind.example", 80, false, false, Set.of()));
        }
    }

    @Nested
    @DisplayName("the produced client against a real loopback server")
    class EndToEnd {

        private Vertx vertx;
        private HttpServer server;
        private final AtomicInteger received = new AtomicInteger();

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/internal", exchange -> {
                received.incrementAndGet();
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

        private VertxHttpClient client(boolean ssrfProtectionEnabled, String allowedHosts) {
            HttpClientModule module = new HttpClientModule();
            module.vertx = vertx;
            module.ssrfProtectionEnabled = ssrfProtectionEnabled;
            return module.provideHttpClient(5, 5, 0, 5000, Optional.ofNullable(allowedHosts));
        }

        private int get(VertxHttpClient client) throws Exception {
            return client.getWebClient().get(server.getAddress().getPort(), "127.0.0.1", "/internal").send().toCompletionStage()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS).statusCode();
        }

        @Test
        @DisplayName("protection on: a connection that reached loopback is refused and the request is never sent")
        void loopbackPeerRefusedBeforeSending() {
            var e = assertThrows(ExecutionException.class, () -> get(client(true, null)));

            assertInstanceOf(IllegalArgumentException.class, e.getCause(), String.valueOf(e.getCause()));
            assertTrue(e.getCause().getMessage().contains("connected to 127.0.0.1"), e.getCause().getMessage());
            assertEquals(0, received.get(), "not a byte of the request may reach the private peer");
        }

        @Test
        @DisplayName("protection on: an allow-listed host is sent")
        void allowListedHostSent() throws Exception {
            assertEquals(200, get(client(true, "127.0.0.1")));
            assertEquals(1, received.get());
        }

        @Test
        @DisplayName("protection off: a loopback API stays reachable")
        void protectionOffSent() throws Exception {
            assertEquals(200, get(client(false, null)));
            assertEquals(1, received.get());
        }
    }
}
