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
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The executor used to declare and catch a private
 * {@code ApiCallsValidationException} that no code path ever threw, which meant
 * the {@code validationError} flag handed to the post-response property
 * instructions was permanently {@code false} while pretending to be dynamic.
 */
@DisplayName("ApiCallExecutor — post-response validation flag")
class ApiCallExecutorValidationErrorTest {

    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
    private static final int DEFAULT_MAX_RESPONSE_SIZE = 2_000_000;

    private PrePostUtils prePostUtils;
    private ApiCallExecutor executor;
    private IConversationMemory memory;
    private IRequest mockRequest;
    private IResponse mockResponse;

    @BeforeEach
    void setUp() throws Exception {
        IHttpClient httpClient = mock(IHttpClient.class);
        IJsonSerialization jsonSerialization = mock(IJsonSerialization.class);
        IRuntime runtime = mock(IRuntime.class);
        prePostUtils = mock(PrePostUtils.class);

        SecretResolver secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));

        executor = new ApiCallExecutor(httpClient, jsonSerialization, runtime, prePostUtils, globalVariableResolver, secretResolver, false,
                DEFAULT_TIMEOUT_MILLIS, DEFAULT_MAX_RESPONSE_SIZE);

        memory = mock(IConversationMemory.class);
        IWritableConversationStep currentStep = mock(IWritableConversationStep.class);
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

    @Test
    @DisplayName("post-response instructions are told the call did not fail validation")
    void postResponseIsInvokedWithoutValidationError() throws Exception {
        ApiCall call = createApiCallWithRetry();
        setupResponse(200, "{}", "application/json");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        verify(prePostUtils).runPostResponse(eq(memory), eq(call.getPostResponse()), any(), eq(200), eq(false));
    }

    @Test
    @DisplayName("an error response still reports validationError=false and the real http code")
    void errorResponseStillReportsNoValidationError() throws Exception {
        ApiCall call = createApiCallWithRetry();
        call.getPostResponse().getRetryApiCallInstruction().setRetryOnHttpCodes(List.of());
        setupResponse(503, "gateway down", "text/plain");

        executor.execute(call, memory, new HashMap<>(), "http://example.com");

        verify(prePostUtils).runPostResponse(eq(memory), eq(call.getPostResponse()), any(), eq(503), eq(false));
    }

    private ApiCall createApiCallWithRetry() {
        ApiCall call = new ApiCall();
        call.setName("test-call");
        call.setSaveResponse(false);
        call.setFireAndForget(false);
        call.setResponseObjectName("response");

        Request request = new Request();
        request.setPath("/api/test");
        request.setMethod("GET");
        call.setRequest(request);

        var retryInstruction = new RetryApiCallInstruction();
        retryInstruction.setMaxRetries(2);
        retryInstruction.setRetryOnHttpCodes(List.of(500));

        var postResponse = new HttpPostResponse();
        postResponse.setRetryApiCallInstruction(retryInstruction);
        call.setPostResponse(postResponse);

        return call;
    }

    private void setupResponse(int httpCode, String body, String contentType) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", contentType);
        when(mockResponse.getHttpCode()).thenReturn(httpCode);
        when(mockResponse.getContentAsString()).thenReturn(body);
        when(mockResponse.getHttpHeader()).thenReturn(headers);
    }
}
