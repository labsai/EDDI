/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.oversight.AgentCallMarker;
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
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every outbound httpcall carries the engine-owned {@code X-EDDI-Agent-Call}
 * marker, set after the configured headers, so the server can refuse an
 * oversight write an agent's tool call makes.
 */
@DisplayName("ApiCallExecutor — agent-call marker header")
class ApiCallExecutorAgentCallMarkerTest {

    private static final String SERVER = "http://api.example.com";

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
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        executor = new ApiCallExecutor(httpClient, mock(IJsonSerialization.class), mock(IRuntime.class), prePostUtils, globalVariableResolver,
                secretResolver, callerIdentityResolver, mock(CallerIdentityContext.class), new RequestRedactor(callerIdentityResolver),
                mock(ConnectionResolver.class), false, 30_000L, 2_000_000);

        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));
        mockRequest = mock(IRequest.class);
        IResponse response = mock(IResponse.class);
        when(mockRequest.toMap()).thenReturn(new HashMap<>());
        when(httpClient.newRequest(any(), any())).thenReturn(mockRequest);
        when(mockRequest.send()).thenReturn(response);
        when(mockRequest.setHttpHeader(any(), any())).thenReturn(mockRequest);
        when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(prePostUtils.templateValues(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(response.getHttpCode()).thenReturn(200);
        when(response.getContentAsString()).thenReturn("ok");
        when(response.getHttpHeader()).thenReturn(new HashMap<>());
    }

    @Test
    @DisplayName("the marker is sent on a call with no configured headers")
    void markerOnEveryCall() throws Exception {
        executor.execute(call(Map.of()), memory, new HashMap<>(), SERVER);

        assertEquals(List.of(AgentCallMarker.HEADER), headerNames());
    }

    @Test
    @DisplayName("the marker is written last, so a configured header of that name cannot replace it")
    void markerOverridesConfiguredHeader() throws Exception {
        executor.execute(call(Map.of("Accept", "application/json", AgentCallMarker.HEADER, "false")), memory, new HashMap<>(), SERVER);

        var names = ArgumentCaptor.forClass(String.class);
        var values = ArgumentCaptor.forClass(String.class);
        verify(mockRequest, atLeastOnce()).setHttpHeader(names.capture(), values.capture());
        int last = names.getAllValues().size() - 1;
        assertEquals(AgentCallMarker.HEADER, names.getAllValues().get(last), "the engine's write must come after every configured one");
        assertEquals(AgentCallMarker.VALUE, values.getAllValues().get(last));
    }

    private List<String> headerNames() {
        var names = ArgumentCaptor.forClass(String.class);
        verify(mockRequest, atLeastOnce()).setHttpHeader(names.capture(), any());
        return names.getAllValues();
    }

    private static ApiCall call(Map<String, String> headers) {
        ApiCall call = new ApiCall();
        call.setName("marker-call");
        call.setSaveResponse(false);
        call.setResponseObjectName("response");
        call.setFireAndForget(false);
        Request request = new Request();
        request.setPath("/api/test");
        request.setMethod("POST");
        request.setHeaders(new LinkedHashMap<>(headers));
        call.setRequest(request);
        return call;
    }
}
