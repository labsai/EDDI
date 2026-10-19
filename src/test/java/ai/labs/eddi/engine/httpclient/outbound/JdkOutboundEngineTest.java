/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JDK engine against a real loopback server: bodies capped mid-stream, slow
 * servers cut off by the deadline, and requests routed through a forward proxy.
 */
class JdkOutboundEngineTest {

    private HttpServer server;
    private ExecutorService serverThreads;
    private int port;

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

    private static OutboundHttp facade(OutboundSettings settings) {
        return new OutboundHttp(settings, new JdkOutboundEngine(settings, JdkOutboundEngine.TlsContexts.NONE, UnaryOperator.identity()),
                false, new SimpleMeterRegistry());
    }

    private URI local(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    @Test
    @DisplayName("a chunked body with no Content-Length fails mid-stream at the cap, and the download stops")
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

        OutboundHttp facade = facade(OutboundSettings.defaults(Duration.ofSeconds(10)));
        assertThrows(BodyTooLargeException.class, () -> facade.send(
                OutboundRequest.get(Profile.DEFAULT, local("/big")).ssrf(SsrfPolicy.NONE).build(),
                BodyMode.bytes(256 * 1024, BodyMode.OnOverflow.FAIL)));
        assertTrue(cutOff.await(5, TimeUnit.SECONDS), "the 10 MiB body must not be read to the end");
    }

    @Test
    @DisplayName("a server that never answers is cut off by the hop timeout or the total budget, whichever is first")
    void silentServerHitsDeadline() {
        server.createContext("/silent", exchange -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();

        OutboundHttp facade = facade(OutboundSettings.defaults(Duration.ofMillis(200)));
        long start = System.nanoTime();
        HttpTimeoutException e = assertThrows(HttpTimeoutException.class, () -> facade.send(
                OutboundRequest.get(Profile.DEFAULT, URI.create("http://127.0.0.1:" + port + "/silent?key=s3cret"))
                        .ssrf(SsrfPolicy.NONE).timeout(Duration.ofMillis(300)).build(),
                BodyMode.string(1024, BodyMode.OnOverflow.FAIL)));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(elapsedMs < 3000, "took " + elapsedMs + "ms");
        assertFalse(e.getMessage().contains("s3cret"), e.getMessage());
    }

    @Test
    @DisplayName("with a proxy configured, requests go to the proxy with the absolute target URI")
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

        OutboundSettings.ProfileSettings profile = new OutboundSettings.ProfileSettings(Duration.ofSeconds(5), null,
                OutboundSettings.Protocol.HTTP1, null);
        Map<Profile, OutboundSettings.ProfileSettings> profiles = new EnumMap<>(Profile.class);
        for (Profile p : Profile.values()) {
            profiles.put(p, profile);
        }
        OutboundSettings settings = new OutboundSettings(OutboundSettings.JDK_ENGINE, profiles,
                new OutboundSettings.ProxySettings("127.0.0.1", port, List.of("localhost"), null));

        OutboundResponse<String> response = facade(settings).send(
                OutboundRequest.get(Profile.DEFAULT, URI.create("http://upstream.example/data?q=1")).ssrf(SsrfPolicy.NONE).build(),
                BodyMode.string(1024, BodyMode.OnOverflow.FAIL));

        assertEquals("via proxy", response.body());
        assertEquals(List.of("http://upstream.example/data?q=1"), seenByProxy);
    }

    @Test
    @DisplayName("non-proxy-hosts patterns match exactly or with a leading/trailing wildcard")
    void nonProxyHosts() {
        var selector = new JdkOutboundEngine.NonProxyHostsSelector(new InetSocketAddress("127.0.0.1", 3128),
                List.of("localhost", "*.internal", "10.*"));

        assertTrue(selector.bypasses("localhost"));
        assertTrue(selector.bypasses("svc.internal"));
        assertTrue(selector.bypasses("10.1.2.3"));
        assertFalse(selector.bypasses("example.com"));
        assertFalse(selector.bypasses("internal.example.com"));
    }

    @Test
    @DisplayName("an unresolved vault reference in the proxy credentials fails closed")
    void unresolvedProxyCredentials() {
        OutboundSettings.ProfileSettings profile = new OutboundSettings.ProfileSettings(Duration.ofSeconds(5), null,
                OutboundSettings.Protocol.AUTO, null);
        Map<Profile, OutboundSettings.ProfileSettings> profiles = new EnumMap<>(Profile.class);
        for (Profile p : Profile.values()) {
            profiles.put(p, profile);
        }
        OutboundSettings settings = new OutboundSettings(OutboundSettings.JDK_ENGINE, profiles,
                new OutboundSettings.ProxySettings("127.0.0.1", 3128, List.of(), "${vault:proxy-creds}"));
        JdkOutboundEngine engine = new JdkOutboundEngine(settings, JdkOutboundEngine.TlsContexts.NONE, UnaryOperator.identity());

        assertThrows(IllegalStateException.class, () -> engine.client(Profile.DEFAULT));
    }
}
