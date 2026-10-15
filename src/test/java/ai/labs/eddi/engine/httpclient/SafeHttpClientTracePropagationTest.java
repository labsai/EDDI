/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

import ai.labs.eddi.engine.tracing.TracePropagation;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

/**
 * W3C trace context on {@link SafeHttpClient}: added to the first hop, kept on
 * a same-origin redirect, never carried onto a cross-origin one, and never
 * replacing a header the caller set.
 */
@DisplayName("SafeHttpClient trace propagation")
class SafeHttpClientTracePropagationTest {

    private SdkTracerProvider tracerProvider;
    private Span span;
    private Scope scope;
    private HttpServer origin;
    private HttpServer other;

    @BeforeEach
    void setUp() throws IOException {
        tracerProvider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create())).build();
        span = tracerProvider.get("test").spanBuilder("execute_tool fetch").startSpan();
        scope = span.makeCurrent();
        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void tearDown() {
        scope.close();
        span.end();
        tracerProvider.close();
        origin.stop(0);
        other.stop(0);
    }

    private String expectedTraceparent() {
        return "00-" + span.getSpanContext().getTraceId() + "-" + span.getSpanContext().getSpanId() + "-"
                + span.getSpanContext().getTraceFlags().asHex();
    }

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
    }

    private SafeHttpClient tracingClient() {
        SafeHttpClient client = new SafeHttpClient(10_000);
        client.tracePropagation = new TracePropagation(true);
        SafeHttpClient spy = Mockito.spy(client);
        doNothing().when(spy).validateRedirectTarget(anyString());
        return spy;
    }

    @Test
    @DisplayName("withTraceContext adds the current span's traceparent")
    void addsHeader() {
        HttpRequest traced = SafeHttpClient.withTraceContext(get("https://api.example.com/x"), new TracePropagation(true));
        assertEquals(expectedTraceparent(), traced.headers().firstValue("traceparent").orElse(null));
    }

    @Test
    @DisplayName("withTraceContext never overwrites a caller-set traceparent")
    void keepsCallerHeader() {
        String own = "00-11111111111111111111111111111111-2222222222222222-01";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.example.com/x")).header("TraceParent", own).GET().build();

        HttpRequest traced = SafeHttpClient.withTraceContext(request, new TracePropagation(true));

        assertSame(request, traced);
        assertEquals(own, traced.headers().firstValue("traceparent").orElse(null));
    }

    @Test
    @DisplayName("withTraceContext is a no-op when propagation is off or absent")
    void offOrAbsent() {
        HttpRequest request = get("https://api.example.com/x");
        assertSame(request, SafeHttpClient.withTraceContext(request, new TracePropagation(false)));
        assertSame(request, SafeHttpClient.withTraceContext(request, null));
    }

    @Test
    @DisplayName("the header reaches the server and survives a same-origin redirect")
    void sameOriginRedirectKeepsHeader() throws Exception {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        int port = origin.getAddress().getPort();
        origin.createContext("/start", exchange -> {
            first.set(exchange.getRequestHeaders().getFirst("traceparent"));
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + port + "/end");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        origin.createContext("/end", exchange -> {
            second.set(exchange.getRequestHeaders().getFirst("traceparent"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        origin.start();

        HttpResponse<Void> response = tracingClient().send(get("http://127.0.0.1:" + port + "/start"), HttpResponse.BodyHandlers.discarding());

        assertEquals(200, response.statusCode());
        assertEquals(expectedTraceparent(), first.get());
        assertEquals(expectedTraceparent(), second.get());
    }

    @Test
    @DisplayName("a cross-origin redirect hop carries no trace header")
    void crossOriginRedirectDropsHeader() throws Exception {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>("not called");
        int otherPort = other.getAddress().getPort();
        origin.createContext("/start", exchange -> {
            first.set(exchange.getRequestHeaders().getFirst("traceparent"));
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + otherPort + "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        other.createContext("/elsewhere", exchange -> {
            second.set(exchange.getRequestHeaders().getFirst("traceparent"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        origin.start();
        other.start();

        HttpResponse<Void> response = tracingClient().send(get("http://127.0.0.1:" + origin.getAddress().getPort() + "/start"),
                HttpResponse.BodyHandlers.discarding());

        assertEquals(200, response.statusCode());
        assertEquals(expectedTraceparent(), first.get());
        assertNull(second.get(), "a different port is a different origin: no traceparent on that hop");
    }
}
