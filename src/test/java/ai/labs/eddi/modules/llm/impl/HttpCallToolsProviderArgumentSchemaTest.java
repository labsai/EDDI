/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.agenttests.ToolMockRegistry;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.tools.spi.ToolArgumentValidator;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Typed tool parameters, argument checking and rendering for an httpcall that
 * declares {@code argumentSchema} — and the unchanged behaviour of one that
 * does not.
 */
class HttpCallToolsProviderArgumentSchemaTest {

    private static final String AGENT_ID = "agent-1";
    private static final int AGENT_VERSION = 1;
    private static final String WORKFLOW_URI = "eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1";
    private static final String HTTPCALLS_URI = "eddi://ai.labs.httpcalls/apicallstore/apicalls/api-1?version=1";
    private static final String TOOL = "invite";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ITemplatingEngine TEMPLATES = new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build());

    private IConversationMemory memory;
    private IAgentStore agentStore;
    private IWorkflowStore workflowStore;
    private IResourceClientLibrary resourceClientLibrary;
    private IApiCallExecutor apiCallExecutor;
    private IMemoryItemConverter memoryItemConverter;
    private IJsonSerialization jsonSerialization;

    @BeforeEach
    void setUp() throws Exception {
        WorkflowTraversal.clearCache();
        memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn(AGENT_ID);
        when(memory.getAgentVersion()).thenReturn(AGENT_VERSION);
        agentStore = mock(IAgentStore.class);
        workflowStore = mock(IWorkflowStore.class);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        apiCallExecutor = mock(IApiCallExecutor.class);
        when(apiCallExecutor.execute(any(), any(), any(), anyString())).thenReturn(Map.of("httpCode", 201));
        memoryItemConverter = mock(IMemoryItemConverter.class);
        when(memoryItemConverter.convert(any())).thenAnswer(i -> new HashMap<>());
        jsonSerialization = new JsonSerialization(new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        WorkflowTraversal.clearCache();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema() throws Exception {
        return MAPPER.readValue("""
                {"type":"object","required":["teamId","notify","requestBody"],"properties":{
                  "teamId":{"type":"integer"},
                  "notify":{"type":"boolean"},
                  "requestBody":{"type":"object","required":["emails","role"],"properties":{
                    "emails":{"type":"array","items":{"type":"string"}},
                    "role":{"type":"string","enum":["manager","curator"]},
                    "seats":{"type":"integer"}}}
                }}""", Map.class);
    }

    private static ApiCall call(Map<String, Object> schema, String unknownFields) {
        var request = new Request();
        request.setMethod("post");
        request.setPath("/teams/{teamId}/invites");
        request.setQueryParams(new LinkedHashMap<>(Map.of("notify", "{notify}")));
        request.setContentType("application/json");
        request.setBody("{requestBody}");
        var call = new ApiCall();
        call.setName(TOOL);
        call.setRequest(request);
        var parameters = new LinkedHashMap<String, String>();
        parameters.put("teamId", "The team");
        parameters.put("notify", "Send mail");
        parameters.put("requestBody", "The complete JSON request body");
        call.setParameters(parameters);
        call.setArgumentSchema(schema);
        call.setUnknownFields(unknownFields);
        return call;
    }

    private ToolContribution discover(ApiCall apiCall) throws Exception {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create(WORKFLOW_URI)));
        when(agentStore.read(AGENT_ID, AGENT_VERSION)).thenReturn(agentConfig);
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.httpcalls"));
        step.setConfig(Map.of("uri", HTTPCALLS_URI));
        var workflowConfig = new WorkflowConfiguration();
        workflowConfig.setWorkflowSteps(List.of(step));
        when(workflowStore.read("wf-1", 1)).thenReturn(workflowConfig);
        var httpCallsConfig = new ApiCallsConfiguration();
        httpCallsConfig.setTargetServerUrl("https://api.example");
        httpCallsConfig.setHttpCalls(List.of(apiCall));
        when(resourceClientLibrary.getResource(eq(URI.create(HTTPCALLS_URI)), eq(ApiCallsConfiguration.class))).thenReturn(httpCallsConfig);
        return new HttpCallToolsProvider(agentStore, workflowStore, resourceClientLibrary, apiCallExecutor, jsonSerialization,
                memoryItemConverter).discover(memory);
    }

    private static ToolExecutionRequest toolCall(String arguments) {
        return ToolExecutionRequest.builder().id("call-1").name(TOOL).arguments(arguments).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sentTemplateData() throws Exception {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(apiCallExecutor).execute(any(), any(), captor.capture(), anyString());
        Map<String, Object> data = new HashMap<>(captor.getValue());
        data.remove(ToolMockRegistry.TOOL_PATH_MARKER);
        return data;
    }

    @Test
    void toolSpecIsTypedFromTheSchema() throws Exception {
        var spec = discover(call(schema(), null)).specs().getFirst();
        JsonObjectSchema parameters = spec.parameters();
        assertInstanceOf(JsonIntegerSchema.class, parameters.properties().get("teamId"));
        assertInstanceOf(JsonBooleanSchema.class, parameters.properties().get("notify"));
        var body = assertInstanceOf(JsonObjectSchema.class, parameters.properties().get("requestBody"));
        assertEquals("The complete JSON request body", body.description(), "the parameter description is kept");
        assertInstanceOf(JsonArraySchema.class, body.properties().get("emails"));
        var role = assertInstanceOf(JsonEnumSchema.class, body.properties().get("role"));
        assertEquals(List.of("manager", "curator"), role.enumValues());
        assertEquals(List.of("emails", "role"), body.required());
        assertEquals(List.of("teamId", "notify", "requestBody"), parameters.required());
    }

    @Test
    void aFreeFormObjectParameterIsOfferedAsAString() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = MAPPER.readValue("{\"type\":\"object\",\"properties\":{\"requestBody\":{\"type\":\"object\","
                + "\"properties\":{\"meta\":{\"type\":\"object\"}}}}}", Map.class);
        var spec = discover(call(schema, null)).specs().getFirst();
        assertInstanceOf(JsonStringSchema.class, spec.parameters().properties().get("requestBody"));
    }

    @Test
    void withoutASchemaEveryParameterStaysARequiredString() throws Exception {
        var spec = discover(call(null, null)).specs().getFirst();
        spec.parameters().properties().values().forEach(p -> assertInstanceOf(JsonStringSchema.class, p));
        assertEquals(List.of("teamId", "notify", "requestBody"), spec.parameters().required());
        var executor = discover(call(null, null)).executors().get(TOOL);
        assertTrue(((ToolArgumentValidator) executor).argumentViolations(toolCall("{\"nonsense\":1}")).isEmpty(),
                "no schema, nothing refused");
    }

    @Test
    void typedArgumentsRenderAsJsonNotJavaToString() throws Exception {
        var executor = discover(call(schema(), null)).executors().get(TOOL);
        executor.execute(toolCall("{\"teamId\":7,\"notify\":true,\"requestBody\":{\"emails\":[\"a@x\",\"b\\\"q@x\"],"
                + "\"role\":\"manager\",\"seats\":3}}"), "m");

        Map<String, Object> data = sentTemplateData();
        assertEquals("7", data.get("teamId"));
        assertEquals("true", data.get("notify"));
        String body = TEMPLATES.processTemplate("{requestBody}", data);
        assertEquals("{\"emails\":[\"a@x\",\"b\\\"q@x\"],\"role\":\"manager\",\"seats\":3}", body);
        assertEquals(MAPPER.readTree("{\"emails\":[\"a@x\",\"b\\\"q@x\"],\"role\":\"manager\",\"seats\":3}"), MAPPER.readTree(body),
                "the body is valid JSON with the quote still escaped");
        assertEquals("/teams/7/invites?notify=true", TEMPLATES.processTemplate("/teams/{teamId}/invites?notify={notify}", data));
    }

    @Test
    void aTemplateInsideAnArgumentIsSentAsDataNotEvaluated() throws Exception {
        var executor = discover(call(schema(), null)).executors().get(TOOL);
        executor.execute(toolCall("{\"teamId\":7,\"notify\":false,\"requestBody\":{\"emails\":[\"{context.userToken}\"],"
                + "\"role\":\"curator\"}}"), "m");
        String body = TEMPLATES.processTemplate("{requestBody}", sentTemplateData());
        assertTrue(body.contains("{context.userToken}"), body);
    }

    @Test
    void aBodyStillSentAsAJsonStringIsDecodedAndRenderedCompact() throws Exception {
        var executor = discover(call(schema(), null)).executors().get(TOOL);
        executor.execute(
                toolCall("{\"teamId\":7,\"notify\":true,\"requestBody\":\"{ \\\"emails\\\": [\\\"a@x\\\"], \\\"role\\\": \\\"curator\\\" }\"}"),
                "m");
        assertEquals("{\"emails\":[\"a@x\"],\"role\":\"curator\"}", sentTemplateData().get("requestBody"));
    }

    @Test
    void dropModeRemovesTheUndeclaredFieldFromTheRequest() throws Exception {
        var executor = discover(call(schema(), "drop")).executors().get(TOOL);
        executor.execute(toolCall("{\"teamId\":7,\"notify\":true,\"requestBody\":{\"emails\":[\"a@x\"],\"role\":\"curator\","
                + "\"colour\":\"red\"}}"), "m");
        assertEquals("{\"emails\":[\"a@x\"],\"role\":\"curator\"}", sentTemplateData().get("requestBody"));
    }

    @Test
    void anInvalidCallReachingTheExecutorIsRefusedAndNotSent() throws Exception {
        var executor = discover(call(schema(), null)).executors().get(TOOL);
        var request = toolCall("{\"teamId\":7,\"notify\":true,\"requestBody\":{\"emials\":[\"a@x\"],\"role\":\"boss\"}}");

        List<String> violations = ((ToolArgumentValidator) executor).argumentViolations(request);
        assertTrue(violations.contains("$.requestBody.role: must be one of manager, curator"), violations.toString());
        assertTrue(violations.contains("$.requestBody.emails: required"), violations.toString());

        String result = executor.execute(request, "m");
        assertEquals("NOT_EXECUTED", MAPPER.readTree(result).get("status").asText());
        verify(apiCallExecutor, never()).execute(any(), any(), any(), anyString());
    }

    /**
     * An old config: no argumentSchema. The template data that reaches body
     * rendering is exactly the deserialised arguments, so every request renders
     * byte-for-byte as before — including the Java toString of a structured value a
     * hand-written per-field template receives.
     */
    @Test
    void withoutASchemaTheRenderedRequestIsUnchanged() throws Exception {
        ApiCall legacy = call(null, null);
        legacy.getRequest().setBody("{\"count\": {count}, \"name\": \"{name}\", \"tags\": \"{tags}\"}");
        var executor = discover(legacy).executors().get(TOOL);
        executor.execute(toolCall("{\"teamId\":7,\"notify\":true,\"count\":5,\"name\":\"x\",\"tags\":{\"a\":1}}"), "m");

        Map<String, Object> data = sentTemplateData();
        assertEquals(7, data.get("teamId"), "an integer argument stays the deserialised Integer");
        assertEquals(Boolean.TRUE, data.get("notify"));
        assertEquals("{\"count\": 5, \"name\": \"x\", \"tags\": \"{a=1}\"}", TEMPLATES.processTemplate(legacy.getRequest().getBody(), data));
    }
}
