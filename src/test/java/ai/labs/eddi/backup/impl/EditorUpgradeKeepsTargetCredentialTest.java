/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource;
import ai.labs.eddi.backup.IResourceSource.AgentSourceData;
import ai.labs.eddi.backup.IResourceSource.ExtensionSourceData;
import ai.labs.eddi.backup.IResourceSource.SnippetSourceData;
import ai.labs.eddi.backup.IResourceSource.WorkflowSourceData;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.llm.rest.RestLlmStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An editor-run upgrade of an agent whose LLM configuration holds a plaintext
 * {@code apiKey} keeps that plaintext in the target.
 * <p>
 * The source is an export, so its {@code apiKey} is the scrubber's
 * {@code ${vault:REDACTED}} placeholder, and the upgrade puts the target's own
 * value back in its place. That value used to be read through the loopback REST
 * client, which since M1 hands an editor the read-redaction marker: the preview
 * diffed against the marker and the write would put the marker over the stored
 * key — or, with the marker guard, be refused. The matcher now reads the target
 * unredacted, server-side.
 */
class EditorUpgradeKeepsTargetCredentialTest {

    private static final String TARGET_AGENT_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TARGET_WF_ID = "bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String TARGET_LLM_ID = "cccccccccccccccccccccccc";
    private static final String PLAINTEXT = "sk-target-plaintext-key";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IRestAgentStore agentStore;
    private IDocumentDescriptorStore descriptorStore;
    private IRestWorkflowStore workflowStore;
    private IRestPromptSnippetStore snippetStore;
    private ILlmStore llmStore;
    private RestLlmStore restLlmStore;
    private StructuralMatcher matcher;
    private IJsonSerialization json;

    @BeforeEach
    void setUp() throws Exception {
        agentStore = mock(IRestAgentStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        workflowStore = mock(IRestWorkflowStore.class);
        snippetStore = mock(IRestPromptSnippetStore.class);
        json = new JacksonJsonSerialization();

        // The real LLM store, for an EDITOR: readLlm redacts, readUnredacted does not,
        // and every write refuses the marker.
        llmStore = mock(ILlmStore.class);
        var editor = mock(ResourceAccessGuard.class);
        when(editor.isAdmin()).thenReturn(false);
        restLlmStore = new RestLlmStore(llmStore, descriptorStore, mock(IJsonSchemaCreator.class), editor);
        when(llmStore.read(TARGET_LLM_ID, 1)).thenReturn(llm(PLAINTEXT, "gpt-4"));
        var current = mock(IResourceStore.IResourceId.class);
        when(current.getId()).thenReturn(TARGET_LLM_ID);
        when(current.getVersion()).thenReturn(1);
        when(llmStore.getCurrentResourceId(TARGET_LLM_ID)).thenReturn(current);
        when(llmStore.update(eq(TARGET_LLM_ID), eq(1), any())).thenReturn(2);

        // The loopback client the matcher used to read through answers like readLlm
        // does for this caller — redacted.
        var restInterfaceFactory = mock(IRestInterfaceFactory.class);
        doReturn(restLlmStore).when(restInterfaceFactory).get(IRestLlmStore.class);

        matcher = new StructuralMatcher(agentStore, descriptorStore, snippetStore, workflowStore, restInterfaceFactory, json);
        matcher.llmStore = restLlmStore;

        lenient().when(descriptorStore.readCurrentDescriptor(anyString())).thenAnswer(inv -> descriptor(inv.getArgument(0), 1));
        lenient().when(descriptorStore.readDescriptor(anyString(), anyInt())).thenAnswer(inv -> descriptor(inv.getArgument(0), inv.getArgument(1)));
        doReturn(Collections.emptyList()).when(snippetStore).readSnippetDescriptors(anyString(), anyInt(), anyInt());
        var agent = new AgentConfiguration();
        agent.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + TARGET_WF_ID + "?version=1"))));
        doReturn(agent).when(agentStore).readAgent(TARGET_AGENT_ID, 1);
        doReturn(descriptor(TARGET_AGENT_ID, 1)).when(descriptorStore).readDescriptor(eq(TARGET_AGENT_ID), isNull());
        doReturn(descriptor(TARGET_WF_ID, 1)).when(descriptorStore).readDescriptor(eq(TARGET_WF_ID), isNull());
        doReturn(workflow()).when(workflowStore).readWorkflow(TARGET_WF_ID, 1);
    }

    @Test
    @DisplayName("the preview diffs against the stored plaintext, not the marker")
    void previewSeesThePlaintext() throws Exception {
        ImportPreview preview = matcher.buildPreview(exportedSource(), TARGET_AGENT_ID, true);

        ResourceDiff llmDiff = llmDiff(preview);
        assertTrue(llmDiff.targetContent().contains(PLAINTEXT), llmDiff.targetContent());
        assertFalse(llmDiff.targetContent().contains(ConfigSecretRedactor.MARKER_PREFIX), llmDiff.targetContent());
    }

    @Test
    @DisplayName("the upgrade writes the source's change with the target's plaintext key, and is not refused")
    @SuppressWarnings("unchecked")
    void upgradeKeepsThePlaintext() throws Exception {
        var executor = new UpgradeExecutor(agentStore, workflowStore, snippetStore, json, matcher, descriptorStore, mock(BackupMetrics.class),
                mock(ResourceAccessGuard.class));

        try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
            CDI<Object> cdi = mock(CDI.class);
            cdiMock.when(CDI::current).thenReturn(cdi);
            Instance<IRestLlmStore> instance = mock(Instance.class);
            when(cdi.select(IRestLlmStore.class)).thenReturn(instance);
            when(instance.get()).thenReturn(restLlmStore);

            executor.executeUpgrade(exportedSource(), TARGET_AGENT_ID, null, null);
        }

        var written = ArgumentCaptor.forClass(LlmConfiguration.class);
        verify(llmStore).update(eq(TARGET_LLM_ID), eq(1), written.capture());
        Map<String, String> parameters = written.getValue().tasks().get(0).getParameters();
        assertEquals(PLAINTEXT, parameters.get("apiKey"), "the target's own key is kept");
        assertEquals("gpt-5", parameters.get("modelName"), "the source's change is applied");
    }

    // ==================== Fixtures ====================

    private static LlmConfiguration llm(String apiKey, String model) {
        var task = new LlmConfiguration.Task();
        task.setId("main");
        task.setType("openai");
        task.setParameters(new LinkedHashMap<>(Map.of("apiKey", apiKey, "modelName", model)));
        return new LlmConfiguration(new ArrayList<>(List.of(task)));
    }

    private static WorkflowConfiguration workflow() {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.llm"));
        step.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.llm/llmstore/llms/" + TARGET_LLM_ID + "?version=1")));
        step.setExtensions(new HashMap<>());
        var config = new WorkflowConfiguration();
        config.setWorkflowSteps(new ArrayList<>(List.of(step)));
        return config;
    }

    /** What an export carries: the scrubbed placeholder, and a changed model. */
    private static IResourceSource exportedSource() throws Exception {
        var sourceWorkflow = workflow();
        String key = WorkflowExtensions.scan(sourceWorkflow).getFirst().key();
        var extension = new ExtensionSourceData("src-llm", "GPT Config", "langchain", "eddi://ai.labs.llm",
                MAPPER.writeValueAsString(llm("${vault:REDACTED}", "gpt-5")));
        var wf = new WorkflowSourceData("src-wf", "Workflow", 0, sourceWorkflow, Map.of(key, extension));
        var agent = new AgentConfiguration();
        agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + TARGET_WF_ID + "?version=1")));
        return new IResourceSource() {
            @Override
            public AgentSourceData readAgent() {
                return new AgentSourceData("src-agent", "Source Agent", agent);
            }

            @Override
            public List<WorkflowSourceData> readWorkflows() {
                return List.of(wf);
            }

            @Override
            public List<SnippetSourceData> readSnippets() {
                return List.of();
            }
        };
    }

    private static DocumentDescriptor descriptor(String id, Integer version) {
        var descriptor = new DocumentDescriptor();
        descriptor.setName("Target " + id);
        String type = TARGET_LLM_ID.equals(id)
                ? "ai.labs.llm/llmstore/llms/"
                : TARGET_WF_ID.equals(id) ? "ai.labs.workflow/workflowstore/workflows/" : "ai.labs.agent/agentstore/agents/";
        descriptor.setResource(URI.create("eddi://" + type + id + "?version=" + (version == null ? 1 : version)));
        return descriptor;
    }

    private static ResourceDiff llmDiff(ImportPreview preview) {
        return preview.resources().stream().filter(d -> "langchain".equals(d.resourceType())).findFirst()
                .orElseThrow(() -> new AssertionError("no langchain diff: " + preview.resources()));
    }

    /** A real serializer — the merge under test is about JSON, not mocks. */
    private static final class JacksonJsonSerialization implements IJsonSerialization {
        @Override
        public String serialize(Object model) throws IOException {
            return MAPPER.writeValueAsString(model);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String json) throws IOException {
            return (T) MAPPER.readValue(json, Object.class);
        }

        @Override
        public <T> T deserialize(String json, Class<T> type) throws IOException {
            return MAPPER.readValue(json, type);
        }
    }
}
