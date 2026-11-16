/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Vert.x engine against real loopback sockets: the pre-send peer check (no
 * request byte reaches a refused peer), the host differential, deadlines,
 * mid-stream caps, proxy routing and the per-host pool limit.
 */
class VertxOutboundEngineTest {

    private static Vertx vertx;

    private HttpServer server;
    private ExecutorService serverThreads;
    private int port;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    private static VertxOutboundEngine engine(OutboundSettings settings) {
        return new VertxOutboundEngine(vertx, settings, VertxOutboundEngine.TlsOptions.NONE, UnaryOperator.identity());
    }

    private static OutboundSettings vertxSettings(Integer maxPerHost, OutboundSettings.ProxySettings proxy) {
        OutboundSettings.ProfileSettings profile = new OutboundSettings.ProfileSettings(Duration.ofSeconds(5), null,
                OutboundSettings.Protocol.AUTO, null, OutboundSettings.VERTX_ENGINE, maxPerHost);
        Map<Profile, OutboundSettings.ProfileSettings> profiles = new EnumMap<>(Profile.class);
        for (Profile p : Profile.values()) {
            profiles.put(p, profile);
        }
        return new OutboundSettings(OutboundSettings.VERTX_ENGINE, profiles, proxy);
    }

    private static OutboundHttp facade(OutboundSettings settings) {
        return new OutboundHttp(settings, engine(settings), false, new SimpleMeterRegistry());
    }

    private static OutboundEngine.Hop get(URI uri, OutboundEngine.PeerCheck peerCheck) {
        return new OutboundEngine.Hop(Profile.DEFAULT, "GET", uri, Map.of("X-Test", List.of("1")), new byte[0],
                Duration.ofSeconds(5), System.nanoTime() + Duration.ofSeconds(5).toNanos(), peerCheck);
    }

    private URI local(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    @Test
    @DisplayName("a refused peer is reset before a single request byte is written")
    void refusedPeerReceivesNoBytes() throws Exception {
        try (ServerSocket raw = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Integer> received = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = raw.accept()) {
                    socket.setSoTimeout(2000);
                    InputStream in = socket.getInputStream();
                    int count = 0;
                    try {
                        while (in.read() != -1) {
                            count++;
                        }
                    } catch (SocketTimeoutException e) {
                        // Nothing more arrived.
                    }
                    return count;
                } catch (IOException e) {
                    return -1;
                }
            });
            URI target = URI.create("http://127.0.0.1:" + raw.getLocalPort() + "/secret");

            OutboundBlockedException refused = assertThrows(OutboundBlockedException.class,
                    () -> engine(vertxSettings(5, OutboundSettings.ProxySettings.NONE)).send(get(target, ConnectedPeers.STRICT)));

            assertTrue(refused.getMessage().contains("127.0.0.1"), refused.getMessage());
            assertEquals(0, received.get(10, TimeUnit.SECONDS), "the refused peer must not see the request");
        }
    }

    @Test
    @DisplayName("the peer check sees the connected address and passes an allowed one")
    void peerCheckSeesConnectedAddress() throws Exception {
        server.createContext("/ok", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        List<InetAddress> seen = new CopyOnWriteArrayList<>();

        OutboundEngine.Received received = engine(vertxSettings(5, OutboundSettings.ProxySettings.NONE))
                .send(get(local("/ok"), seen::add));

        assertEquals(200, received.status());
        try (InputStream body = received.body()) {
            assertEquals("ok", new String(body.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(List.of(InetAddress.getByName("127.0.0.1")), seen);
    }

    @Test
    @DisplayName("a registry-based authority (no java.net.URI host) is refused, never re-parsed from the string")
    void hostDifferentialRefused() {
        URI differential = URI.create("http://x_y.example:" + port + "/");
        assertEquals(null, differential.getHost(), "the precondition: java.net.URI sees no host");

        IOException e = assertThrows(IOException.class,
                () -> engine(vertxSettings(5, OutboundSettings.ProxySettings.NONE)).send(get(differential, null)));
        assertTrue(e.getMessage().contains("no host"), e.getMessage());
    }

    @Test
    @DisplayName("a chunked body over the cap fails mid-stream and the download stops")
    void chunkedOverCapFailsMidStream() throws Exception {
        CountDownLatch cutOff = new CountDownLatch(1);
        server.createContext("/big", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[64 * 1024];
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 160; i++) {
                    out.write(chunk);
                }
            } catch (IOException e) {
                cutOff.countDown();
            }
        });
        server.start();

        assertThrows(BodyTooLargeException.class, () -> facade(vertxSettings(5, OutboundSettings.ProxySettings.NONE)).send(
                OutboundRequest.get(Profile.DEFAULT, local("/big")).ssrf(SsrfPolicy.NONE).build(),
                BodyMode.bytes(256 * 1024, BodyMode.OnOverflow.FAIL)));
        assertTrue(cutOff.await(5, TimeUnit.SECONDS), "the 10 MiB body must not be read to the end");
    }

    @Test
    @DisplayName("a server that never answers is cut off in time, without the query in the message")
    void silentServerHitsDeadline() {
        server.createContext("/silent", exchange -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();

        long start = System.nanoTime();
        HttpTimeoutException e = assertThrows(HttpTimeoutException.class,
                () -> facade(vertxSettings(5, OutboundSettings.ProxySettings.NONE)).send(
                        OutboundRequest.get(Profile.DEFAULT, URI.create("http://127.0.0.1:" + port + "/silent?key=s3cret"))
                                .ssrf(SsrfPolicy.NONE).timeout(Duration.ofMillis(300)).build(),
                        BodyMode.string(1024, BodyMode.OnOverflow.FAIL)));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(elapsedMs < 3000, "took " + elapsedMs + "ms");
        assertFalse(e.getMessage().contains("s3cret"), e.getMessage());
    }

    @Test
    @DisplayName("with a proxy configured, requests go to the proxy with the absolute target URI, and skip the peer check")
    void routesThroughProxy() throws Exception {
        List<String> seenByProxy = new CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            seenByProxy.add(exchange.getRequestURI().toString());
            byte[] body = "via proxy".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        OutboundSettings settings = vertxSettings(5,
                new OutboundSettings.ProxySettings("127.0.0.1", port, List.of("localhost"), null));

        // The peer is the (loopback) proxy: STRICT would refuse it, so the engine must
        // not apply it.
        OutboundEngine.Received received = engine(settings).send(get(URI.create("http://upstream.example/data?q=1"),
                ConnectedPeers.STRICT));

        try (InputStream body = received.body()) {
            assertEquals("via proxy", new String(body.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(List.of("http://upstream.example/data?q=1"), seenByProxy);
    }

    @Test
    @DisplayName("max-connections-per-host queues requests beyond the limit instead of opening more connections")
    void poolLimitQueues() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        server.createContext("/slow", exchange -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            byte[] body = "done".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        OutboundHttp http = facade(vertxSettings(1, OutboundSettings.ProxySettings.NONE));
        ExecutorService callers = Executors.newFixedThreadPool(3);
        try {
            List<CompletableFuture<String>> calls = List.of(1, 2, 3).stream()
                    .map(i -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return http.send(OutboundRequest.get(Profile.DEFAULT, local("/slow")).ssrf(SsrfPolicy.NONE).build(),
                                    BodyMode.string(1024, BodyMode.OnOverflow.FAIL)).body();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }, callers))
                    .toList();
            for (CompletableFuture<String> call : calls) {
                assertEquals("done", call.get(10, TimeUnit.SECONDS));
            }
        } finally {
            callers.shutdownNow();
        }
        assertEquals(1, maxInFlight.get(), "one connection per host: the server never saw two requests at once");
    }
}
