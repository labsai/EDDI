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
import org.mockito.Mock;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Additional branch coverage tests for {@link ApiCallExecutor} focusing on: -
 * null argument validation (call, memory, templateDataObjects) - fireAndForget
 * path - response content type branches (JSON, text, not-present, unknown) -
 * saveResponse=true path with various content types - retryCall with
 * retryOnHttpCodes match - retryCall with null postResponse - retryCall with
 * null retryApiCallInstruction - path building: no slash, http:// prefix, body
 * present with custom content type - request delay > 0 (scheduled executor) -
 * scrubSensitiveHeaders with various header names - empty targetServerUrl
 */
@DisplayName("ApiCallExecutor — Branch Coverage v2")
class ApiCallExecutorBranchCoverageTest {

    @Mock
    private IHttpClient httpClient;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private IRuntime runtime;
    @Mock
    private PrePostUtils prePostUtils;
    @Mock
    private GlobalVariableResolver globalVariableResolver;
    @Mock
    private SecretResolver secretResolver;
    @Mock
    private IConversationMemory memory;
    @Mock
    private IWritableConversationStep currentStep;
    @Mock
    private IRequest mockRequest;
    @Mock
    private IResponse mockResponse;

    private ApiCallExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        executor = new ApiCallExecutor(httpClient, jsonSerialization, runtime,
                prePostUtils, globalVariableResolver, secretResolver, false);

        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(mockRequest.toMap()).thenReturn(new HashMap<>());
        when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(prePostUtils.templateValues(anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(secretResolver.resolveValue(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(mockRequest);
        when(mockRequest.send()).thenReturn(mockResponse);
        when(mockRequest.setBodyEntity(any(), any(), any())).thenReturn(mockRequest);
        when(mockRequest.setHttpHeader(any(), any())).thenReturn(mockRequest);
        when(mockRequest.setQueryParam(any(), any())).thenReturn(mockRequest);
    }

    // ═══════════════════════════════════════════════════════════════
    // Null argument checks
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Null argument validation")
    class NullArgValidation {

        @Test
        @DisplayName("null call → IllegalArgumentException")
        void nullCall() {
            assertThrows(IllegalArgumentException.class,
                    () -> executor.execute(null, memory, new HashMap<>(), "http://x.com"));
        }

        @Test
        @DisplayName("null memory → IllegalArgumentException")
        void nullMemory() {
            assertThrows(IllegalArgumentException.class,
                    () -> executor.execute(new ApiCall(), null, new HashMap<>(), "http://x.com"));
        }

        @Test
        @DisplayName("null templateDataObjects → IllegalArgumentException")
        void nullTemplateData() {
            assertThrows(IllegalArgumentException.class,
                    () -> executor.execute(new ApiCall(), memory, null, "http://x.com"));
        }

        @Test
        @DisplayName("empty targetServerUrl → IllegalArgumentException")
        void emptyTargetServer() {
            assertThrows(IllegalArgumentException.class,
                    () -> executor.execute(new ApiCall(), memory, new HashMap<>(), "  "));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // saveResponse=true — content type branches
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("saveResponse=true content type branches")
    class SaveResponseContentTypes {

        @Test
        @DisplayName("application/json content type → deserializes as Object")
        void jsonContentType() throws Exception {
            ApiCall call = createCall("json-call", true);
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json; charset=utf-8");
            when(mockResponse.getHttpCode()).thenReturn(200);
            when(mockResponse.getContentAsString()).thenReturn("{\"key\":\"value\"}");
            when(mockResponse.getHttpHeader()).thenReturn(headers);
            when(jsonSerialization.deserialize(eq("{\"key\":\"value\"}"), eq(Object.class)))
                    .thenReturn(Map.of("key", "value"));

            Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");
            assertNotNull(result.get("body"));
            assertEquals(200, result.get("httpCode"));
        }

        @Test
        @DisplayName("text/html content type → uses raw body string")
        void textHtmlContentType() throws Exception {
            ApiCall call = createCall("html-call", true);
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "text/html");
            when(mockResponse.getHttpCode()).thenReturn(200);
            when(mockResponse.getContentAsString()).thenReturn("<html>hi</html>");
            when(mockResponse.getHttpHeader()).thenReturn(headers);

            Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");
            assertEquals("<html>hi</html>", result.get("body"));
        }

        @Test
        @DisplayName("null content type → treated as <not-present>, uses raw body")
        void nullContentType() throws Exception {
            ApiCall call = createCall("null-ct-call", true);
            Map<String, String> headers = new HashMap<>();
            // No Content-Type header
            when(mockResponse.getHttpCode()).thenReturn(200);
            when(mockResponse.getContentAsString()).thenReturn("plain text");
            when(mockResponse.getHttpHeader()).thenReturn(headers);

            Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");
            assertEquals("plain text", result.get("body"));
        }

        @Test
        @DisplayName("image/png content type → logs warning, uses raw body")
        void imageContentType() throws Exception {
            ApiCall call = createCall("image-call", true);
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "image/png");
            when(mockResponse.getHttpCode()).thenReturn(200);
            when(mockResponse.getContentAsString()).thenReturn("binary-data");
            when(mockResponse.getHttpHeader()).thenReturn(headers);

            Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");
            assertEquals("binary-data", result.get("body"));
        }

        @Test
        @DisplayName("non-2xx response with saveResponse=true → doesn't save body")
        void non2xxResponse() throws Exception {
            ApiCall call = createCall("fail-call", true);
            when(mockResponse.getHttpCode()).thenReturn(500);
            when(mockResponse.getContentAsString()).thenReturn("error");
            when(mockResponse.getHttpCodeMessage()).thenReturn("Internal Server Error");
            when(mockResponse.getHttpHeader()).thenReturn(new HashMap<>());

            Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");
            assertFalse(result.containsKey("body"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // retryCall — match httpCodes
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("retryCall — http code matching")
    class RetryHttpCodeMatch {

        @Test
        @DisplayName("retryOnHttpCodes doesn't match → no retry")
        void noMatchNoRetry() throws Exception {
            ApiCall call = createCall("no-match-call", false);
            HttpPostResponse postResponse = new HttpPostResponse();
            RetryApiCallInstruction retry = new RetryApiCallInstruction();
            retry.setMaxRetries(2);
            retry.setRetryOnHttpCodes(List.of(503));
            retry.setExponentialBackoffDelayInMillis(0);
            postResponse.setRetryApiCallInstruction(retry);
            call.setPostResponse(postResponse);

            when(mockResponse.getHttpCode()).thenReturn(400);
            when(mockResponse.getContentAsString()).thenReturn("bad request");
            when(mockResponse.getHttpCodeMessage()).thenReturn("Bad Request");
            when(mockResponse.getHttpHeader()).thenReturn(new HashMap<>());

            executor.execute(call, memory, new HashMap<>(), "http://example.com");
            verify(mockRequest, times(1)).send();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Path building — http prefix, no slash, custom content type
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Path building edge cases")
    class PathBuilding {

        @Test
        @DisplayName("path starting with http — used as full URL")
        void httpPath() throws Exception {
            ApiCall call = createCall("http-path", false);
            call.getRequest().setPath("http://other-server.com/api");
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");
            verify(httpClient).newRequest(eq(URI.create("http://other-server.com/api")), any());
        }

        @Test
        @DisplayName("path without slash and not http — slash prepended")
        void noSlashPath() throws Exception {
            ApiCall call = createCall("no-slash", false);
            call.getRequest().setPath("api/test");
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");
            verify(httpClient).newRequest(eq(URI.create("http://example.com/api/test")), any());
        }

        @Test
        @DisplayName("body with custom content type")
        void bodyWithCustomContentType() throws Exception {
            ApiCall call = createCall("custom-ct", false);
            call.getRequest().setBody("<xml>data</xml>");
            call.getRequest().setContentType("application/xml");
            call.getRequest().setMethod("POST");
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");
            verify(mockRequest).setBodyEntity("<xml>data</xml>", "utf-8", "application/xml");
        }

        @Test
        @DisplayName("request with headers and query params")
        void headersAndQueryParams() throws Exception {
            ApiCall call = createCall("headers-qp", false);
            call.getRequest().setHeaders(Map.of("Accept", "application/json"));
            call.getRequest().setQueryParams(Map.of("page", "1"));
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");
            verify(mockRequest).setHttpHeader("Accept", "application/json");
            verify(mockRequest).setQueryParam("page", "1");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // scrubSensitiveHeaders — comprehensive header name checks
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("scrubSensitiveHeaders — all header name patterns")
    class ScrubHeaders {

        @Test
        @DisplayName("Authorization header → scrubbed")
        void authorizationHeader() throws Exception {
            verifyScrubbing("Authorization", "Bearer xyz", true);
        }

        @Test
        @DisplayName("X-API-Key header → scrubbed")
        void xApiKeyHeader() throws Exception {
            verifyScrubbing("X-API-Key", "key123", true);
        }

        @Test
        @DisplayName("api_key header → scrubbed")
        void apiUnderscoreKeyHeader() throws Exception {
            verifyScrubbing("api_key", "key123", true);
        }

        @Test
        @DisplayName("x-token header → scrubbed")
        void tokenHeader() throws Exception {
            verifyScrubbing("x-token", "tok123", true);
        }

        @Test
        @DisplayName("X-Secret-Value header → scrubbed")
        void secretHeader() throws Exception {
            verifyScrubbing("X-Secret-Value", "sec123", true);
        }

        @Test
        @DisplayName("vault reference in value → scrubbed")
        void vaultRef() throws Exception {
            verifyScrubbing("X-Custom", "${vault:my-secret}", true);
        }

        @Test
        @DisplayName("normal Accept header → NOT scrubbed")
        void normalHeader() throws Exception {
            verifyScrubbing("Accept", "application/json", false);
        }

        private void verifyScrubbing(String headerName, String headerValue, boolean shouldBeScrubbed)
                throws Exception {
            ApiCall call = createCall("scrub-test-" + headerName, false);
            Map<String, Object> requestMap = new HashMap<>();
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put(headerName, headerValue);
            requestMap.put("headers", headers);
            when(mockRequest.toMap()).thenReturn(requestMap);
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");

            var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(prePostUtils, atLeastOnce()).createMemoryEntry(
                    eq(currentStep), captor.capture(), contains("Request"), eq("httpCalls"));

            @SuppressWarnings("unchecked")
            var capturedMap = (Map<String, Object>) captor.getValue();
            @SuppressWarnings("unchecked")
            var scrubbedHeaders = (Map<String, Object>) capturedMap.get("headers");
            if (shouldBeScrubbed) {
                assertEquals("<REDACTED>", scrubbedHeaders.get(headerName));
            } else {
                assertEquals(headerValue, scrubbedHeaders.get(headerName));
            }
            // Reset for next test
            reset(mockRequest, prePostUtils);
            when(mockRequest.toMap()).thenReturn(new HashMap<>());
            when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any()))
                    .thenAnswer(inv -> inv.getArgument(1));
            when(prePostUtils.templateValues(anyString(), any()))
                    .thenAnswer(inv -> inv.getArgument(0));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════
    // fireAndForget=true, no batch
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("fireAndForget=true, no batch")
    class FireAndForgetNoBatch {

        @Test
        @DisplayName("should return empty map when fireAndForget is true and preRequest is null")
        void returnsEmptyMap() throws Exception {
            ApiCall call = createCall("fnf-call", false);
            call.setFireAndForget(true);
            call.setPreRequest(null);

            Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty map when fireAndForget is true and batchRequests is null")
        void returnsEmptyMapWithPreRequestNoBatch() throws Exception {
            ApiCall call = createCall("fnf-call-2", false);
            call.setFireAndForget(true);
            HttpPreRequest preRequest = new HttpPreRequest();
            preRequest.setBatchRequests(null);
            call.setPreRequest(preRequest);

            Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");

            assertTrue(result.isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // responseHeaderObjectName set
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("responseHeaderObjectName — stores headers")
    class ResponseHeaderObjectName {

        @Test
        @DisplayName("should store response headers in templateDataObjects and result")
        void storesResponseHeaders() throws Exception {
            ApiCall call = createCall("header-call", false);
            call.setResponseHeaderObjectName("respHeaders");
            Map<String, String> responseHeaders = new HashMap<>();
            responseHeaders.put("X-Request-Id", "abc123");
            responseHeaders.put("Content-Type", "application/json");
            when(mockResponse.getHttpCode()).thenReturn(200);
            when(mockResponse.getContentAsString()).thenReturn("ok");
            when(mockResponse.getHttpHeader()).thenReturn(responseHeaders);

            Map<String, Object> templateData = new HashMap<>();
            Map<String, Object> result = executor.execute(call, memory, templateData, "http://example.com");

            // Response headers should be stored in templateDataObjects under
            // responseHeaderObjectName
            assertNotNull(templateData.get("respHeaders"));
            // Result should contain headers
            assertNotNull(result.get("headers"));
            // Verify memory entry was created for headers
            verify(prePostUtils).createMemoryEntry(eq(currentStep), any(), eq("respHeaders"), eq("httpCalls"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // retryCall — null postResponse, null retry instruction, maxRetries=0
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("retryCall — no retry scenarios")
    class RetryCallNoRetry {

        @Test
        @DisplayName("null postResponse → no retry, single execution")
        void nullPostResponse() throws Exception {
            ApiCall call = createCall("no-retry-1", false);
            call.setPostResponse(null);
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");

            verify(mockRequest, times(1)).send();
        }

        @Test
        @DisplayName("null retryApiCallInstruction → no retry")
        void nullRetryInstruction() throws Exception {
            ApiCall call = createCall("no-retry-2", false);
            HttpPostResponse postResponse = new HttpPostResponse();
            postResponse.setRetryApiCallInstruction(null);
            call.setPostResponse(postResponse);
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");

            verify(mockRequest, times(1)).send();
        }

        @Test
        @DisplayName("maxRetries=0 → no retry")
        void maxRetriesZero() throws Exception {
            ApiCall call = createCall("no-retry-3", false);
            HttpPostResponse postResponse = new HttpPostResponse();
            RetryApiCallInstruction retry = new RetryApiCallInstruction();
            retry.setMaxRetries(0);
            retry.setRetryOnHttpCodes(List.of(503));
            retry.setExponentialBackoffDelayInMillis(0);
            postResponse.setRetryApiCallInstruction(retry);
            call.setPostResponse(postResponse);

            when(mockResponse.getHttpCode()).thenReturn(503);
            when(mockResponse.getContentAsString()).thenReturn("service unavailable");
            when(mockResponse.getHttpCodeMessage()).thenReturn("Service Unavailable");
            when(mockResponse.getHttpHeader()).thenReturn(new HashMap<>());

            executor.execute(call, memory, new HashMap<>(), "http://example.com");

            // maxRetries=0 means condition maxRetries >= 1 is false → no retry
            verify(mockRequest, times(1)).send();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // saveResponse=false — no body in result
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("saveResponse=false — response body not saved")
    class SaveResponseFalse {

        @Test
        @DisplayName("should not include body in result when saveResponse is false")
        void noBodyInResult() throws Exception {
            ApiCall call = createCall("no-save", false);
            setupSuccessResponse(200, "response-body", "application/json");

            Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");

            assertFalse(result.containsKey("body"));
            assertFalse(result.containsKey("httpCode"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // null targetServerUrl
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("null targetServerUrl")
    class NullTargetServerUrl {

        @Test
        @DisplayName("null targetServerUrl → IllegalArgumentException")
        void nullTargetServer() {
            assertThrows(IllegalArgumentException.class,
                    () -> executor.execute(new ApiCall(), memory, new HashMap<>(), null));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // scrubSensitiveHeaders — additional patterns
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("scrubSensitiveHeaders — additional header patterns")
    class ScrubHeadersAdditional {

        @Test
        @DisplayName("eddivault: reference in header value → scrubbed")
        void eddivaultRef() throws Exception {
            verifyScrubbing("X-Custom-Header", "${eddivault:my-api-key}", true);
        }

        @Test
        @DisplayName("credential keyword in header name → scrubbed")
        void credentialHeader() throws Exception {
            verifyScrubbing("X-Credential-Token", "cred123", true);
        }

        @Test
        @DisplayName("apikey (no separator) in header name → scrubbed")
        void apikeyHeader() throws Exception {
            verifyScrubbing("X-Apikey", "key123", true);
        }

        private void verifyScrubbing(String headerName, String headerValue, boolean shouldBeScrubbed)
                throws Exception {
            ApiCall call = createCall("scrub-extra-" + headerName, false);
            Map<String, Object> requestMap = new HashMap<>();
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put(headerName, headerValue);
            requestMap.put("headers", headers);
            when(mockRequest.toMap()).thenReturn(requestMap);
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");

            var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(prePostUtils, atLeastOnce()).createMemoryEntry(
                    eq(currentStep), captor.capture(), contains("Request"), eq("httpCalls"));

            @SuppressWarnings("unchecked")
            var capturedMap = (Map<String, Object>) captor.getValue();
            @SuppressWarnings("unchecked")
            var scrubbedHeaders = (Map<String, Object>) capturedMap.get("headers");
            if (shouldBeScrubbed) {
                assertEquals("<REDACTED>", scrubbedHeaders.get(headerName));
            } else {
                assertEquals(headerValue, scrubbedHeaders.get(headerName));
            }
            // Reset for next test
            reset(mockRequest, prePostUtils);
            when(mockRequest.toMap()).thenReturn(new HashMap<>());
            when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any()))
                    .thenAnswer(inv -> inv.getArgument(1));
            when(prePostUtils.templateValues(anyString(), any()))
                    .thenAnswer(inv -> inv.getArgument(0));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // buildRequest — empty path
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("buildRequest — empty path")
    class BuildRequestEmptyPath {

        @Test
        @DisplayName("empty path → no slash prepended, uses targetServerUrl directly")
        void emptyPath() throws Exception {
            ApiCall call = createCall("empty-path", false);
            call.getRequest().setPath("");
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");

            // Empty path means no slash prepended; path is just ""
            // targetDestination = "http://example.com" (server + empty path)
            verify(httpClient).newRequest(eq(URI.create("http://example.com")), any());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // buildRequest — body with null content type → defaults to text/plain
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("buildRequest — body with null content type")
    class BuildRequestBodyNullContentType {

        @Test
        @DisplayName("body set but contentType is null → defaults to text/plain")
        void bodyWithNullContentType() throws Exception {
            ApiCall call = createCall("null-ct-body", false);
            call.getRequest().setBody("some body content");
            call.getRequest().setContentType(null);
            call.getRequest().setMethod("POST");
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");

            verify(mockRequest).setBodyEntity("some body content", "utf-8", "text/plain");
        }

        @Test
        @DisplayName("body set but contentType is empty → defaults to text/plain")
        void bodyWithEmptyContentType() throws Exception {
            ApiCall call = createCall("empty-ct-body", false);
            call.getRequest().setBody("some body content");
            call.getRequest().setContentType("");
            call.getRequest().setMethod("POST");
            setupSuccessResponse(200, "ok", "text/plain");

            executor.execute(call, memory, new HashMap<>(), "http://example.com");

            verify(mockRequest).setBodyEntity("some body content", "utf-8", "text/plain");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // getDelayInMillis — preRequest with delay
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getDelayInMillis — preRequest with delay")
    class PreRequestDelay {

        @Test
        @DisplayName("non-retry call with preRequest delay > 0 → uses scheduled executor")
        void preRequestDelayUsed() throws Exception {
            ApiCall call = createCall("delayed-call", false);
            HttpPreRequest preRequest = new HttpPreRequest();
            preRequest.setDelayBeforeExecutingInMillis(500);
            call.setPreRequest(preRequest);
            setupSuccessResponse(200, "ok", "text/plain");

            var scheduledExecutorService = mock(java.util.concurrent.ScheduledExecutorService.class);
            when(runtime.getScheduledExecutorService()).thenReturn(scheduledExecutorService);

            @SuppressWarnings("unchecked")
            java.util.concurrent.ScheduledFuture<IResponse> future = mock(java.util.concurrent.ScheduledFuture.class);
            when(future.get()).thenReturn(mockResponse);
            when(scheduledExecutorService.schedule(any(java.util.concurrent.Callable.class), eq(500L),
                    eq(java.util.concurrent.TimeUnit.MILLISECONDS)))
                    .thenReturn(future);

            Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), "http://example.com");

            verify(scheduledExecutorService).schedule(any(java.util.concurrent.Callable.class), eq(500L),
                    eq(java.util.concurrent.TimeUnit.MILLISECONDS));
            assertNotNull(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // response header is null — responseHeaderObjectName check
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("responseHeaderObjectName — null response headers")
    class ResponseHeaderNull {

        @Test
        @DisplayName("should use empty map when response.getHttpHeader() returns null")
        void nullResponseHeaders() throws Exception {
            ApiCall call = createCall("null-header-call", false);
            call.setResponseHeaderObjectName("respHeaders");
            when(mockResponse.getHttpCode()).thenReturn(200);
            when(mockResponse.getContentAsString()).thenReturn("ok");
            // Return null for httpHeader on first call (for responseHeaderObjectName check)
            // and a map for the saveResponse check
            when(mockResponse.getHttpHeader()).thenReturn(null);

            Map<String, Object> templateData = new HashMap<>();
            Map<String, Object> result = executor.execute(call, memory, templateData, "http://example.com");

            // Should still store headers (as empty map via requireNonNullElse)
            assertNotNull(templateData.get("respHeaders"));
            assertTrue(((Map<?, ?>) templateData.get("respHeaders")).isEmpty());
            assertNotNull(result.get("headers"));
        }
    }
}
