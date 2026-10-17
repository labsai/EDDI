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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A call with {@code saveResponse: true} and no {@code responseObjectName} put
 * its response body under a {@code null} key in the template data and in
 * memory; the memory serializer then refused the step ("Null key for a Map not
 * allowed in JSON") and every turn running the call ended in ERROR. The store
 * refuses the config now; a config stored before that skips the save and logs
 * it.
 */
@DisplayName("ApiCallExecutor — saveResponse without responseObjectName is skipped, not stored under a null key")
class ApiCallExecutorSaveResponseGuardTest {

    private PrePostUtils prePostUtils;
    private ApiCallExecutor executor;
    private IConversationMemory memory;
    private IRequest mockRequest;

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

        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        executor = new ApiCallExecutor(httpClient, jsonSerialization, runtime, prePostUtils, globalVariableResolver, secretResolver,
                callerIdentityResolver, new CallerIdentityContext(null, null), new RequestRedactor(callerIdentityResolver), null, false, 30_000L,
                2_000_000);

        memory = mock(IConversationMemory.class);
        IWritableConversationStep currentStep = mock(IWritableConversationStep.class);
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
    @DisplayName("a successful response is not stored under a null key; the status code still travels")
    void successIsSkippedNotStoredUnderNullKey() throws Exception {
        // Built before the outer when(): response() stubs its own mock, and Mockito
        // reports a nested stubbing as UnfinishedStubbing.
        IResponse ok = response(200, "hello", "text/plain");
        when(mockRequest.send()).thenReturn(ok);
        Map<String, Object> templateData = new HashMap<>();

        Map<String, Object> result = executor.execute(callWithoutResponseObjectName(), memory, templateData, "http://example.com");

        assertFalse(templateData.containsKey(null), "the body must not be stored under a null key: " + templateData);
        verify(prePostUtils, never()).createMemoryEntry(any(), any(), isNull(), any());
        assertEquals(200, result.get("httpCode"));
        assertFalse(result.containsKey("body"), "an unsaved body is not reported either: " + result);
    }

    @Test
    @DisplayName("an error response is not stored under the 'nullError' key either")
    void errorIsSkipped() throws Exception {
        IResponse failed = response(500, "boom", "text/plain");
        when(mockRequest.send()).thenReturn(failed);

        executor.execute(callWithoutResponseObjectName(), memory, new HashMap<>(), "http://example.com");

        verify(prePostUtils, never()).createMemoryEntry(any(), any(), eq("nullError"), any());
        // The status key is responseObjectName + "HttpCode" — with no name,
        // "nullHttpCode".
        verify(prePostUtils, never()).createMemoryEntry(any(), any(), eq("nullHttpCode"), any());
    }

    private static ApiCall callWithoutResponseObjectName() {
        ApiCall call = new ApiCall();
        call.setName("no-name-call");
        call.setSaveResponse(true);
        call.setFireAndForget(false);
        call.setResponseObjectName(null);
        Request request = new Request();
        request.setPath("/api/test");
        request.setMethod("GET");
        call.setRequest(request);
        return call;
    }

    private static IResponse response(int httpCode, String body, String contentType) {
        IResponse response = mock(IResponse.class);
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", contentType);
        when(response.getHttpCode()).thenReturn(httpCode);
        when(response.getContentAsString()).thenReturn(body);
        when(response.getHttpHeader()).thenReturn(headers);
        return response;
    }
}
