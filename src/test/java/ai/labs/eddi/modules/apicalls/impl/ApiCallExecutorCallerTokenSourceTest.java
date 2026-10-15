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
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.modules.properties.impl.SecretPropertyVault;
import ai.labs.eddi.modules.templating.impl.CallerNamespaceResolver;
import ai.labs.eddi.modules.templating.impl.ConfigReferenceNamespaceResolvers;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import ai.labs.eddi.secrets.SecretResolver;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code ${caller:token}} is released only to a reference written in the header
 * itself. Saving one needs an author role ({@code CallerTokenAuthorPolicy}),
 * and that check reads the configuration as written; a {@code ${vars:...}}
 * value that resolves to the reference would bypass it — and could be changed
 * after the config was saved.
 */
class ApiCallExecutorCallerTokenSourceTest {

    private IHttpClient httpClient;
    private IRequest request;
    private GlobalVariableResolver globalVariableResolver;
    private CallerIdentityResolver callerIdentityResolver;
    private ApiCallExecutor executor;
    private IConversationMemory memory;

    @BeforeEach
    void setUp() throws Exception {
        httpClient = mock(IHttpClient.class);
        var jsonSerialization = mock(IJsonSerialization.class);
        var secretResolver = mock(SecretResolver.class);
        callerIdentityResolver = mock(CallerIdentityResolver.class);
        globalVariableResolver = mock(GlobalVariableResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        // The runtime engine passes ${caller:…}, ${vars:…} and ${vault:…} through
        // untouched; without these resolvers a ${vars:…} header fails to RENDER, and
        // the refusal test would pass without ever reaching the guard.
        var engine = Engine.builder().addDefaults().strictRendering(false)
                .addNamespaceResolver(new CallerNamespaceResolver())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.Vault())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.GlobalVariable())
                .build();
        var prePostUtils = new PrePostUtils(jsonSerialization, mock(IMemoryItemConverter.class), new TemplatingEngine(engine),
                mock(IDataFactory.class), mock(SecretPropertyVault.class));
        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));

        request = mock(IRequest.class);
        var response = mock(IResponse.class);
        when(request.toMap()).thenReturn(new HashMap<>());
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(request);
        when(request.send()).thenReturn(response);
        when(request.setHttpHeader(any(), any())).thenReturn(request);
        when(response.getHttpCode()).thenReturn(200);
        when(response.getContentAsString()).thenReturn("{}");
        when(response.getHttpHeader()).thenReturn(new HashMap<>());

        executor = new ApiCallExecutor(httpClient, jsonSerialization, mock(IRuntime.class), prePostUtils, globalVariableResolver,
                secretResolver, callerIdentityResolver, mock(CallerIdentityContext.class), new RequestRedactor(callerIdentityResolver), null,
                false, 30_000L, 2_000_000);
    }

    private static ApiCall callWithAuthorization(String authorization) {
        var call = new ApiCall();
        call.setName("admin");
        call.setSaveResponse(false);
        call.setFireAndForget(false);
        var req = new Request();
        req.setPath("/administration/x");
        req.setMethod("GET");
        req.setHeaders(Map.of("Authorization", authorization));
        call.setRequest(req);
        return call;
    }

    @Test
    @DisplayName("a ${vars:…} value that resolves to ${caller:token} is refused before the token is resolved or sent")
    void tokenFromAVariableIsRefused() throws Exception {
        when(globalVariableResolver.resolveValue("Bearer ${vars:t}")).thenReturn("Bearer ${caller:token}");

        var refused = assertThrows(LifecycleException.class,
                () -> executor.execute(callWithAuthorization("Bearer ${vars:t}"), memory, new HashMap<>(), "http://127.0.0.1:7070"));
        assertTrue(messages(refused).contains("receives ${caller:token} from a ${vars:...} or ${vault:...} value"), messages(refused));

        verify(callerIdentityResolver, never()).resolveValue(contains("${caller:token}"), any());
        verify(request, never()).send();
    }

    @Test
    @DisplayName("a ${caller:token} written in the header itself is resolved as before")
    void tokenWrittenInTheHeaderIsResolved() throws Exception {
        when(callerIdentityResolver.resolveValue(eq("Bearer ${caller:token}"), any())).thenReturn("Bearer caller-token-value");

        executor.execute(callWithAuthorization("Bearer ${caller:token}"), memory, new HashMap<>(), "http://127.0.0.1:7070");

        // The resolved token is what goes on the wire, not the literal reference.
        verify(request).setHttpHeader("Authorization", "Bearer caller-token-value");
        verify(request).send();
    }

    private static String messages(Throwable t) {
        var text = new StringBuilder();
        for (Throwable cause = t; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            text.append(cause.getMessage()).append(" | ");
        }
        return text.toString();
    }
}
