/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a sync preview reports for the ordinary promotion loop — iterate on the
 * source, promote to the target, repeat. Every case here once reported
 * something the executor then did not do, or failed to report something it did.
 */
@DisplayName("StructuralMatcher — the promotion loop")
class StructuralMatcherSyncTest {

    private static final String TARGET_AGENT = "a0a0a0a0a0a0a0a0a0a0a0a0";
    private static final String TARGET_WF = "b0b0b0b0b0b0b0b0b0b0b0b0";
    private static final String TARGET_WF2 = "b1b1b1b1b1b1b1b1b1b1b1b1";
    private static final String TARGET_LLM = "c0c0c0c0c0c0c0c0c0c0c0c0";
    private static final String TARGET_OUT = "d0d0d0d0d0d0d0d0d0d0d0d0";
    private static final String SOURCE_AGENT = "a9a9a9a9a9a9a9a9a9a9a9a9";
    private static final String SOURCE_WF = "b9b9b9b9b9b9b9b9b9b9b9b9";
    private static final String SOURCE_LLM = "c9c9c9c9c9c9c9c9c9c9c9c9";
    private static final String LLM_KEY = "eddi://ai.labs.llm#0/config";
    private static final String LLM_JSON = """
            {"tasks":[{"id":"t","type":"openai","actions":["ask"],"parameters":{"systemMessage":"hi"}}]}""";

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonSerialization jsonSerialization;
    private IRestAgentStore agentStore;
    private IDocumentDescriptorStore descriptorStore;
    private IRestWorkflowStore workflowStore;
    private IRestLlmStore llmStore;
    private StructuralMatcher matcher;
    private AgentConfiguration targetAgent;

    @BeforeEach
    void setUp() throws Exception {
        jsonSerialization = new JsonSerialization(mapper);
        agentStore = mock(IRestAgentStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        workflowStore = mock(IRestWorkflowStore.class);
        var snippetStore = mock(IRestPromptSnippetStore.class);
        var restInterfaceFactory = mock(IRestInterfaceFactory.class);
        llmStore = mock(IRestLlmStore.class);
        var outputStore = mock(IRestOutputStore.class);
        doReturn(llmStore).when(restInterfaceFactory).get(IRestLlmStore.class);
        doReturn(outputStore).when(restInterfaceFactory).get(IRestOutputStore.class);
        lenient().when(snippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        lenient().when(descriptorStore.readCurrentDescriptor(anyString())).thenReturn(new DocumentDescriptor());
        lenient().when(descriptorStore.readDescriptor(anyString(), anyInt())).thenReturn(new DocumentDescriptor());

        matcher = new StructuralMatcher(agentStore, descriptorStore, snippetStore, workflowStore,
                restInterfaceFactory, jsonSerialization);

        targetAgent = new AgentConfiguration();
        targetAgent.setWorkflows(new ArrayList<>(List.of(uri("workflow", TARGET_WF, 4))));
        lenient().doReturn(resourceId(TARGET_AGENT, 7)).when(agentStore).getCurrentResourceId(TARGET_AGENT);
        lenient().when(agentStore.readAgent(TARGET_AGENT, 7)).thenReturn(targetAgent);

        lenient().when(workflowStore.readWorkflow(TARGET_WF, 4)).thenReturn(workflow(step("llm", uri("llm", TARGET_LLM, 3))));
        lenient().when(llmStore.readLlm(TARGET_LLM, 3)).thenReturn(mapper.readValue(LLM_JSON, LlmConfiguration.class));
        lenient().when(outputStore.readOutputSet(TARGET_OUT, 2, "", "", 0, 0)).thenReturn(new OutputConfigurationSet());
    }

    @Test
    @DisplayName("an unchanged agent previews as unchanged, although every id differs between the instances")
    void unchangedPipelineIsSkipAcrossInstances() {
        ImportPreview preview = matcher.buildPreview(source(LLM_JSON), TARGET_AGENT, true);

        // Compared as written, both name each other's ids and never agree: the agent
        // and the workflow read as UPDATE on every sync while the executor (rightly)
        // wrote nothing — the preview said "2 changes" for ever.
        assertEquals(DiffAction.SKIP, row(preview, "agent").action());
        assertEquals(DiffAction.SKIP, row(preview, "workflow").action());
        assertEquals(DiffAction.SKIP, row(preview, "langchain").action());
    }

    @Test
    @DisplayName("an agent-level setting that changed is an agent UPDATE; the target's identity is not one")
    void agentSettingsAreCompared() {
        var sourceAgent = sourceAgentConfig();
        sourceAgent.setDescription("now with a description");
        ImportPreview changed = matcher.buildPreview(source(sourceAgent, LLM_JSON), TARGET_AGENT, true);
        assertEquals(DiffAction.UPDATE, row(changed, "agent").action());

        targetAgent.setIdentity(new AgentConfiguration.AgentIdentity("did:target", "target-public-key"));
        ImportPreview unchanged = matcher.buildPreview(source(LLM_JSON), TARGET_AGENT, true);
        assertEquals(DiffAction.SKIP, row(unchanged, "agent").action(),
                "the identity is bound to the target instance and never travels, so it is no difference");
    }

    @Test
    @DisplayName("a step the source removed is listed as REMOVE, naming the target's resource")
    void removedStepIsListed() throws Exception {
        when(workflowStore.readWorkflow(TARGET_WF, 4)).thenReturn(workflow(
                step("llm", uri("llm", TARGET_LLM, 3)), step("output", uri("output", TARGET_OUT, 2))));

        ImportPreview preview = matcher.buildPreview(source(LLM_JSON), TARGET_AGENT, true);

        assertEquals(DiffAction.UPDATE, row(preview, "workflow").action());
        ResourceDiff removed = row(preview, "output");
        assertEquals(DiffAction.REMOVE, removed.action());
        assertEquals(TARGET_OUT, removed.targetId());
        assertEquals(TARGET_OUT, removed.sourceId(), "the selection names a REMOVE row by the target's id");
    }

    @Test
    @DisplayName("a workflow the source no longer has is listed as REMOVE")
    void removedWorkflowIsListed() throws Exception {
        targetAgent.setWorkflows(new ArrayList<>(List.of(uri("workflow", TARGET_WF, 4), uri("workflow", TARGET_WF2, 1))));
        lenient().when(workflowStore.readWorkflow(TARGET_WF2, 1)).thenReturn(workflow());

        ImportPreview preview = matcher.buildPreview(source(LLM_JSON), TARGET_AGENT, true);

        ResourceDiff removed = preview.resources().stream()
                .filter(diff -> diff.action() == DiffAction.REMOVE).findFirst().orElseThrow();
        assertEquals("workflow", removed.resourceType());
        assertEquals(TARGET_WF2, removed.targetId());
        assertEquals(1, removed.workflowIndex());
    }

    @Test
    @DisplayName("a resource edited here since the last sync, and changed on the source too, is a CONFLICT")
    void localEditIsAConflict() throws Exception {
        String changed = LLM_JSON.replace("\"hi\"", "\"hello\"");
        // v2 is what the last sync wrote; v3, the current one, is a hand edit here.
        when(llmStore.readLlm(TARGET_LLM, 2)).thenReturn(mapper.readValue(
                LLM_JSON.replace("\"hi\"", "\"what staging sent\""), LlmConfiguration.class));

        descriptorWithSyncedVersion(TARGET_LLM, 3, 2);
        assertEquals(DiffAction.CONFLICT, row(matcher.buildPreview(source(changed), TARGET_AGENT, true), "langchain").action());

        descriptorWithSyncedVersion(TARGET_LLM, 3, 3);
        assertEquals(DiffAction.UPDATE, row(matcher.buildPreview(source(changed), TARGET_AGENT, true), "langchain").action(),
                "the version the last sync wrote is no local edit");

        descriptorWithSyncedVersion(TARGET_LLM, 3, null);
        assertEquals(DiffAction.UPDATE, row(matcher.buildPreview(source(changed), TARGET_AGENT, true), "langchain").action(),
                "a resource with no baseline cannot be told apart from a synced one, so it is never a conflict");
    }

    /**
     * Setting production's own API key right after a first promotion is the
     * expected local edit, and a sync never overwrites it. Counted as a conflict,
     * it made every later change to that LLM stop and ask.
     */
    @Test
    @DisplayName("a local edit to the target's own secrets only is no conflict — a sync keeps them anyway")
    void secretOnlyLocalEditIsNoConflict() throws Exception {
        String withKey = "{\"tasks\":[{\"id\":\"t\",\"type\":\"openai\",\"actions\":[\"ask\"],"
                + "\"parameters\":{\"systemMessage\":\"hi\",\"apiKey\":\"%s\"}}]}";
        when(llmStore.readLlm(TARGET_LLM, 3)).thenReturn(
                mapper.readValue(withKey.formatted("${vault:prod-openai-key}"), LlmConfiguration.class));
        when(llmStore.readLlm(TARGET_LLM, 2)).thenReturn(
                mapper.readValue(withKey.formatted("${vault:openai-key}"), LlmConfiguration.class));
        descriptorWithSyncedVersion(TARGET_LLM, 3, 2);

        String sourceChanged = withKey.formatted("${vault:openai-key}").replace("\"hi\"", "\"hello\"");
        assertEquals(DiffAction.UPDATE,
                row(matcher.buildPreview(source(sourceChanged), TARGET_AGENT, true), "langchain").action());
    }

    @Test
    @DisplayName("an edit here that the source did not change is no conflict — nothing would be written")
    void localEditWithoutSourceChangeIsSkip() {
        descriptorWithSyncedVersion(TARGET_LLM, 3, 2);

        assertEquals(DiffAction.SKIP, row(matcher.buildPreview(source(LLM_JSON), TARGET_AGENT, true), "langchain").action());
    }

    /**
     * Agent-level settings are written now, so a hotfix to them here — a HITL gate,
     * a capability — is guarded like any other resource's.
     */
    @Test
    @DisplayName("an agent setting changed here, and changed on the source too, is a CONFLICT")
    void localAgentSettingIsAConflict() throws Exception {
        var baseline = new AgentConfiguration();
        baseline.setWorkflows(new ArrayList<>(List.of(uri("workflow", TARGET_WF, 3))));
        when(agentStore.readAgent(TARGET_AGENT, 6)).thenReturn(baseline);
        descriptorWithSyncedVersion(TARGET_AGENT, 7, 6);
        targetAgent.setDescription("production hotfix");
        var sourceAgent = sourceAgentConfig();
        sourceAgent.setDescription("from staging");

        assertEquals(DiffAction.CONFLICT,
                row(matcher.buildPreview(source(sourceAgent, LLM_JSON), TARGET_AGENT, true), "agent").action());
    }

    /**
     * Editing an extension in the Manager moves the agent onto a new workflow
     * version. That is not an edit to the agent's own settings.
     */
    @Test
    @DisplayName("an agent that moved here only onto newer workflow versions is no conflict")
    void workflowVersionMoveIsNoAgentConflict() throws Exception {
        var baseline = new AgentConfiguration();
        baseline.setWorkflows(new ArrayList<>(List.of(uri("workflow", TARGET_WF, 3))));
        when(agentStore.readAgent(TARGET_AGENT, 6)).thenReturn(baseline);
        descriptorWithSyncedVersion(TARGET_AGENT, 7, 6);
        var sourceAgent = sourceAgentConfig();
        sourceAgent.setDescription("from staging");

        assertEquals(DiffAction.UPDATE,
                row(matcher.buildPreview(source(sourceAgent, LLM_JSON), TARGET_AGENT, true), "agent").action());
    }

    /**
     * Adopting the source's steps drops a step added here. That has to be a choice,
     * not a side effect of an unrelated promotion.
     */
    @Test
    @DisplayName("a step added here, where the source's steps differ, makes the workflow a CONFLICT")
    void localStepMakesTheWorkflowAConflict() throws Exception {
        when(workflowStore.readWorkflow(TARGET_WF, 4)).thenReturn(workflow(
                step("llm", uri("llm", TARGET_LLM, 3)), step("output", uri("output", TARGET_OUT, 2))));
        when(workflowStore.readWorkflow(TARGET_WF, 3)).thenReturn(workflow(step("llm", uri("llm", TARGET_LLM, 2))));
        descriptorWithSyncedVersion(TARGET_WF, 4, 3);

        ImportPreview preview = matcher.buildPreview(source(LLM_JSON), TARGET_AGENT, true);

        assertEquals(DiffAction.CONFLICT, row(preview, "workflow").action());
        assertEquals(DiffAction.REMOVE, row(preview, "output").action(),
                "what overwriting it would remove is still listed");
    }

    @Test
    @DisplayName("a workflow that moved here only onto newer extension versions is no conflict")
    void extensionVersionMoveIsNoWorkflowConflict() throws Exception {
        when(workflowStore.readWorkflow(TARGET_WF, 4)).thenReturn(workflow(
                step("llm", uri("llm", TARGET_LLM, 3)), step("output", uri("output", TARGET_OUT, 2))));
        when(workflowStore.readWorkflow(TARGET_WF, 3)).thenReturn(workflow(
                step("llm", uri("llm", TARGET_LLM, 2)), step("output", uri("output", TARGET_OUT, 1))));
        descriptorWithSyncedVersion(TARGET_WF, 4, 3);

        assertEquals(DiffAction.UPDATE, row(matcher.buildPreview(source(LLM_JSON), TARGET_AGENT, true), "workflow").action());
    }

    @Test
    @DisplayName("a target agent that does not exist is a 404, not a 500 with no body")
    void missingTargetIsNotFound() throws Exception {
        String missing = "f0f0f0f0f0f0f0f0f0f0f0f0";
        when(agentStore.getCurrentResourceId(missing)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));
        when(descriptorStore.readCurrentDescriptor(missing)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));

        var thrown = assertThrows(NotFoundException.class, () -> matcher.buildPreview(source(LLM_JSON), missing, true));
        assertTrue(thrown.getMessage().contains(missing), thrown.getMessage());
    }

    @Test
    @DisplayName("what the source warns about reaches the preview")
    void sourceWarningsAreCarried() {
        IResourceSource source = source(sourceAgentConfig(), LLM_JSON, List.of("2 snippets on the source are named 'tone'"));

        ImportPreview preview = matcher.buildPreview(source, TARGET_AGENT, true);

        assertEquals(List.of("2 snippets on the source are named 'tone'"), preview.warnings());
    }

    // ==================== helpers ====================

    private void descriptorWithSyncedVersion(String id, int version, Integer synced) {
        var descriptor = new DocumentDescriptor();
        descriptor.setSyncedVersion(synced);
        try {
            when(descriptorStore.readDescriptor(id, version)).thenReturn(descriptor);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private AgentConfiguration sourceAgentConfig() {
        var agent = new AgentConfiguration();
        agent.setWorkflows(new ArrayList<>(List.of(uri("workflow", SOURCE_WF, 1))));
        return agent;
    }

    private IResourceSource source(String llmJson) {
        return source(sourceAgentConfig(), llmJson);
    }

    private IResourceSource source(AgentConfiguration agent, String llmJson) {
        return source(agent, llmJson, List.of());
    }

    private IResourceSource source(AgentConfiguration agent, String llmJson, List<String> warnings) {
        Map<String, IResourceSource.ExtensionSourceData> extensions = new LinkedHashMap<>();
        extensions.put(LLM_KEY, new IResourceSource.ExtensionSourceData(SOURCE_LLM, "LLM", "langchain",
                "eddi://ai.labs.llm", asServed(llmJson)));
        var workflowData = new IResourceSource.WorkflowSourceData(SOURCE_WF, "Main", 0,
                workflow(step("llm", uri("llm", SOURCE_LLM, 1))), extensions);
        return new IResourceSource() {
            @Override
            public AgentSourceData readAgent() {
                return new AgentSourceData(SOURCE_AGENT, "Support Bot", agent);
            }

            @Override
            public List<WorkflowSourceData> readWorkflows() {
                return List.of(workflowData);
            }

            @Override
            public List<SnippetSourceData> readSnippets() {
                return List.of();
            }

            @Override
            public List<String> warnings() {
                return warnings;
            }
        };
    }

    /**
     * The document as another instance's store would serve it — every default field
     * included.
     */
    private String asServed(String llmJson) {
        try {
            return mapper.writeValueAsString(mapper.readValue(llmJson, LlmConfiguration.class));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static WorkflowConfiguration.WorkflowStep step(String type, URI uri) {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs." + type));
        step.setConfig(new LinkedHashMap<>(Map.of("uri", uri.toString())));
        return step;
    }

    private static WorkflowConfiguration workflow(WorkflowConfiguration.WorkflowStep... steps) {
        var workflow = new WorkflowConfiguration();
        workflow.setWorkflowSteps(new ArrayList<>(List.of(steps)));
        return workflow;
    }

    private static URI uri(String kind, String id, int version) {
        String path = switch (kind) {
            case "workflow" -> "eddi://ai.labs.workflow/workflowstore/workflows/";
            case "llm" -> "eddi://ai.labs.llm/llmstore/llms/";
            case "output" -> "eddi://ai.labs.output/outputstore/outputsets/";
            default -> throw new IllegalArgumentException(kind);
        };
        return URI.create(path + id + "?version=" + version);
    }

    private static IResourceId resourceId(String id, int version) {
        return new IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    private static ResourceDiff row(ImportPreview preview, String type) {
        ResourceDiff row = preview.resources().stream()
                .filter(diff -> type.equals(diff.resourceType()))
                .findFirst().orElse(null);
        assertNotNull(row, "no " + type + " row in " + preview.resources());
        return row;
    }
}
