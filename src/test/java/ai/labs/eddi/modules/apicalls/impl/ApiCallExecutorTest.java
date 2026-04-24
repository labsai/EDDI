/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.*;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.ICompleteListener;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
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

    @BeforeEach
    void setUp() throws Exception {
        httpClient = mock(IHttpClient.class);
        jsonSerialization = mock(IJsonSerialization.class);
        runtime = mock(IRuntime.class);
        prePostUtils = mock(PrePostUtils.class);
        SecretResolver secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));

        executor = new ApiCallExecutor(httpClient, jsonSerialization, runtime, prePostUtils, secretResolver);

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
        headers.put("X-Custom-Auth", "${eddivault:my-secret}");
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
}
