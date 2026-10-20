/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import ai.labs.eddi.engine.httpclient.outbound.OutboundEngine.Hop;
import ai.labs.eddi.engine.tracing.TracePropagation;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * W3C trace context on {@link OutboundHttp}: added to the first hop, kept on a
 * same-origin redirect, never carried onto a cross-origin one, and never
 * replacing a header the caller set. The engine is a recorder, so nothing here
 * binds a socket.
 */
@DisplayName("OutboundHttp trace propagation")
class OutboundHttpTracePropagationTest {

    private static final String ORIGIN = "https://api.example.com";

    private SdkTracerProvider tracerProvider;
    private Span span;
    private Scope scope;
    private final List<Hop> sent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tracerProvider = SdkTracerProvider.builder().build();
        span = tracerProvider.get("test").spanBuilder("execute_tool fetch").startSpan();
        scope = span.makeCurrent();
        sent.clear();
    }

    @AfterEach
    void tearDown() {
        scope.close();
        span.end();
        tracerProvider.close();
    }

    private String expectedTraceparent() {
        SpanContext sc = span.getSpanContext();
        return "00-" + sc.getTraceId() + "-" + sc.getSpanId() + "-" + sc.getTraceFlags().asHex();
    }

    private static String header(Hop hop, String name) {
        List<String> values = OutboundRequest.caseInsensitiveCopy(hop.headers()).get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    /**
     * Answers the first hop with a 302 to {@code location} (when set), every other
     * with 200.
     */
    private OutboundHttp facade(String location, TracePropagation propagation) {
        OutboundEngine engine = hop -> {
            sent.add(hop);
            if (location != null && sent.size() == 1) {
                return new OutboundEngine.Received(302, null, Map.of("Location", List.of(location)), new ByteArrayInputStream(new byte[0]));
            }
            return new OutboundEngine.Received(200, null, Map.of(), new ByteArrayInputStream("ok".getBytes()));
        };
        return TestOutbound.facade(engine, propagation);
    }

    private static OutboundRequest.Builder request() {
        return OutboundRequest.get(Profile.DEFAULT, URI.create(ORIGIN + "/start")).ssrf(SsrfPolicy.NONE);
    }

    private void send(OutboundHttp facade, OutboundRequest request) throws Exception {
        facade.send(request, BodyMode.string(1024, BodyMode.OnOverflow.TRUNCATE));
    }

    @Test
    @DisplayName("the first hop carries the current span's traceparent")
    void firstHopCarriesHeader() throws Exception {
        send(facade(null, new TracePropagation(true)), request().build());

        assertEquals(expectedTraceparent(), header(sent.getFirst(), "traceparent"));
    }

    @Test
    @DisplayName("a caller-set traceparent is never overwritten")
    void callerHeaderWins() throws Exception {
        String own = "00-11111111111111111111111111111111-2222222222222222-01";

        send(facade(null, new TracePropagation(true)), request().header("TraceParent", own).build());

        assertEquals(own, header(sent.getFirst(), "traceparent"));
    }

    @Test
    @DisplayName("nothing is added when propagation is off or absent (standalone facade)")
    void offOrAbsent() throws Exception {
        send(facade(null, new TracePropagation(false)), request().build());
        send(facade(null, null), request().build());

        assertNull(header(sent.get(0), "traceparent"));
        assertNull(header(sent.get(1), "traceparent"));
    }

    @Test
    @DisplayName("a same-origin redirect hop keeps the trace context")
    void sameOriginRedirectKeepsHeader() throws Exception {
        send(facade(ORIGIN + "/end", new TracePropagation(true)), request().build());

        assertEquals(2, sent.size());
        assertEquals(expectedTraceparent(), header(sent.get(1), "traceparent"));
    }

    @Test
    @DisplayName("a cross-origin redirect hop carries no trace header")
    void crossOriginRedirectDropsHeader() throws Exception {
        send(facade("https://elsewhere.example.net/landing", new TracePropagation(true)), request().build());

        assertEquals(2, sent.size());
        assertEquals(expectedTraceparent(), header(sent.get(0), "traceparent"));
        assertNull(header(sent.get(1), "traceparent"), "the caller vouched for one origin, not for the one it was redirected to");
        assertNull(header(sent.get(1), "tracestate"));
    }

    @Test
    @DisplayName("a different port is a different origin")
    void differentPortIsCrossOrigin() throws Exception {
        send(facade("https://api.example.com:8443/landing", new TracePropagation(true)), request().build());

        assertNull(header(sent.get(1), "traceparent"));
    }
}
