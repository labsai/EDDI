/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.compat;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AgentCompatibilityLint - walks an agent and reports, never fails")
class AgentCompatibilityLintTest {

    private static final String AGENT = "aabb11112222333344445555";
    private static final String WORKFLOW = "aabb11112222333344446666";
    private static final String LLM_URI = "eddi://ai.labs.llm/llmstore/llms/aabb11112222333344447777?version=1";
    private static final String HTTP_URI = "eddi://ai.labs.apicalls/apicallstore/apicalls/aabb11112222333344448888?version=1";
    private static final String PROP_URI = "eddi://ai.labs.property/propertysetterstore/propertysetters/aabb11112222333344449999?version=1";

    private final ObjectMapper mapper = new ObjectMapper();
    private IAgentStore agentStore;
    private IWorkflowStore workflowStore;
    private IResourceClientLibrary resources;
    private AgentCompatibilityLint lint;

    @BeforeEach
    void setUp() throws Exception {
        agentStore = mock(IAgentStore.class);
        workflowStore = mock(IWorkflowStore.class);
        resources = mock(IResourceClientLibrary.class);
        var json = mock(IJsonSerialization.class);
        when(json.serialize(any())).thenAnswer(inv -> mapper.writeValueAsString(inv.getArgument(0)));
        when(json.deserialize(anyString(), eq(Map.class))).thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), Map.class));
        lint = new AgentCompatibilityLint(agentStore, workflowStore, resources, new TemplatingEngine(Engine.builder().addDefaults().build()),
                json);

        var agent = new AgentConfiguration();
        agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW + "?version=1")));
        when(agentStore.read(AGENT, 1)).thenReturn(agent);
    }

    private void workflowWith(String... uris) throws Exception {
        var workflow = new WorkflowConfiguration();
        for (String uri : uris) {
            var step = new WorkflowConfiguration.WorkflowStep();
            step.setConfig(new HashMap<>(Map.of("uri", uri)));
            workflow.getWorkflowSteps().add(step);
        }
        when(workflowStore.read(eq(WORKFLOW), anyInt())).thenReturn(workflow);
    }

    private LlmConfiguration llm(String type, Map<String, String> parameters) {
        var task = new LlmConfiguration.Task();
        task.setId("chat");
        task.setType(type);
        task.setParameters(new HashMap<>(parameters));
        return new LlmConfiguration(List.of(task));
    }

    @Test
    @DisplayName("a typical migrated 5.x LLM agent yields one line per problem")
    void reportsTheFiveXShape() throws Exception {
        workflowWith(LLM_URI, HTTP_URI, PROP_URI);
        when(resources.getResource(URI.create(LLM_URI), LlmConfiguration.class))
                .thenReturn(llm("gemini", Map.of("apiKey", "{properties.geminiToken}", "responseFormat", "json", "systemMessage", "hi")));
        when(resources.getResource(URI.create(HTTP_URI), ApiCallsConfiguration.class))
                .thenReturn(new ApiCallsConfiguration());
        var property = new PropertySetterConfiguration();
        when(resources.getResource(URI.create(PROP_URI), PropertySetterConfiguration.class)).thenReturn(property);

        List<String> warnings = lint.lint(AGENT, 1);

        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("[LLM_API_KEY_TEMPLATE] llm task 'chat'")));
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("[LLM_RESPONSE_FORMAT_WITHOUT_CONVERT]")));
    }

    @Test
    @DisplayName("a template in a system prompt that does not parse is reported")
    void reportsUnparsablePrompt() throws Exception {
        workflowWith(LLM_URI);
        when(resources.getResource(URI.create(LLM_URI), LlmConfiguration.class))
                .thenReturn(llm("openai", Map.of("systemMessage", "{userInfo.m : 'x') : 'y')}")));

        List<String> warnings = lint.lint(AGENT, 1);

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("[TEMPLATE_DOES_NOT_PARSE] llm task 'chat'.parameters.systemMessage"), warnings.get(0));
    }

    @Test
    @DisplayName("a clean agent has no warnings")
    void cleanAgent() throws Exception {
        workflowWith(LLM_URI);
        when(resources.getResource(URI.create(LLM_URI), LlmConfiguration.class))
                .thenReturn(llm("openai", Map.of("apiKey", "${vault:k}", "systemMessage", "Hello {properties.name}")));

        assertEquals(List.of(), lint.lint(AGENT, 1));
    }

    @Test
    @DisplayName("lint never throws: unreadable agent, workflow or resource just yield fewer warnings")
    void neverThrows() throws Exception {
        when(agentStore.read("ffff11112222333344445555", 1)).thenThrow(new IllegalStateException("store down"));
        assertEquals(List.of(), lint.lint("ffff11112222333344445555", 1));

        when(workflowStore.read(eq(WORKFLOW), anyInt())).thenThrow(new IllegalStateException("store down"));
        assertEquals(List.of(), lint.lint(AGENT, 1));

        workflowWith(LLM_URI);
        when(resources.getResource(any(), any())).thenThrow(new IllegalStateException("boom"));
        assertEquals(List.of(), lint.lint(AGENT, 1));
    }
}
