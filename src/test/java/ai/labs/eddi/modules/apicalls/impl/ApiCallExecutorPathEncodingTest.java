/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import ai.labs.eddi.secrets.SecretResolver;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A model-supplied tool argument must not be able to change WHICH endpoint an
 * httpcall hits.
 * <p>
 * LLM tool arguments are merged into the template data as top-level entries
 * ({@code HttpCallToolsProvider#safeTemplateMerge}) and substituted into path
 * templates such as {@code /agentstore/agents/{id}}. Without encoding, a value
 * of {@code ../../secretstore/secrets/default/key} rewrites the target path.
 * That matters most for the Platform Operator: its gate classifies on the
 * CONFIGURED endpoint, and read endpoints are exempt from approval, so a GET
 * tool steered to a different same-host GET would execute with no human in the
 * loop, carrying whatever Authorization the config resolves.
 * <p>
 * These tests use a REAL Qute engine and a REAL {@link PrePostUtils} — mocking
 * {@code templateValues} (as the sibling executor tests do) would step over the
 * very substitution under test.
 */
class ApiCallExecutorPathEncodingTest {

    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
    private static final int DEFAULT_MAX_RESPONSE_SIZE = 2_000_000;

    private IHttpClient httpClient;
    private ApiCallExecutor executor;
    private IConversationMemory memory;
    private IRequest mockRequest;

    @BeforeEach
    void setUp() throws Exception {
        httpClient = mock(IHttpClient.class);
        var jsonSerialization = mock(IJsonSerialization.class);
        var runtime = mock(IRuntime.class);
        var secretResolver = mock(SecretResolver.class);
        var callerIdentityResolver = mock(CallerIdentityResolver.class);
        var callerIdentityContext = mock(CallerIdentityContext.class);
        var globalVariableResolver = mock(GlobalVariableResolver.class);

        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        // Real templating: the point of these tests.
        var realEngine = Engine.builder().addDefaults().strictRendering(false).build();
        var prePostUtils = new PrePostUtils(jsonSerialization, mock(IMemoryItemConverter.class),
                new TemplatingEngine(realEngine), mock(IDataFactory.class));

        executor = new ApiCallExecutor(httpClient, jsonSerialization, runtime, prePostUtils, globalVariableResolver,
                secretResolver, callerIdentityResolver, callerIdentityContext, new RequestRedactor(callerIdentityResolver),
                false, DEFAULT_TIMEOUT_MILLIS, DEFAULT_MAX_RESPONSE_SIZE);

        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));

        mockRequest = mock(IRequest.class);
        var mockResponse = mock(IResponse.class);
        when(mockRequest.toMap()).thenReturn(new HashMap<>());
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(mockRequest);
        when(mockRequest.send()).thenReturn(mockResponse);
        when(mockRequest.setBodyEntity(any(), any(), any())).thenReturn(mockRequest);
        when(mockRequest.setHttpHeader(any(), any())).thenReturn(mockRequest);
        when(mockRequest.setQueryParam(any(), any())).thenReturn(mockRequest);
        when(mockResponse.getHttpCode()).thenReturn(200);
        when(mockResponse.getContentAsString()).thenReturn("{}");
        when(mockResponse.getHttpHeader()).thenReturn(new HashMap<>());
    }

    private URI executeWithId(String idValue) throws Exception {
        var call = new ApiCall();
        call.setName("readAgent");
        call.setSaveResponse(false);
        call.setFireAndForget(false);
        var request = new Request();
        request.setPath("/agentstore/agents/{id}");
        request.setMethod("GET");
        call.setRequest(request);

        Map<String, Object> templateData = new HashMap<>();
        templateData.put("id", idValue);

        executor.execute(call, memory, templateData, "http://localhost:7070");

        var captor = ArgumentCaptor.forClass(URI.class);
        verify(httpClient).newRequest(captor.capture(), any());
        return captor.getValue();
    }

    /**
     * Assertions use {@link URI#getRawPath()}, never {@code getPath()}:
     * {@code getPath()} percent-DECODES, so it reports the attacker's original
     * string and reads like a failure even when the wire format is correctly
     * encoded. What travels on the wire — and what the receiving server routes on —
     * is the raw form.
     */
    @Test
    @DisplayName("a traversal argument cannot escape its path segment")
    void traversalArgumentStaysWithinItsSegment() throws Exception {
        URI uri = executeWithId("../../secretstore/secrets/default/masterkey");

        assertFalse(uri.getRawPath().contains("/secretstore/"),
                "the argument rewrote the endpoint — resolved to: " + uri);
        assertEquals("/agentstore/agents/", uri.getRawPath().substring(0, "/agentstore/agents/".length()),
                "the call must still target the configured endpoint, got: " + uri);
        assertEquals(3, uri.getRawPath().chars().filter(c -> c == '/').count(),
                "the substituted value must remain a single path segment: " + uri);
        // normalize() applies dot-segment removal (RFC 3986 §5.2.4) to the RAW path,
        // before any decoding — so the encoded form must survive it. This is why '.'
        // is excluded from the unreserved set.
        assertEquals(uri.getRawPath(), uri.normalize().getRawPath(),
                "the path collapsed a level once normalized: " + uri.normalize());
    }

    @Test
    @DisplayName("a query/fragment argument cannot rewrite the query or truncate the URL")
    void queryAndFragmentArgumentsAreEncoded() throws Exception {
        URI uri = executeWithId("abc?admin=true#x");

        assertNull(uri.getRawQuery(), "the argument injected a query string: " + uri);
        assertNull(uri.getRawFragment(), "the argument injected a fragment: " + uri);
        assertTrue(uri.toString().contains("%3F"), "'?' must be percent-encoded, got: " + uri);
        assertTrue(uri.toString().contains("%23"), "'#' must be percent-encoded, got: " + uri);
    }

    @Test
    @DisplayName("ordinary identifiers round-trip unchanged")
    void ordinaryIdentifiersAreUntouched() throws Exception {
        URI uri = executeWithId("6a7d818a524a6102900dea0f");

        assertEquals("http://localhost:7070/agentstore/agents/6a7d818a524a6102900dea0f", uri.toString());
    }

    // ==================== nested (conversation-state) values ====================

    /**
     * The encoding cannot stop at tool arguments. A conversation property is
     * routinely captured FROM user input — {@code PropertySetterTask} with
     * {@code valueString: "{memory.current.input}"} is the documented slot-filling
     * pattern — so a path of {@code /agentstore/agents/{properties.agentId}}
     * substitutes whatever the user typed. Who authored the template is not who
     * controls the value.
     */
    @Test
    @DisplayName("a nested conversation property cannot escape its path segment either")
    void nestedPropertyIsEncoded() throws Exception {
        var call = new ApiCall();
        call.setName("readAgent");
        call.setSaveResponse(false);
        call.setFireAndForget(false);
        var request = new Request();
        request.setPath("/agentstore/agents/{properties.agentId}");
        request.setMethod("GET");
        call.setRequest(request);

        Map<String, Object> templateData = new HashMap<>();
        templateData.put("properties", Map.of("agentId", "../../secretstore/secrets/default/masterkey"));

        executor.execute(call, memory, templateData, "http://localhost:7070");

        var captor = ArgumentCaptor.forClass(URI.class);
        verify(httpClient).newRequest(captor.capture(), any());
        URI uri = captor.getValue();

        assertFalse(uri.getRawPath().contains("/secretstore/"),
                "a user-captured property rewrote the endpoint — resolved to: " + uri);
        assertEquals(3, uri.getRawPath().chars().filter(c -> c == '/').count(),
                "the substituted property must remain a single path segment: " + uri);
    }

    /**
     * Neither a number nor a boolean can render a {@code /}, {@code ?} or {@code #}
     * via {@code toString()}, so encoding them would only mangle legitimate output.
     */
    @Test
    @DisplayName("non-String scalars pass through untouched")
    void nonStringScalarsAreLeftAlone() {
        assertEquals(42, ApiCallExecutor.encodePathValue(42, 0));
        assertEquals(true, ApiCallExecutor.encodePathValue(true, 0));
    }

    /**
     * The depth bound exists so a pathological or self-referential structure cannot
     * turn a request into a stack overflow. Passing the value through past the
     * ceiling (rather than dropping it) keeps a legitimate deep template rendering
     * what it always did.
     */
    @Test
    @DisplayName("recursion is depth-bounded and terminates on a self-referential map")
    void recursionIsDepthBounded() {
        var selfReferential = new HashMap<String, Object>();
        selfReferential.put("self", selfReferential);
        selfReferential.put("value", "../escape");

        assertDoesNotThrow(() -> ApiCallExecutor.encodePathValue(selfReferential, 0),
                "a cyclic template structure must not blow the stack");
    }

    // ==================== the encoder itself ====================

    @Test
    void encodePathSegment_leavesUnreservedAlone() {
        assertEquals("abcXYZ019-_~", ApiCallExecutor.encodePathSegment("abcXYZ019-_~"));
    }

    @Test
    void encodePathSegment_encodesSeparatorsAndDots() {
        assertEquals("%2E%2E", ApiCallExecutor.encodePathSegment(".."));
        assertEquals("a%2Fb", ApiCallExecutor.encodePathSegment("a/b"));
        assertEquals("a%3Fb", ApiCallExecutor.encodePathSegment("a?b"));
        assertEquals("a%23b", ApiCallExecutor.encodePathSegment("a#b"));
    }

    @Test
    @DisplayName("multi-byte characters are encoded per UTF-8 byte")
    void encodePathSegment_encodesUtf8PerByte() {
        assertEquals("%C3%A4", ApiCallExecutor.encodePathSegment("ä"));
        assertEquals("%E2%82%AC", ApiCallExecutor.encodePathSegment("€"));
    }
}
