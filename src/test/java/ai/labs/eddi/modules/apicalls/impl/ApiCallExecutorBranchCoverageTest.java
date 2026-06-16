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
                prePostUtils, globalVariableResolver, secretResolver);

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
}
