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
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A secret context value only exists during the request that carried it. An
 * HTTP call that resolves it later — after a HITL resume, or in a later turn —
 * gets the placeholder, and must refuse to send it rather than fail at the API
 * with a 401 nothing explains.
 */
@DisplayName("ApiCallExecutor — expired secret context values")
class ApiCallExecutorSecretContextTest {

    private static final String SERVER = "http://api.example.com";
    private static final String EXPIRED = MemoryKeys.SECRET_CONTEXT_PLACEHOLDER;

    private ApiCallExecutor executor;
    private IConversationMemory memory;
    private IRequest mockRequest;

    @BeforeEach
    void setUp() throws Exception {
        IHttpClient httpClient = mock(IHttpClient.class);
        PrePostUtils prePostUtils = mock(PrePostUtils.class);
        SecretResolver secretResolver = mock(SecretResolver.class);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        executor = new ApiCallExecutor(httpClient, mock(IJsonSerialization.class), mock(IRuntime.class), prePostUtils, globalVariableResolver,
                secretResolver, callerIdentityResolver, mock(CallerIdentityContext.class), new RequestRedactor(callerIdentityResolver), null,
                false, 30_000L, 2_000_000);

        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));

        mockRequest = mock(IRequest.class);
        IResponse mockResponse = mock(IResponse.class);
        when(mockRequest.toMap()).thenReturn(new HashMap<>());
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(mockRequest);
        when(mockRequest.send()).thenReturn(mockResponse);
        when(mockRequest.setBodyEntity(any(), any(), any())).thenReturn(mockRequest);
        when(mockRequest.setHttpHeader(any(), any())).thenReturn(mockRequest);
        when(mockRequest.setQueryParam(any(), any())).thenReturn(mockRequest);
        when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(prePostUtils.templateValues(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(mockResponse.getHttpCode()).thenReturn(200);
        when(mockResponse.getContentAsString()).thenReturn("ok");
        when(mockResponse.getHttpHeader()).thenReturn(new HashMap<>());
    }

    private static ApiCall call(String path, Map<String, String> headers, Map<String, String> queryParams, String body) {
        ApiCall call = new ApiCall();
        call.setName("downstream");
        call.setSaveResponse(false);
        call.setResponseObjectName("response");
        call.setFireAndForget(false);
        Request request = new Request();
        request.setPath(path);
        request.setMethod("POST");
        request.setHeaders(new LinkedHashMap<>(headers));
        request.setQueryParams(new LinkedHashMap<>(queryParams));
        request.setBody(body);
        call.setRequest(request);
        return call;
    }

    private void assertRefused(ApiCall call, String location) throws Exception {
        var failure = assertThrows(LifecycleException.class, () -> executor.execute(call, memory, new HashMap<>(), SERVER));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertTrue(failure.getMessage().contains(location) && failure.getMessage().contains("marked secret"),
                "the message must name where the expired value is and why; was: " + failure.getMessage());
        verify(mockRequest, never()).send();
    }

    @Test
    @DisplayName("a header carrying the placeholder is refused, naming the header")
    void header() throws Exception {
        assertRefused(call("/api", Map.of("Authorization", "Bearer " + EXPIRED), Map.of(), ""), "header 'Authorization'");
    }

    @Test
    @DisplayName("a query parameter carrying the placeholder is refused")
    void queryParameter() throws Exception {
        assertRefused(call("/api", Map.of(), Map.of("token", EXPIRED), ""), "query parameter 'token'");
    }

    @Test
    @DisplayName("a body carrying the placeholder is refused")
    void body() throws Exception {
        assertRefused(call("/api", Map.of(), Map.of(), "{\"token\":\"" + EXPIRED + "\"}"), "a request body");
    }

    @Test
    @DisplayName("a path carrying the URL-encoded placeholder is refused")
    void encodedPath() throws Exception {
        assertRefused(call("/users/%3Csecret%20context%3E/items", Map.of(), Map.of(), ""), "the request path");
    }

    @Test
    @DisplayName("a request without the placeholder is sent")
    void liveValueIsSent() throws Exception {
        executor.execute(call("/api", Map.of("Authorization", "Bearer tok-aaaa-bbbb"), Map.of("q", "100%25"), "{}"), memory, new HashMap<>(), SERVER);

        verify(mockRequest).send();
    }
}
