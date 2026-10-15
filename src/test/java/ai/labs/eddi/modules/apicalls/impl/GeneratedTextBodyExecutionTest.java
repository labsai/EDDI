/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.mcp.McpApiToolBuilder;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.modules.templating.impl.CallerNamespaceResolver;
import ai.labs.eddi.modules.templating.impl.ConfigReferenceNamespaceResolvers;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import ai.labs.eddi.secrets.SecretResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tool {@link McpApiToolBuilder} generates for a {@code text/plain} body,
 * run through the REAL templating engine and {@link ApiCallExecutor}: the
 * model's value must reach the wire verbatim, with the right content type, and
 * must never be treated as a template.
 */
@DisplayName("Generated text/plain tool — execution and pinning")
class GeneratedTextBodyExecutionTest {

    private static final String SERVER = "https://eddi.example.com";
    private static final String SPEC = """
            {"openapi":"3.0.3","info":{"title":"t","version":"1"},"servers":[{"url":"https://eddi.example.com"}],
             "paths":{"/agentstore/agents/{id}/updateResourceUri":{"put":{"operationId":"updateResourceInAgent",
              "parameters":[
                {"name":"id","in":"path","required":true,"schema":{"type":"string"}},
                {"name":"version","in":"query","required":true,"schema":{"type":"integer"}}],
              "requestBody":{"required":true,"content":{"text/plain":{"schema":{"type":"string","format":"uri"}}}},
              "responses":{"200":{"description":"ok"}}}}}}
            """;

    private ApiCallExecutor executor;
    private IRequest request;
    private IConversationMemory memory;
    private final AtomicReference<String> sentBody = new AtomicReference<>();
    private final AtomicReference<String> sentContentType = new AtomicReference<>();
    private ApiCall call;

    @BeforeEach
    void setUp() throws Exception {
        ITemplatingEngine templating = new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false)
                .addNamespaceResolver(new CallerNamespaceResolver())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.Vault())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.LegacyVault())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.Connection())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.GlobalVariable())
                .build());
        PrePostUtils prePostUtils = mock(PrePostUtils.class);
        when(prePostUtils.templateValues(any(), any())).thenAnswer(inv -> templating.processTemplate(inv.getArgument(0), inv.getArgument(1)));

        when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));

        SecretResolver secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveValue(any())).thenAnswer(inv -> inv.getArgument(0));
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveValue(any())).thenAnswer(inv -> inv.getArgument(0));
        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        IHttpClient httpClient = mock(IHttpClient.class);
        executor = new ApiCallExecutor(httpClient, new JsonSerialization(new ObjectMapper()), mock(IRuntime.class), prePostUtils,
                globalVariableResolver, secretResolver, callerIdentityResolver, mock(CallerIdentityContext.class),
                new RequestRedactor(callerIdentityResolver), mock(ConnectionResolver.class), false, 30_000L, 2_000_000);

        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));
        when(memory.getConversationProperties()).thenReturn(new ConversationProperties(memory));

        request = mock(IRequest.class);
        IResponse response = mock(IResponse.class);
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(request);
        when(request.send()).thenReturn(response);
        when(request.setBodyEntity(any(), any(), any())).thenAnswer(inv -> {
            sentBody.set(inv.getArgument(0));
            sentContentType.set(inv.getArgument(2));
            return request;
        });
        when(request.setHttpHeader(any(), any())).thenReturn(request);
        when(request.setQueryParam(any(), any())).thenReturn(request);
        // What resolve() reads back: the method/uri it was built with and the body set
        // above.
        when(request.toMap()).thenAnswer(inv -> {
            var map = new HashMap<String, Object>();
            map.put(IRequest.KEY_METHOD, "PUT");
            map.put(IRequest.KEY_URI, SERVER + "/agentstore/agents/a1/updateResourceUri");
            map.put(IRequest.KEY_BODY, sentBody.get());
            map.put(IRequest.KEY_HEADERS, Map.of());
            return map;
        });
        when(response.getHttpCode()).thenReturn(200);
        when(response.getContentAsString()).thenReturn("ok");
        when(response.getHttpHeader()).thenReturn(new HashMap<>());

        call = McpApiToolBuilder.parseAndBuild(SPEC, null, SERVER, null).configsByGroup().values().iterator().next().getHttpCalls().getFirst();
    }

    private static Map<String, Object> toolArguments(String requestBody) {
        var data = new HashMap<String, Object>();
        data.put("id", "a1");
        data.put("version", "2");
        data.put("requestBody", requestBody);
        data.put("memory", Map.of("current", Map.of("input", "hi")));
        data.put("conversationInfo", Map.of("agentId", "agent1", "conversationId", "conv1"));
        return data;
    }

    @Test
    @DisplayName("the URI is sent verbatim as text/plain — not JSON-quoted, not wrapped")
    void bodyIsSentVerbatim() throws Exception {
        String uri = "eddi://ai.labs.workflow/workflowstore/workflows/abc123?version=3";

        executor.execute(call, memory, toolArguments(uri), SERVER);

        assertEquals(uri, sentBody.get());
        assertEquals("text/plain", sentContentType.get());
        verify(request).send();
    }

    @Test
    @DisplayName("braces and template syntax inside the value are data: sent as written, never rendered")
    void templateSyntaxInTheValueIsNotEvaluated() throws Exception {
        String hostile = "x {memory.current.input} {#for i in 1..3}{i}{/for} {vars.secret} ?version=2";

        executor.execute(call, memory, toolArguments(hostile), SERVER);

        assertEquals(hostile, sentBody.get());
    }

    @Test
    @DisplayName("a credential reference the model typed into the body is refused, same as for JSON bodies")
    void injectedReferenceIsRefused() throws Exception {
        assertThrows(LifecycleException.class, () -> executor.execute(call, memory, toolArguments("${vault:api-key}"), SERVER));
        verify(request, never()).send();
    }

    @Test
    @DisplayName("the approval preview shows the raw URI and the fingerprint binds to it")
    void previewAndFingerprintCoverTheBody() throws Exception {
        executor.execute(call, memory, toolArguments("eddi://ai.labs.workflow/workflowstore/workflows/abc123?version=3"), SERVER);
        // execute() above only primes the mock's body; resolve() is what gate time
        // calls.
        ResolvedRequest first = executor.resolve(call, memory, toolArguments("eddi://ai.labs.workflow/workflowstore/workflows/abc123?version=3"),
                SERVER);
        ResolvedRequest second = executor.resolve(call, memory, toolArguments("eddi://ai.labs.workflow/workflowstore/workflows/abc123?version=4"),
                SERVER);

        assertNotNull(first.fingerprint());
        assertEquals("eddi://ai.labs.workflow/workflowstore/workflows/abc123?version=3", first.body());
        assertEquals("eddi://ai.labs.workflow/workflowstore/workflows/abc123?version=4", second.body());
        assertNotEquals(first.fingerprint(), second.fingerprint(), "a different body must not share the approved fingerprint");
        ArgumentCaptor<String> contentType = ArgumentCaptor.forClass(String.class);
        verify(request, atLeastOnce()).setBodyEntity(any(), any(), contentType.capture());
        assertEquals("text/plain", contentType.getValue());
        verify(request, times(1)).send();
        verify(request, never()).setBodyEntity(any(), any(), eq("application/json"));
    }
}
