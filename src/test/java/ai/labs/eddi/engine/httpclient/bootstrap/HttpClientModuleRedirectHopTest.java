/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.bootstrap;

import ai.labs.eddi.engine.httpclient.bootstrap.HttpClientModule.AddressClass;
import ai.labs.eddi.engine.httpclient.impl.VertxHttpClient;
import com.sun.net.httpserver.HttpServer;
import io.vertx.core.Vertx;
import io.vertx.core.http.RequestOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security review M9.3: with {@code eddi.security.ssrf-protection.enabled} off
 * the httpcalls client follows redirects, so a {@code 302} from a public server
 * could land a call on loopback or an internal address. Each hop is now checked
 * against the address class of the request that redirected.
 */
@DisplayName("HttpClientModule refuses redirect hops into a more private address class")
class HttpClientModuleRedirectHopTest {

    /** Literal-only resolver: no DNS in unit tests. */
    private static final HttpClientModule.HostResolver LITERALS = host -> {
        if ("unresolvable.example".equals(host)) {
            throw new UnknownHostException(host);
        }
        if ("api.example.com".equals(host)) {
            return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
        }
        return new InetAddress[]{InetAddress.getByName(host)};
    };

    private static RequestOptions hop(String host) {
        return new RequestOptions().setHost(host).setPort(80).setURI("/next");
    }

    @Nested
    @DisplayName("the per-hop rule")
    class Rule {

        @Test
        @DisplayName("a public server redirecting to loopback or a private address is refused")
        void publicToPrivateRefused() {
            for (String target : new String[]{"127.0.0.1", "10.0.0.5", "192.168.1.1", "::1", "[::1]", "fd00::1"}) {
                var e = assertThrows(IllegalArgumentException.class,
                        () -> HttpClientModule.requireHopWithinAddressClass("93.184.216.34", "api.example.com", hop(target), Set.of(), LITERALS),
                        target);
                assertTrue(e.getMessage().contains("Redirect refused"), e.getMessage());
            }
        }

        @Test
        @DisplayName("an internal API redirecting within its own network keeps working")
        void sameClassFollowed() {
            RequestOptions privateHop = hop("10.0.0.6");
            assertSame(privateHop, HttpClientModule.requireHopWithinAddressClass("10.0.0.5", null, privateHop, Set.of(), LITERALS));
            RequestOptions loopbackHop = hop("127.0.0.1");
            assertSame(loopbackHop, HttpClientModule.requireHopWithinAddressClass("127.0.0.1", null, loopbackHop, Set.of(), LITERALS));
        }

        @Test
        @DisplayName("a private origin cannot step to loopback, and an unknown origin is not trusted")
        void crossClassRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> HttpClientModule.requireHopWithinAddressClass("10.0.0.5", null, hop("127.0.0.1"), Set.of(), LITERALS));
            assertThrows(IllegalArgumentException.class,
                    () -> HttpClientModule.requireHopWithinAddressClass(null, null, hop("10.0.0.5"), Set.of(), LITERALS));
        }

        @Test
        @DisplayName("the previous host is resolved when the connected address is unknown")
        void previousHostResolved() {
            assertThrows(IllegalArgumentException.class,
                    () -> HttpClientModule.requireHopWithinAddressClass(null, "api.example.com", hop("127.0.0.1"), Set.of(), LITERALS));
            RequestOptions ok = hop("10.0.0.9");
            assertSame(ok, HttpClientModule.requireHopWithinAddressClass(null, "10.0.0.1", ok, Set.of(), LITERALS));
        }

        @Test
        @DisplayName("public hops are followed from anywhere")
        void publicHopFollowed() {
            RequestOptions next = hop("api.example.com");
            assertSame(next, HttpClientModule.requireHopWithinAddressClass("10.0.0.5", null, next, Set.of(), LITERALS));
        }

        @Test
        @DisplayName("an allow-listed host is followed even from a public origin")
        void allowListed() {
            RequestOptions bare = hop("10.0.0.5");
            assertSame(bare, HttpClientModule.requireHopWithinAddressClass("93.184.216.34", null, bare, Set.of("10.0.0.5"), LITERALS));
            RequestOptions withPort = hop("10.0.0.5");
            assertSame(withPort,
                    HttpClientModule.requireHopWithinAddressClass("93.184.216.34", null, withPort, Set.of("10.0.0.5:80"), LITERALS));
            assertThrows(IllegalArgumentException.class, () -> HttpClientModule.requireHopWithinAddressClass("93.184.216.34", null,
                    hop("10.0.0.5"), Set.of("10.0.0.5:8080"), LITERALS));
        }

        @Test
        @DisplayName("a host-less Location and an unresolvable host are refused")
        void hostlessAndUnresolvable() {
            var hostless = assertThrows(IllegalArgumentException.class,
                    () -> HttpClientModule.requireHopWithinAddressClass("127.0.0.1", null, new RequestOptions().setURI("/x"), Set.of(), LITERALS));
            assertTrue(hostless.getMessage().contains("names no host"), hostless.getMessage());
            assertThrows(IllegalArgumentException.class, () -> HttpClientModule.requireHopWithinAddressClass("127.0.0.1", null,
                    new RequestOptions().setHost(" "), Set.of(), LITERALS));
            assertThrows(IllegalArgumentException.class, () -> HttpClientModule.requireHopWithinAddressClass("93.184.216.34", null,
                    hop("unresolvable.example"), Set.of(), LITERALS));
        }

        @Test
        @DisplayName("address classes")
        void classify() {
            assertEquals(AddressClass.LOOPBACK, HttpClientModule.classify("127.0.0.1", LITERALS));
            assertEquals(AddressClass.PRIVATE, HttpClientModule.classify("172.16.0.1", LITERALS));
            assertEquals(AddressClass.PUBLIC, HttpClientModule.classify("93.184.216.34", LITERALS));
            assertNull(HttpClientModule.classify("unresolvable.example", LITERALS));
        }
    }

    @Nested
    @DisplayName("the produced client against a real loopback server")
    class EndToEnd {

        private Vertx vertx;
        private HttpServer server;
        private final AtomicInteger landed = new AtomicInteger();

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/same-class", exchange -> {
                exchange.getResponseHeaders().add("Location", "/landed");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/to-private", exchange -> {
                // 10.255.255.1 is unroutable: were the hop attempted, the call would sit in
                // connect until the timeout instead of failing at once
                exchange.getResponseHeaders().add("Location", "http://10.255.255.1/internal");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/landed", exchange -> {
                landed.incrementAndGet();
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

        private VertxHttpClient client() {
            HttpClientModule module = new HttpClientModule();
            module.vertx = vertx;
            return module.provideHttpClient(5, 5, 0, 5000, Optional.empty());
        }

        @Test
        @DisplayName("a loopback service redirecting to itself is followed")
        void loopbackToLoopbackFollowed() throws Exception {
            int status = client().getWebClient().get(server.getAddress().getPort(), "127.0.0.1", "/same-class").send().toCompletionStage()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS).statusCode();
            assertEquals(200, status);
            assertEquals(1, landed.get());
        }

        @Test
        @DisplayName("a loopback service redirecting into another private range is refused before connecting")
        void loopbackToPrivateRefused() {
            long start = System.nanoTime();
            var e = assertThrows(ExecutionException.class,
                    () -> client().getWebClient().get(server.getAddress().getPort(), "127.0.0.1", "/to-private").send().toCompletionStage()
                            .toCompletableFuture().get(10, TimeUnit.SECONDS));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(e.getCause() instanceof IllegalArgumentException, String.valueOf(e.getCause()));
            assertTrue(e.getCause().getMessage().contains("Redirect refused"), e.getCause().getMessage());
            assertTrue(elapsedMs < 4_000, "refused without a connect attempt, took " + elapsedMs + "ms");
        }
    }
}
