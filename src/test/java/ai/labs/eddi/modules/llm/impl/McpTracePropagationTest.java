/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.security.ResolutionPrincipal;
import ai.labs.eddi.engine.security.ResolutionPrincipal.Provenance;
import ai.labs.eddi.engine.security.ResolutionPrincipalContext;
import ai.labs.eddi.engine.security.TurnOrigin;
import ai.labs.eddi.engine.tracing.TracePropagation;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpHeadersSupplier;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.McpCallToolRequest;
import dev.langchain4j.mcp.protocol.McpClientParams;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W3C trace context on MCP calls: in {@code params._meta} (SEP-414) through the
 * client's meta supplier, and as an HTTP header mirrored from that
 * {@code _meta} by the transport's header supplier — so the header is right
 * even when the transport builds the request on a thread where no span is
 * current (its session-expiry retry runs on an HTTP client callback thread).
 */
@DisplayName("McpToolProviderManager trace propagation")
class McpTracePropagationTest {

    private static final String TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    private SdkTracerProvider tracerProvider;
    private McpToolProviderManager manager;

    @BeforeEach
    void setUp() {
        tracerProvider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create())).build();
        GlobalVariableResolver globals = mock(GlobalVariableResolver.class);
        SecretResolver secrets = mock(SecretResolver.class);
        when(globals.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(secrets.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        manager = new McpToolProviderManager(globals, secrets);
        manager.tracePropagation = new TracePropagation(true);
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    private static McpCallContext toolCallWithMeta(Map<String, Object> meta) {
        McpCallToolRequest request = new McpCallToolRequest(1L, "lookupOrder", Map.of());
        if (meta != null) {
            McpClientParams params = request.getParams() != null ? request.getParams() : new McpClientParams();
            params.setMeta(new HashMap<>(meta));
            request.setParams(params);
        }
        return new McpCallContext(null, request);
    }

    /**
     * The per-request header supplier exactly as {@code createTransport} installs
     * it.
     */
    private McpHeadersSupplier headerSupplierFor(String apiKey) throws Exception {
        McpServerConfig config = new McpServerConfig();
        config.setName("orders");
        config.setUrl("https://mcp.example.com/mcp");
        config.setApiKey(apiKey);
        Method createTransport = McpToolProviderManager.class.getDeclaredMethod("createTransport", McpServerConfig.class, Duration.class);
        createTransport.setAccessible(true);
        McpTransport transport = (McpTransport) createTransport.invoke(manager, config, Duration.ofSeconds(1));
        Field supplier = transport.getClass().getDeclaredField("customHeadersSupplier");
        supplier.setAccessible(true);
        return (McpHeadersSupplier) supplier.get(transport);
    }

    @Test
    @DisplayName("the meta supplier puts the current span's traceparent into _meta, and never baggage")
    void metaSupplierCarriesCurrentSpan() {
        Span span = tracerProvider.get("test").spanBuilder("execute_tool lookupOrder").startSpan();
        Map<String, Object> meta;
        try (Scope ignored = span.makeCurrent()) {
            meta = manager.traceMetaSupplier().apply(toolCallWithMeta(null));
        } finally {
            span.end();
        }

        assertEquals("00-" + span.getSpanContext().getTraceId() + "-" + span.getSpanContext().getSpanId() + "-"
                + span.getSpanContext().getTraceFlags().asHex(), meta.get("traceparent"));
        assertFalse(meta.containsKey("baggage"));
    }

    @Test
    @DisplayName("no span, disabled or absent propagation: the meta supplier adds nothing")
    void metaSupplierEmptyWithoutSpan() {
        assertTrue(manager.traceMetaSupplier().apply(toolCallWithMeta(null)).isEmpty());

        Span span = tracerProvider.get("test").spanBuilder("s").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            manager.tracePropagation = new TracePropagation(false);
            assertTrue(manager.traceMetaSupplier().apply(toolCallWithMeta(null)).isEmpty());
            manager.tracePropagation = null;
            assertTrue(manager.traceMetaSupplier().apply(toolCallWithMeta(null)).isEmpty());
        } finally {
            span.end();
        }
    }

    @Test
    @DisplayName("the header supplier mirrors _meta into the HTTP headers, on a thread with no span current")
    void headerMirrorsMetaOffThread() throws Exception {
        McpHeadersSupplier supplier = headerSupplierFor(null);
        McpCallContext call = toolCallWithMeta(Map.of("traceparent", TRACEPARENT, "tracestate", "vendor=v1"));

        // A plain pool thread: Context.current() is empty there, as on the
        // transport's retry callback.
        Map<String, String> headers = CompletableFuture.supplyAsync(() -> supplier.apply(call)).get();

        assertEquals(TRACEPARENT, headers.get("traceparent"));
        assertEquals("vendor=v1", headers.get("tracestate"));
    }

    @Test
    @DisplayName("the trace header joins a static API key's Authorization header rather than replacing it")
    void headerJoinsAuthorization() throws Exception {
        McpHeadersSupplier supplier = headerSupplierFor("sk-static");

        Map<String, String> headers = supplier.apply(toolCallWithMeta(Map.of("traceparent", TRACEPARENT)));

        assertEquals("Bearer sk-static", headers.get("Authorization"));
        assertEquals(TRACEPARENT, headers.get("traceparent"));
    }

    @Test
    @DisplayName("no _meta, no trace header — the request goes out as before")
    void noMetaNoHeader() throws Exception {
        McpHeadersSupplier supplier = headerSupplierFor(null);

        assertTrue(supplier.apply(toolCallWithMeta(null)).isEmpty());
        assertTrue(supplier.apply(new McpCallContext(null, null)).isEmpty());
    }

    @Test
    @DisplayName("an existing trace header in the map is not overwritten")
    void existingHeaderKept() {
        Map<String, String> headers = McpToolProviderManager.withTraceHeaders(Map.of("TraceParent", "00-own-01"),
                toolCallWithMeta(Map.of("traceparent", TRACEPARENT)));

        assertEquals(Map.of("TraceParent", "00-own-01"), headers);
    }

    @Test
    @DisplayName("an author's own traceparent suppresses tracestate too: presence is all-or-nothing, as on every other outbound path")
    void authorTraceparentSuppressesTracestate() {
        Map<String, String> headers = McpToolProviderManager.withTraceHeaders(Map.of("TraceParent", "00-own-01"),
                toolCallWithMeta(Map.of("traceparent", TRACEPARENT, "tracestate", "vendor=v1")));

        assertEquals(Map.of("TraceParent", "00-own-01"), headers);
    }

    @Test
    @DisplayName("an author's own tracestate suppresses traceparent too")
    void authorTracestateSuppressesTraceparent() {
        Map<String, String> headers = McpToolProviderManager.withTraceHeaders(Map.of("tracestate", "own=1"),
                toolCallWithMeta(Map.of("traceparent", TRACEPARENT, "tracestate", "vendor=v1")));

        assertEquals(Map.of("tracestate", "own=1"), headers);
    }

    /**
     * The sending half of the MCP routes: a tainted turn calling
     * {@code chat_with_agent}, {@code create_schedule} or
     * {@code start_group_discussion} on EDDI's own MCP server sends
     * {@code X-Eddi-Turn-Origin: email}. The taint is written into
     * {@code params._meta} on the calling thread and mirrored from there, so the
     * session-expiry retry that rebuilds the request on an HTTP-client thread
     * carries it too.
     */
    @Test
    @DisplayName("a tainted turn's MCP message carries the taint in _meta, and every request built from it the header")
    void taintTravelsInMetaAndHeader() throws Exception {
        var principals = new ResolutionPrincipalContext();
        Map<String, Object> meta;
        try {
            principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));
            meta = manager.traceMetaSupplier().apply(toolCallWithMeta(null));
        } finally {
            principals.clear();
        }
        assertEquals(TurnOrigin.EMAIL, meta.get(McpToolProviderManager.META_TURN_ORIGIN));

        // On a thread with no taint bound, as the transport's retry runs.
        Map<String, String> headers = headerSupplierFor(null).apply(toolCallWithMeta(meta));
        assertEquals(TurnOrigin.EMAIL, headers.get(TurnOrigin.HEADER));
        Map<String, String> withKey = headerSupplierFor("sk-static").apply(toolCallWithMeta(meta));
        assertEquals(TurnOrigin.EMAIL, withKey.get(TurnOrigin.HEADER));
        assertEquals("Bearer sk-static", withKey.get("Authorization"));
    }

    @Test
    @DisplayName("an untainted turn's MCP message carries neither")
    void untaintedCarriesNeither() throws Exception {
        assertFalse(manager.traceMetaSupplier().apply(toolCallWithMeta(null)).containsKey(McpToolProviderManager.META_TURN_ORIGIN));
        assertFalse(headerSupplierFor(null).apply(toolCallWithMeta(Map.of(McpToolProviderManager.META_TURN_ORIGIN, "none")))
                .containsKey(TurnOrigin.HEADER));
    }
}
