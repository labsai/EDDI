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
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ApiCallExecutor, focusing on: - L1 fix: content-type equality check
 * (equals vs startsWith) - L2 fix: retryCall returns false instead of throwing
 */
class ApiCallExecutorTest {

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
    private GlobalVariableResolver globalVariableResolver;

    @BeforeEach
    void setUp() throws Exception {
        httpClient = mock(IHttpClient.class);
        jsonSerialization = mock(IJsonSerialization.class);
        runtime = mock(IRuntime.class);
        prePostUtils = mock(PrePostUtils.class);
        secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));

        executor = new ApiCallExecutor(httpClient, jsonSerialization, runtime, prePostUtils, globalVariableResolver, secretResolver, false);

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
                secretResolver, true);
        ApiCall call = createSimpleApiCall("ssrf-call", false);
        // 169.254.169.254 is a literal IP (no DNS) blocked by UrlValidationUtils.
        assertThrows(LifecycleException.class, () -> protectedExecutor.execute(call, memory, new HashMap<>(), "http://169.254.169.254"));
    }

    @Test
    void execute_ssrfProtectionEnabled_disablesRedirectsOnPublicUrl() throws Exception {
        ApiCallExecutor protectedExecutor = new ApiCallExecutor(httpClient, jsonSerialization, runtime, prePostUtils, globalVariableResolver,
                secretResolver, true);
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
        // 100000 * 2^9 = 51,200,000 — capped to the 5-minute ceiling.
        assertEquals(300_000, ApiCallExecutor.getDelayInMillis(call, true, 10));
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

        var scheduledExecutor = mock(java.util.concurrent.ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        var future = mock(java.util.concurrent.ScheduledFuture.class);
        when(future.get()).thenReturn(mockResponse);
        when(scheduledExecutor.schedule(any(java.util.concurrent.Callable.class), eq(100L), eq(java.util.concurrent.TimeUnit.MILLISECONDS)))
                .thenReturn(future);
        when(runtime.getScheduledExecutorService()).thenReturn(scheduledExecutor);

        setupSuccessResponse(200, "ok", "text/plain");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        verify(scheduledExecutor).schedule(any(java.util.concurrent.Callable.class), eq(100L), eq(java.util.concurrent.TimeUnit.MILLISECONDS));
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

        var scheduledExecutor = mock(java.util.concurrent.ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        var future = mock(java.util.concurrent.ScheduledFuture.class);
        when(runtime.getScheduledExecutorService()).thenReturn(scheduledExecutor);

        // Use a flag to switch from 503 to 200; toggled when the scheduler is invoked.
        var retried = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(mockResponse.getHttpCode()).thenAnswer(inv -> retried.get() ? 200 : 503);
        when(mockResponse.getContentAsString()).thenAnswer(inv -> retried.get() ? "ok" : "retry");
        when(mockResponse.getHttpCodeMessage()).thenAnswer(inv -> retried.get() ? "OK" : "Service Unavailable");
        when(mockResponse.getHttpHeader()).thenReturn(new HashMap<>());

        // When the scheduled executor is invoked (retry with delay), flip the flag,
        // execute the callable, and return a future whose get() yields the result.
        doAnswer(inv -> {
            retried.set(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.Callable<IResponse> callable = inv.getArgument(0);
            IResponse result = callable.call();
            when(future.get()).thenReturn(result);
            return future;
        }).when(scheduledExecutor).schedule(any(java.util.concurrent.Callable.class), anyLong(), any());

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        verify(scheduledExecutor, times(1)).schedule(
                any(java.util.concurrent.Callable.class), eq(50L),
                eq(java.util.concurrent.TimeUnit.MILLISECONDS));
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

    @Test
    void execute_requestSendThrowsException_wrapsInLifecycleException() throws Exception {
        ApiCall call = createSimpleApiCall("err-call", false);
        when(mockRequest.send()).thenThrow(new ai.labs.eddi.engine.httpclient.IRequest.HttpRequestException("Connection refused"));

        assertThrows(ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException.class,
                () -> executor.execute(call, memory, new HashMap<>(), "http://example.com"));
    }
}
