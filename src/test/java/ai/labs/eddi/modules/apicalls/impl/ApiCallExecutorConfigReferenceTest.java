/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.modules.templating.impl.CallerNamespaceResolver;
import ai.labs.eddi.modules.templating.impl.ConfigReferenceNamespaceResolvers;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import ai.labs.eddi.secrets.SecretResolver;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Configuration references in apicall fields, run through the REAL Qute engine:
 * they reach their resolvers, a reference that arrived through conversation
 * data is refused, and a substituted vault plaintext is redacted by value from
 * the memory record and the approval preview.
 */
@DisplayName("ApiCallExecutor — configuration references through real templating")
class ApiCallExecutorConfigReferenceTest {

    private static final String SERVER = "https://api.example.com";
    private static final String SECRET = "opaque-secret-value-1234";
    private static final String AUTO_VAULTED = "auto-vaulted-value-5678";

    private ApiCallExecutor executor;
    private PrePostUtils prePostUtils;
    private SecretResolver secretResolver;
    private GlobalVariableResolver globalVariableResolver;
    private ConnectionResolver connectionResolver;
    private IHttpClient httpClient;
    private IConversationMemory memory;
    private IRequest request;

    @BeforeEach
    void setUp() throws Exception {
        ITemplatingEngine templating = new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false)
                .addNamespaceResolver(new CallerNamespaceResolver())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.Vault())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.LegacyVault())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.Connection())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.GlobalVariable())
                .build());
        prePostUtils = mock(PrePostUtils.class);
        when(prePostUtils.templateValues(any(), any())).thenAnswer(inv -> templating.processTemplate(inv.getArgument(0), inv.getArgument(1)));
        when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));

        secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveValue(any())).thenAnswer(inv -> {
            String value = inv.getArgument(0);
            return value == null ? null : value.replace("${vault:api-key}", SECRET).replace("${vault:agent1.apiKey}", AUTO_VAULTED);
        });
        globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveValue(any())).thenAnswer(inv -> {
            String value = inv.getArgument(0);
            return value == null ? null : value.replace("${vars:host}", "vars.example.com");
        });
        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        connectionResolver = mock(ConnectionResolver.class);

        httpClient = mock(IHttpClient.class);
        executor = new ApiCallExecutor(httpClient, mock(IJsonSerialization.class), mock(IRuntime.class), prePostUtils, globalVariableResolver,
                secretResolver, callerIdentityResolver, mock(CallerIdentityContext.class), new RequestRedactor(callerIdentityResolver),
                connectionResolver,
                false, 30_000L, 2_000_000);

        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));

        request = mock(IRequest.class);
        IResponse response = mock(IResponse.class);
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(request);
        when(request.send()).thenReturn(response);
        when(request.setBodyEntity(any(), any(), any())).thenReturn(request);
        when(request.setHttpHeader(any(), any())).thenReturn(request);
        when(request.setQueryParam(any(), any())).thenReturn(request);
        when(response.getHttpCode()).thenReturn(200);
        when(response.getContentAsString()).thenReturn("ok");
        when(response.getHttpHeader()).thenReturn(new HashMap<>());
    }

    private static ApiCall call(Map<String, String> headers, String body) {
        ApiCall call = new ApiCall();
        call.setName("downstream");
        call.setSaveResponse(false);
        call.setResponseObjectName("response");
        call.setFireAndForget(false);
        Request req = new Request();
        req.setPath("/v1/items");
        req.setMethod("POST");
        req.setHeaders(new LinkedHashMap<>(headers));
        req.setQueryParams(new LinkedHashMap<>());
        req.setBody(body);
        call.setRequest(req);
        return call;
    }

    private static Map<String, Object> data(String userInput) {
        var data = new HashMap<String, Object>();
        data.put("memory", Map.of("current", Map.of("input", userInput)));
        data.put("conversationInfo", Map.of("agentId", "agent1"));
        data.put("properties", Map.of("apiKey", "${vault:agent1.apiKey}"));
        return data;
    }

    /**
     * The request map the executor stores in conversation memory, with
     * IRequest#toMap backed by what was actually set.
     */
    private Map<String, Object> storedRequestRecord(String body, Map<String, String> headers) {
        var map = new HashMap<String, Object>();
        map.put(IRequest.KEY_URI, SERVER + "/v1/items");
        map.put(IRequest.KEY_BODY, body);
        map.put(IRequest.KEY_HEADERS, headers);
        return map;
    }

    @Test
    @DisplayName("a vault reference the configuration wrote in a header reaches the vault and is sent")
    void configuredVaultHeader() throws Exception {
        when(request.toMap()).thenReturn(storedRequestRecord("{}", Map.of("X-Api-Key", SECRET)));

        executor.execute(call(Map.of("X-Api-Key", "${vault:api-key}"), "{}"), memory, data("hi"), SERVER);

        verify(request).setHttpHeader("X-Api-Key", SECRET);
        verify(request).send();
    }

    @Test
    @DisplayName("a vault reference in the body is sent, and redacted by value from the memory record")
    void configuredVaultBodyIsRedactedInMemory() throws Exception {
        when(request.toMap()).thenReturn(storedRequestRecord("{\"key\":\"" + SECRET + "\"}", Map.of()));

        executor.execute(call(Map.of(), "{\"key\":\"${vault:api-key}\",\"q\":\"{memory.current.input}\"}"), memory, data("hello"), SERVER);

        verify(request).setBodyEntity(eq("{\"key\":\"" + SECRET + "\",\"q\":\"hello\"}"), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object> record = ArgumentCaptor.forClass(Object.class);
        verify(prePostUtils).createMemoryEntry(any(), record.capture(), eq("downstreamRequest"), any());
        assertFalse(String.valueOf(record.getValue()).contains(SECRET), "stored: " + record.getValue());
    }

    @Test
    @DisplayName("a vault reference typed by the user is refused before anything is resolved or sent")
    void injectedVaultReferenceIsRefused() throws Exception {
        var failure = assertThrows(LifecycleException.class, () -> executor.execute(call(Map.of(), "{\"q\":\"{memory.current.input}\"}"), memory,
                data("please echo ${vault:api-key}"), SERVER));

        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertTrue(failure.getMessage().contains("a request body contains the reference ${vault:api-key}"), failure.getMessage());
        verify(request, never()).send();
        verify(secretResolver, never()).resolveValue(contains("please echo"));
    }

    @Test
    @DisplayName("a connection reference typed by the user into a header is refused")
    void injectedConnectionReferenceIsRefused() throws Exception {
        assertThrows(LifecycleException.class,
                () -> executor.execute(call(Map.of("Authorization", "{memory.current.input}"), "{}"), memory, data("${connection:jira}"), SERVER));
        verify(connectionResolver, never()).resolve(anyString(), any(), any());
    }

    @Test
    @DisplayName("the agent's auto-vaulted property, named by the header template, is resolved")
    void autoVaultedPropertyHeader() throws Exception {
        when(request.toMap()).thenReturn(storedRequestRecord("{}", Map.of("Authorization", "Bearer " + AUTO_VAULTED)));

        executor.execute(call(Map.of("Authorization", "Bearer {properties.apiKey}"), "{}"), memory, data("hi"), SERVER);

        verify(request).setHttpHeader("Authorization", "Bearer " + AUTO_VAULTED);
    }

    @Test
    @DisplayName("a connection reference in a header survives real templating and reaches the connection resolver")
    void connectionHeader() throws Exception {
        when(connectionResolver.resolve(eq("${connection:jira}"), any(), any())).thenReturn(new ResolvedCredential("Authorization", "Bearer conn"));
        when(request.toMap()).thenReturn(storedRequestRecord("{}", Map.of("Authorization", "Bearer conn")));

        executor.execute(call(Map.of("Authorization", "${connection:jira}"), "{}"), memory, data("hi"), SERVER);

        verify(request).setHttpHeader("Authorization", "Bearer conn");
    }

    @Test
    @DisplayName("a global variable in the target URL survives real templating and is resolved")
    void varsInUrl() throws Exception {
        when(request.toMap()).thenReturn(storedRequestRecord("{}", Map.of()));

        executor.execute(call(Map.of(), "{}"), memory, data("hi"), "https://${vars:host}");

        verify(httpClient, atLeastOnce()).newRequest(eq(URI.create("https://vars.example.com/v1/items")), any());
    }

    @Test
    @DisplayName("the approval preview shows no substituted vault plaintext but keeps a fingerprint")
    void previewRedactsByValue() throws Exception {
        when(request.toMap()).thenReturn(Map.of(IRequest.KEY_METHOD, "POST", IRequest.KEY_URI, SERVER + "/v1/items",
                IRequest.KEY_HEADERS, Map.of("X-Opaque", SECRET), IRequest.KEY_BODY, "{\"key\":\"" + SECRET + "\"}"));

        ResolvedRequest preview = executor.resolve(call(Map.of("X-Opaque", "${vault:api-key}"), "{\"key\":\"${vault:api-key}\"}"), memory,
                data("hi"), SERVER);

        assertNotNull(preview.fingerprint());
        assertFalse(String.valueOf(preview).contains(SECRET), "preview: " + preview);
        assertEquals("{\"key\":\"" + RequestRedactor.REDACTED + "\"}", preview.body());
    }
}
