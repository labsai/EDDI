/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.HttpPreRequest;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A call with {@code preRequest.propertyInstructions} still renders its request
 * from the template data the caller handed in.
 * <p>
 * The LLM tool path merges the model's arguments into that map
 * ({@code HttpCallToolsProvider}), and a rule-triggered batch shares it across
 * the calls of one turn. The pre-request step used to rebuild the template data
 * from conversation memory after running the instructions, which dropped both:
 * {@code DELETE /items/{id}} went out as {@code DELETE /items/}.
 * <p>
 * A real {@link PrePostUtils} and a real Qute engine: stubbing the pre-request
 * step as a pass-through, as the sibling executor tests do, steps over the very
 * behaviour under test.
 */
class ApiCallExecutorPreRequestTest {

    private IHttpClient httpClient;
    private ApiCallExecutor executor;
    private IConversationMemory memory;

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

        var engine = Engine.builder().addDefaults().strictRendering(false).build();
        var prePostUtils = new PrePostUtils(jsonSerialization, new TemplatingEngine(engine),
                mock(IDataFactory.class), mock(SecretPropertyVault.class));

        executor = new ApiCallExecutor(httpClient, jsonSerialization, mock(IRuntime.class), prePostUtils, globalVariableResolver,
                secretResolver, callerIdentityResolver, mock(CallerIdentityContext.class), new RequestRedactor(callerIdentityResolver), null,
                false, 30_000L, 2_000_000);

        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));
        when(memory.getConversationProperties()).thenReturn(new ConversationProperties(null));

        var request = mock(IRequest.class);
        var response = mock(IResponse.class);
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(request);
        when(request.toMap()).thenReturn(new HashMap<>());
        when(request.send()).thenReturn(response);
        when(request.setBodyEntity(any(), any(), any())).thenReturn(request);
        when(request.setHttpHeader(any(), any())).thenReturn(request);
        when(request.setQueryParam(any(), any())).thenReturn(request);
        when(response.getHttpCode()).thenReturn(204);
        when(response.getContentAsString()).thenReturn("");
        when(response.getHttpHeader()).thenReturn(new HashMap<>());
    }

    @Test
    @DisplayName("a template-data value and the property the instruction set both reach the request")
    void callerValueAndInstructionPropertyBothRender() throws Exception {
        var instruction = new PropertyInstruction();
        instruction.setName("reason");
        instruction.setValueString("cleanup");
        instruction.setScope(Property.Scope.conversation);

        URI uri = executeDelete("/items/{id}/{properties.reason}", List.of(instruction));

        assertEquals("/items/42/cleanup", uri.getRawPath());
    }

    @Test
    @DisplayName("an empty instruction list keeps the template data too")
    void emptyInstructionListKeepsTemplateData() throws Exception {
        URI uri = executeDelete("/items/{id}", new ArrayList<>());

        assertEquals("/items/42", uri.getRawPath());
    }

    private URI executeDelete(String path, List<PropertyInstruction> instructions) throws Exception {
        var call = new ApiCall();
        call.setName("deleteItem");
        call.setSaveResponse(false);
        call.setFireAndForget(false);
        var request = new Request();
        request.setPath(path);
        request.setMethod("DELETE");
        call.setRequest(request);
        var preRequest = new HttpPreRequest();
        preRequest.setPropertyInstructions(instructions);
        call.setPreRequest(preRequest);

        // What HttpCallToolsProvider hands in for a model argument {"id": "42"}.
        Map<String, Object> templateData = new HashMap<>();
        templateData.put("id", "42");

        executor.execute(call, memory, templateData, "http://api.example.com");

        var captor = ArgumentCaptor.forClass(URI.class);
        verify(httpClient).newRequest(captor.capture(), any());
        return captor.getValue();
    }
}
