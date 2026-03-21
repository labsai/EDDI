package ai.labs.eddi.modules.httpcalls.impl;

import ai.labs.eddi.configs.httpcalls.model.*;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.*;

/**
 * Tests for HttpCallExecutor, focusing on:
 * - L1 fix: content-type equality check (equals vs startsWith)
 * - L2 fix: retryCall returns false instead of throwing
 */
class HttpCallExecutorTest {

    private IHttpClient httpClient;
    private IJsonSerialization jsonSerialization;
    private IRuntime runtime;
    private PrePostUtils prePostUtils;
    private HttpCallExecutor executor;

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

        executor = new HttpCallExecutor(httpClient, jsonSerialization, runtime, prePostUtils, secretResolver);

        memory = mock(IConversationMemory.class);
        currentStep = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);

        mockRequest = mock(IRequest.class);
        mockResponse = mock(IResponse.class);
        when(mockRequest.toMap()).thenReturn(new HashMap<>());

        // Default: let prePostUtils pass through template objects
        when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(prePostUtils.templateValues(anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

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
        HttpCall call = createSimpleHttpCall("test-call", true);
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
        HttpCall call = createSimpleHttpCall("test-call", true);
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
        HttpCall call = createSimpleHttpCall("test-call", true);
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
        HttpCall call = createSimpleHttpCall("test-call", true);
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
        HttpCall call = createSimpleHttpCall("test-call", true);
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
        HttpCall call = createSimpleHttpCall("test-call", false);
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
        HttpCall call = createSimpleHttpCall("test-call", false);
        HttpPostResponse postResponse = new HttpPostResponse();
        RetryHttpCallInstruction retryInstruction = new RetryHttpCallInstruction();
        retryInstruction.setMaxRetries(2);
        retryInstruction.setRetryOnHttpCodes(List.of(503));
        retryInstruction.setExponentialBackoffDelayInMillis(0); // No delay for test
        postResponse.setRetryHttpCallInstruction(retryInstruction);
        call.setPostResponse(postResponse);

        // First call returns 503 (retry), second returns 503 (retry), third time maxRetries exceeded
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
        HttpCall call = createSimpleHttpCall("test-call", false);
        HttpPostResponse postResponse = new HttpPostResponse();
        RetryHttpCallInstruction retryInstruction = new RetryHttpCallInstruction();
        retryInstruction.setMaxRetries(3);
        retryInstruction.setRetryOnHttpCodes(List.of(503));
        postResponse.setRetryHttpCallInstruction(retryInstruction);
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
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(null, memory, new HashMap<>(), "http://example.com"));
    }

    @Test
    void execute_nullMemory_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(new HttpCall(), null, new HashMap<>(), "http://example.com"));
    }

    @Test
    void execute_nullTemplateData_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(new HttpCall(), memory, null, "http://example.com"));
    }

    @Test
    void execute_emptyServerUrl_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(new HttpCall(), memory, new HashMap<>(), "  "));
    }

    // ==================== Helpers ====================

    private HttpCall createSimpleHttpCall(String name, boolean saveResponse) {
        HttpCall call = new HttpCall();
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
