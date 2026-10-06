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
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import ai.labs.eddi.secrets.SecretResolver;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A templated host in an httpcall's {@code targetServerUrl}, as 5.x allowed
 * ({@code https://{properties.apiHost}}).
 * <p>
 * Templated values used to be percent-encoded as path segments everywhere,
 * including the host, so {@code api.example.com} reached the client as
 * {@code api%2Eexample%2Ecom}. The authority is now rendered separately, with
 * the dot kept — and still without the characters that could change what the
 * authority means. The resolved URL still goes through the SSRF checks. Real
 * Qute and a real {@link PrePostUtils}, as in the path-encoding test.
 */
class ApiCallExecutorHostTemplateTest {

    private static final long TIMEOUT = 30_000L;
    private static final int MAX_RESPONSE = 2_000_000;

    private IHttpClient httpClient;
    private PrePostUtils prePostUtils;
    private GlobalVariableResolver globalVariableResolver;
    private SecretResolver secretResolver;
    private CallerIdentityResolver callerIdentityResolver;
    private CallerIdentityContext callerIdentityContext;
    private IJsonSerialization jsonSerialization;
    private IRuntime runtime;
    private IConversationMemory memory;

    @BeforeEach
    void setUp() throws Exception {
        httpClient = mock(IHttpClient.class);
        jsonSerialization = mock(IJsonSerialization.class);
        runtime = mock(IRuntime.class);
        secretResolver = mock(SecretResolver.class);
        callerIdentityResolver = mock(CallerIdentityResolver.class);
        callerIdentityContext = mock(CallerIdentityContext.class);
        globalVariableResolver = mock(GlobalVariableResolver.class);

        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        var realEngine = Engine.builder().addDefaults().strictRendering(false).build();
        prePostUtils = new PrePostUtils(jsonSerialization, mock(IMemoryItemConverter.class), new TemplatingEngine(realEngine),
                mock(IDataFactory.class), mock(SecretPropertyVault.class));

        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));

        var mockRequest = mock(IRequest.class);
        var mockResponse = mock(IResponse.class);
        when(mockRequest.toMap()).thenReturn(new HashMap<>());
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(mockRequest);
        when(mockRequest.send()).thenReturn(mockResponse);
        when(mockRequest.setBodyEntity(any(), any(), any())).thenReturn(mockRequest);
        when(mockRequest.setHttpHeader(any(), any())).thenReturn(mockRequest);
        when(mockRequest.setQueryParam(any(), any())).thenReturn(mockRequest);
        when(mockResponse.getHttpCode()).thenReturn(200);
        when(mockResponse.getContentAsString()).thenReturn("{}");
        when(mockResponse.getHttpHeader()).thenReturn(new HashMap<>());
    }

    private ApiCallExecutor executor(boolean ssrfProtection) {
        return new ApiCallExecutor(httpClient, jsonSerialization, runtime, prePostUtils, globalVariableResolver, secretResolver,
                callerIdentityResolver, callerIdentityContext, new RequestRedactor(callerIdentityResolver), null, ssrfProtection, TIMEOUT,
                MAX_RESPONSE);
    }

    private ApiCall call(String path) {
        var call = new ApiCall();
        call.setName("graph");
        call.setSaveResponse(false);
        call.setFireAndForget(false);
        var request = new Request();
        request.setPath(path);
        request.setMethod("GET");
        call.setRequest(request);
        return call;
    }

    private Map<String, Object> data(String host, String id) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("apiHost", host);
        Map<String, Object> templateData = new HashMap<>();
        templateData.put("properties", properties);
        templateData.put("id", id);
        return templateData;
    }

    private URI execute(boolean ssrf, String targetServerUrl, String path, Map<String, Object> templateData) throws Exception {
        executor(ssrf).execute(call(path), memory, templateData, targetServerUrl);
        var captor = ArgumentCaptor.forClass(URI.class);
        verify(httpClient).newRequest(captor.capture(), any());
        return captor.getValue();
    }

    @Test
    @DisplayName("a templated host keeps its dots (5.x https://{properties.apiHost})")
    void templatedHostIsNotPercentEncoded() throws Exception {
        URI uri = execute(false, "https://{properties.apiHost}", "/v1/items", data("graph.example.com", "x"));

        assertEquals("graph.example.com", uri.getHost());
        assertEquals("/v1/items", uri.getRawPath());
        assertFalse(uri.toString().contains("%2E"), uri.toString());
    }

    @Test
    @DisplayName("a templated host with a port is kept, and the path value is still encoded")
    void templatedHostWithPortAndEncodedPath() throws Exception {
        URI uri = execute(false, "https://{properties.apiHost}", "/items/{id}", data("graph.example.com:8443", "../secret"));

        assertEquals("graph.example.com", uri.getHost());
        assertEquals(8443, uri.getPort());
        assertFalse(uri.getRawPath().contains("/secret"), "the path value must stay one segment: " + uri);
    }

    @Test
    @DisplayName("a value cannot add a path, userinfo or query to the authority")
    void hostValueCannotChangeTheAuthority() throws Exception {
        // '@', '/' and '?' stay percent-encoded in the host, so the host is unusable
        // rather than redirected: it can never become "attacker.example.net".
        try {
            URI uri = execute(false, "https://{properties.apiHost}", "/v1", data("good.example.com@attacker.example.net/", "x"));
            assertNotEquals("attacker.example.net", uri.getHost(), uri.toString());
            assertNull(uri.getUserInfo(), uri.toString());
            assertEquals("/v1", uri.getRawPath(), uri.toString());
        } catch (IllegalArgumentException | LifecycleException expected) {
            // URI.create refusing the encoded authority is the other acceptable outcome
        }
    }

    @Test
    @DisplayName("a host template inside a full URL given as the call path is handled the same way")
    void fullUrlInPathHostIsTemplated() throws Exception {
        URI uri = execute(false, "http://unused.example.com", "https://{properties.apiHost}/v2/x", data("graph.example.com", "x"));

        assertEquals("graph.example.com", uri.getHost());
        assertEquals("/v2/x", uri.getRawPath());
    }

    @Test
    @DisplayName("SSRF protection on: a templated host resolving to loopback is refused")
    void templatedLoopbackHostIsBlockedWhenProtectionIsOn() {
        assertThrows(LifecycleException.class,
                () -> executor(true).execute(call("/v1"), memory, data("127.0.0.1", "x"), "https://{properties.apiHost}"));
        verify(httpClient, never()).newRequest(any(), any());
    }

    @Test
    @DisplayName("a templated host resolving to the cloud metadata address is refused, protection on or off")
    void templatedMetadataHostIsAlwaysBlocked() {
        for (boolean ssrf : new boolean[]{true, false}) {
            assertThrows(LifecycleException.class,
                    () -> executor(ssrf).execute(call("/latest/meta-data/"), memory, data("169.254.169.254", "x"),
                            "http://{properties.apiHost}"));
        }
        verify(httpClient, never()).newRequest(any(), any());
    }

    @Test
    @DisplayName("authorityEnd stops at the first slash outside a template expression")
    void authorityEndIgnoresSlashesInsideBraces() {
        assertEquals("https://{a}".length(), ApiCallExecutor.authorityEnd("https://{a}/p"));
        assertEquals("https://{str:x('a/b')}".length(), ApiCallExecutor.authorityEnd("https://{str:x('a/b')}/p"));
        assertEquals("http://h:1".length(), ApiCallExecutor.authorityEnd("http://h:1?q=1"));
        assertEquals(-1, ApiCallExecutor.authorityEnd("/relative/path"));
    }
}
