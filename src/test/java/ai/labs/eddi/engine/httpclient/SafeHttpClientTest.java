/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

/**
 * Unit tests for {@link SafeHttpClient} — security-critical infrastructure.
 * <p>
 * Tests that need redirect following (too-many-redirects, 307/308 preservation)
 * use a Mockito spy to bypass {@code validateRedirectTarget()} since the
 * embedded server runs on loopback which would be rejected by SSRF validation.
 *
 * @since 6.0.2
 */
class SafeHttpClientTest {

    private HttpServer server;
    private int port;
    private SafeHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        client = new SafeHttpClient(10000);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // --- Redirect SSRF blocking (uses real validation) ---

    @Test
    @DisplayName("send() blocks redirect to 127.0.0.1 (SSRF via 302)")
    void shouldBlockRedirectToLoopback() {
        server.createContext("/redirect-to-loopback", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/redirect-to-loopback"))
                .GET()
                .build();

        // send() doesn't validate the initial URL, but validates redirect targets
        assertThrows(IllegalArgumentException.class,
                () -> client.send(request, HttpResponse.BodyHandlers.ofString()),
                "Redirect to loopback should be blocked by SSRF validation");
    }

    @Test
    @DisplayName("send() blocks redirect to 169.254.169.254 (cloud metadata)")
    void shouldBlockRedirectToCloudMetadata() {
        server.createContext("/redirect-to-metadata", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/redirect-to-metadata"))
                .GET()
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> client.send(request, HttpResponse.BodyHandlers.ofString()),
                "Redirect to cloud metadata should be blocked");
    }

    // --- Redirect mechanics (uses spy to bypass validation for loopback) ---

    @Test
    @DisplayName("send() throws IOException on >5 redirect hops")
    void shouldRejectTooManyRedirects() {
        // Bypass redirect validation so we can test the hop counter
        SafeHttpClient spy = Mockito.spy(client);
        doNothing().when(spy).validateRedirectTarget(anyString());

        for (int i = 0; i < 7; i++) {
            final int next = i + 1;
            server.createContext("/hop" + i, exchange -> {
                exchange.getResponseHeaders().set("Location",
                        "http://127.0.0.1:" + port + "/hop" + next);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
        }
        server.createContext("/hop7", exchange -> {
            byte[] body = "Final".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/hop0"))
                .GET()
                .build();

        IOException ex = assertThrows(IOException.class,
                () -> spy.send(request, HttpResponse.BodyHandlers.ofString()));
        assertTrue(ex.getMessage().contains("Too many redirects"), ex.getMessage());
    }

    @Test
    @DisplayName("send() throws IOException on redirect without Location header")
    void shouldRejectRedirectWithoutLocation() {
        server.createContext("/no-location", exchange -> {
            // 302 without Location header
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/no-location"))
                .GET()
                .build();

        IOException ex = assertThrows(IOException.class,
                () -> client.send(request, HttpResponse.BodyHandlers.ofString()));
        assertTrue(ex.getMessage().contains("without Location header"), ex.getMessage());
    }

    // --- Non-redirect responses ---

    @Test
    @DisplayName("send() returns 200 response directly")
    void shouldReturnOkResponseDirectly() throws Exception {
        server.createContext("/ok", exchange -> {
            byte[] body = "Hello EDDI".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/ok"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("Hello EDDI", response.body());
    }

    @Test
    @DisplayName("send() returns 404 response directly")
    void shouldReturn404ResponseDirectly() throws Exception {
        server.createContext("/not-found", exchange -> {
            byte[] body = "Not Found".getBytes();
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/not-found"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
    }

    // --- sendValidated() — initial URL validation ---

    @Test
    @DisplayName("sendValidated() rejects loopback initial URL")
    void shouldRejectLoopbackOnSendValidated() {
        server.start(); // not actually reached

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/anything"))
                .GET()
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> client.sendValidated(request, HttpResponse.BodyHandlers.ofString()),
                "sendValidated should reject loopback on the initial URL");
    }

    @Test
    @DisplayName("UrlValidationUtils blocks file:// scheme (defense layer used by sendValidated)")
    void shouldRejectFileScheme() {
        // file:// URIs are rejected by both Java's HttpRequest.Builder AND
        // UrlValidationUtils. We test the validation layer directly here.
        assertThrows(IllegalArgumentException.class,
                () -> ai.labs.eddi.modules.llm.tools.UrlValidationUtils.validateUrl("file:///etc/passwd"),
                "file:// scheme should be blocked by URL validation");
    }

    // --- 307/308 method preservation (RFC 7538) ---

    @Test
    @DisplayName("307 redirect preserves POST method and body")
    void shouldPreserveMethodOn307Redirect() throws Exception {
        // Bypass redirect validation for loopback
        SafeHttpClient spy = Mockito.spy(client);
        doNothing().when(spy).validateRedirectTarget(anyString());

        // 307 redirect from /post-here to /final
        server.createContext("/post-here", exchange -> {
            exchange.getResponseHeaders().set("Location",
                    "http://127.0.0.1:" + port + "/final");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/final", exchange -> {
            // Echo back the received method
            String method = exchange.getRequestMethod();
            byte[] body = ("method=" + method).getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/post-here"))
                .POST(HttpRequest.BodyPublishers.ofString("test-body"))
                .build();

        HttpResponse<String> response = spy.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("method=POST", response.body(),
                "307 redirect should preserve POST method");
    }

    @Test
    @DisplayName("302 redirect downgrades POST to GET (RFC 7231)")
    void shouldDowngradeMethodOn302Redirect() throws Exception {
        // Bypass redirect validation for loopback
        SafeHttpClient spy = Mockito.spy(client);
        doNothing().when(spy).validateRedirectTarget(anyString());

        // 302 redirect from /post-redirect to /final-get
        server.createContext("/post-redirect", exchange -> {
            exchange.getResponseHeaders().set("Location",
                    "http://127.0.0.1:" + port + "/final-get");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/final-get", exchange -> {
            String method = exchange.getRequestMethod();
            byte[] body = ("method=" + method).getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/post-redirect"))
                .POST(HttpRequest.BodyPublishers.ofString("test-body"))
                .build();

        HttpResponse<String> response = spy.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("method=GET", response.body(),
                "302 redirect should downgrade POST to GET");
    }
}
