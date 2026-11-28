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
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.engine.security.ResolutionPrincipal;
import ai.labs.eddi.engine.security.ResolutionPrincipal.Provenance;
import ai.labs.eddi.engine.security.ResolutionPrincipalContext;
import ai.labs.eddi.engine.security.TurnOrigin;
import ai.labs.eddi.modules.properties.impl.SecretPropertyVault;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import ai.labs.eddi.secrets.SecretResolver;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The escalation guard (email plan v3 §5.3) on the {@code httpcalls} path: a
 * rule-driven or LLM-driven HTTP call of a turn a mail started never writes to
 * EDDI's channel store, whatever the call is named.
 */
@DisplayName("ApiCallExecutor — the email escalation guard")
class ApiCallExecutorEscalationGuardTest {

    private final ResolutionPrincipalContext principals = new ResolutionPrincipalContext();
    private IHttpClient httpClient;
    private ApiCallExecutor executor;
    private IConversationMemory memory;

    @BeforeEach
    void setUp() throws Exception {
        httpClient = mock(IHttpClient.class);
        SecretResolver secretResolver = mock(SecretResolver.class);
        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        IJsonSerialization jsonSerialization = mock(IJsonSerialization.class);
        var prePostUtils = new PrePostUtils(jsonSerialization, new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build()),
                mock(IDataFactory.class), mock(SecretPropertyVault.class));
        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));
        var request = mock(IRequest.class);
        var response = mock(IResponse.class);
        when(request.toMap()).thenReturn(new HashMap<>());
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(request);
        when(request.send()).thenReturn(response);
        when(request.setBodyEntity(any(), any(), any())).thenReturn(request);
        when(request.setHttpHeader(any(), any())).thenReturn(request);
        when(request.setQueryParam(any(), any())).thenReturn(request);
        when(response.getHttpCode()).thenReturn(200);
        when(response.getContentAsString()).thenReturn("{}");
        when(response.getHttpHeader()).thenReturn(new HashMap<>());
        executor = new ApiCallExecutor(httpClient, jsonSerialization, mock(IRuntime.class), prePostUtils, globalVariableResolver, secretResolver,
                callerIdentityResolver, mock(CallerIdentityContext.class), new RequestRedactor(callerIdentityResolver), null, true,
                Optional.of("graph.example.com"), 30_000L, 2_000_000);
    }

    @AfterEach
    void tearDown() {
        principals.clear();
    }

    private static ApiCall call(String method, String path) {
        var call = new ApiCall();
        call.setName("innocentLookingName");
        call.setSaveResponse(false);
        call.setFireAndForget(false);
        var request = new Request();
        request.setPath(path);
        request.setMethod(method);
        call.setRequest(request);
        return call;
    }

    @Test
    @DisplayName("a tainted POST to the channel store is refused before a request exists")
    void taintedWriteRefused() {
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));

        assertThrows(Exception.class,
                () -> executor.execute(call("POST", "/channelstore/channels/abc/email-senders"), memory, new HashMap<>(),
                        "https://graph.example.com"));
        verify(httpClient, never()).newRequest(any(URI.class), any());
    }

    @Test
    @DisplayName("the same call is made from an untainted turn, and a tainted read is made too")
    void othersSent() throws Exception {
        executor.execute(call("POST", "/channelstore/channels/abc/email-senders"), memory, new HashMap<>(), "https://graph.example.com");
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));
        executor.execute(call("GET", "/channelstore/channels/abc/email-senders"), memory, new HashMap<>(), "https://graph.example.com");

        verify(httpClient, times(2)).newRequest(any(URI.class), any());
    }
}
