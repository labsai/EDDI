/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.*;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.ICompleteListener;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ApiCallExecutor, focusing on: - L1 fix: content-type equality check
 * (equals vs startsWith) - L2 fix: retryCall returns false instead of throwing
 */
class ApiCallExecutorTest {

    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
    private static final int DEFAULT_MAX_RESPONSE_SIZE = 2_000_000;

    private IHttpClient httpClient;
    private IJsonSerialization jsonSerialization;
    private IRuntime runtime;
    private PrePostUtils prePostUtils;
    private ApiCallExecutor executor;

    private IConversationMemory memory;
    private IWritableConversationStep currentStep;
    private IRequest mockRequest;
    private IResponse mockResponse;
    private SecretResolver secretResolver;
    private CallerIdentityResolver callerIdentityResolver;
    private CallerIdentityContext callerIdentityContext;
    private GlobalVariableResolver globalVariableResolver;

    @BeforeEach
    void setUp() throws Exception {
        httpClient = mock(IHttpClient.class);
        jsonSerialization = mock(IJsonSerialization.class);
        runtime = mock(IRuntime.class);
        prePostUtils = mock(PrePostUtils.class);
        secretResolver = mock(SecretResolver.class);
        callerIdentityResolver = mock(CallerIdentityResolver.class);
        callerIdentityContext = mock(CallerIdentityContext.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        // Pass-through: these tests exercise no ${caller:...} references.
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));

        executor = new ApiCallExecutor(httpClient, jsonSerialization, runtime, prePostUtils, globalVariableResolver, secretResolver,
                callerIdentityResolver, callerIdentityContext, new RequestRedactor(callerIdentityResolver), false, DEFAULT_TIMEOUT_MILLIS,
                DEFAULT_MAX_RESPONSE_SIZE);

        memory = mock(IConversationMemory.class);
        currentStep = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);

        mockRequest = mock(IRequest.class);
        mockResponse = mock(IResponse.class);
        when(mockRequest.toMap()).thenReturn(new HashMap<>());

        // Default: let prePostUtils pass through template objects
        when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(prePostUtils.templateValues(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        // Default HTTP client returns mock request
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(mockRequest);
        when(mockRequest.send()).thenReturn(mockResponse);
        when(mockRequest.setBodyEntity(any(), any(), any())).thenReturn(mockRequest);
        when(mockRequest.setHttpHeader(any(), any())).thenReturn(mockRequest);
        when(mockRequest.setQueryParam(any(), any())).thenReturn(mockRequest);
    }

    // ==================== L1: Content-Type Equality Tests ====================

    @Test
    void execute_jsonContentType_deserializesAsJson() throws Exception {
        // Given: response with exact "application/json" content-type
        ApiCall call = createSimpleApiCall("test-call", true);
        setupSuccessResponse(200, "{\"key\":\"value\"}", "application/json");

        Map<String, Object> parsed = Map.of("key", "value");
        when(jsonSerialization.deserialize("{\"key\":\"value\"}", Object.class)).thenReturn(parsed);

        // When
        Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");

        // Then: should have deserialized as JSON
        verify(jsonSerialization).deserialize("{\"key\":\"value\"}", Object.class);
        assertEquals(parsed, result.get("body"));
    }

    @Test
    void execute_jsonContentTypeWithCharset_deserializesAsJson() throws Exception {
        // Given: response with "application/json; charset=utf-8" content-type
        // After split(";")[0], this becomes "application/json"
        ApiCall call = createSimpleApiCall("test-call", true);
        setupSuccessResponse(200, "{\"key\":\"value\"}", "application/json; charset=utf-8");

        Map<String, Object> parsed = Map.of("key", "value");
        when(jsonSerialization.deserialize("{\"key\":\"value\"}", Object.class)).thenReturn(parsed);

        // When
        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        // Then: should still deserialize as JSON after charset stripping
        verify(jsonSerialization).deserialize("{\"key\":\"value\"}", Object.class);
    }

    @Test
    void execute_jsonPatchContentType_notDeserializedAsJson() throws Exception {
        // Given: response with "application/json-patch+json" content-type
        // L1 fix: this must NOT match "application/json" (equals check, not startsWith)
        ApiCall call = createSimpleApiCall("test-call", true);
        setupSuccessResponse(200, "[{\"op\":\"replace\"}]", "application/json-patch+json");

        // When
        Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");

        // Then: should NOT attempt JSON deserialization
        verify(jsonSerialization, never()).deserialize(any(), any());
        // Should store raw string as body
        assertEquals("[{\"op\":\"replace\"}]", result.get("body"));
    }

    @Test
    void execute_textPlainContentType_notDeserializedAsJson() throws Exception {
        // Given: response with "text/plain" content-type
        ApiCall call = createSimpleApiCall("test-call", true);
        setupSuccessResponse(200, "plain text", "text/plain");

        // When
        Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");

        // Then: should NOT attempt JSON deserialization
        verify(jsonSerialization, never()).deserialize(any(), any());
        assertEquals("plain text", result.get("body"));
    }

    @Test
    void execute_noContentTypeHeader_treatedAsNonJson() throws Exception {
        // Given: response with no Content-Type header
        ApiCall call = createSimpleApiCall("test-call", true);
        Map<String, String> headers = new HashMap<>();
        // No Content-Type header
        when(mockResponse.getHttpCode()).thenReturn(200);
        when(mockResponse.getContentAsString()).thenReturn("some response");
        when(mockResponse.getHttpHeader()).thenReturn(headers);

        // When
        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        // Then: should treat as non-JSON (actualContentType = "<not-present>")
        verify(jsonSerialization, never()).deserialize(any(), any());
    }

    // ==================== L2: retryCall Returns False Tests ====================

    @Test
    void execute_noPostResponse_noRetry() throws Exception {
        // Given: call with no postResponse (no retry config)
        ApiCall call = createSimpleApiCall("test-call", false);
        call.setPostResponse(null);
        setupSuccessResponse(200, "ok", "text/plain");

        // When
        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        // Then: only one execution, no retry triggered
        verify(mockRequest, times(1)).send();
    }

    @Test
    void execute_retryOnMatchingHttpCode_retriesThenStops() throws Exception {
        // Given: retry config with max 2 retries on 503
        ApiCall call = createSimpleApiCall("test-call", false);
        HttpPostResponse postResponse = new HttpPostResponse();
        RetryApiCallInstruction retryInstruction = new RetryApiCallInstruction();
        retryInstruction.setMaxRetries(2);
        retryInstruction.setRetryOnHttpCodes(List.of(503));
        retryInstruction.setExponentialBackoffDelayInMillis(0); // No delay for test
        postResponse.setRetryApiCallInstruction(retryInstruction);
        call.setPostResponse(postResponse);

        // First call returns 503 (retry), second returns 503 (retry), third time
        // maxRetries exceeded
        when(mockResponse.getHttpCode()).thenReturn(503);
        when(mockResponse.getContentAsString()).thenReturn("Service Unavailable");
        when(mockResponse.getHttpCodeMessage()).thenReturn("Service Unavailable");
        when(mockResponse.getHttpHeader()).thenReturn(new HashMap<>());

        // When
        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        // Then: should have called send() 3 times (1 initial + 2 retries)
        verify(mockRequest, times(3)).send();
    }

    @Test
    void execute_retryOnNonMatchingHttpCode_noRetry() throws Exception {
        // Given: retry config for 503 but response is 200
        ApiCall call = createSimpleApiCall("test-call", false);
        HttpPostResponse postResponse = new HttpPostResponse();
        RetryApiCallInstruction retryInstruction = new RetryApiCallInstruction();
        retryInstruction.setMaxRetries(3);
        retryInstruction.setRetryOnHttpCodes(List.of(503));
        postResponse.setRetryApiCallInstruction(retryInstruction);
        call.setPostResponse(postResponse);

        setupSuccessResponse(200, "ok", "text/plain");

        // When: retryCall should return false (L2 fix - no throw!)
        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        // Then: only one execution
        verify(mockRequest, times(1)).send();
    }

    // ==================== Validation Tests ====================

    @Test
    void execute_nullCall_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> executor.execute(null, memory, new HashMap<>(), "http://example.com"));
    }

    @Test
    void execute_nullMemory_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> executor.execute(new ApiCall(), null, new HashMap<>(), "http://example.com"));
    }

    @Test
    void execute_nullTemplateData_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> executor.execute(new ApiCall(), memory, null, "http://example.com"));
    }

    @Test
    void execute_emptyServerUrl_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> executor.execute(new ApiCall(), memory, new HashMap<>(), "  "));
    }

    // ==================== Fire-and-Forget Tests ====================

    @Test
    void execute_fireAndForget_returnsEmptyMap() throws Exception {
        ApiCall call = createSimpleApiCall("fnf-call", false);
        call.setFireAndForget(true);

        Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");

        assertTrue(result.isEmpty());
        // Fire-and-forget uses send(ICompleteListener) via executeFireAndForgetCall
        verify(mockRequest).send(any(ICompleteListener.class));
    }

    // ==================== Response Header Tests ====================

    @Test
    void execute_responseHeaderObjectName_storesHeadersInResult() throws Exception {
        ApiCall call = createSimpleApiCall("header-call", true);
        call.setResponseHeaderObjectName("respHeaders");

        Map<String, String> responseHeaders = new HashMap<>();
        responseHeaders.put("X-Request-Id", "abc123");
        responseHeaders.put("Content-Type", "application/json");
        when(mockResponse.getHttpCode()).thenReturn(200);
        when(mockResponse.getContentAsString()).thenReturn("{}");
        when(mockResponse.getHttpHeader()).thenReturn(responseHeaders);
        when(jsonSerialization.deserialize("{}", Object.class)).thenReturn(Map.of());

        Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");

        assertNotNull(result.get("headers"));
        assertEquals(200, result.get("httpCode"));
    }

    @Test
    void execute_responseHeaderObjectName_null_skipsHeaderExtraction() throws Exception {
        ApiCall call = createSimpleApiCall("no-header-call", true);
        call.setResponseHeaderObjectName(null);
        setupSuccessResponse(200, "ok", "text/plain");

        Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");

        assertNull(result.get("headers"));
    }

    // ==================== Header Scrubbing Tests ====================

    @Test
    @DisplayName("a caller token in an unconventionally named header is still redacted before persistence")
    void execute_callerTokenInUnconventionalHeader_isRedacted() throws Exception {
        // The header-name patterns cannot catch this one; only value matching can.
        // With the resolver mocked as a pass-through this assertion is vacuous, so
        // a real resolver with a real bound identity is used here.
        var realContext = new CallerIdentityContext(null, null);
        realContext.bind(new CallerIdentity("caller-jwt-value", "alice", "https://eddi.example:443"));
        var realResolver = new CallerIdentityResolver(realContext, true);
        var executorWithRealResolver = new ApiCallExecutor(httpClient, jsonSerialization, runtime, prePostUtils, globalVariableResolver,
                secretResolver, realResolver, realContext, new RequestRedactor(realResolver), false, DEFAULT_TIMEOUT_MILLIS,
                DEFAULT_MAX_RESPONSE_SIZE);
        try {
            ApiCall call = createSimpleApiCall("redact-call", false);

            Map<String, Object> requestMap = new HashMap<>();
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put("X-Trace-Context", "id=1; tok=caller-jwt-value");
            requestMap.put("headers", headers);
            when(mockRequest.toMap()).thenReturn(requestMap);
            setupSuccessResponse(200, "ok", "text/plain");

            executorWithRealResolver.execute(call, memory, new HashMap<>(), "http://example.com");

            var captor = ArgumentCaptor.forClass(Object.class);
            verify(prePostUtils, atLeastOnce()).createMemoryEntry(
                    eq(currentStep), captor.capture(), contains("Request"), eq("httpCalls"));
            @SuppressWarnings("unchecked")
            var capturedMap = (Map<String, Object>) captor.getValue();
            @SuppressWarnings("unchecked")
            var scrubbedHeaders = (Map<String, Object>) capturedMap.get("headers");
            String persisted = String.valueOf(scrubbedHeaders.get("X-Trace-Context"));
            assertFalse(persisted.contains("caller-jwt-value"),
                    "the caller's token must not reach conversation memory, whatever the header is called");
            assertTrue(persisted.contains("<REDACTED>"), persisted);
        } finally {
            realContext.clear();
        }
    }

    @Test
    @DisplayName("a call carrying query parameters still pins — they arrive as List values, not Strings")
    void resolve_withQueryParameters_stillProducesAFingerprint() throws Exception {
        // HttpClientWrapper stores query params as Map<String, List<String>> (a
        // param may legitimately repeat). Reading them back as Map<String, String>
        // erases cleanly at the cast and then throws deep inside the fingerprint
        // canonicaliser — which pinResolvedRequest catches and downgrades to
        // "approved unpinned". The whole pinning guarantee would silently not
        // apply to any endpoint with a query param, deploy?version=N included.
        ApiCall call = createSimpleApiCall("query-call", false);

        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put("uri", "http://example.com/administration/production/deploy/agent-1");
        requestMap.put("method", "POST");
        requestMap.put("headers", new LinkedHashMap<String, Object>());
        Map<String, List<String>> queryParams = new LinkedHashMap<>();
        queryParams.put("version", List.of("3"));
        requestMap.put("queryParams", queryParams);
        when(mockRequest.toMap()).thenReturn(requestMap);

        ResolvedRequest resolved = executor.resolve(call, memory, new HashMap<>(), "http://example.com");

        assertNotNull(resolved.fingerprint(), "a call with a query parameter must still be pinnable");
        assertTrue(resolved.isPinned());
        assertEquals("3", resolved.queryParams().get("version"));
    }

    @Test
    @DisplayName("a repeated query parameter keeps both values distinguishable in the fingerprint")
    void resolve_withRepeatedQueryParameter_doesNotCollapseValues() throws Exception {
        ApiCall call = createSimpleApiCall("multi-query-call", false);

        ResolvedRequest twoValues = resolveWithQuery(call, Map.of("tag", List.of("a", "b")));
        ResolvedRequest oneValue = resolveWithQuery(call, Map.of("tag", List.of("a")));

        assertNotEquals(twoValues.fingerprint(), oneValue.fingerprint(),
                "dropping a repeated value changes what the request does and must change the hash");
    }

    private ResolvedRequest resolveWithQuery(ApiCall call, Map<String, List<String>> queryParams) throws Exception {
        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put("uri", "http://example.com/x");
        requestMap.put("method", "GET");
        requestMap.put("headers", new LinkedHashMap<String, Object>());
        requestMap.put("queryParams", new LinkedHashMap<>(queryParams));
        when(mockRequest.toMap()).thenReturn(requestMap);
        return executor.resolve(call, memory, new HashMap<>(), "http://example.com");
    }

    /** A request map shaped like the one HttpClientWrapper hands back. */
    private void stubRequestMap() {
        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put("uri", "http://example.com/api/test");
        requestMap.put("method", "POST");
        requestMap.put("headers", new LinkedHashMap<String, Object>());
        requestMap.put("queryParams", new LinkedHashMap<String, List<String>>());
        when(mockRequest.toMap()).thenReturn(requestMap);
    }

    @Test
    @DisplayName("an EMPTY preRequest.propertyInstructions list still makes the call unpinnable")
    void resolve_withEmptyPropertyInstructions_isNotPinned() throws Exception {
        // The fail-open this closes. The old predicate used isNullOrEmpty, so an
        // empty list read as "absent" and the call was PINNED — while
        // PrePostUtils guards on != null and therefore still re-runs
        // memoryItemConverter.convert, discarding the model arguments merged in
        // for this call. Gate time and resume time both skip that (both go
        // through resolve), so they agreed with each other and the guard passed
        // while execute() sent a request with every {arg} rendered empty.
        ApiCall call = createSimpleApiCall("empty-instructions-call", false);
        var preRequest = new HttpPreRequest();
        preRequest.setPropertyInstructions(new java.util.ArrayList<>());
        call.setPreRequest(preRequest);
        stubRequestMap();

        ResolvedRequest resolved = executor.resolve(call, memory, new HashMap<>(), "http://example.com");

        assertNull(resolved.fingerprint(), "an empty-but-present instruction list must not be treated as absent");
        assertFalse(resolved.isPinned());
        // Unpinnable is not unreviewable: the approver still gets a preview.
        assertNotNull(resolved.uri());
    }

    @Test
    @DisplayName("fireAndForget with batchRequests is unpinnable — one resolved request cannot stand for N")
    void resolve_withFireAndForgetBatch_isNotPinned() throws Exception {
        // execute() routes these to executeFireAndForgetCalls, which calls
        // buildRequest once PER iteration object. resolve() builds exactly one,
        // with the iteration variable empty. batchRequests is a different field
        // from propertyInstructions, so this used to be pinned: the approver saw
        // one request, the re-check compared that same never-sent request, and N
        // unapproved requests went out on a background thread.
        ApiCall call = createSimpleApiCall("batch-call", false);
        call.setFireAndForget(true);
        var preRequest = new HttpPreRequest();
        preRequest.setBatchRequests(new BatchRequestBuildingInstruction());
        call.setPreRequest(preRequest);
        stubRequestMap();

        ResolvedRequest resolved = executor.resolve(call, memory, new HashMap<>(), "http://example.com");

        assertNull(resolved.fingerprint(), "a batched fire-and-forget call must not claim a fingerprint");
        assertFalse(resolved.isPinned());
    }

    @Test
    @DisplayName("a retryable call is unpinnable — attempts 2..N are rebuilt from mutated template data")
    void resolve_withRetryInstruction_isNotPinned() throws Exception {
        // buildRequest sits INSIDE execute()'s retry do-while, and between
        // attempts the shared templateDataObjects map gains the response object,
        // its error, its httpCode and the response headers. A call templating any
        // of those sends attempts 2..N as requests nobody resolved, previewed or
        // fingerprinted — while the approver saw only attempt 1. Same "one
        // resolved request cannot stand for N" argument as the batched
        // fire-and-forget case.
        ApiCall call = createSimpleApiCall("retry-call", false);
        var postResponse = new HttpPostResponse();
        var retry = new RetryApiCallInstruction();
        retry.setMaxRetries(2);
        postResponse.setRetryApiCallInstruction(retry);
        call.setPostResponse(postResponse);
        stubRequestMap();

        ResolvedRequest resolved = executor.resolve(call, memory, new HashMap<>(), "http://example.com");

        assertNull(resolved.fingerprint(), "a call that can retry must not claim a fingerprint");
        assertFalse(resolved.isPinned());
    }

    @Test
    @DisplayName("a retry instruction that cannot fire (maxRetries 0) stays pinned")
    void resolve_withDisabledRetryInstruction_remainsPinned() throws Exception {
        // Mirrors retryCall()'s own test (maxRetries >= 1), so a present-but-inert
        // instruction does not needlessly unpin an otherwise verifiable call.
        ApiCall call = createSimpleApiCall("no-retry-call", false);
        var postResponse = new HttpPostResponse();
        var retry = new RetryApiCallInstruction();
        retry.setMaxRetries(0);
        postResponse.setRetryApiCallInstruction(retry);
        call.setPostResponse(postResponse);
        stubRequestMap();

        ResolvedRequest resolved = executor.resolve(call, memory, new HashMap<>(), "http://example.com");

        assertNotNull(resolved.fingerprint(), "an inert retry instruction must not unpin the call");
    }

    @Test
    @DisplayName("an ordinary call is still pinned — the divergence check is not a blanket opt-out")
    void resolve_withOrdinaryCall_remainsPinned() throws Exception {
        // The mirror direction. Widening the predicate must not quietly unpin
        // everything, which would disable enforcement while every test above
        // still passed.
        ApiCall call = createSimpleApiCall("ordinary-call", false);
        stubRequestMap();

        ResolvedRequest resolved = executor.resolve(call, memory, new HashMap<>(), "http://example.com");

        assertNotNull(resolved.fingerprint(), "an ordinary call must still be pinnable");
        assertTrue(resolved.isPinned());
    }

    @Test
    @DisplayName("fireAndForget WITHOUT batchRequests stays pinned — it sends exactly one request")
    void resolve_withPlainFireAndForget_remainsPinned() throws Exception {
        ApiCall call = createSimpleApiCall("plain-fnf-call", false);
        call.setFireAndForget(true);
        stubRequestMap();

        ResolvedRequest resolved = executor.resolve(call, memory, new HashMap<>(), "http://example.com");

        assertNotNull(resolved.fingerprint(), "a single fire-and-forget request is still one request");
    }

    @Test
    @DisplayName("a secret in the request BODY is scrubbed before persistence, not just headers")
    void execute_secretInBody_isRedacted() throws Exception {
        // Header-name matching cannot see into a body. A config write (create an
        // agent, set a provider key) carries its credential there, and this map is
        // persisted to the conversation document.
        ApiCall call = createSimpleApiCall("body-secret-call", false);

        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put("headers", new LinkedHashMap<String, Object>());
        // Zero-entropy on purpose — see ResolvedRequestTest.BodyRedaction: the
        // `sk-` shape is what the filter matches, the randomness is what trips
        // the repo's secret scanner.
        requestMap.put("body", "{\"apiKey\":\"sk-aaaaaaaaaaaaaaaaaaaaaaaaaa\",\"name\":\"billing\"}");
        when(mockRequest.toMap()).thenReturn(requestMap);
        setupSuccessResponse(200, "ok", "text/plain");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(prePostUtils, atLeastOnce()).createMemoryEntry(
                eq(currentStep), captor.capture(), contains("Request"), eq("httpCalls"));
        @SuppressWarnings("unchecked")
        var capturedMap = (Map<String, Object>) captor.getValue();
        String persistedBody = String.valueOf(capturedMap.get("body"));
        assertFalse(persistedBody.contains("sk-aaaaaaaaaaaaaaaaaaaaaaaaaa"), persistedBody);
        assertTrue(persistedBody.contains("REDACTED"), persistedBody);
        // Over-redaction would make the debug record useless — the rest survives.
        assertTrue(persistedBody.contains("billing"), persistedBody);
    }

    @Test
    @DisplayName("a credential in a QUERY parameter is scrubbed before persistence, and the live request is untouched")
    void execute_secretInQueryParam_isRedactedWithoutCorruptingTheRequest() throws Exception {
        ApiCall call = createSimpleApiCall("query-secret-call", false);

        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put("headers", new LinkedHashMap<String, Object>());
        // The live map RequestWrapper#toMap hands back by reference, not a copy.
        Map<String, List<String>> liveQueryParams = new LinkedHashMap<>();
        liveQueryParams.put("api_key", new ArrayList<>(List.of("super-secret-value")));
        liveQueryParams.put("version", new ArrayList<>(List.of("3")));
        requestMap.put("queryParams", liveQueryParams);
        when(mockRequest.toMap()).thenReturn(requestMap);
        setupSuccessResponse(200, "ok", "text/plain");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(prePostUtils, atLeastOnce()).createMemoryEntry(
                eq(currentStep), captor.capture(), contains("Request"), eq("httpCalls"));
        @SuppressWarnings("unchecked")
        var capturedMap = (Map<String, Object>) captor.getValue();
        String persisted = String.valueOf(capturedMap.get("queryParams"));
        assertFalse(persisted.contains("super-secret-value"), persisted);
        assertTrue(persisted.contains("<REDACTED>"), persisted);
        assertTrue(persisted.contains("3"), "an ordinary parameter must survive: " + persisted);

        // The entry is REPLACED, never rewritten in place — the request that was
        // already sent still carries its real credential.
        assertEquals(List.of("super-secret-value"), liveQueryParams.get("api_key"),
                "redacting the debug record must not mutate the live request");
    }

    @Test
    void execute_sensitiveHeaders_areScrubbed() throws Exception {
        ApiCall call = createSimpleApiCall("scrub-call", false);

        Map<String, Object> requestMap = new HashMap<>();
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer secret-token");
        headers.put("X-Api-Key", "my-api-key");
        headers.put("X-Custom", "visible-value");
        headers.put("Token", "jwt-token");
        headers.put("X-Secret-Data", "confidential");
        requestMap.put("headers", headers);
        when(mockRequest.toMap()).thenReturn(requestMap);
        setupSuccessResponse(200, "ok", "text/plain");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        // Verify scrubbing via ArgumentCaptor on the memory entry call
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(prePostUtils, atLeastOnce()).createMemoryEntry(
                eq(currentStep), captor.capture(), contains("Request"), eq("httpCalls"));

        @SuppressWarnings("unchecked")
        var capturedMap = (Map<String, Object>) captor.getValue();
        @SuppressWarnings("unchecked")
        var scrubbedHeaders = (Map<String, Object>) capturedMap.get("headers");
        assertEquals("<REDACTED>", scrubbedHeaders.get("Authorization"));
        assertEquals("<REDACTED>", scrubbedHeaders.get("X-Api-Key"));
        assertEquals("<REDACTED>", scrubbedHeaders.get("Token"));
        assertEquals("<REDACTED>", scrubbedHeaders.get("X-Secret-Data"));
        assertEquals("visible-value", scrubbedHeaders.get("X-Custom"));
    }

    @Test
    @DisplayName("a caller reference in the path fails with a message that names the cause")
    void execute_callerReferenceInPath_isRejectedClearly() {
        // A real resolver, for the same reason as the redaction test above: the
        // mocked one does nothing, so the assertion would hold either way.
        var realContext = new CallerIdentityContext(null, null);
        realContext.bind(new CallerIdentity("caller-jwt-value", "alice", "https://eddi.example:443"));
        var realResolver = new CallerIdentityResolver(realContext, true);
        var executorWithRealResolver = new ApiCallExecutor(httpClient, jsonSerialization, runtime, prePostUtils, globalVariableResolver,
                secretResolver, realResolver, realContext, new RequestRedactor(realResolver), false, DEFAULT_TIMEOUT_MILLIS,
                DEFAULT_MAX_RESPONSE_SIZE);
        try {
            ApiCall call = createSimpleApiCall("path-ref-call", false);
            call.getRequest().setPath("/users/${caller:userId}/profile");

            // Without the guard this reaches URI.create() and dies as "Illegal
            // character in path", naming the symptom and not the cause.
            var e = assertThrows(LifecycleException.class,
                    () -> executorWithRealResolver.execute(call, memory, new HashMap<>(), "http://example.com"));
            String message = String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            assertTrue(message.contains("${caller:userId}"), message);
            assertTrue(message.contains("the request path"), message);
        } finally {
            realContext.clear();
        }
    }

    @Test
    @DisplayName("header scrubbing survives a Turkish locale")
    void execute_sensitiveHeaders_areScrubbedUnderTurkishLocale() throws Exception {
        // "AUTHORIZATION".toLowerCase() under tr-TR gives a dotless 'ı', so a
        // locale-sensitive lowercase makes every name test miss and the secret is
        // persisted to conversation memory in the clear.
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            ApiCall call = createSimpleApiCall("scrub-locale-call", false);

            Map<String, Object> requestMap = new HashMap<>();
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put("AUTHORIZATION", "Bearer secret-token");
            headers.put("X-API-KEY", "my-api-key");
            requestMap.put("headers", headers);
            when(mockRequest.toMap()).thenReturn(requestMap);
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");

            var captor = ArgumentCaptor.forClass(Object.class);
            verify(prePostUtils, atLeastOnce()).createMemoryEntry(
                    eq(currentStep), captor.capture(), contains("Request"), eq("httpCalls"));

            @SuppressWarnings("unchecked")
            var capturedMap = (Map<String, Object>) captor.getValue();
            @SuppressWarnings("unchecked")
            var scrubbedHeaders = (Map<String, Object>) capturedMap.get("headers");
            assertEquals("<REDACTED>", scrubbedHeaders.get("AUTHORIZATION"));
            assertEquals("<REDACTED>", scrubbedHeaders.get("X-API-KEY"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void execute_vaultRefInHeaderValue_isScrubbed() throws Exception {
        ApiCall call = createSimpleApiCall("vault-call", false);

        Map<String, Object> requestMap = new HashMap<>();
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("X-Custom-Auth", "${vault:my-secret}");
        requestMap.put("headers", headers);
        when(mockRequest.toMap()).thenReturn(requestMap);
        setupSuccessResponse(200, "ok", "text/plain");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(prePostUtils, atLeastOnce()).createMemoryEntry(
                eq(currentStep), captor.capture(), contains("Request"), eq("httpCalls"));

        @SuppressWarnings("unchecked")
        var capturedMap = (Map<String, Object>) captor.getValue();
        @SuppressWarnings("unchecked")
        var scrubbedHeaders = (Map<String, Object>) capturedMap.get("headers");
        assertEquals("<REDACTED>", scrubbedHeaders.get("X-Custom-Auth"));
    }

    @Test
    void execute_noHeadersInRequestMap_scrubDoesNotThrow() throws Exception {
        ApiCall call = createSimpleApiCall("no-headers", false);
        when(mockRequest.toMap()).thenReturn(new HashMap<>());
        setupSuccessResponse(200, "ok", "text/plain");

        // Should not throw even when there are no headers in the request map
        assertDoesNotThrow(() -> executor.execute(call, memory, new HashMap<>(), "http://example.com"));
    }

    // ==================== Non-2xx Response Tests ====================

    @Test
    void execute_non2xxResponse_doesNotSaveBody() throws Exception {
        ApiCall call = createSimpleApiCall("err-call", true);
        when(mockResponse.getHttpCode()).thenReturn(500);
        when(mockResponse.getHttpCodeMessage()).thenReturn("Internal Server Error");
        when(mockResponse.getContentAsString()).thenReturn("error body");
        when(mockResponse.getHttpHeader()).thenReturn(new HashMap<>());

        Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");

        assertFalse(result.containsKey("body"));
        verify(jsonSerialization, never()).deserialize(any(), any());
    }

    @Test
    void execute_400Response_logsWarning() throws Exception {
        ApiCall call = createSimpleApiCall("bad-req", false);
        when(mockResponse.getHttpCode()).thenReturn(400);
        when(mockResponse.getHttpCodeMessage()).thenReturn("Bad Request");
        when(mockResponse.getContentAsString()).thenReturn("bad");
        when(mockResponse.getHttpHeader()).thenReturn(new HashMap<>());

        // Should complete without exception (non-2xx is logged, not thrown)
        assertDoesNotThrow(() -> executor.execute(call, memory, new HashMap<>(), "http://example.com"));
    }

    // ==================== Path Handling Tests ====================

    @Test
    void execute_pathWithoutLeadingSlash_addsSlash() throws Exception {
        ApiCall call = createSimpleApiCall("path-call", false);
        call.getRequest().setPath("api/test");
        setupSuccessResponse(200, "ok", "text/plain");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        // buildRequest should have prepended "/" to the path
        verify(httpClient).newRequest(eq(URI.create("http://example.com/api/test")), any());
    }

    @Test
    void execute_absoluteHttpPath_usesDirectly() throws Exception {
        ApiCall call = createSimpleApiCall("abs-call", false);
        call.getRequest().setPath("http://other-server.com/api/test");
        setupSuccessResponse(200, "ok", "text/plain");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        verify(httpClient).newRequest(eq(URI.create("http://other-server.com/api/test")), any());
    }

    // ==================== Request Body Tests ====================

    @Test
    void execute_requestWithBody_setsBodyEntity() throws Exception {
        ApiCall call = createSimpleApiCall("body-call", false);
        call.getRequest().setBody("{\"key\":\"value\"}");
        call.getRequest().setContentType("application/json");
        call.getRequest().setMethod("POST");
        setupSuccessResponse(200, "ok", "text/plain");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        verify(mockRequest).setBodyEntity("{\"key\":\"value\"}", "utf-8", "application/json");
    }

    @Test
    void execute_requestWithQueryParams_setsParams() throws Exception {
        ApiCall call = createSimpleApiCall("qp-call", false);
        call.getRequest().getQueryParams().put("q", "test");
        call.getRequest().getQueryParams().put("page", "1");
        setupSuccessResponse(200, "ok", "text/plain");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        verify(mockRequest).setQueryParam("q", "test");
        verify(mockRequest).setQueryParam("page", "1");
    }

    @Test
    void execute_requestWithHeaders_setsHeaders() throws Exception {
        ApiCall call = createSimpleApiCall("hdr-call", false);
        call.getRequest().getHeaders().put("Accept", "application/json");
        setupSuccessResponse(200, "ok", "text/plain");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        verify(mockRequest).setHttpHeader("Accept", "application/json");
    }

    // ==================== Result Content Tests ====================

    @Test
    void execute_successfulSave_resultContainsHttpCode() throws Exception {
        ApiCall call = createSimpleApiCall("code-call", true);
        setupSuccessResponse(200, "response body", "text/plain");

        Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");

        assertEquals(200, result.get("httpCode"));
        assertEquals("response body", result.get("body"));
    }

    // ==================== Helpers ====================

    // ==================== SSRF Protection (opt-in) ====================

    @Test
    void execute_ssrfProtectionEnabled_blocksInternalUrl() {
        ApiCallExecutor protectedExecutor = new ApiCallExecutor(httpClient, jsonSerialization, runtime, prePostUtils, globalVariableResolver,
                secretResolver, callerIdentityResolver, callerIdentityContext, new RequestRedactor(callerIdentityResolver), true,
                DEFAULT_TIMEOUT_MILLIS, DEFAULT_MAX_RESPONSE_SIZE);
        ApiCall call = createSimpleApiCall("ssrf-call", false);
        // 169.254.169.254 is a literal IP (no DNS) blocked by UrlValidationUtils.
        assertThrows(LifecycleException.class, () -> protectedExecutor.execute(call, memory, new HashMap<>(), "http://169.254.169.254"));
    }

    @Test
    void execute_ssrfProtectionEnabled_disablesRedirectsOnPublicUrl() throws Exception {
        ApiCallExecutor protectedExecutor = new ApiCallExecutor(httpClient, jsonSerialization, runtime, prePostUtils, globalVariableResolver,
                secretResolver, callerIdentityResolver, callerIdentityContext, new RequestRedactor(callerIdentityResolver), true,
                DEFAULT_TIMEOUT_MILLIS, DEFAULT_MAX_RESPONSE_SIZE);
        ApiCall call = createSimpleApiCall("redir-call", false);
        setupSuccessResponse(200, "ok", "text/plain");
        // 1.1.1.1 is a public literal IP — passes validation without a DNS lookup.
        protectedExecutor.execute(call, memory, new HashMap<>(), "http://1.1.1.1");
        verify(mockRequest).setFollowRedirects(false);
    }

    @Test
    void execute_ssrfProtectionDisabled_allowsInternalUrlAndKeepsRedirects() throws Exception {
        // Default executor (protection off): no validation, no redirect override.
        ApiCall call = createSimpleApiCall("internal-call", false);
        setupSuccessResponse(200, "ok", "text/plain");
        executor.execute(call, memory, new HashMap<>(), "http://169.254.169.254");
        verify(mockRequest, never()).setFollowRedirects(anyBoolean());
    }

    // ==================== Exponential Backoff Curve ====================

    @Test
    void getDelayInMillis_isExponentialNotLinear() {
        ApiCall call = callWithBackoff(100);
        assertEquals(100, ApiCallExecutor.getDelayInMillis(call, true, 1)); // 100 * 2^0
        assertEquals(200, ApiCallExecutor.getDelayInMillis(call, true, 2)); // 100 * 2^1
        assertEquals(400, ApiCallExecutor.getDelayInMillis(call, true, 3)); // 100 * 2^2
        assertEquals(800, ApiCallExecutor.getDelayInMillis(call, true, 4)); // 100 * 2^3
    }

    @Test
    void getDelayInMillis_cappedAtCeiling() {
        ApiCall call = callWithBackoff(100_000);
        // 100000 * 2^9 = 51,200,000 — capped to the hard ceiling, which has to stay
        // inside the turn budget (a retry sleeps on the conversation thread).
        assertEquals(30_000, ApiCallExecutor.getDelayInMillis(call, true, 10));
        assertEquals(30_000, ApiCallExecutor.MAX_BACKOFF_MILLIS);
    }

    @Test
    void getDelayInMillis_perCallBackoffCapLowersCeiling() {
        ApiCall call = callWithBackoff(1_000);
        call.getPostResponse().getRetryApiCallInstruction().setMaxBackoffDelayInMillis(2_500);
        // 1000 * 2^4 = 16,000 — lowered to the configured 2.5s cap.
        assertEquals(2_500, ApiCallExecutor.getDelayInMillis(call, true, 5));
    }

    @Test
    void getDelayInMillis_perCallBackoffCapCannotExceedHardCeiling() {
        ApiCall call = callWithBackoff(100_000);
        call.getPostResponse().getRetryApiCallInstruction().setMaxBackoffDelayInMillis(600_000);
        assertEquals(30_000, ApiCallExecutor.getDelayInMillis(call, true, 10));
    }

    @Test
    void getDelayInMillis_noRetry_returnsZeroWithoutPreRequestDelay() {
        ApiCall call = callWithBackoff(100);
        assertEquals(0, ApiCallExecutor.getDelayInMillis(call, false, 3));
    }

    private ApiCall callWithBackoff(int baseDelayMillis) {
        ApiCall call = createSimpleApiCall("backoff", false);
        HttpPostResponse postResponse = new HttpPostResponse();
        RetryApiCallInstruction retry = new RetryApiCallInstruction();
        retry.setExponentialBackoffDelayInMillis(baseDelayMillis);
        postResponse.setRetryApiCallInstruction(retry);
        call.setPostResponse(postResponse);
        return call;
    }

    private ApiCall createSimpleApiCall(String name, boolean saveResponse) {
        ApiCall call = new ApiCall();
        call.setName(name);
        call.setSaveResponse(saveResponse);
        call.setResponseObjectName("response");
        call.setFireAndForget(false);

        Request request = new Request();
        request.setPath("/api/test");
        request.setMethod("GET");
        call.setRequest(request);

        return call;
    }

    private void setupSuccessResponse(int httpCode, String body, String contentType) {
        Map<String, String> headers = new HashMap<>();
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        when(mockResponse.getHttpCode()).thenReturn(httpCode);
        when(mockResponse.getContentAsString()).thenReturn(body);
        when(mockResponse.getHttpHeader()).thenReturn(headers);
    }

    // ==================== Non-text, non-JSON Content Type Tests
    // ====================

    @Test
    void execute_nonTextNonJsonContentType_logsWarningAndStoresAsString() throws Exception {
        // Given: response with "application/xml" content type (not text, not JSON)
        ApiCall call = createSimpleApiCall("xml-call", true);
        setupSuccessResponse(200, "<root>data</root>", "application/xml");

        // When
        Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");

        // Then: should NOT attempt JSON deserialization, stores raw string
        verify(jsonSerialization, never()).deserialize(any(), any());
        assertEquals("<root>data</root>", result.get("body"));
    }

    // ==================== saveResponse=false Tests ====================

    @Test
    void execute_saveResponseFalse_doesNotStoreBody() throws Exception {
        ApiCall call = createSimpleApiCall("no-save", false); // saveResponse = false
        setupSuccessResponse(200, "response data", "text/plain");

        Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");

        // Body should NOT be in result when saveResponse is false
        assertFalse(result.containsKey("body"));
    }

    // ==================== PreRequest Delay Tests ====================

    @Test
    void execute_preRequestDelay_schedulesWithDelay() throws Exception {
        ApiCall call = createSimpleApiCall("delay-call", false);
        var preRequest = new HttpPreRequest();
        preRequest.setDelayBeforeExecutingInMillis(100);
        call.setPreRequest(preRequest);

        var scheduledExecutor = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        var future = mock(ScheduledFuture.class);
        when(future.get()).thenReturn(mockResponse);
        when(scheduledExecutor.schedule(any(Callable.class), eq(100L), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(future);
        when(runtime.getScheduledExecutorService()).thenReturn(scheduledExecutor);

        setupSuccessResponse(200, "ok", "text/plain");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        verify(scheduledExecutor).schedule(any(Callable.class), eq(100L), eq(TimeUnit.MILLISECONDS));
    }

    // ==================== Retry with Exponential Backoff Tests
    // ====================

    @Test
    void execute_retryExponentialBackoff_usesDelay() throws Exception {
        ApiCall call = createSimpleApiCall("backoff-call", false);
        HttpPostResponse postResponse = new HttpPostResponse();
        RetryApiCallInstruction retryInstruction = new RetryApiCallInstruction();
        retryInstruction.setMaxRetries(2);
        retryInstruction.setRetryOnHttpCodes(List.of(503));
        retryInstruction.setExponentialBackoffDelayInMillis(50);
        postResponse.setRetryApiCallInstruction(retryInstruction);
        call.setPostResponse(postResponse);

        var scheduledExecutor = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        var future = mock(ScheduledFuture.class);
        when(runtime.getScheduledExecutorService()).thenReturn(scheduledExecutor);

        // Use a flag to switch from 503 to 200; toggled when the scheduler is invoked.
        var retried = new AtomicBoolean(false);
        when(mockResponse.getHttpCode()).thenAnswer(inv -> retried.get() ? 200 : 503);
        when(mockResponse.getContentAsString()).thenAnswer(inv -> retried.get() ? "ok" : "retry");
        when(mockResponse.getHttpCodeMessage()).thenAnswer(inv -> retried.get() ? "OK" : "Service Unavailable");
        when(mockResponse.getHttpHeader()).thenReturn(new HashMap<>());

        // When the scheduled executor is invoked (retry with delay), flip the flag,
        // execute the callable, and return a future whose get() yields the result.
        doAnswer(inv -> {
            retried.set(true);
            @SuppressWarnings("unchecked")
            Callable<IResponse> callable = inv.getArgument(0);
            IResponse result = callable.call();
            when(future.get()).thenReturn(result);
            return future;
        }).when(scheduledExecutor).schedule(any(Callable.class), anyLong(), any());

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        verify(scheduledExecutor, times(1)).schedule(
                any(Callable.class), eq(50L),
                eq(TimeUnit.MILLISECONDS));
    }

    // ==================== Null PostResponse RetryInstruction Tests
    // ====================

    @Test
    void execute_postResponseWithNullRetryInstruction_noRetry() throws Exception {
        ApiCall call = createSimpleApiCall("no-retry", false);
        HttpPostResponse postResponse = new HttpPostResponse();
        postResponse.setRetryApiCallInstruction(null);
        call.setPostResponse(postResponse);
        setupSuccessResponse(200, "ok", "text/plain");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        // Only one send() call — no retry
        verify(mockRequest, times(1)).send();
    }

    // ==================== Exception from request.send() Tests ====================

    // ==================== Timeout / Response Size Cap ====================

    @Test
    void execute_appliesDefaultTimeoutAndResponseSizeCapToRequest() throws Exception {
        ApiCall call = createSimpleApiCall("bounded-call", false);
        setupSuccessResponse(200, "ok", "text/plain");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        verify(mockRequest).setTimeout(DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        verify(mockRequest).setMaxResponseSize(ApiCallExecutor.MAX_TRANSPORT_RESPONSE_SIZE_BYTES);
    }

    @Test
    void execute_perCallTimeoutOverridesDefault() throws Exception {
        ApiCall call = createSimpleApiCall("tight-call", false);
        call.setTimeoutInMillis(1_500);
        call.setMaxResponseSizeInBytes(4_096);
        setupSuccessResponse(200, "ok", "text/plain");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        verify(mockRequest).setTimeout(1_500L, TimeUnit.MILLISECONDS);
    }

    /**
     * The client rejects an oversize body with an exception — there is no partial
     * body to keep — so handing it the memory cap would make
     * {@code truncateResponseBody} unreachable and turn every response above
     * {@code maxResponseSizeInBytes} into a failed turn instead of a truncated one.
     */
    @Test
    void execute_transportCapStaysAboveTheMemoryCapSoTruncationRemainsReachable() throws Exception {
        ApiCall call = createSimpleApiCall("tight-call", false);
        call.setMaxResponseSizeInBytes(4_096);
        setupSuccessResponse(200, "ok", "text/plain");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        ArgumentCaptor<Integer> transportCap = ArgumentCaptor.forClass(Integer.class);
        verify(mockRequest).setMaxResponseSize(transportCap.capture());
        assertTrue(transportCap.getValue() > 4_096,
                "the transport ceiling must exceed the in-memory cap, otherwise the truncation branch is dead code — got "
                        + transportCap.getValue());
        assertEquals(ApiCallExecutor.MAX_TRANSPORT_RESPONSE_SIZE_BYTES, transportCap.getValue().intValue());
    }

    @Test
    void execute_hugePerCallMemoryCapRaisesTheTransportCeiling() throws Exception {
        int hugeCap = ApiCallExecutor.MAX_TRANSPORT_RESPONSE_SIZE_BYTES * 2;
        ApiCall call = createSimpleApiCall("bulk-call", false);
        call.setMaxResponseSizeInBytes(hugeCap);
        setupSuccessResponse(200, "ok", "text/plain");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        verify(mockRequest).setMaxResponseSize(hugeCap);
    }

    @Test
    void execute_oversizedSuccessBody_isTruncatedBeforeBeingStored() throws Exception {
        ApiCall call = createSimpleApiCall("huge-call", true);
        call.setMaxResponseSizeInBytes(100);
        String hugeBody = "x".repeat(100_000);
        setupSuccessResponse(200, hugeBody, "text/plain");

        Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");

        assertEquals("x".repeat(100), result.get("body"));
        verify(prePostUtils).createMemoryEntry(eq(currentStep), eq("x".repeat(100)), eq("response"), eq("httpCalls"));
    }

    @Test
    void execute_successBodyWithinCap_isStoredUnchanged() throws Exception {
        ApiCall call = createSimpleApiCall("small-call", true);
        call.setMaxResponseSizeInBytes(100);
        setupSuccessResponse(200, "short body", "text/plain");

        Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");

        assertEquals("short body", result.get("body"));
    }

    @Test
    void execute_requestSendThrowsException_wrapsInLifecycleException() throws Exception {
        ApiCall call = createSimpleApiCall("err-call", false);
        when(mockRequest.send()).thenThrow(new IRequest.HttpRequestException("Connection refused"));

        assertThrows(LifecycleException.class,
                () -> executor.execute(call, memory, new HashMap<>(), "http://example.com"));
    }
}
