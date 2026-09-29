/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

/**
 * What {@link SafeHttpClient} does about response <em>bodies</em>: the
 * wall-clock deadline reaches the body read, a handler's own failure surfaces
 * with its own exception type, and a redirect's body is never handed to the
 * caller's handler.
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

    private void trickle(String path) {
        // Headers at once, then one byte every 200ms for 6 seconds.
        server.createContext(path, exchange -> {
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
    }

    @Test
    @DisplayName("a body trickled past the deadline fails the call instead of holding it open")
    void trickledBodyHitsTheDeadline() {
        // The per-request timeout stops counting when the headers arrive, so before
        // the deadline covered the body this call simply waited the six seconds out.
        trickle("/trickle");
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
        trickle("/trickle");
        server.start();

        SafeHttpClient client = new SafeHttpClient(200);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/trickle"))
                .timeout(Duration.ofMillis(300)).GET().build();

        assertThrows(HttpTimeoutException.class, () -> client.sendNoRedirect(request, HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    @DisplayName("a handler's own IOException reaches the caller by type, with the caller's frames attached")
    void handlerFailureKeepsItsType() {
        server.createContext("/big", exchange -> {
            // Chunked, so the handler has to trip mid-stream.
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

        TooLarge e = assertThrows(TooLarge.class, () -> client.send(request, refusingAbove(256 * 1024)));
        // Created on the client's executor: the caller's own frames ride along as a
        // suppressed exception, so a log shows who made the call.
        boolean callerFramesAttached = Arrays.stream(e.getSuppressed())
                .filter(SafeHttpClient.CallerFrames.class::isInstance)
                .flatMap(s -> Arrays.stream(s.getStackTrace()))
                .anyMatch(frame -> frame.getClassName().startsWith(SafeHttpClientBodyBoundsTest.class.getName()));
        assertTrue(callerFramesAttached, "the caller's stack frames must be attached to the rethrown exception");
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
        finalOk();
        server.start();

        SafeHttpClient spy = Mockito.spy(new SafeHttpClient(10_000));
        doNothing().when(spy).validateRedirectTarget(anyString());

        // An ofInputStream caller (every send*Bounded path) used to receive an
        // InputStream for the 302 as well, which it never saw and so never closed.
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
    @DisplayName("sendNoRedirect still hands the 3xx body to the caller — the caller decides what a redirect means")
    void noRedirectKeepsTheRedirectBody() throws Exception {
        server.createContext("/moved", exchange -> {
            byte[] body = "see elsewhere".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + port + "/final");
            exchange.sendResponseHeaders(302, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        HttpResponse<String> response = new SafeHttpClient(10_000).sendNoRedirect(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/moved")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(302, response.statusCode());
        assertEquals("see elsewhere", response.body());
    }

    @Test
    @DisplayName("an oversized redirect body is dropped after a bounded read and the redirect still completes")
    void oversizedRedirectBodyIsDroppedNotDownloaded() throws Exception {
        CountDownLatch redirectBodyCutOff = new CountDownLatch(1);
        server.createContext("/moved", exchange -> {
            // Chunked, so the discard has to trip mid-stream: 10 MiB, far past its cap.
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + port + "/final");
            exchange.sendResponseHeaders(302, 0);
            byte[] chunk = new byte[64 * 1024];
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 160; i++) {
                    out.write(chunk);
                }
            } catch (IOException e) {
                redirectBodyCutOff.countDown();
            }
        });
        finalOk();
        server.start();

        SafeHttpClient spy = Mockito.spy(new SafeHttpClient(10_000));
        doNothing().when(spy).validateRedirectTarget(anyString());

        // The caller's bound is far below the redirect body, and must not apply to it.
        HttpResponse<String> response = spy.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/moved")).GET().build(), refusingAbove(16));

        assertEquals(200, response.statusCode());
        assertEquals("ok", response.body());
        // The server's write fails once the client drops the connection; give its
        // handler thread a moment to observe that.
        assertTrue(redirectBodyCutOff.await(5, TimeUnit.SECONDS), "the redirect body must not be read to the end");
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

    @Test
    @DisplayName("discard: a body under the limit is drained and completes with null")
    void discardDrainsSmallBody() {
        var cancelled = new boolean[1];
        var requested = new long[1];
        var subscriber = new SafeHttpClient.DiscardingSubscriber<String>(10, OptionalLong.empty());
        subscriber.onSubscribe(subscription(requested, cancelled));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[6])));
        subscriber.onComplete();

        assertEquals(Long.MAX_VALUE, requested[0]);
        assertFalse(cancelled[0]);
        assertNull(subscriber.getBody().toCompletableFuture().join());
    }

    @Test
    @DisplayName("discard: past the limit the connection is dropped and the body still completes normally")
    void discardStopsPastLimitWithoutFailing() {
        var cancelled = new boolean[1];
        var subscriber = new SafeHttpClient.DiscardingSubscriber<String>(10, OptionalLong.empty());
        subscriber.onSubscribe(subscription(new long[1], cancelled));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[6]), ByteBuffer.wrap(new byte[6])));

        assertTrue(cancelled[0]);
        assertNull(subscriber.getBody().toCompletableFuture().join());
    }

    @Test
    @DisplayName("discard: a declared Content-Length above the limit reads nothing and completes normally")
    void discardRefusesOversizedDeclaredLength() {
        var cancelled = new boolean[1];
        var requested = new long[1];
        var subscriber = new SafeHttpClient.DiscardingSubscriber<String>(10, OptionalLong.of(11));
        subscriber.onSubscribe(subscription(requested, cancelled));

        assertEquals(0, requested[0]);
        assertTrue(cancelled[0]);
        assertNull(subscriber.getBody().toCompletableFuture().join());
    }

    private void finalOk() {
        server.createContext("/final", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    private static Flow.Subscription subscription(long[] requested, boolean[] cancelled) {
        return new Flow.Subscription() {
            @Override
            public void request(long n) {
                requested[0] = n;
            }

            @Override
            public void cancel() {
                cancelled[0] = true;
            }
        };
    }

    /** A handler's own failure type, which must reach the caller unchanged. */
    static final class TooLarge extends IOException {
        TooLarge() {
            super("body over the test limit");
        }
    }

    /**
     * A string handler that fails with {@link TooLarge} once more than
     * {@code limit} bytes arrive, cancelling the exchange.
     */
    private static HttpResponse.BodyHandler<String> refusingAbove(long limit) {
        return info -> new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<String> result = new CompletableFuture<>();
            private final StringBuilder received = new StringBuilder();
            private Flow.Subscription subscription;
            private long total;

            @Override
            public CompletionStage<String> getBody() {
                return result;
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(List<ByteBuffer> items) {
                for (ByteBuffer item : items) {
                    total += item.remaining();
                    received.append(StandardCharsets.UTF_8.decode(item));
                }
                if (total > limit && !result.isDone()) {
                    result.completeExceptionally(new TooLarge());
                    subscription.cancel();
                }
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(received.toString());
            }
        };
    }
}
