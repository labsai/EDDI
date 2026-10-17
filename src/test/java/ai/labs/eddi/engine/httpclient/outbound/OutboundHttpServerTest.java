/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OutboundHttp} on the real JDK engine against a loopback server: the
 * redirect, SSRF, deadline and body guarantees end to end, over real sockets.
 * <p>
 * Carried over from {@code SafeHttpClientTest} and
 * {@code SafeHttpClientBodyBoundsTest} when that adapter was retired. Where
 * those tests used a Mockito spy to let a redirect reach loopback, these pass a
 * no-op {@link OutboundRequest#hopValidator()} — the same switch, now part of
 * the API.
 */
class OutboundHttpServerTest {

    /** Lets a redirect hop reach the loopback test server. */
    private static final TargetValidator ALLOW_LOOPBACK_HOPS = url -> {
    };

    private HttpServer server;
    private ExecutorService serverThreads;
    private int port;
    private final OutboundHttp http = OutboundHttp.standalone(Duration.ofSeconds(10));

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // One thread per exchange: a trickling handler must not block the others.
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    private URI local(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    /**
     * A request whose initial URL is trusted and whose redirect hops are checked.
     */
    private OutboundRequest.Builder hopsChecked(String method, String path) {
        return OutboundRequest.builder(Profile.DEFAULT, local(path)).method(method).ssrf(SsrfPolicy.HOPS_ONLY);
    }

    /** A request free to reach loopback on every hop. */
    private OutboundRequest.Builder loopback(String method, String path) {
        return hopsChecked(method, path).hopValidator(ALLOW_LOOPBACK_HOPS);
    }

    private static BodyMode<String> text() {
        return BodyMode.string(1024 * 1024, BodyMode.OnOverflow.FAIL);
    }

    private void redirect(String path, int status, String location) {
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().set("Location", location);
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    /** Answers with the method and body it received. */
    private void echo(String path) {
        server.createContext(path, exchange -> {
            byte[] bytes = (exchange.getRequestMethod() + ":" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    // --- SSRF on redirect hops (real validation) ---

    @Test
    @DisplayName("a redirect to 127.0.0.1 is refused (SSRF via 302)")
    void redirectToLoopbackRefused() {
        redirect("/to-loopback", 302, "http://127.0.0.1:1/secret");
        server.start();

        assertThrows(OutboundBlockedException.class, () -> http.send(hopsChecked("GET", "/to-loopback").build(), text()));
    }

    @Test
    @DisplayName("a redirect to the cloud metadata service is refused")
    void redirectToMetadataRefused() {
        redirect("/to-metadata", 302, "http://169.254.169.254/latest/meta-data/");
        server.start();

        assertThrows(OutboundBlockedException.class, () -> http.send(hopsChecked("GET", "/to-metadata").build(), text()));
    }

    @Test
    @DisplayName("STRICT refuses a loopback initial URL")
    void strictRefusesLoopback() {
        server.start();

        assertThrows(OutboundBlockedException.class,
                () -> http.send(OutboundRequest.get(Profile.DEFAULT, local("/anything")).build(), text()));
    }

    // --- Redirect mechanics ---

    @Test
    @DisplayName("more than 5 hops fail with an IOException")
    void tooManyRedirects() {
        for (int i = 0; i < 7; i++) {
            redirect("/hop" + i, 302, "http://127.0.0.1:" + port + "/hop" + (i + 1));
        }
        respond("/hop7", 200, "Final");
        server.start();

        IOException e = assertThrows(IOException.class, () -> http.send(loopback("GET", "/hop0").build(), text()));
        assertTrue(e.getMessage().contains("Too many redirects"), e.getMessage());
    }

    /**
     * HEAD must stay HEAD across every redirect code: rewriting it makes the second
     * hop download the whole body — the opposite of why a caller chose HEAD.
     */
    @Test
    @DisplayName("HEAD stays HEAD across a 302")
    void headSurvives302() throws Exception {
        AtomicReference<String> methodSeen = new AtomicReference<>();
        redirect("/head-redirect", 302, "http://127.0.0.1:" + port + "/head-target");
        server.createContext("/head-target", exchange -> {
            methodSeen.set(exchange.getRequestMethod());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        OutboundResponse<Void> response = http.send(loopback("HEAD", "/head-redirect").build(), BodyMode.discard());

        assertEquals(200, response.status());
        assertEquals("HEAD", methodSeen.get());
    }

    @Test
    @DisplayName("POST becomes GET across a 302 (RFC 9110 §15.4.3)")
    void postBecomesGetOn302() throws Exception {
        redirect("/post-redirect", 302, "http://127.0.0.1:" + port + "/echo");
        echo("/echo");
        server.start();

        OutboundResponse<String> response = http.send(
                loopback("POST", "/post-redirect").method("POST", RequestBody.ofString("payload")).build(), text());

        assertEquals("GET:", response.body());
    }

    @Test
    @DisplayName("307 keeps POST and its body")
    void postSurvives307() throws Exception {
        redirect("/post-here", 307, "http://127.0.0.1:" + port + "/echo");
        echo("/echo");
        server.start();

        OutboundResponse<String> response = http.send(
                loopback("POST", "/post-here").method("POST", RequestBody.ofString("test-body")).build(), text());

        assertEquals("POST:test-body", response.body());
    }

    /**
     * A redirected PUT rewritten to GET hands the caller a 200 for a write that
     * never happened.
     */
    @Test
    @DisplayName("PUT and its body survive a 302")
    void putSurvives302() throws Exception {
        redirect("/put-here", 302, "http://127.0.0.1:" + port + "/echo");
        echo("/echo");
        server.start();

        OutboundResponse<String> response = http.send(
                loopback("PUT", "/put-here").method("PUT", RequestBody.ofString("payload")).build(), text());

        assertEquals("PUT:payload", response.body());
    }

    @Test
    @DisplayName("a 3xx without Location fails")
    void redirectWithoutLocation() {
        server.createContext("/no-location", exchange -> {
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();

        IOException e = assertThrows(IOException.class, () -> http.send(loopback("GET", "/no-location").build(), text()));
        assertTrue(e.getMessage().contains("without Location header"), e.getMessage());
    }

    @Test
    @DisplayName("a cross-origin hop loses Authorization; the same origin keeps it")
    void authorizationStrippedCrossOrigin() throws Exception {
        AtomicReference<String> sameOrigin = new AtomicReference<>();
        AtomicReference<String> crossOrigin = new AtomicReference<>();
        // localhost and 127.0.0.1 are the same socket but different origins.
        redirect("/start", 302, "http://127.0.0.1:" + port + "/same");
        server.createContext("/same", exchange -> {
            sameOrigin.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Location", "http://localhost:" + port + "/other");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/other", exchange -> {
            crossOrigin.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        http.send(loopback("GET", "/start").header("Authorization", "Bearer s3cret").build(), BodyMode.discard());

        assertEquals("Bearer s3cret", sameOrigin.get());
        assertEquals("null", crossOrigin.get(), "the credential must not reach another origin");
    }

    // --- Responses that are not redirects ---

    @Test
    @DisplayName("a 200 and a 404 come back as they are")
    void plainResponses() throws Exception {
        respond("/ok", 200, "Hello EDDI");
        respond("/missing", 404, "Not Found");
        server.start();

        OutboundResponse<String> ok = http.send(loopback("GET", "/ok").build(), text());
        assertEquals(200, ok.status());
        assertEquals("Hello EDDI", ok.body());
        assertEquals(404, http.send(loopback("GET", "/missing").build(), text()).status());
    }

    // --- No redirect at all (an OAuth token request must never follow a hop) ---

    @Test
    @DisplayName("NEVER hands a 307 back instead of re-sending the body to its Location")
    void neverReturnsTheRedirect() throws Exception {
        AtomicReference<String> secondHop = new AtomicReference<>();
        redirect("/token", 307, "http://127.0.0.1:" + port + "/elsewhere");
        server.createContext("/elsewhere", exchange -> {
            secondHop.set(exchange.getRequestMethod());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        OutboundResponse<String> response = http.send(OutboundRequest.builder(Profile.DEFAULT, local("/token"))
                .ssrf(SsrfPolicy.NONE)
                .redirects(RedirectPolicy.NEVER)
                .header("Authorization", "Basic Y2xpZW50OnNlY3JldA==")
                .method("POST", RequestBody.ofString("grant_type=refresh_token&refresh_token=rt"))
                .build(), text());

        assertEquals(307, response.status());
        assertTrue(response.firstHeader("Location").isPresent());
        assertNull(secondHop.get(), "exactly one request must be made");
    }

    @Test
    @DisplayName("NEVER still keeps the 3xx body for the caller")
    void neverKeepsTheRedirectBody() throws Exception {
        server.createContext("/moved", exchange -> {
            byte[] body = "see elsewhere".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + port + "/final");
            exchange.sendResponseHeaders(302, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        OutboundResponse<String> response = http.send(loopback("GET", "/moved").redirects(RedirectPolicy.NEVER).build(), text());

        assertEquals(302, response.status());
        assertEquals("see elsewhere", response.body());
    }

    @Test
    @DisplayName("NEVER does not follow a self-referential redirect even once")
    void neverMakesOneRequest() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/token", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + port + "/token");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();

        OutboundResponse<String> response = http.send(OutboundRequest.builder(Profile.DEFAULT, local("/token"))
                .ssrf(SsrfPolicy.NONE).redirects(RedirectPolicy.NEVER)
                .method("POST", RequestBody.ofString("grant_type=client_credentials")).build(), text());

        assertEquals(302, response.status());
        assertEquals(1, hits.get());
    }

    // --- Time and bodies ---

    @Test
    @DisplayName("a body trickled past the read budget fails instead of holding the call open, without leaking the query")
    void trickledBodyHitsTheBudget() {
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

        long start = System.nanoTime();
        HttpTimeoutException e = assertThrows(HttpTimeoutException.class, () -> http.send(
                OutboundRequest.get(Profile.DEFAULT, URI.create("http://127.0.0.1:" + port + "/trickle?key=s3cret"))
                        .ssrf(SsrfPolicy.NONE).timeout(Duration.ofMillis(300)).build(),
                text()));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // 4 × the 300ms hop timeout, not the six seconds the server would take.
        assertTrue(elapsedMs < 4000, "took " + elapsedMs + "ms");
        assertFalse(e.getMessage().contains("s3cret"), e.getMessage());
    }

    @Test
    @DisplayName("a redirect's body never reaches the caller, and an oversized one is cut off")
    void oversizedRedirectBodyIsDropped() throws Exception {
        CountDownLatch cutOff = new CountDownLatch(1);
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
                cutOff.countDown();
            }
        });
        respond("/final", 200, "ok");
        server.start();

        // The caller's cap is far below the redirect body and must not apply to it.
        OutboundResponse<String> response = http.send(loopback("GET", "/moved").build(),
                BodyMode.string(16, BodyMode.OnOverflow.FAIL));

        assertEquals(200, response.status());
        assertEquals("ok", response.body());
        assertEquals(List.of(local("/moved")), response.redirectChain());
        assertTrue(cutOff.await(5, TimeUnit.SECONDS), "the redirect body must not be read to the end");
    }

    @Test
    @DisplayName("a transport failure keeps its type and carries the caller's frames")
    void transportFailureKeepsTypeAndFrames() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        server.start();

        IOException e = assertThrows(IOException.class, () -> http.send(OutboundRequest
                .get(Profile.DEFAULT, URI.create("http://127.0.0.1:" + closedPort + "/")).ssrf(SsrfPolicy.NONE).build(), text()));

        boolean callerFramesAttached = Arrays.stream(e.getSuppressed())
                .filter(CallerFrames.class::isInstance)
                .flatMap(s -> Arrays.stream(s.getStackTrace()))
                .anyMatch(frame -> frame.getClassName().startsWith(OutboundHttpServerTest.class.getName()));
        assertTrue(callerFramesAttached, "the caller's stack frames must be attached: " + e);
    }
}
