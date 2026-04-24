/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource;
import ai.labs.eddi.backup.IResourceSource.*;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link UpgradeExecutor} covering snippet processing, extension
 * dispatch, workflow URI updating, agent config update, selective filtering,
 * and workflow reordering.
 */
class UpgradeExecutorTest {

    private IRestAgentStore agentStore;
    private IRestWorkflowStore workflowStore;
    private IRestPromptSnippetStore snippetStore;
    private IJsonSerialization jsonSerialization;
    private StructuralMatcher structuralMatcher;
    private IDocumentDescriptorStore descriptorStore;
    private UpgradeExecutor executor;

    @BeforeEach
    void setUp() {
        agentStore = Mockito.mock(IRestAgentStore.class);
        workflowStore = Mockito.mock(IRestWorkflowStore.class);
        snippetStore = Mockito.mock(IRestPromptSnippetStore.class);
        jsonSerialization = Mockito.mock(IJsonSerialization.class);
        structuralMatcher = Mockito.mock(StructuralMatcher.class);
        descriptorStore = Mockito.mock(IDocumentDescriptorStore.class);

        executor = new UpgradeExecutor(agentStore, workflowStore,
                snippetStore, jsonSerialization, structuralMatcher, descriptorStore);
    }

    // ==================== Snippet Processing ====================

    @Nested
    @DisplayName("Snippet processing")
    class SnippetProcessing {

        @Test
        @DisplayName("should update existing snippet when action is UPDATE")
        void updatesExistingSnippet() throws Exception {
            var sourceSnippet = new SnippetSourceData("src-snp-1", "greeting",
                    createSnippet("greeting", "Hello!"));

            var source = createSource(List.of(), List.of(sourceSnippet));

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-snp-1", "snippet", "greeting",
                    DiffAction.UPDATE, "tgt-snp-1", 2, "name", null, null, -1));

            setupPreviewAndAgent("target-1", 1, diffs);

            executor.executeUpgrade(source, "target-1", null, null);

            verify(snippetStore).updateSnippet(eq("tgt-snp-1"), eq(2), any(PromptSnippet.class));
        }

        @Test
        @DisplayName("should create new snippet when action is CREATE")
        void createsNewSnippet() throws Exception {
            var sourceSnippet = new SnippetSourceData("src-snp-2", "new_snippet",
                    createSnippet("new_snippet", "Brand new"));

            var source = createSource(List.of(), List.of(sourceSnippet));

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-snp-2", "snippet", "new_snippet",
                    DiffAction.CREATE, null, null, null, null, null, -1));

            setupPreviewAndAgent("target-1", 1, diffs);

            executor.executeUpgrade(source, "target-1", null, null);

            verify(snippetStore).createSnippet(any(PromptSnippet.class));
        }

        @Test
        @DisplayName("should skip snippet when not in selected set")
        void skipsUnselectedSnippet() throws Exception {
            var sourceSnippet = new SnippetSourceData("src-snp-1", "greeting",
                    createSnippet("greeting", "Hello!"));

            var source = createSource(List.of(), List.of(sourceSnippet));

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-snp-1", "snippet", "greeting",
                    DiffAction.UPDATE, "tgt-snp-1", 2, "name", null, null, -1));

            setupPreviewAndAgent("target-1", 1, diffs);

            // Only select "other-resource", not "src-snp-1"
            executor.executeUpgrade(source, "target-1", Set.of("other-resource"), null);

            verify(snippetStore, never()).updateSnippet(anyString(), anyInt(), any());
            verify(snippetStore, never()).createSnippet(any());
        }
    }

    // ==================== Selective Filtering ====================

    @Nested
    @DisplayName("Selective filtering")
    class SelectiveFiltering {

        @Test
        @DisplayName("null selectedSourceIds should process all resources")
        void nullSelectedProcessesAll() throws Exception {
            var sourceSnippet = new SnippetSourceData("src-snp-1", "test",
                    createSnippet("test", "content"));
            var source = createSource(List.of(), List.of(sourceSnippet));

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-snp-1", "snippet", "test",
                    DiffAction.CREATE, null, null, null, null, null, -1));

            setupPreviewAndAgent("target-1", 1, diffs);

            executor.executeUpgrade(source, "target-1", null, null);

            verify(snippetStore).createSnippet(any());
        }
    }

    // ==================== Agent Config Update ====================

    @Nested
    @DisplayName("Agent config update")
    class AgentConfigUpdate {

        @Test
        @DisplayName("should update agent with new version and return URI")
        void updatesAgentAndReturnsUri() throws Exception {
            var source = createSource(List.of(), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));

            setupPreviewAndAgent("target-1", 3, diffs);

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>());
            when(agentStore.readAgent("target-1", 3)).thenReturn(agentConfig);
            when(agentStore.updateAgent(eq("target-1"), eq(3), any()))
                    .thenReturn(Response.ok().build());

            URI result = executor.executeUpgrade(source, "target-1", null, null);

            assertNotNull(result);
            assertTrue(result.toString().contains("target-1"));
            assertTrue(result.toString().contains("version=4"));
        }
    }

    // ==================== Workflow Reordering ====================

    @Nested
    @DisplayName("Workflow reordering")
    class WorkflowReordering {

        @Test
        @DisplayName("should reorder workflows according to specified order")
        void reordersWorkflows() throws Exception {
            // Use valid 24-char hex IDs (MongoDB ObjectId format) for
            // RestUtilities.extractResourceId
            String wfIdA = "aaaaaaaaaaaaaaaaaaaaaaaa";
            String wfIdB = "bbbbbbbbbbbbbbbbbbbbbbbb";
            String wfIdC = "cccccccccccccccccccccccc";

            var source = createSource(List.of(), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));

            // Setup preview and descriptor without setting up default agentConfig
            var preview = new ImportPreview("src-1", "Source Agent", "target-1", "Target Agent", diffs);
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(false))).thenReturn(preview);

            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/target-1?version=1"));
            when(descriptorStore.readDescriptor(eq("target-1"), isNull())).thenReturn(descriptor);

            // Agent with 3 workflows
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + wfIdA + "?version=1"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + wfIdB + "?version=1"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + wfIdC + "?version=1"))));
            when(agentStore.readAgent("target-1", 1)).thenReturn(agentConfig);
            when(agentStore.updateAgent(eq("target-1"), eq(1), any()))
                    .thenReturn(Response.ok().build());

            // Reverse order
            executor.executeUpgrade(source, "target-1", null, List.of(wfIdC, wfIdB, wfIdA));

            var captor = org.mockito.ArgumentCaptor.forClass(AgentConfiguration.class);
            verify(agentStore).updateAgent(eq("target-1"), eq(1), captor.capture());

            List<URI> workflows = captor.getValue().getWorkflows();
            assertEquals(3, workflows.size());
            assertTrue(workflows.get(0).toString().contains(wfIdC));
            assertTrue(workflows.get(1).toString().contains(wfIdB));
            assertTrue(workflows.get(2).toString().contains(wfIdA));
        }
    }

    // ==================== Extension Processing ====================

    @Nested
    @DisplayName("Extension processing")
    class ExtensionProcessing {

        @Test
        @DisplayName("should update matched extension and rewrite workflow URI")
        @SuppressWarnings("unchecked")
        void updatesExtensionAndRewritesWorkflowUri() throws Exception {
            String wfId = "aaaaaaaaaaaaaaaaaaaaaaaa";
            String extId = "bbbbbbbbbbbbbbbbbbbbbbbb";

            var llmExt = new ExtensionSourceData("src-ext-1", "GPT Config", "langchain", "ai.labs.llm", "{\"model\":\"gpt-4\"}");
            var sourceWf = new WorkflowSourceData("src-wf-1", "Workflow 1", 0,
                    new WorkflowConfiguration(), Map.of("ai.labs.llm", llmExt));
            var source = createSource(List.of(sourceWf), List.of());

            // Diffs: agent SKIP, workflow UPDATE with extension UPDATE
            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-wf-1", "workflow", "Workflow 1",
                    DiffAction.UPDATE, wfId, 1, "position", null, null, 0));
            diffs.add(new ResourceDiff("src-ext-1", "langchain", "GPT Config",
                    DiffAction.UPDATE, extId, 2, "type", null, null, -1));

            var preview = new ImportPreview("src-1", "Source Agent", "target-1", "Target Agent", diffs);
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(false))).thenReturn(preview);

            // Setup agent descriptor
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/target-1?version=1"));
            when(descriptorStore.readDescriptor(eq("target-1"), isNull())).thenReturn(descriptor);

            // Mock LLM store via CDI (getStore now uses CDI.current().select())
            var llmStore = Mockito.mock(ai.labs.eddi.configs.llm.IRestLlmStore.class);
            when(jsonSerialization.deserialize(eq("{\"model\":\"gpt-4\"}"), any()))
                    .thenReturn(new ai.labs.eddi.modules.llm.model.LlmConfiguration(List.of()));
            when(llmStore.updateLlm(eq(extId), eq(2), any())).thenReturn(Response.ok().build());

            // Workflow config with workflowStep referencing old extension URI
            var targetWfConfig = new WorkflowConfiguration();
            var step = new WorkflowConfiguration.WorkflowStep();
            step.setType(URI.create("ai.labs.llm"));
            step.setExtensions(new HashMap<>(Map.of("uri", "eddi://ai.labs.llm/llmstore/llms/" + extId + "?version=2")));
            targetWfConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.readWorkflow(wfId, 1)).thenReturn(targetWfConfig);
            when(workflowStore.updateWorkflow(eq(wfId), eq(1), any())).thenReturn(Response.ok().build());

            // Agent config
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + wfId + "?version=1"))));
            when(agentStore.readAgent("target-1", 1)).thenReturn(agentConfig);
            when(agentStore.updateAgent(eq("target-1"), eq(1), any())).thenReturn(Response.ok().build());

            @SuppressWarnings("rawtypes")
            MockedStatic<jakarta.enterprise.inject.spi.CDI> cdiMock = Mockito.mockStatic(jakarta.enterprise.inject.spi.CDI.class);
            try (cdiMock) {
                var cdiInstance = Mockito.mock(jakarta.enterprise.inject.spi.CDI.class);
                cdiMock.when(jakarta.enterprise.inject.spi.CDI::current).thenReturn(cdiInstance);
                var instanceLlm = (jakarta.enterprise.inject.Instance<ai.labs.eddi.configs.llm.IRestLlmStore>) Mockito
                        .mock(jakarta.enterprise.inject.Instance.class);
                when(cdiInstance.select(ai.labs.eddi.configs.llm.IRestLlmStore.class)).thenReturn(instanceLlm);
                when(instanceLlm.get()).thenReturn(llmStore);

                URI result = executor.executeUpgrade(source, "target-1", null, null);

                assertNotNull(result);
                verify(llmStore).updateLlm(eq(extId), eq(2), any());
                verify(workflowStore).updateWorkflow(eq(wfId), eq(1), any());
            }
        }

        @Test
        @DisplayName("should create new extension when action is CREATE")
        @SuppressWarnings("unchecked")
        void createsNewExtension() throws Exception {
            String wfId = "aaaaaaaaaaaaaaaaaaaaaaaa";

            var ragExt = new ExtensionSourceData("src-ext-2", "RAG Config", "rag", "ai.labs.rag", "{\"vectorStore\":\"pgvector\"}");
            var sourceWf = new WorkflowSourceData("src-wf-1", "Workflow 1", 0,
                    new WorkflowConfiguration(), Map.of("ai.labs.rag", ragExt));
            var source = createSource(List.of(sourceWf), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-wf-1", "workflow", "Workflow 1",
                    DiffAction.UPDATE, wfId, 1, "position", null, null, 0));
            diffs.add(new ResourceDiff("src-ext-2", "rag", "RAG Config",
                    DiffAction.CREATE, null, null, null, null, null, -1));

            var preview = new ImportPreview("src-1", "Source Agent", "target-1", "Target Agent", diffs);
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(false))).thenReturn(preview);

            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/target-1?version=1"));
            when(descriptorStore.readDescriptor(eq("target-1"), isNull())).thenReturn(descriptor);

            // Mock RAG direct store via CDI for create (dispatchCreateDirect uses CDI)
            var ragDirectStore = Mockito.mock(ai.labs.eddi.configs.rag.IRagStore.class);
            var ragRestStore = Mockito.mock(ai.labs.eddi.configs.rag.IRestRagStore.class);
            var ragResourceId = new ai.labs.eddi.datastore.IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return "newragid1234567890123456";
                }
                @Override
                public Integer getVersion() {
                    return 1;
                }
            };
            when(jsonSerialization.deserialize(eq("{\"vectorStore\":\"pgvector\"}"), any()))
                    .thenReturn(new ai.labs.eddi.configs.rag.model.RagConfiguration());
            when(ragDirectStore.create(any())).thenReturn(ragResourceId);

            // Workflow config (empty steps — no URI rewriting needed for CREATE)
            when(workflowStore.readWorkflow(wfId, 1)).thenReturn(new WorkflowConfiguration());

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + wfId + "?version=1"))));
            when(agentStore.readAgent("target-1", 1)).thenReturn(agentConfig);
            when(agentStore.updateAgent(eq("target-1"), eq(1), any())).thenReturn(Response.ok().build());

            @SuppressWarnings("rawtypes")
            MockedStatic<jakarta.enterprise.inject.spi.CDI> cdiMock = Mockito.mockStatic(jakarta.enterprise.inject.spi.CDI.class);
            try (cdiMock) {
                var cdiInstance = Mockito.mock(jakarta.enterprise.inject.spi.CDI.class);
                cdiMock.when(jakarta.enterprise.inject.spi.CDI::current).thenReturn(cdiInstance);

                // CDI lookup for IRestRagStore (getStore in resolveExtensionOps)
                var instanceRestRag = (jakarta.enterprise.inject.Instance<ai.labs.eddi.configs.rag.IRestRagStore>) Mockito
                        .mock(jakarta.enterprise.inject.Instance.class);
                when(cdiInstance.select(ai.labs.eddi.configs.rag.IRestRagStore.class)).thenReturn(instanceRestRag);
                when(instanceRestRag.get()).thenReturn(ragRestStore);

                // CDI lookup for IRagStore (dispatchCreateDirect)
                var instanceRag = (jakarta.enterprise.inject.Instance<ai.labs.eddi.configs.rag.IRagStore>) Mockito
                        .mock(jakarta.enterprise.inject.Instance.class);
                when(cdiInstance.select(ai.labs.eddi.configs.rag.IRagStore.class)).thenReturn(instanceRag);
                when(instanceRag.get()).thenReturn(ragDirectStore);

                executor.executeUpgrade(source, "target-1", null, null);

                verify(ragDirectStore).create(any());
            }
        }
    }

    // ==================== New Workflow Creation ====================

    @Nested
    @DisplayName("New workflow creation")
    class NewWorkflowCreation {

        @Test
        @DisplayName("should create new workflow and append to agent config")
        @SuppressWarnings("unchecked")
        void createsNewWorkflowAndAppendsToAgent() throws Exception {
            var newWf = new WorkflowSourceData("src-wf-1", "New Workflow", 0,
                    new WorkflowConfiguration(), Map.of());
            var source = createSource(List.of(newWf), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-wf-1", "workflow", "New Workflow",
                    DiffAction.CREATE, null, null, null, null, null, 0));

            setupPreviewAndAgent("target-1", 1, diffs);

            // Mock IWorkflowStore via CDI for direct create
            String newWfId = "newwfid123456789012";
            var wfDirectStore = Mockito.mock(ai.labs.eddi.configs.workflows.IWorkflowStore.class);
            var wfResourceId = new ai.labs.eddi.datastore.IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return newWfId;
                }
                @Override
                public Integer getVersion() {
                    return 1;
                }
            };
            when(wfDirectStore.create(any())).thenReturn(wfResourceId);

            @SuppressWarnings("rawtypes")
            MockedStatic<jakarta.enterprise.inject.spi.CDI> cdiMock = Mockito.mockStatic(jakarta.enterprise.inject.spi.CDI.class);
            try (cdiMock) {
                var cdiInstance = Mockito.mock(jakarta.enterprise.inject.spi.CDI.class);
                cdiMock.when(jakarta.enterprise.inject.spi.CDI::current).thenReturn(cdiInstance);
                var instanceWf = (jakarta.enterprise.inject.Instance<ai.labs.eddi.configs.workflows.IWorkflowStore>) Mockito
                        .mock(jakarta.enterprise.inject.Instance.class);
                when(cdiInstance.select(ai.labs.eddi.configs.workflows.IWorkflowStore.class)).thenReturn(instanceWf);
                when(instanceWf.get()).thenReturn(wfDirectStore);

                URI result = executor.executeUpgrade(source, "target-1", null, null);

                assertNotNull(result);
                verify(wfDirectStore).create(any());
                // Verify agent was updated with new workflow appended
                var captor = org.mockito.ArgumentCaptor.forClass(AgentConfiguration.class);
                verify(agentStore).updateAgent(eq("target-1"), eq(1), captor.capture());
                assertTrue(captor.getValue().getWorkflows().stream()
                        .anyMatch(uri -> uri.toString().contains(newWfId)));
            }
        }
    }

    // ==================== Error Handling ====================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("should throw RuntimeException on upgrade failure")
        void throwsOnFailure() {
            var source = createSource(List.of(), List.of());

            when(structuralMatcher.buildPreview(any(), anyString(), anyBoolean()))
                    .thenThrow(new RuntimeException("Preview failed"));

            assertThrows(RuntimeException.class, () -> executor.executeUpgrade(source, "target-1", null, null));
        }

        @Test
        @DisplayName("should propagate exception when agent update fails")
        void propagatesAgentUpdateFailure() throws Exception {
            var source = createSource(List.of(), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));

            var preview = new ImportPreview("src-1", "Source Agent", "target-1", "Target Agent", diffs);
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(false))).thenReturn(preview);

            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/target-1?version=1"));
            when(descriptorStore.readDescriptor(eq("target-1"), isNull())).thenReturn(descriptor);

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>());
            when(agentStore.readAgent("target-1", 1)).thenReturn(agentConfig);
            when(agentStore.updateAgent(eq("target-1"), eq(1), any()))
                    .thenThrow(new RuntimeException("DB error"));

            assertThrows(RuntimeException.class,
                    () -> executor.executeUpgrade(source, "target-1", null, null));
        }
    }

    // ==================== Test Helpers ====================

    private ResourceDiff agentDiff(String sourceId, String targetId, DiffAction action) {
        return new ResourceDiff(sourceId, "agent", "Agent", action, targetId, 1, "targetAgent", null, null, -1);
    }

    private void setupPreviewAndAgent(String targetAgentId, int version, List<ResourceDiff> diffs) throws Exception {
        var preview = new ImportPreview("src-1", "Source Agent", targetAgentId, "Target Agent", diffs);
        when(structuralMatcher.buildPreview(any(), eq(targetAgentId), eq(false))).thenReturn(preview);

        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + targetAgentId + "?version=" + version));
        when(descriptorStore.readDescriptor(eq(targetAgentId), isNull())).thenReturn(descriptor);

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(new ArrayList<>());
        when(agentStore.readAgent(targetAgentId, version)).thenReturn(agentConfig);
        when(agentStore.updateAgent(eq(targetAgentId), eq(version), any()))
                .thenReturn(Response.ok().build());
    }

    private IResourceSource createSource(List<WorkflowSourceData> workflows, List<SnippetSourceData> snippets) {
        var agentConfig = new AgentConfiguration();
        return new IResourceSource() {
            @Override
            public AgentSourceData readAgent() {
                return new AgentSourceData("src-1", "Source Agent", agentConfig);
            }
            @Override
            public List<WorkflowSourceData> readWorkflows() {
                return workflows;
            }
            @Override
            public List<SnippetSourceData> readSnippets() {
                return snippets;
            }
        };
    }

    private PromptSnippet createSnippet(String name, String content) {
        var snippet = new PromptSnippet();
        snippet.setName(name);
        snippet.setContent(content);
        return snippet;
    }
}
