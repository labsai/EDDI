/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

import ai.labs.eddi.engine.httpclient.BoundedBodyHandlers.ResponseTooLargeException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

/**
 * What {@link SafeHttpClient} does about response <em>bodies</em>: the
 * wall-clock deadline reaches the body read, a bounded handler's refusal
 * surfaces as its own exception type, and a redirect's body is never handed to
 * the caller's handler.
 * <p>
 * Server-backed, on loopback, like {@code SafeHttpClientTest}.
 */
@DisplayName("SafeHttpClient — response bodies")
class SafeHttpClientBodyBoundsTest {

    private HttpServer server;
    private ExecutorService serverThreads;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // One thread per exchange: the trickling handler must not block the others.
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    @Test
    @DisplayName("a body trickled past the deadline fails the call instead of holding it open")
    void trickledBodyHitsTheDeadline() {
        // Headers at once, then one byte every 200ms for 6 seconds. The per-request
        // timeout stops counting when the headers arrive, so before the deadline
        // covered the body this call simply waited the six seconds out.
        server.createContext("/trickle", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 30; i++) {
                    out.write('x');
                    out.flush();
                    Thread.sleep(200);
                }
            } catch (IOException | InterruptedException e) {
                // The client gave up — which is the point.
            }
        });
        server.start();

        // connect timeout 200ms → budget floor 600ms; the 300ms request timeout is
        // below it.
        SafeHttpClient client = new SafeHttpClient(200);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/trickle"))
                .timeout(Duration.ofMillis(300)).GET().build();

        long start = System.nanoTime();
        assertThrows(HttpTimeoutException.class, () -> client.send(request, HttpResponse.BodyHandlers.ofString()));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertTrue(elapsedMs < 3000, "the call must end near its 600ms budget, not when the server finishes; took " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("sendNoRedirect is bounded by the same deadline")
    void noRedirectPathHasTheDeadlineToo() {
        server.createContext("/trickle", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 30; i++) {
                    out.write('x');
                    out.flush();
                    Thread.sleep(200);
                }
            } catch (IOException | InterruptedException e) {
                // expected
            }
        });
        server.start();

        SafeHttpClient client = new SafeHttpClient(200);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/trickle"))
                .timeout(Duration.ofMillis(300)).GET().build();

        assertThrows(HttpTimeoutException.class, () -> client.sendNoRedirect(request, HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    @DisplayName("a bounded handler's refusal reaches the caller as ResponseTooLargeException")
    void oversizedBodyIsRefusedByType() {
        server.createContext("/big", exchange -> {
            // Chunked, so there is no Content-Length to refuse on: the limit has to
            // trip mid-stream.
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[64 * 1024];
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 160; i++) {
                    out.write(chunk);
                }
            } catch (IOException e) {
                // The client cancelled — expected.
            }
        });
        server.start();

        SafeHttpClient client = new SafeHttpClient(10_000);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/big")).GET().build();

        ResponseTooLargeException e = assertThrows(ResponseTooLargeException.class,
                () -> client.send(request, BoundedBodyHandlers.ofString(256 * 1024)));
        assertEquals(256 * 1024, e.getLimit());
    }

    @Test
    @DisplayName("a redirect's body is discarded, never handed to the caller's handler")
    void redirectBodiesNeverReachTheCallersHandler() throws Exception {
        server.createContext("/moved", exchange -> {
            byte[] body = "redirect body nobody reads".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + port + "/final");
            exchange.sendResponseHeaders(302, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/final", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        SafeHttpClient spy = Mockito.spy(new SafeHttpClient(10_000));
        doNothing().when(spy).validateRedirectTarget(anyString());

        // An ofInputStream caller used to receive an InputStream for the 302 as
        // well, which it never saw and so never closed.
        List<Integer> handedToCaller = new CopyOnWriteArrayList<>();
        HttpResponse.BodyHandler<String> recording = info -> {
            handedToCaller.add(info.statusCode());
            return HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        };

        HttpResponse<String> response = spy.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/moved")).GET().build(), recording);

        assertEquals(200, response.statusCode());
        assertEquals("ok", response.body());
        assertEquals(List.of(200), handedToCaller);
    }

    @Test
    @DisplayName("the budget is three connect timeouts, or the request's own timeout when longer")
    void totalBudget() {
        SafeHttpClient client = new SafeHttpClient(10_000);
        URI uri = URI.create("https://example.com/");

        assertEquals(Duration.ofSeconds(30), client.totalBudget(HttpRequest.newBuilder(uri).build()));
        assertEquals(Duration.ofSeconds(30), client.totalBudget(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build()));
        // A caller that asked for a long timeout (an A2A peer configured for 120s)
        // must not be cut short by the default budget.
        assertEquals(Duration.ofSeconds(120), client.totalBudget(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(120)).build()));
    }
}
