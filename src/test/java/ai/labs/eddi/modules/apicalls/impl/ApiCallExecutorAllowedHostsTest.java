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
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultHostAllowlist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The executor's half of M1: every {@code ${vault:…}} reference an outbound
 * request carries is checked against the fully resolved target before anything
 * is sent.
 */
@DisplayName("ApiCallExecutor — allowedHosts of the vault references in a request")
class ApiCallExecutorAllowedHostsTest {

    private static final String SERVER = "http://api.example.com";

    private ApiCallExecutor executor;
    private VaultHostAllowlist allowlist;
    private IConversationMemory memory;
    private IRequest mockRequest;

    @BeforeEach
    void setUp() throws Exception {
        IHttpClient httpClient = mock(IHttpClient.class);
        PrePostUtils prePostUtils = mock(PrePostUtils.class);
        SecretResolver secretResolver = mock(SecretResolver.class);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).replace("${vault:api-key}", "live-key-42"));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        executor = new ApiCallExecutor(httpClient, mock(IJsonSerialization.class), mock(IRuntime.class), prePostUtils, globalVariableResolver,
                secretResolver, callerIdentityResolver, mock(CallerIdentityContext.class), new RequestRedactor(callerIdentityResolver),
                mock(ConnectionResolver.class), false, 30_000L, 2_000_000);
        allowlist = mock(VaultHostAllowlist.class);
        executor.vaultHostAllowlist = allowlist;

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

    @Test
    @DisplayName("the references the request carries are checked against the resolved target, named by the call")
    void referencesCheckedAgainstTarget() throws Exception {
        executor.execute(callWithHeaders(Map.of("Authorization", "Bearer ${vault:api-key}")), memory, templateData(), SERVER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<String>> references = ArgumentCaptor.forClass(Iterable.class);
        verify(allowlist).requireAllowed(references.capture(), eq("http://api.example.com/api/test"), eq("API call 'vault-call'"));
        assertEquals(Set.of("${vault:api-key}"), references.getValue());
        verify(mockRequest).send();
    }

    @Test
    @DisplayName("a refusal stops the call before it is sent")
    void refusalStopsTheCall() throws Exception {
        doThrow(new VaultHostAllowlist.HostNotAllowedException("not on allowedHosts")).when(allowlist).requireAllowed(any(Iterable.class),
                anyString(), anyString());

        assertThrows(Exception.class,
                () -> executor.execute(callWithHeaders(Map.of("Authorization", "Bearer ${vault:api-key}")), memory, templateData(), SERVER));

        verify(mockRequest, never()).send();
    }

    @Test
    @DisplayName("a request without a vault reference is checked with an empty set — and nothing is looked up")
    void noReferencesNothingToCheck() throws Exception {
        executor.execute(callWithHeaders(Map.of("Accept", "application/json")), memory, templateData(), SERVER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<String>> references = ArgumentCaptor.forClass(Iterable.class);
        verify(allowlist).requireAllowed(references.capture(), anyString(), anyString());
        assertEquals(Set.of(), references.getValue());
    }

    /**
     * Security review M9.4: the header flag was set by the resolved set growing, so
     * a secret the body had already resolved left a later custom header carrying it
     * unflagged — and a GET would then follow a cross-origin redirect with it.
     */
    @Test
    @DisplayName("a header reusing a vault secret the body already resolved still disables redirects")
    void headerReusingABodySecretIsStillACredential() throws Exception {
        ApiCall call = callWithHeaders(Map.of("X-Custom-Auth", "${vault:api-key}"));
        call.getRequest().setMethod("POST");
        call.getRequest().setContentType("application/json");
        call.getRequest().setBody("{\"key\":\"${vault:api-key}\"}");

        executor.execute(call, memory, templateData(), SERVER);

        verify(mockRequest).setHttpHeader("X-Custom-Auth", "live-key-42");
        verify(mockRequest).setFollowRedirects(false);
    }

    @Test
    @DisplayName("a header without any reference leaves redirects alone")
    void plainHeaderKeepsRedirects() throws Exception {
        executor.execute(callWithHeaders(Map.of("Accept", "application/json")), memory, templateData(), SERVER);

        verify(mockRequest, never()).setFollowRedirects(false);
    }

    private static ApiCall callWithHeaders(Map<String, String> headers) {
        ApiCall call = new ApiCall();
        call.setName("vault-call");
        call.setSaveResponse(false);
        call.setResponseObjectName("response");
        call.setFireAndForget(false);
        Request request = new Request();
        request.setPath("/api/test");
        request.setMethod("GET");
        request.setHeaders(new LinkedHashMap<>(headers));
        call.setRequest(request);
        return call;
    }

    private static Map<String, Object> templateData() {
        var data = new HashMap<String, Object>();
        data.put("userInfo", new HashMap<String, Object>(Map.of("userId", "alice")));
        return data;
    }
}
