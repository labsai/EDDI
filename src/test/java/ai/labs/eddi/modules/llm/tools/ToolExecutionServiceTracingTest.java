/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.tracing.TracePropagation;
import ai.labs.eddi.modules.llm.impl.LlmTelemetryListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * The {@code execute_tool} span {@link ToolExecutionService} opens around every
 * tool call, and the property the trace propagation depends on: while the tool
 * runs, that span is current, so the {@code traceparent} an outbound request
 * carries names it.
 */
@DisplayName("ToolExecutionService execute_tool span")
class ToolExecutionServiceTracingTest {

    private static final String SCOPE = "u:0123456789abcdef0123456789abcdef";
    private static final String CONVERSATION_ID = "0123456789abcdef01234567";

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private ToolExecutionService service;

    @Mock
    private ToolCacheService cacheService;

    @Mock
    private ToolRateLimiter rateLimiter;

    @Mock
    private ToolCostTracker costTracker;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();

        service = new ToolExecutionService();
        setField(service, "cacheService", cacheService);
        setField(service, "rateLimiter", rateLimiter);
        setField(service, "costTracker", costTracker);
        setField(service, "meterRegistry", new SimpleMeterRegistry());
        service.tracer = tracerProvider.get(ToolExecutionService.TRACER_NAME);
        service.init();

        when(rateLimiter.tryAcquire(anyString(), anyString(), anyInt())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private String run(String tool, Supplier<String> body, boolean rateLimit, boolean cache, int timeoutMs) {
        return service.executeToolWrapped(ToolInvocation.of(tool), "{}", cache ? SCOPE : null, CONVERSATION_ID, body, rateLimit, cache, false, 10,
                timeoutMs);
    }

    private SpanData onlySpan() {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size(), "exactly one execute_tool span per call");
        return spans.get(0);
    }

    @ParameterizedTest(name = "timeoutMs={0}")
    @ValueSource(ints = {ToolExecutionService.TIMEOUT_DISABLED, 5_000})
    @DisplayName("the traceparent an outbound call makes inside the tool names the execute_tool span — inline and on the timeout worker")
    void propagatedHeaderNamesTheToolSpan(int timeoutMs) {
        var propagation = new TracePropagation(true);
        AtomicReference<Map<String, String>> seenByTool = new AtomicReference<>();

        String result = run("searchWeb", () -> {
            seenByTool.set(propagation.currentHeaders());
            return "ok";
        }, false, false, timeoutMs);

        assertEquals("ok", result);
        SpanData span = onlySpan();
        String expected = "00-" + span.getTraceId() + "-" + span.getSpanId() + "-" + span.getSpanContext().getTraceFlags().asHex();
        assertEquals(expected, seenByTool.get().get("traceparent"));
    }

    @Test
    @DisplayName("the span carries the GenAI semconv attributes, the pinned schema version, and the source and call id from ToolCallTrace")
    void spanAttributes() {
        try (Scope ignored = new ToolCallTrace("mcp", "call_0123456789abcdef").makeCurrent()) {
            run("lookupOrder", () -> "ok", false, false, ToolExecutionService.TIMEOUT_DISABLED);
        }

        SpanData span = onlySpan();
        assertEquals("execute_tool lookupOrder", span.getName());
        assertEquals(SpanKind.INTERNAL, span.getKind());
        assertEquals("execute_tool", span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")));
        assertEquals("lookupOrder", span.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.name")));
        assertEquals("call_0123456789abcdef", span.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.call.id")));
        assertEquals("mcp", span.getAttributes().get(AttributeKey.stringKey("eddi.tool.source")));
        assertEquals(LlmTelemetryListener.SEMCONV_SCHEMA_VERSION,
                span.getAttributes().get(AttributeKey.stringKey("eddi.semconv.schema_version")));
        assertEquals(Boolean.FALSE, span.getAttributes().get(AttributeKey.booleanKey("eddi.tool.cache_hit")));
        assertNull(span.getAttributes().get(AttributeKey.stringKey("error.type")));
        assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
    }

    @Test
    @DisplayName("the span is a child of whatever span was current when the call came in")
    void childOfCallerSpan() {
        Span parent = tracerProvider.get("test").spanBuilder("gen_ai.client.inference").startSpan();
        try (Scope ignored = parent.makeCurrent()) {
            run("calculate", () -> "4", false, false, ToolExecutionService.TIMEOUT_DISABLED);
        } finally {
            parent.end();
        }

        SpanData tool = exporter.getFinishedSpanItems().stream().filter(s -> s.getName().startsWith("execute_tool")).findFirst().orElseThrow();
        assertEquals(parent.getSpanContext().getSpanId(), tool.getParentSpanId());
        assertEquals(parent.getSpanContext().getTraceId(), tool.getTraceId());
    }

    @Test
    @DisplayName("without ToolCallTrace the optional attributes are simply absent")
    void noCallTrace() {
        run("calculate", () -> "4", false, false, ToolExecutionService.TIMEOUT_DISABLED);

        SpanData span = onlySpan();
        assertNull(span.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.call.id")));
        assertNull(span.getAttributes().get(AttributeKey.stringKey("eddi.tool.source")));
    }

    @Test
    @DisplayName("a cache hit still gets a span, marked cache_hit=true, and the tool never runs")
    void cacheHit() {
        when(cacheService.get(SCOPE, "searchWeb", "{}")).thenReturn("cached");

        String result = run("searchWeb", () -> {
            throw new AssertionError("the tool must not run on a cache hit");
        }, false, true, ToolExecutionService.TIMEOUT_DISABLED);

        assertEquals("cached", result);
        assertEquals(Boolean.TRUE, onlySpan().getAttributes().get(AttributeKey.booleanKey("eddi.tool.cache_hit")));
    }

    @Test
    @DisplayName("a rate-limited call gets a span with error.type=rate_limited")
    void rateLimited() {
        when(rateLimiter.tryAcquire(anyString(), anyString(), anyInt())).thenReturn(false);

        run("searchWeb", () -> "never", true, false, ToolExecutionService.TIMEOUT_DISABLED);

        SpanData span = onlySpan();
        assertEquals("rate_limited", span.getAttributes().get(AttributeKey.stringKey("error.type")));
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    }

    @Test
    @DisplayName("a failing tool sets error.type to the exception class")
    void failure() {
        run("searchWeb", () -> {
            throw new IllegalStateException("boom");
        }, false, false, ToolExecutionService.TIMEOUT_DISABLED);

        SpanData span = onlySpan();
        assertEquals(IllegalStateException.class.getName(), span.getAttributes().get(AttributeKey.stringKey("error.type")));
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    }

    @Test
    @DisplayName("a timed-out tool sets error.type=timeout and the span still ends")
    void timeout() {
        String result = run("slowTool", () -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late";
        }, false, false, 100);

        assertTrue(result.startsWith("Error: Execution timed out"));
        SpanData span = onlySpan();
        assertEquals("timeout", span.getAttributes().get(AttributeKey.stringKey("error.type")));
        assertTrue(span.hasEnded());
    }

    @Test
    @DisplayName("no span is left current after the call returns")
    void scopeClosed() {
        run("calculate", () -> "4", false, false, ToolExecutionService.TIMEOUT_DISABLED);

        assertFalse(Span.current().getSpanContext().isValid());
        assertNotNull(onlySpan());
    }
}
