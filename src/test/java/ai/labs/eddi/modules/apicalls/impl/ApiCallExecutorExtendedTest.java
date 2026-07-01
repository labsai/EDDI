/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.*;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ApiCallExecutor Extended Branch Coverage Tests")
class ApiCallExecutorExtendedTest {

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
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));

        executor = new ApiCallExecutor(httpClient, jsonSerialization, runtime, prePostUtils, globalVariableResolver, secretResolver, false);

        memory = mock(IConversationMemory.class);
        currentStep = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);

        mockRequest = mock(IRequest.class);
        mockResponse = mock(IResponse.class);
        when(mockRequest.toMap()).thenReturn(new HashMap<>());

        when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(prePostUtils.templateValues(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        when(httpClient.newRequest(any(URI.class), any())).thenReturn(mockRequest);
        when(mockRequest.send()).thenReturn(mockResponse);
        when(mockRequest.setBodyEntity(any(), any(), any())).thenReturn(mockRequest);
        when(mockRequest.setHttpHeader(any(), any())).thenReturn(mockRequest);
        when(mockRequest.setQueryParam(any(), any())).thenReturn(mockRequest);
    }

    @Nested
    @DisplayName("Null argument validation")
    class NullValidation {

        @Test
        @DisplayName("null targetServerUrl throws IllegalArgumentException")
        void nullServerUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> executor.execute(new ApiCall(), memory, new HashMap<>(), null));
        }
    }

    @Nested
    @DisplayName("Response header handling")
    class ResponseHeaderHandling {

        @Test
        @DisplayName("response header object name with null httpHeader — uses empty map")
        void responseHeaderNullHttpHeader() throws Exception {
            // saveResponse=false avoids the getHttpHeader().get("Content-Type") NPE path
            ApiCall call = createCall("header-null", false);
            call.setResponseHeaderObjectName("respHeaders");

            when(mockResponse.getHttpCode()).thenReturn(200);
            when(mockResponse.getContentAsString()).thenReturn("ok");
            when(mockResponse.getHttpHeader()).thenReturn(null);

            Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");
            assertNotNull(result.get("headers"));
        }
    }

    @Nested
    @DisplayName("retryCall branches")
    class RetryCallBranches {

        @Test
        @DisplayName("retryOnHttpCodes empty but valuePathMatchers present — checks matchers")
        void retryWithValuePathMatchers() throws Exception {
            ApiCall call = createCall("retry-matcher", false);
            HttpPostResponse postResponse = new HttpPostResponse();
            RetryApiCallInstruction retryInstruction = new RetryApiCallInstruction();
            retryInstruction.setMaxRetries(2);
            retryInstruction.setRetryOnHttpCodes(List.of()); // empty
            retryInstruction.setExponentialBackoffDelayInMillis(0);
            var matcher = new RetryApiCallInstruction.MatchingInfo();
            matcher.setValuePath("result.status");
            matcher.setEquals("error");
            matcher.setTrueIfNoMatch(false);
            retryInstruction.setResponseValuePathMatchers(List.of(matcher));
            postResponse.setRetryApiCallInstruction(retryInstruction);
            call.setPostResponse(postResponse);

            when(mockResponse.getHttpCode()).thenReturn(200);
            when(mockResponse.getContentAsString()).thenReturn("{\"result\":{\"status\":\"ok\"}}");
            when(mockResponse.getHttpHeader()).thenReturn(new HashMap<>());

            assertDoesNotThrow(() -> executor.execute(call, memory, new HashMap<>(), "http://example.com"));
        }

        @Test
        @DisplayName("maxRetries=0 — no retry regardless of httpCode")
        void maxRetriesZero() throws Exception {
            ApiCall call = createCall("no-retry", false);
            HttpPostResponse postResponse = new HttpPostResponse();
            RetryApiCallInstruction retryInstruction = new RetryApiCallInstruction();
            retryInstruction.setMaxRetries(0);
            retryInstruction.setRetryOnHttpCodes(List.of(503));
            postResponse.setRetryApiCallInstruction(retryInstruction);
            call.setPostResponse(postResponse);

            when(mockResponse.getHttpCode()).thenReturn(503);
            when(mockResponse.getContentAsString()).thenReturn("unavailable");
            when(mockResponse.getHttpCodeMessage()).thenReturn("Service Unavailable");
            when(mockResponse.getHttpHeader()).thenReturn(new HashMap<>());

            executor.execute(call, memory, new HashMap<>(), "http://example.com");
            verify(mockRequest, times(1)).send();
        }
    }

    @Nested
    @DisplayName("Path building branches")
    class PathBuildingBranches {

        @Test
        @DisplayName("empty path — doesn't add slash")
        void emptyPath() throws Exception {
            ApiCall call = createCall("empty-path", false);
            call.getRequest().setPath("");
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");
            verify(httpClient).newRequest(eq(URI.create("http://example.com")), any());
        }

        @Test
        @DisplayName("path starting with / — uses as-is")
        void slashPath() throws Exception {
            ApiCall call = createCall("slash-path", false);
            call.getRequest().setPath("/api/test");
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");
            verify(httpClient).newRequest(eq(URI.create("http://example.com/api/test")), any());
        }

        @Test
        @DisplayName("path with spaces gets trimmed")
        void spacePath() throws Exception {
            ApiCall call = createCall("space-path", false);
            call.getRequest().setPath("  /api/test  ");
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");
            verify(httpClient).newRequest(eq(URI.create("http://example.com/api/test")), any());
        }
    }

    @Nested
    @DisplayName("Request body contentType branches")
    class ContentTypeBranches {

        @Test
        @DisplayName("empty body — doesn't set body entity")
        void emptyBody() throws Exception {
            ApiCall call = createCall("empty-body", false);
            call.getRequest().setBody("");
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");
            verify(mockRequest, never()).setBodyEntity(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("null contentType — defaults to text/plain")
        void nullContentType() throws Exception {
            ApiCall call = createCall("null-ct", false);
            call.getRequest().setBody("{\"key\":\"value\"}");
            call.getRequest().setContentType(null);
            call.getRequest().setMethod("POST");
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");
            verify(mockRequest).setBodyEntity("{\"key\":\"value\"}", "utf-8", "text/plain");
        }
    }

    @Nested
    @DisplayName("Scrub sensitive headers branches")
    class ScrubHeadersBranches {

        @Test
        @DisplayName("eddivault ref in header value — scrubbed")
        void eddiVaultRef() throws Exception {
            ApiCall call = createCall("eddivault-call", false);
            Map<String, Object> requestMap = new HashMap<>();
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put("X-Custom-Auth", "${eddivault:my-secret}");
            requestMap.put("headers", headers);
            when(mockRequest.toMap()).thenReturn(requestMap);
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");

            var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(prePostUtils, atLeastOnce()).createMemoryEntry(
                    eq(currentStep), captor.capture(), contains("Request"), eq("httpCalls"));

            var capturedMap = (Map<String, Object>) captor.getValue();
            var scrubbedHeaders = (Map<String, Object>) capturedMap.get("headers");
            assertEquals("<REDACTED>", scrubbedHeaders.get("X-Custom-Auth"));
        }

        @Test
        @DisplayName("non-sensitive header with normal value — not scrubbed")
        void nonSensitiveHeader() throws Exception {
            ApiCall call = createCall("normal-call", false);
            Map<String, Object> requestMap = new HashMap<>();
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/json");
            requestMap.put("headers", headers);
            when(mockRequest.toMap()).thenReturn(requestMap);
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");

            var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(prePostUtils, atLeastOnce()).createMemoryEntry(
                    eq(currentStep), captor.capture(), contains("Request"), eq("httpCalls"));

            var capturedMap = (Map<String, Object>) captor.getValue();
            var scrubbedHeaders = (Map<String, Object>) capturedMap.get("headers");
            assertEquals("application/json", scrubbedHeaders.get("Accept"));
        }

        @Test
        @DisplayName("credential header — scrubbed")
        void credentialHeader() throws Exception {
            ApiCall call = createCall("cred-call", false);
            Map<String, Object> requestMap = new HashMap<>();
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put("X-Credential-Info", "secret-data");
            requestMap.put("headers", headers);
            when(mockRequest.toMap()).thenReturn(requestMap);
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");

            var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(prePostUtils, atLeastOnce()).createMemoryEntry(
                    eq(currentStep), captor.capture(), contains("Request"), eq("httpCalls"));

            var capturedMap = (Map<String, Object>) captor.getValue();
            var scrubbedHeaders = (Map<String, Object>) capturedMap.get("headers");
            assertEquals("<REDACTED>", scrubbedHeaders.get("X-Credential-Info"));
        }

        @Test
        @DisplayName("apikey header (lowercase) — scrubbed")
        void apikeyHeader() throws Exception {
            ApiCall call = createCall("apikey-call", false);
            Map<String, Object> requestMap = new HashMap<>();
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put("apikey", "my-key-value");
            requestMap.put("headers", headers);
            when(mockRequest.toMap()).thenReturn(requestMap);
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");

            var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(prePostUtils, atLeastOnce()).createMemoryEntry(
                    eq(currentStep), captor.capture(), contains("Request"), eq("httpCalls"));

            var capturedMap = (Map<String, Object>) captor.getValue();
            var scrubbedHeaders = (Map<String, Object>) capturedMap.get("headers");
            assertEquals("<REDACTED>", scrubbedHeaders.get("apikey"));
        }

        @Test
        @DisplayName("headers obj is not a Map — no NPE")
        void headersNotAMap() throws Exception {
            ApiCall call = createCall("non-map-call", false);
            Map<String, Object> requestMap = new HashMap<>();
            requestMap.put("headers", "not-a-map");
            when(mockRequest.toMap()).thenReturn(requestMap);
            setupSuccessResponse(200, "ok", "text/plain");

            assertDoesNotThrow(() -> executor.execute(call, memory, new HashMap<>(), "http://example.com"));
        }
    }

    @Nested
    @DisplayName("getDelayInMillis branches")
    class DelayBranches {

        @Test
        @DisplayName("retryCall=true with null exponentialBackoffDelay — delay stays 0, uses preRequest delay")
        void retryNullBackoff() throws Exception {
            ApiCall call = createCall("backoff-null", false);
            HttpPostResponse postResponse = new HttpPostResponse();
            RetryApiCallInstruction retryInstruction = new RetryApiCallInstruction();
            retryInstruction.setMaxRetries(2);
            retryInstruction.setRetryOnHttpCodes(List.of(503));
            retryInstruction.setExponentialBackoffDelayInMillis(null);
            postResponse.setRetryApiCallInstruction(retryInstruction);
            call.setPostResponse(postResponse);

            // Pre-request delay of 0 means no scheduled executor involved
            when(mockResponse.getHttpCode()).thenReturn(503, 503, 200);
            when(mockResponse.getContentAsString()).thenReturn("err", "err", "ok");
            when(mockResponse.getHttpCodeMessage()).thenReturn("Err", "Err", "OK");
            when(mockResponse.getHttpHeader()).thenReturn(new HashMap<>());

            assertDoesNotThrow(() -> executor.execute(call, memory, new HashMap<>(), "http://example.com"));
        }
    }

    // --- Helpers ---

    private ApiCall createCall(String name, boolean saveResponse) {
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
