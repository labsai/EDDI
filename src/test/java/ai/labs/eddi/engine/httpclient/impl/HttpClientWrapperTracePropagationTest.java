/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.impl;

import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.tracing.TracePropagation;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W3C trace context on the httpcalls client: sent, never written into the
 * request record ({@code toMap()} feeds conversation memory and the approval
 * fingerprint), and never replacing an author-configured header.
 */
@DisplayName("HttpClientWrapper trace propagation")
class HttpClientWrapperTracePropagationTest {

    private Vertx vertx;
    private WebClient webClient;
    private SdkTracerProvider tracerProvider;
    private Span span;
    private Scope scope;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        webClient = WebClient.create(vertx);
        tracerProvider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create())).build();
        span = tracerProvider.get("test").spanBuilder("execute_tool getWeather").startSpan();
        scope = span.makeCurrent();
    }

    @AfterEach
    void tearDown() {
        scope.close();
        span.end();
        tracerProvider.close();
        webClient.close();
        vertx.close();
    }

    private String expectedTraceparent() {
        return "00-" + span.getSpanContext().getTraceId() + "-" + span.getSpanContext().getSpanId() + "-"
                + span.getSpanContext().getTraceFlags().asHex();
    }

    @Test
    @DisplayName("the header goes on a copy; the original request is untouched")
    void addsToCopyOnly() {
        HttpRequest<Buffer> original = webClient.requestAbs(HttpMethod.GET, "https://api.example.com/weather");

        HttpRequest<Buffer> traced = HttpClientWrapper.withTraceContext(original, new TracePropagation(true));

        assertNotSame(original, traced);
        assertEquals(expectedTraceparent(), traced.headers().get("traceparent"));
        assertNull(original.headers().get("traceparent"));
    }

    @Test
    @DisplayName("an author-configured traceparent is never overwritten")
    void authorHeaderWins() {
        String own = "00-11111111111111111111111111111111-2222222222222222-01";
        HttpRequest<Buffer> original = webClient.requestAbs(HttpMethod.GET, "https://api.example.com/weather").putHeader("TraceParent", own);

        HttpRequest<Buffer> traced = HttpClientWrapper.withTraceContext(original, new TracePropagation(true));

        assertSame(original, traced);
        assertEquals(own, traced.headers().get("traceparent"));
    }

    @Test
    @DisplayName("off or absent propagation leaves the request alone")
    void offOrAbsent() {
        HttpRequest<Buffer> original = webClient.requestAbs(HttpMethod.GET, "https://api.example.com/weather");
        assertSame(original, HttpClientWrapper.withTraceContext(original, new TracePropagation(false)));
        assertSame(original, HttpClientWrapper.withTraceContext(original, null));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("send() hands the traced copy to the transport, and toMap() does not record the header")
    void sendWiresTracedCopy() throws Exception {
        VertxHttpClient vertxHttpClient = mock(VertxHttpClient.class);
        when(vertxHttpClient.getWebClient()).thenReturn(webClient);
        HttpClientWrapper wrapper = spy(new HttpClientWrapper(vertxHttpClient, "eddi", "1.0"));
        wrapper.tracePropagation = new TracePropagation(true);
        doNothing().when(wrapper).executePipedSend(any(), any(), any(), any());

        IRequest request = wrapper.newRequest(URI.create("https://api.example.com/weather"), IHttpClient.Method.GET);
        request.send(response -> {
        });

        ArgumentCaptor<HttpRequest<Buffer>> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(wrapper).executePipedSend(sent.capture(), any(), any(), any());
        assertEquals(expectedTraceparent(), sent.getValue().headers().get("traceparent"));

        Map<String, String> recordedHeaders = (Map<String, String>) request.toMap().get(IRequest.KEY_HEADERS);
        assertFalse(recordedHeaders.keySet().stream().anyMatch(TracePropagation::isTraceHeader),
                "the memory record / approval fingerprint must not change per send");
    }
}
