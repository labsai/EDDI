/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.HttpPostResponse;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.apicalls.model.RetryApiCallInstruction;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The executor used to wrap its whole request/retry loop in a {@code try { … }
 * catch (ApiCallsValidationException)} for an exception that no code path could
 * ever throw; removing it re-shaped the loop and hard-coded the
 * {@code validationError} flag handed to the post-response instructions.
 * <p>
 * The removal is behaviour-neutral by construction, so these tests do not try
 * to distinguish old from new code — they pin the observable behaviour of the
 * re-shaped loop (how many times the request is sent, which http code the
 * post-response stage sees, what lands in conversation memory) so that a future
 * change to that block cannot silently alter it.
 */
@DisplayName("ApiCallExecutor — request/retry loop and post-response stage")
class ApiCallExecutorValidationErrorTest {

    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
    private static final int DEFAULT_MAX_RESPONSE_SIZE = 2_000_000;
    private static final String KEY_HTTP_CALLS = "httpCalls";

    private IJsonSerialization jsonSerialization;
    private PrePostUtils prePostUtils;
    private ApiCallExecutor executor;
    private IConversationMemory memory;
    private IWritableConversationStep currentStep;
    private IRequest mockRequest;

    @BeforeEach
    void setUp() throws Exception {
        IHttpClient httpClient = mock(IHttpClient.class);
        jsonSerialization = mock(IJsonSerialization.class);
        IRuntime runtime = mock(IRuntime.class);
        prePostUtils = mock(PrePostUtils.class);

        SecretResolver secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));

        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        executor = new ApiCallExecutor(httpClient, jsonSerialization, runtime, prePostUtils, globalVariableResolver, secretResolver,
                callerIdentityResolver, new CallerIdentityContext(null, null), new RequestRedactor(callerIdentityResolver), null, false,
                DEFAULT_TIMEOUT_MILLIS, DEFAULT_MAX_RESPONSE_SIZE);

        memory = mock(IConversationMemory.class);
        currentStep = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);

        mockRequest = mock(IRequest.class);
        when(mockRequest.toMap()).thenReturn(new HashMap<>());

        when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(prePostUtils.templateValues(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        when(httpClient.newRequest(any(URI.class), any())).thenReturn(mockRequest);
        when(mockRequest.setBodyEntity(any(), any(), any())).thenReturn(mockRequest);
        when(mockRequest.setHttpHeader(any(), any())).thenReturn(mockRequest);
        when(mockRequest.setQueryParam(any(), any())).thenReturn(mockRequest);
    }

    @Test
    @DisplayName("a retryable http code is re-sent up to maxRetries and the post-response stage still runs exactly once")
    void retryableHttpCodeIsResentUntilMaxRetries() throws Exception {
        ApiCall call = apiCall(retryOn(500, 2));
        // Build the stubs BEFORE opening the outer stubbing: response() stubs its own
        // mock, and Mockito treats a nested when() inside an unfinished when() as
        // UnfinishedStubbing.
        IResponse first = response(500, "boom", null);
        IResponse second = response(500, "boom", null);
        IResponse third = response(500, "boom", null);
        when(mockRequest.send()).thenReturn(first, second, third);

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        // maxRetries=2 means one initial attempt plus two retries.
        verify(mockRequest, times(3)).send();
        verify(prePostUtils, times(1)).runPostResponse(eq(memory), eq(call.getPostResponse()), any(), eq(500), eq(false));
    }

    @Test
    @DisplayName("the retry loop stops as soon as a call succeeds and the post-response stage sees the final http code")
    void retryLoopStopsOnSuccessAndReportsFinalHttpCode() throws Exception {
        ApiCall call = apiCall(retryOn(500, 2));
        call.setSaveResponse(true);
        var deserialized = Map.of("ok", true);
        when(jsonSerialization.deserialize("{\"ok\":true}", Object.class)).thenReturn(deserialized);
        IResponse failure = response(500, "boom", null);
        IResponse success = response(200, "{\"ok\":true}", "application/json");
        when(mockRequest.send()).thenReturn(failure, success);

        Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");

        verify(mockRequest, times(2)).send();
        verify(prePostUtils, times(1)).runPostResponse(eq(memory), eq(call.getPostResponse()), any(), eq(200), eq(false));
        assertSame(deserialized, result.get("body"));
        assertEquals(200, result.get("httpCode"));
    }

    @Test
    @DisplayName("a non-retryable error response is sent once and its body is truncated to 2000 chars in memory")
    void errorResponseIsStoredTruncatedAndNotRetried() throws Exception {
        ApiCall call = apiCall(new HttpPostResponse());
        call.setSaveResponse(true);
        String errorBody = "x".repeat(2500);
        IResponse errorResponse = response(500, errorBody, null);
        when(mockRequest.send()).thenReturn(errorResponse);

        Map<String, Object> templateDataObjects = new HashMap<>();
        Map<String, Object> result = executor.execute(call, memory, templateDataObjects, "http://example.com");

        verify(mockRequest, times(1)).send();
        verify(prePostUtils).createMemoryEntry(currentStep, "x".repeat(2000), "responseError", KEY_HTTP_CALLS);
        verify(prePostUtils).createMemoryEntry(currentStep, 500, "responseHttpCode", KEY_HTTP_CALLS);
        assertEquals("x".repeat(2000), templateDataObjects.get("responseError"));
        assertEquals(500, templateDataObjects.get("responseHttpCode"));
        // An error body is never promoted to the response OBJECT (memory sees it
        // only under the *Error key, verified above) — but it IS the tool result:
        // an LLM whose failed call returned "{}" could not report the failure, so
        // the result map carries the same truncated body plus the code.
        assertEquals("x".repeat(2000), result.get("body"),
                "the truncated error body must reach the tool result");
        assertEquals(500, result.get("httpCode"));
        verify(prePostUtils, times(1)).runPostResponse(eq(memory), eq(call.getPostResponse()), any(), eq(500), eq(false));
    }

    @Test
    @DisplayName("an error response whose code is not in retryOnHttpCodes is sent once and reports the real http code")
    void errorResponseWithNonMatchingRetryCodeIsNotRetried() throws Exception {
        // A retry instruction is configured, but no http code qualifies for a retry —
        // the post-response stage must still see the real code and
        // validationError=false.
        ApiCall call = apiCall(retryOn(500, 2));
        call.getPostResponse().getRetryApiCallInstruction().setRetryOnHttpCodes(List.of());
        IResponse errorResponse = response(503, "gateway down", "text/plain");
        when(mockRequest.send()).thenReturn(errorResponse);

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        verify(mockRequest, times(1)).send();
        verify(prePostUtils, times(1)).runPostResponse(eq(memory), eq(call.getPostResponse()), any(), eq(503), eq(false));
    }

    @Test
    @DisplayName("response headers are exposed to templates and to the result when a header object name is configured")
    void responseHeadersAreExposedWhenConfigured() throws Exception {
        ApiCall call = apiCall(new HttpPostResponse());
        call.setResponseHeaderObjectName("responseHeaders");
        IResponse response = response(200, "{}", "application/json");
        when(mockRequest.send()).thenReturn(response);

        Map<String, Object> templateDataObjects = new HashMap<>();
        Map<String, Object> result = executor.execute(call, memory, templateDataObjects, "http://example.com");

        assertEquals(response.getHttpHeader(), result.get("headers"));
        assertEquals(response.getHttpHeader(), templateDataObjects.get("responseHeaders"));
        verify(prePostUtils).createMemoryEntry(currentStep, response.getHttpHeader(), "responseHeaders", KEY_HTTP_CALLS);
    }

    @Test
    @DisplayName("post-response instructions are told the call did not fail validation")
    void postResponseIsInvokedWithoutValidationError() throws Exception {
        ApiCall call = apiCall(retryOn(500, 2));
        IResponse okResponse = response(200, "{}", "application/json");
        when(mockRequest.send()).thenReturn(okResponse);

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        verify(prePostUtils).runPostResponse(eq(memory), eq(call.getPostResponse()), any(), eq(200), eq(false));
    }

    private static HttpPostResponse retryOn(int httpCode, int maxRetries) {
        var retryInstruction = new RetryApiCallInstruction();
        retryInstruction.setMaxRetries(maxRetries);
        retryInstruction.setRetryOnHttpCodes(List.of(httpCode));
        // No backoff: a non-zero delay would push the send onto the runtime's
        // scheduled executor, which is not what these tests are about.
        retryInstruction.setExponentialBackoffDelayInMillis(0);

        var postResponse = new HttpPostResponse();
        postResponse.setRetryApiCallInstruction(retryInstruction);
        return postResponse;
    }

    private static ApiCall apiCall(HttpPostResponse postResponse) {
        ApiCall call = new ApiCall();
        call.setName("test-call");
        call.setSaveResponse(false);
        call.setFireAndForget(false);
        call.setResponseObjectName("response");

        Request request = new Request();
        request.setPath("/api/test");
        request.setMethod("GET");
        call.setRequest(request);
        call.setPostResponse(postResponse);

        return call;
    }

    private static IResponse response(int httpCode, String body, String contentType) {
        IResponse response = mock(IResponse.class);
        Map<String, String> headers = new HashMap<>();
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        when(response.getHttpCode()).thenReturn(httpCode);
        when(response.getContentAsString()).thenReturn(body);
        when(response.getHttpHeader()).thenReturn(headers);
        return response;
    }
}
