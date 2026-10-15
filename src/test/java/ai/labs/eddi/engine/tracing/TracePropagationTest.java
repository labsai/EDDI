/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TracePropagation — W3C trace context on outbound calls")
class TracePropagationTest {

    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        tracerProvider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create())).build();
        tracer = tracerProvider.get("test");
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    @DisplayName("an active span yields a traceparent naming exactly that span")
    void activeSpanYieldsTraceparent() {
        Span span = tracer.spanBuilder("execute_tool x").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            Map<String, String> headers = new TracePropagation(true).currentHeaders();

            SpanContext sc = span.getSpanContext();
            assertEquals("00-" + sc.getTraceId() + "-" + sc.getSpanId() + "-" + sc.getTraceFlags().asHex(), headers.get("traceparent"));
        } finally {
            span.end();
        }
    }

    @Test
    @DisplayName("nothing is produced when propagation is switched off")
    void disabledProducesNothing() {
        Span span = tracer.spanBuilder("s").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            assertTrue(new TracePropagation(false).currentHeaders().isEmpty());
            assertTrue(TracePropagation.disabled().currentHeaders().isEmpty());
        } finally {
            span.end();
        }
    }

    @Test
    @DisplayName("no span, or the no-op span the disabled SDK hands out, never yields an all-zero traceparent")
    void invalidSpanContextProducesNothing() {
        var propagation = new TracePropagation(true);
        assertTrue(propagation.headersFor(Context.root()).isEmpty());
        assertTrue(propagation.headersFor(null).isEmpty());

        Span noop = OpenTelemetry.noop().getTracer("x").spanBuilder("s").startSpan();
        try (Scope ignored = noop.makeCurrent()) {
            assertTrue(propagation.currentHeaders().isEmpty(), "OTel SDK disabled ⇒ invalid span context ⇒ no header");
        }
    }

    @Test
    @DisplayName("baggage is never sent, tracestate is")
    void baggageNeverSent() {
        SpanContext remote = SpanContext.createFromRemoteParent("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", TraceFlags.getSampled(),
                TraceState.builder().put("vendor", "v1").build());
        Context context = Context.root().with(Span.wrap(remote)).with(Baggage.builder().put("user.email", "a@example.com").build());

        Map<String, String> headers = new TracePropagation(true).headersFor(context);

        assertEquals("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", headers.get("traceparent"));
        assertEquals("vendor=v1", headers.get("tracestate"));
        assertFalse(headers.containsKey("baggage"));
        assertEquals(2, headers.size());
    }

    @Test
    @DisplayName("a request that already carries a trace header gets neither header")
    void authorHeaderWins() {
        Span span = tracer.spanBuilder("s").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            var propagation = new TracePropagation(true);
            assertTrue(propagation.headersMissingFrom("traceparent"::equals).isEmpty());
            assertTrue(propagation.headersMissingFrom("tracestate"::equals).isEmpty());
            assertFalse(propagation.headersMissingFrom(name -> false).isEmpty());
        } finally {
            span.end();
        }
    }

    @Test
    @DisplayName("inject adds the header to a JDK request builder")
    void injectIntoJdkBuilder() {
        Span span = tracer.spanBuilder("s").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            HttpRequest request = new TracePropagation(true).inject(HttpRequest.newBuilder(URI.create("https://peer.example/a2a"))).build();
            assertTrue(request.headers().firstValue("traceparent").orElseThrow().contains(span.getSpanContext().getSpanId()));
        } finally {
            span.end();
        }
    }

    @Test
    @DisplayName("isTraceHeader is case-insensitive and does not match baggage")
    void isTraceHeader() {
        assertTrue(TracePropagation.isTraceHeader("TraceParent"));
        assertTrue(TracePropagation.isTraceHeader("tracestate"));
        assertFalse(TracePropagation.isTraceHeader("baggage"));
        assertFalse(TracePropagation.isTraceHeader(null));
    }
}
