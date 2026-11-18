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
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.ToolArgumentBodyEscaper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A value the model supplies as a tool argument cannot change the structure of
 * the JSON request body it is substituted into. Drives the real tool executor
 * and renders the body the way the executor does (the rewritten template from
 * the template data), so the assertion is on the document that would be sent.
 */
class HttpCallToolsProviderArgumentEscapingTest {

    private static final String AGENT_ID = "agent-1";
    private static final String WORKFLOW_URI = "eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1";
    private static final String HTTPCALLS_URI = "eddi://ai.labs.httpcalls/apicallstore/apicalls/api-1?version=1";
    private static final String TOOL = "createUser";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Engine QUTE = Engine.builder().addDefaults().build();

    private IConversationMemory memory;
    private IApiCallExecutor apiCallExecutor;
    private IAgentStore agentStore;
    private IWorkflowStore workflowStore;
    private IResourceClientLibrary resourceClientLibrary;
    private IMemoryItemConverter memoryItemConverter;
    private IJsonSerialization jsonSerialization;

    @BeforeEach
    void setUp() {
        WorkflowTraversal.clearCache();
        memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn(AGENT_ID);
        when(memory.getAgentVersion()).thenReturn(1);
        agentStore = mock(IAgentStore.class);
        workflowStore = mock(IWorkflowStore.class);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        apiCallExecutor = mock(IApiCallExecutor.class);
        memoryItemConverter = mock(IMemoryItemConverter.class);
        when(memoryItemConverter.convert(any())).thenAnswer(inv -> new HashMap<String, Object>());
        jsonSerialization = new JsonSerialization(new ObjectMapper());
    }

    // ------------------------------------------------------------ the finding

    @Test
    void smugglingAttemptStaysInsideTheStringLiteral() throws Exception {
        JsonNode body = sentBody("{\"name\":\"{name}\"}", Map.of("name", "x\",\"role\":\"admin"));
        assertEquals(1, body.size(), "no second field may appear");
        assertEquals("x\",\"role\":\"admin", body.get("name").asText());
        assertFalse(body.has("role"));
    }

    @Test
    void controlCharactersBackslashesAndUnicodeInAStringLiteral() throws Exception {
        String value = "line1\nline2\t\"q\" back\\slash \u0001 café 中文 😀";
        JsonNode body = sentBody("{\"text\":\"Hi {name}!\"}", Map.of("name", value));
        assertEquals("Hi " + value + "!", body.get("text").asText());
    }

    @Test
    void placeholderInsideStringWithSurroundingTextAndTwoUses() throws Exception {
        JsonNode body = sentBody("{\"a\":\"{name}\",\"b\":\"<{name}>\"}", Map.of("name", "\"}, \"c\": {\""));
        assertEquals(2, body.size());
        assertEquals("<\"}, \"c\": {\">", body.get("b").asText());
    }

    // ------------------------------------------------------------ bare values

    @Test
    void bareNumberInTypedCallIsCanonical() throws Exception {
        ApiCall call = call("{\"count\":{count}}");
        call.setArgumentSchema(Map.of("type", "object", "properties", Map.of("count", Map.of("type", "integer"))));
        JsonNode body = sentBody(call, Map.of("count", 42));
        assertTrue(body.get("count").isIntegralNumber());
        assertEquals(42, body.get("count").asInt());
    }

    @Test
    void bareStringDeclaredTypeIsAQuotedString() throws Exception {
        ApiCall call = call("{\"count\":{count}}");
        call.setArgumentSchema(Map.of("type", "object", "properties", Map.of("count", Map.of("type", "string"))));
        // declared string: emitted as a quoted string, never as structure
        JsonNode body = sentBody(call, Map.of("count", "1,\"role\":\"admin\""));
        assertEquals(1, body.size());
        assertEquals("1,\"role\":\"admin\"", body.get("count").asText());
    }

    @Test
    void bareObjectArgumentIsEmittedAsObject() throws Exception {
        ApiCall call = call("{\"filter\":{filter}}");
        call.setArgumentSchema(Map.of("type", "object", "properties", Map.of("filter", Map.of("type", "object"))));
        JsonNode body = sentBody(call, Map.of("filter", Map.of("status", "open", "tag", "a\"b")));
        assertEquals("open", body.get("filter").get("status").asText());
        assertEquals("a\"b", body.get("filter").get("tag").asText());
    }

    @Test
    void bareObjectThatIsNotAnObjectIsRefusedAndNothingIsSent() throws Exception {
        ApiCall call = call("{\"filter\":{filter}}");
        call.setArgumentSchema(Map.of("type", "object", "properties", Map.of("filter", Map.of("type", "object"))));
        // the schema validator accepts a JSON-encoded container; a trailing smuggle is
        // not an object
        ToolExecutor executor = executorFor(call);
        String result = executor.execute(toolCall(jsonSerialization.serialize(Map.of("filter", "{\"a\":1},\"role\":\"admin\""))), "m");
        assertTrue(result.contains("must be an object"), result);
        verify(apiCallExecutor, never()).execute(any(), any(), any(), anyString());
    }

    @Test
    void bareStringInUntypedCallIsAQuotedJsonString() throws Exception {
        JsonNode body = sentBody("{\"name\":{name}}", Map.of("name", "x\",\"role\":\"admin"));
        assertEquals(1, body.size());
        assertEquals("x\",\"role\":\"admin", body.get("name").asText());
    }

    @Test
    void bareNumericStringInUntypedCallKeepsBeingANumber() throws Exception {
        JsonNode body = sentBody("{\"count\":{count}}", Map.of("count", "5"));
        assertTrue(body.get("count").isNumber());
    }

    @Test
    void bareBooleanAndArrayAreCanonical() throws Exception {
        ApiCall call = call("{\"on\":{on},\"ids\":{ids}}");
        call.setArgumentSchema(Map.of("type", "object", "properties",
                Map.of("on", Map.of("type", "boolean"), "ids", Map.of("type", "array"))));
        JsonNode body = sentBody(call, Map.of("on", true, "ids", List.of(1, 2, 3)));
        assertTrue(body.get("on").asBoolean());
        assertEquals(3, body.get("ids").size());
    }

    @Test
    void legitimateObjectArgumentInsideAStringLiteralPropertyAccess() throws Exception {
        ApiCall call = call("{\"who\":\"{user.name}\"}");
        JsonNode body = sentBody(call, Map.of("user", Map.of("name", "a\",\"x\":\"y")));
        assertEquals(1, body.size());
        assertEquals("a\",\"x\":\"y", body.get("who").asText());
    }

    // --------------------------------------------------- what is not touched

    @Test
    void authorDataIsNotEscaped() throws Exception {
        ApiCall call = call("{\"name\":\"{name}\",\"p\":\"{properties.p}\"}");
        ToolExecutor executor = executorFor(call);
        executor.execute(toolCall(jsonSerialization.serialize(Map.of("name", "x"))), "m");
        Map<String, Object> data = capturedData();
        data.put("properties", Map.of("p", "raw\"value"));
        String rendered = render(data, call.getRequest().getBody());
        assertEquals("{\"name\":\"x\",\"p\":\"raw\"value\"}", rendered);
    }

    @Test
    void jsonSerializeExpressionAndWholeBodyVariableAreLeftAlone() {
        Map<String, Object> data = new HashMap<>(Map.of("x", "v"));
        assertNull(ToolArgumentBodyEscaper.prepare("{\"a\":{json:serialize(x)}}", data, Set.of("x"), null));
        assertNull(ToolArgumentBodyEscaper.prepare("{requestBody}", data, Set.of("requestBody"), null));
    }

    @Test
    void nonJsonContentTypeIsNotRewritten() throws Exception {
        ApiCall call = call("name={name}");
        call.getRequest().setContentType("application/x-www-form-urlencoded");
        executorFor(call).execute(toolCall(jsonSerialization.serialize(Map.of("name", "a&b=c"))), "m");
        assertFalse(capturedData().containsKey(ToolArgumentBodyEscaper.REWRITTEN_BODY_KEY));
    }

    @Test
    void jsonSuffixAndParameterisedContentTypesAreJson() throws Exception {
        ApiCall call = call("{\"name\":\"{name}\"}");
        call.getRequest().setContentType("application/merge-patch+json; charset=utf-8");
        executorFor(call).execute(toolCall(jsonSerialization.serialize(Map.of("name", "a\"b"))), "m");
        assertTrue(capturedData().containsKey(ToolArgumentBodyEscaper.REWRITTEN_BODY_KEY));
    }

    // -------------------------------------------------------------- opt-out

    @Test
    void optOutRestoresRawSubstitution() throws Exception {
        ApiCall call = call("{\"name\":\"{name}\"}");
        call.setEscapeToolArguments(false);
        executorFor(call).execute(toolCall(jsonSerialization.serialize(Map.of("name", "x\",\"role\":\"admin"))), "m");
        Map<String, Object> data = capturedData();
        assertFalse(data.containsKey(ToolArgumentBodyEscaper.REWRITTEN_BODY_KEY));
        assertEquals(2, JSON.readTree(render(data, call.getRequest().getBody())).size());
    }

    // -------------------------------------------------------------- headers

    @Test
    void lineBreakInAnArgumentUsedInAHeaderIsRefused() throws Exception {
        ApiCall call = call("{\"a\":1}");
        call.getRequest().getHeaders().put("X-Trace", "{trace}");
        String result = executorFor(call).execute(toolCall(jsonSerialization.serialize(Map.of("trace", "abc\r\nX-Admin: 1"))), "m");
        assertTrue(result.contains("control character"), result);
        verify(apiCallExecutor, never()).execute(any(), any(), any(), anyString());
    }

    @Test
    void lineBreakInAnArgumentNotUsedInAHeaderIsFine() throws Exception {
        ApiCall call = call("{\"t\":\"{text}\"}");
        call.getRequest().getHeaders().put("X-Trace", "static");
        executorFor(call).execute(toolCall(jsonSerialization.serialize(Map.of("text", "a\nb"))), "m");
        verify(apiCallExecutor).execute(any(), any(), any(), anyString());
    }

    // -------------------------------------------------------------- helpers

    private JsonNode sentBody(String template, Map<String, Object> args) throws Exception {
        return sentBody(call(template), args);
    }

    private JsonNode sentBody(ApiCall call, Map<String, Object> args) throws Exception {
        executorFor(call).execute(toolCall(jsonSerialization.serialize(args)), "m");
        return JSON.readTree(render(capturedData(), call.getRequest().getBody()));
    }

    private String render(Map<String, Object> data, String configuredBody) {
        String template = data.get(ToolArgumentBodyEscaper.REWRITTEN_BODY_KEY) instanceof String s ? s : configuredBody;
        return QUTE.parse(template).data(data).render();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedData() throws Exception {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(apiCallExecutor).execute(any(), any(), captor.capture(), anyString());
        return captor.getValue();
    }

    private static ApiCall call(String body) {
        var request = new Request();
        request.setMethod("post");
        request.setPath("/users");
        request.setContentType("application/json");
        request.setBody(body);
        var apiCall = new ApiCall();
        apiCall.setName(TOOL);
        apiCall.setRequest(request);
        return apiCall;
    }

    private static ToolExecutionRequest toolCall(String arguments) {
        return ToolExecutionRequest.builder().id("call-1").name(TOOL).arguments(arguments).build();
    }

    private ToolExecutor executorFor(ApiCall apiCall) throws Exception {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create(WORKFLOW_URI)));
        when(agentStore.read(AGENT_ID, 1)).thenReturn(agentConfig);
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.httpcalls"));
        step.setConfig(Map.of("uri", HTTPCALLS_URI));
        var workflowConfig = new WorkflowConfiguration();
        workflowConfig.setWorkflowSteps(List.of(step));
        when(workflowStore.read("wf-1", 1)).thenReturn(workflowConfig);
        var httpCallsConfig = new ApiCallsConfiguration();
        httpCallsConfig.setTargetServerUrl("https://eddi.example");
        httpCallsConfig.setHttpCalls(List.of(apiCall));
        when(resourceClientLibrary.getResource(eq(URI.create(HTTPCALLS_URI)), eq(ApiCallsConfiguration.class))).thenReturn(httpCallsConfig);
        var provider = new HttpCallToolsProvider(agentStore, workflowStore, resourceClientLibrary, apiCallExecutor, jsonSerialization,
                memoryItemConverter);
        ToolExecutor executor = provider.discover(memory).executors().get(TOOL);
        assertNotNull(executor);
        return executor;
    }
}
