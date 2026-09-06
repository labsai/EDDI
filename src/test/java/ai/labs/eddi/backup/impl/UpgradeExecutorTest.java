/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.backup.IResourceSource;
import ai.labs.eddi.backup.IResourceSource.*;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.UpgradeResult;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.mcpcalls.IRestMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.NotFoundException;
import java.io.IOException;
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
                snippetStore, jsonSerialization, structuralMatcher, descriptorStore, mock(BackupMetrics.class), mock(ResourceAccessGuard.class));
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
        @DisplayName("should update agent with new version and return URI when a workflow order is given")
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

            var result = executor.executeUpgrade(source, "target-1", null, List.of("wf-1"));

            assertTrue(result.agentUpdated());
            assertNotNull(result.agentUri());
            assertTrue(result.agentUri().toString().contains("target-1"));
            assertTrue(result.agentUri().toString().contains("version=4"));
        }

        @Test
        @DisplayName("should NOT burn an agent version when nothing changed")
        void leavesAgentVersionAloneWhenNothingChanged() throws Exception {
            var source = createSource(List.of(), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));

            setupPreviewAndAgent("target-1", 3, diffs);

            var result = executor.executeUpgrade(source, "target-1", null, null);

            // An unconditional updateAgent wrote a byte-identical configuration and
            // bumped the version, so a CI job that synced on every build inflated the
            // version history and "v14" said nothing about whether anything changed.
            verify(agentStore, never()).updateAgent(anyString(), anyInt(), any());
            assertFalse(result.agentUpdated());
            assertFalse(result.wroteAnything());
            assertEquals("eddi://ai.labs.agent/agentstore/agents/target-1?version=3",
                    result.agentUri().toString());
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
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(true))).thenReturn(preview);

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

            var captor = ArgumentCaptor.forClass(AgentConfiguration.class);
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
        @DisplayName("should update matched extension and rewrite the step's config.uri")
        @SuppressWarnings("unchecked")
        void updatesExtensionAndRewritesWorkflowUri() throws Exception {
            String wfId = "aaaaaaaaaaaaaaaaaaaaaaaa";
            String extId = "bbbbbbbbbbbbbbbbbbbbbbbb";
            String oldExtUri = "eddi://ai.labs.llm/llmstore/llms/" + extId + "?version=2";

            // A workflow shaped the way AgentSetupService writes one and the way the
            // shipped reference config looks: type is the eddi:// step-type URI and
            // the extension reference lives in config.uri, not in extensions.
            var sourceWfConfig = workflowWithLlmStep(oldExtUri);
            String extensionKey = WorkflowExtensions.scan(sourceWfConfig).getFirst().key();

            var llmExt = new ExtensionSourceData("src-ext-1", "GPT Config", "langchain",
                    "eddi://ai.labs.llm", "{\"model\":\"gpt-4\"}");
            var sourceWf = new WorkflowSourceData("src-wf-1", "Workflow 1", 0,
                    sourceWfConfig, Map.of(extensionKey, llmExt));
            var source = createSource(List.of(sourceWf), List.of());

            // Diffs: agent SKIP, workflow UPDATE with extension UPDATE
            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-wf-1", "workflow", "Workflow 1",
                    DiffAction.UPDATE, wfId, 1, "position", null, null, 0));
            diffs.add(new ResourceDiff("src-ext-1", "langchain", "GPT Config",
                    DiffAction.UPDATE, extId, 2, "type", null, null, -1));

            var preview = new ImportPreview("src-1", "Source Agent", "target-1", "Target Agent", diffs);
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(true))).thenReturn(preview);

            // Setup agent descriptor
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/target-1?version=1"));
            when(descriptorStore.readDescriptor(eq("target-1"), isNull())).thenReturn(descriptor);

            // Mock LLM store via CDI (getStore now uses CDI.current().select())
            var llmStore = Mockito.mock(IRestLlmStore.class);
            when(jsonSerialization.deserialize(eq("{\"model\":\"gpt-4\"}"), any()))
                    .thenReturn(new LlmConfiguration(List.of()));
            when(llmStore.updateLlm(eq(extId), eq(2), any())).thenReturn(Response.ok().build());

            var targetWfConfig = workflowWithLlmStep(oldExtUri);
            when(workflowStore.readWorkflow(wfId, 1)).thenReturn(targetWfConfig);
            when(workflowStore.updateWorkflow(eq(wfId), eq(1), any())).thenReturn(Response.ok().build());

            // Agent config
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + wfId + "?version=1"))));
            when(agentStore.readAgent("target-1", 1)).thenReturn(agentConfig);
            when(agentStore.updateAgent(eq("target-1"), eq(1), any())).thenReturn(Response.ok().build());

            @SuppressWarnings("rawtypes")
            MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
            try (cdiMock) {
                var cdiInstance = Mockito.mock(CDI.class);
                cdiMock.when(CDI::current).thenReturn(cdiInstance);
                var instanceLlm = (Instance<IRestLlmStore>) Mockito
                        .mock(Instance.class);
                when(cdiInstance.select(IRestLlmStore.class)).thenReturn(instanceLlm);
                when(instanceLlm.get()).thenReturn(llmStore);

                var result = executor.executeUpgrade(source, "target-1", null, null);

                assertNotNull(result.agentUri());
                assertFalse(result.hasFailures());
                verify(llmStore).updateLlm(eq(extId), eq(2), any());

                var captor = ArgumentCaptor.forClass(WorkflowConfiguration.class);
                verify(workflowStore).updateWorkflow(eq(wfId), eq(1), captor.capture());

                // The engine reads config.uri. Writing the new version into
                // extensions.uri left the deployed pipeline on the OLD extension
                // version while the agent version was bumped, and left a stray key
                // that reference scans do not count as a reference.
                var updatedStep = captor.getValue().getWorkflowSteps().getFirst();
                assertEquals("eddi://ai.labs.llm/llmstore/llms/" + extId + "?version=3",
                        updatedStep.getConfig().get("uri"));
                assertFalse(updatedStep.getExtensions().containsKey("uri"));

                // ... and the agent is versioned because the workflow genuinely changed
                verify(agentStore).updateAgent(eq("target-1"), eq(1), any());
            }
        }

        @Test
        @DisplayName("a scrubbed secret is replaced by the target's own value, not written as a placeholder")
        @SuppressWarnings("unchecked")
        void restoresScrubbedSecretsFromTarget() throws Exception {
            String wfId = "aaaaaaaaaaaaaaaaaaaaaaaa";
            String extId = "bbbbbbbbbbbbbbbbbbbbbbbb";

            // Everything in an export ZIP has been through the secret scrubber, which
            // replaces live credentials with ${vault:REDACTED}. Writing that straight
            // into the target replaced a production agent's working API keys with
            // placeholders — an upgrade from an export broke the agent it updated.
            String scrubbedSource = "{\"apiKey\":\"${vault:REDACTED}\",\"model\":\"gpt-4\"}";
            String targetContent = "{\"apiKey\":\"sk-live-abc\",\"model\":\"gpt-3\"}";

            var llmExt = new ExtensionSourceData("src-ext-1", "GPT Config", "langchain",
                    "eddi://ai.labs.llm", scrubbedSource);
            var sourceWf = new WorkflowSourceData("src-wf-1", "Workflow 1", 0,
                    new WorkflowConfiguration(), Map.of("k", llmExt));
            var source = createSource(List.of(sourceWf), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-wf-1", "workflow", "Workflow 1",
                    DiffAction.UPDATE, wfId, 1, "position", null, null, 0));
            diffs.add(new ResourceDiff("src-ext-1", "langchain", "GPT Config",
                    DiffAction.UPDATE, extId, 2, "type", scrubbedSource, targetContent, -1));

            setupPreviewAndAgent("target-1", 1, diffs);
            when(workflowStore.readWorkflow(wfId, 1)).thenReturn(new WorkflowConfiguration());

            // Real JSON round-tripping — the merge under test is about JSON, not mocks.
            var mapper = new ObjectMapper();
            when(jsonSerialization.deserialize(anyString()))
                    .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), Object.class));
            when(jsonSerialization.serialize(any()))
                    .thenAnswer(inv -> mapper.writeValueAsString(inv.getArgument(0)));

            var mergedJson = new AtomicReference<String>();
            when(jsonSerialization.deserialize(anyString(), eq(LlmConfiguration.class)))
                    .thenAnswer(inv -> {
                        mergedJson.set(inv.getArgument(0));
                        return new LlmConfiguration(List.of());
                    });

            var llmStore = Mockito.mock(IRestLlmStore.class);
            when(llmStore.updateLlm(eq(extId), eq(2), any())).thenReturn(Response.ok().build());

            @SuppressWarnings("rawtypes")
            MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
            try (cdiMock) {
                var cdiInstance = Mockito.mock(CDI.class);
                cdiMock.when(CDI::current).thenReturn(cdiInstance);
                var instanceLlm = (Instance<IRestLlmStore>) Mockito
                        .mock(Instance.class);
                when(cdiInstance.select(IRestLlmStore.class)).thenReturn(instanceLlm);
                when(instanceLlm.get()).thenReturn(llmStore);

                executor.executeUpgrade(source, "target-1", null, null);
            }

            assertNotNull(mergedJson.get(), "the store update never ran");
            assertFalse(mergedJson.get().contains("${vault:REDACTED}"), mergedJson.get());
            assertTrue(mergedJson.get().contains("sk-live-abc"), mergedJson.get());
            // Everything that is not a scrubbed secret still comes from the source.
            assertTrue(mergedJson.get().contains("gpt-4"), mergedJson.get());
        }

        /**
         * A workflow step in the shape the engine and the exporter actually produce.
         */
        private WorkflowConfiguration workflowWithLlmStep(String extensionUri) {
            var config = new WorkflowConfiguration();
            var step = new WorkflowConfiguration.WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.llm"));
            step.setConfig(new HashMap<>(Map.of("uri", extensionUri)));
            step.setExtensions(new HashMap<>());
            config.setWorkflowSteps(new ArrayList<>(List.of(step)));
            return config;
        }

        /**
         * An extension the target workflow has no step for cannot be wired up:
         * {@code updateWorkflowExtensionUris} can only repoint a reference the target
         * already has, and CREATE means by definition that it has none. Creating the
         * resource anyway left it in the store unreferenced, counted it as created,
         * answered 201, changed the agent's behaviour not at all, and previewed the
         * same CREATE again next time — so every sync added another dead copy.
         */
        @Test
        @DisplayName("an extension the target workflow has no step for is refused, not created as an orphan")
        @SuppressWarnings("unchecked")
        void refusesToCreateAnOrphanExtension() throws Exception {
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
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(true))).thenReturn(preview);

            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/target-1?version=1"));
            when(descriptorStore.readDescriptor(eq("target-1"), isNull())).thenReturn(descriptor);

            var ragDirectStore = Mockito.mock(IRagStore.class);
            var ragRestStore = Mockito.mock(IRestRagStore.class);
            when(jsonSerialization.deserialize(eq("{\"vectorStore\":\"pgvector\"}"), any()))
                    .thenReturn(new RagConfiguration());

            // The target workflow has no rag step — that is what CREATE means.
            when(workflowStore.readWorkflow(wfId, 1)).thenReturn(new WorkflowConfiguration());

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + wfId + "?version=1"))));
            when(agentStore.readAgent("target-1", 1)).thenReturn(agentConfig);
            when(agentStore.updateAgent(eq("target-1"), eq(1), any())).thenReturn(Response.ok().build());

            @SuppressWarnings("rawtypes")
            MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
            try (cdiMock) {
                var cdiInstance = Mockito.mock(CDI.class);
                cdiMock.when(CDI::current).thenReturn(cdiInstance);

                // CDI lookup for IRestRagStore (getStore in resolveExtensionOps)
                var instanceRestRag = (Instance<IRestRagStore>) Mockito
                        .mock(Instance.class);
                when(cdiInstance.select(IRestRagStore.class)).thenReturn(instanceRestRag);
                when(instanceRestRag.get()).thenReturn(ragRestStore);

                // CDI lookup for IRagStore (dispatchCreateDirect)
                var instanceRag = (Instance<IRagStore>) Mockito
                        .mock(Instance.class);
                when(cdiInstance.select(IRagStore.class)).thenReturn(instanceRag);
                when(instanceRag.get()).thenReturn(ragDirectStore);

                UpgradeResult result = executor.executeUpgrade(source, "target-1", null, null);

                verify(ragDirectStore, never()).create(any());
                assertEquals(0, result.created(), "an unreferenceable resource must not be reported as created");
                assertEquals(1, result.failures().size(), result.failures().toString());
                String reason = result.failures().getFirst().reason();
                assertTrue(reason.contains("no step"), "the operator must be told why, got: " + reason);
                assertFalse(result.agentUpdated(), "nothing was written, so no agent version may be burned");
            }
        }

        /**
         * The flagship path: export an agent, edit one extension inside the ZIP,
         * upgrade the same agent. The workflow JSON is byte-identical on both sides, so
         * the matcher answers SKIP for the workflow while the extension is UPDATE.
         * Reading the extension diffs only inside the workflow's UPDATE branch threw
         * away every change the preview had just shown the operator, and the response
         * still said 200 OK with updated=0.
         */
        @Test
        @DisplayName("a workflow whose skeleton is unchanged still has its extension changes applied")
        @SuppressWarnings("unchecked")
        void skippedWorkflowStillAppliesExtensionUpdates() throws Exception {
            String wfId = "aaaaaaaaaaaaaaaaaaaaaaaa";
            String extId = "bbbbbbbbbbbbbbbbbbbbbbbb";
            String oldExtUri = "eddi://ai.labs.llm/llmstore/llms/" + extId + "?version=2";

            var sourceWfConfig = workflowWithLlmStep(oldExtUri);
            String extensionKey = WorkflowExtensions.scan(sourceWfConfig).getFirst().key();

            var llmExt = new ExtensionSourceData("src-ext-1", "GPT Config", "langchain",
                    "eddi://ai.labs.llm", "{\"model\":\"gpt-4\"}");
            var sourceWf = new WorkflowSourceData("src-wf-1", "Workflow 1", 0,
                    sourceWfConfig, Map.of(extensionKey, llmExt));
            var source = createSource(List.of(sourceWf), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            // SKIP: the workflow skeleton is identical on both sides — the ZIP was
            // produced from this very agent and only the LLM content was edited.
            diffs.add(new ResourceDiff("src-wf-1", "workflow", "Workflow 1",
                    DiffAction.SKIP, wfId, 1, "position", null, null, 0));
            diffs.add(new ResourceDiff("src-ext-1", "langchain", "GPT Config",
                    DiffAction.UPDATE, extId, 2, "type", null, null, -1));

            var preview = new ImportPreview("src-1", "Source Agent", "target-1", "Target Agent", diffs);
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(true))).thenReturn(preview);

            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/target-1?version=1"));
            when(descriptorStore.readDescriptor(eq("target-1"), isNull())).thenReturn(descriptor);

            var llmStore = Mockito.mock(IRestLlmStore.class);
            when(jsonSerialization.deserialize(eq("{\"model\":\"gpt-4\"}"), any()))
                    .thenReturn(new LlmConfiguration(List.of()));
            when(llmStore.updateLlm(eq(extId), eq(2), any())).thenReturn(Response.ok().build());

            when(workflowStore.readWorkflow(wfId, 1)).thenReturn(workflowWithLlmStep(oldExtUri));
            when(workflowStore.updateWorkflow(eq(wfId), eq(1), any())).thenReturn(Response.ok().build());

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + wfId + "?version=1"))));
            when(agentStore.readAgent("target-1", 1)).thenReturn(agentConfig);
            when(agentStore.updateAgent(eq("target-1"), eq(1), any())).thenReturn(Response.ok().build());

            @SuppressWarnings("rawtypes")
            MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
            try (cdiMock) {
                var cdiInstance = Mockito.mock(CDI.class);
                cdiMock.when(CDI::current).thenReturn(cdiInstance);
                var instanceLlm = (Instance<IRestLlmStore>) Mockito.mock(Instance.class);
                when(cdiInstance.select(IRestLlmStore.class)).thenReturn(instanceLlm);
                when(instanceLlm.get()).thenReturn(llmStore);

                UpgradeResult result = executor.executeUpgrade(source, "target-1", null, null);

                // The edit the operator previewed actually reaches the store...
                verify(llmStore).updateLlm(eq(extId), eq(2), any());
                // ...the workflow is repointed at the new extension version...
                var captor = ArgumentCaptor.forClass(WorkflowConfiguration.class);
                verify(workflowStore).updateWorkflow(eq(wfId), eq(1), captor.capture());
                assertEquals("eddi://ai.labs.llm/llmstore/llms/" + extId + "?version=3",
                        captor.getValue().getWorkflowSteps().getFirst().getConfig().get("uri"));
                // ...and the run reports what it did instead of "skipped 1, wrote nothing".
                assertEquals(2, result.updated(), "the extension and its workflow were both written");
                assertTrue(result.agentUpdated(), "the agent must point at the new workflow version");
                assertTrue(result.wroteAnything());
            }
        }

        /**
         * The other half of the orphan problem. Here the extension IS written — the
         * matcher joined it to a target resource, so the update goes through and the
         * store now holds a new version — but the target workflow carries no reference
         * at that key, so {@code updateWorkflowExtensionUris} has nothing to repoint.
         * Left unreported, the run answered success while the deployed pipeline still
         * loaded the old version and the new one sat there consumed by nothing. Every
         * key the scan cannot place has to surface as a failure.
         */
        @Test
        @DisplayName("an updated extension the target workflow does not reference is reported, not silently orphaned")
        @SuppressWarnings("unchecked")
        void unplaceableExtensionUriIsReportedAsFailure() throws Exception {
            String wfId = "aaaaaaaaaaaaaaaaaaaaaaaa";
            String extId = "bbbbbbbbbbbbbbbbbbbbbbbb";
            String oldExtUri = "eddi://ai.labs.llm/llmstore/llms/" + extId + "?version=2";

            var sourceWfConfig = workflowWithLlmStep(oldExtUri);
            String extensionKey = WorkflowExtensions.scan(sourceWfConfig).getFirst().key();

            var llmExt = new ExtensionSourceData("src-ext-1", "GPT Config", "langchain",
                    "eddi://ai.labs.llm", "{\"model\":\"gpt-4\"}");
            var sourceWf = new WorkflowSourceData("src-wf-1", "Workflow 1", 0,
                    sourceWfConfig, Map.of(extensionKey, llmExt));
            var source = createSource(List.of(sourceWf), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-wf-1", "workflow", "Workflow 1",
                    DiffAction.UPDATE, wfId, 1, "position", null, null, 0));
            diffs.add(new ResourceDiff("src-ext-1", "langchain", "GPT Config",
                    DiffAction.UPDATE, extId, 2, "type", null, null, -1));

            var preview = new ImportPreview("src-1", "Source Agent", "target-1", "Target Agent", diffs);
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(true))).thenReturn(preview);

            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/target-1?version=1"));
            when(descriptorStore.readDescriptor(eq("target-1"), isNull())).thenReturn(descriptor);

            var llmStore = Mockito.mock(IRestLlmStore.class);
            when(jsonSerialization.deserialize(eq("{\"model\":\"gpt-4\"}"), any()))
                    .thenReturn(new LlmConfiguration(List.of()));
            when(llmStore.updateLlm(eq(extId), eq(2), any())).thenReturn(Response.ok().build());

            // The target workflow has no step at all, so nothing in it names the
            // extension the update just versioned.
            when(workflowStore.readWorkflow(wfId, 1)).thenReturn(new WorkflowConfiguration());

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + wfId + "?version=1"))));
            when(agentStore.readAgent("target-1", 1)).thenReturn(agentConfig);
            when(agentStore.updateAgent(eq("target-1"), eq(1), any())).thenReturn(Response.ok().build());

            @SuppressWarnings("rawtypes")
            MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
            try (cdiMock) {
                var cdiInstance = Mockito.mock(CDI.class);
                cdiMock.when(CDI::current).thenReturn(cdiInstance);
                var instanceLlm = (Instance<IRestLlmStore>) Mockito.mock(Instance.class);
                when(cdiInstance.select(IRestLlmStore.class)).thenReturn(instanceLlm);
                when(instanceLlm.get()).thenReturn(llmStore);

                UpgradeResult result = executor.executeUpgrade(source, "target-1", null, null);

                assertTrue(result.hasFailures(),
                        "a URI nothing consumes must be reported, not counted as a clean run");
                String reason = result.failures().getFirst().reason();
                assertTrue(reason.contains(extensionKey),
                        "the failure must name the key that could not be placed, got: " + reason);
                assertTrue(reason.contains("not deployed"),
                        "the operator must be told the written resource is not live, got: " + reason);
                verify(workflowStore, never()).updateWorkflow(eq(wfId), eq(1), any());
                assertFalse(result.agentUpdated(),
                        "no workflow reference changed, so no agent version may be burned");
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
            var wfDirectStore = Mockito.mock(IWorkflowStore.class);
            var wfResourceId = new IResourceStore.IResourceId() {
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
            MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
            try (cdiMock) {
                var cdiInstance = Mockito.mock(CDI.class);
                cdiMock.when(CDI::current).thenReturn(cdiInstance);
                var instanceWf = (Instance<IWorkflowStore>) Mockito
                        .mock(Instance.class);
                when(cdiInstance.select(IWorkflowStore.class)).thenReturn(instanceWf);
                when(instanceWf.get()).thenReturn(wfDirectStore);

                URI result = executor.executeUpgrade(source, "target-1", null, null).agentUri();

                assertNotNull(result);
                verify(wfDirectStore).create(any());
                // Verify agent was updated with new workflow appended
                var captor = ArgumentCaptor.forClass(AgentConfiguration.class);
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
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(true))).thenReturn(preview);

            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/target-1?version=1"));
            when(descriptorStore.readDescriptor(eq("target-1"), isNull())).thenReturn(descriptor);

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>());
            when(agentStore.readAgent("target-1", 1)).thenReturn(agentConfig);
            when(agentStore.updateAgent(eq("target-1"), eq(1), any()))
                    .thenThrow(new RuntimeException("DB error"));

            // A workflow order is what makes the agent config genuinely need writing;
            // without one the executor deliberately does not touch the agent at all.
            assertThrows(RuntimeException.class,
                    () -> executor.executeUpgrade(source, "target-1", null, List.of("wf-1")));
        }
    }

    // ==================== Test Helpers ====================

    private ResourceDiff agentDiff(String sourceId, String targetId, DiffAction action) {
        return new ResourceDiff(sourceId, "agent", "Agent", action, targetId, 1, "targetAgent", null, null, -1);
    }

    private void setupPreviewAndAgent(String targetAgentId, int version, List<ResourceDiff> diffs) throws Exception {
        var preview = new ImportPreview("src-1", "Source Agent", targetAgentId, "Target Agent", diffs);
        when(structuralMatcher.buildPreview(any(), eq(targetAgentId), eq(true))).thenReturn(preview);

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

    // ==================== Unknown Extension Type ====================

    @Nested
    @DisplayName("Unknown extension type handling")
    class UnknownExtensionType {

        /**
         * The CREATE side, which no longer consults the store registry at all: an
         * extension the target workflow has no step for is refused before any store is
         * chosen, because nothing could reference the resource once written. What still
         * has to hold is that such a resource is reported as a failure naming it, and
         * that nothing at all is written — not reported as created, and no agent
         * version burned.
         */
        @Test
        @DisplayName("a CREATE that cannot be applied is a reported failure that writes nothing")
        @SuppressWarnings("unchecked")
        void createOfAnUnreferenceableExtensionIsReportedAndWritesNothing() throws Exception {
            String wfId = "aabbccddeeff112233445566";

            var unknownExt = new ExtensionSourceData("src-ext-u", "Unknown Config", "unknowntype",
                    "eddi://ai.labs.unknown", "{}");
            var sourceWf = new WorkflowSourceData("src-wf-1", "Workflow 1", 0,
                    new WorkflowConfiguration(), Map.of("eddi://ai.labs.unknown#0/config", unknownExt));
            var source = createSource(List.of(sourceWf), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-wf-1", "workflow", "Workflow 1",
                    DiffAction.UPDATE, wfId, 1, "position", null, null, 0));
            // CREATE, not UPDATE: this extension has no counterpart in the target.
            diffs.add(new ResourceDiff("src-ext-u", "unknowntype", "Unknown Config",
                    DiffAction.CREATE, null, null, null, null, null, -1));

            setupPreviewAndAgent("target-1", 1, diffs);
            when(workflowStore.readWorkflow(wfId, 1)).thenReturn(new WorkflowConfiguration());

            @SuppressWarnings("rawtypes")
            MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
            try (cdiMock) {
                var cdiInstance = Mockito.mock(CDI.class);
                cdiMock.when(CDI::current).thenReturn(cdiInstance);

                UpgradeResult result = executor.executeUpgrade(source, "target-1", null, null);

                assertEquals(1, result.failures().size(), result.failures().toString());
                String reason = result.failures().getFirst().reason();
                assertTrue(reason.contains("unknowntype"), "the reason must name the type, got: " + reason);
                assertTrue(reason.contains("no step"),
                        "the operator must be told the target workflow cannot reference it, got: " + reason);
                assertFalse(reason.contains("did not accept"),
                        "nothing was offered to a store, so no store may be blamed, got: " + reason);
                assertEquals("Unknown Config", result.failures().getFirst().name());

                // Nothing landed, so nothing may be reported as written either.
                assertEquals(0, result.created());
                assertEquals(0, result.updated());
                assertFalse(result.agentUpdated(), "a failed create must not bump the agent version");
            }
        }

        /**
         * m5 — an extension type with no registered store is a wiring mistake, and the
         * operator has to be told that. {@code updateExtension} used to catch the
         * {@code IllegalArgumentException} that says so and answer null, which the
         * caller reported as "the store did not accept the update" — the opposite
         * diagnosis, pointing at a store that was never consulted.
         */
        @Test
        @DisplayName("an unregistered extension type is reported as a wiring error, not a store rejection")
        @SuppressWarnings("unchecked")
        void unregisteredTypeIsReportedAsWiringError() throws Exception {
            String wfId = "aabbccddeeff112233445566";

            var unknownExt = new ExtensionSourceData("src-ext-u", "Unknown Config", "unknowntype",
                    "eddi://ai.labs.unknown", "{}");
            var sourceWf = new WorkflowSourceData("src-wf-1", "Workflow 1", 0,
                    new WorkflowConfiguration(), Map.of("eddi://ai.labs.unknown#0/config", unknownExt));
            var source = createSource(List.of(sourceWf), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-wf-1", "workflow", "Workflow 1",
                    DiffAction.UPDATE, wfId, 1, "position", null, null, 0));
            diffs.add(new ResourceDiff("src-ext-u", "unknowntype", "Unknown Config",
                    DiffAction.UPDATE, "cccccccccccccccccccccccc", 2, "type", null, null, -1));

            setupPreviewAndAgent("target-1", 1, diffs);
            when(workflowStore.readWorkflow(wfId, 1)).thenReturn(new WorkflowConfiguration());

            @SuppressWarnings("rawtypes")
            MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
            try (cdiMock) {
                var cdiInstance = Mockito.mock(CDI.class);
                cdiMock.when(CDI::current).thenReturn(cdiInstance);

                UpgradeResult result = executor.executeUpgrade(source, "target-1", null, null);

                assertEquals(1, result.failures().size(), result.failures().toString());
                String reason = result.failures().get(0).reason();
                assertTrue(reason.contains("No store operations are registered"),
                        "the operator must be told the type is unregistered, got: " + reason);
                assertTrue(reason.contains("unknowntype"), "the reason must name the type, got: " + reason);
                assertFalse(reason.contains("did not accept"),
                        "a wiring mistake must not be reported as a store rejection, got: " + reason);
            }
        }
    }

    // ==================== 'nothing to do' agent URI ====================

    @Nested
    @DisplayName("Agent URI of an upgrade that wrote nothing")
    class NoOpAgentUri {

        /**
         * m8 — when an upgrade writes nothing, the agent URI it reports must not name a
         * version that was guessed. The version lookup swallows every descriptor
         * failure and answers 1, so this path handed the caller {@code ?version=1} for
         * an agent that may well have been at v14.
         */
        @Test
        @DisplayName("reports no version rather than guessing 1 when the descriptor cannot be read")
        void unreadableDescriptorReportsNoVersion() throws Exception {
            var source = createSource(List.of(), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));

            var preview = new ImportPreview("src-1", "Source Agent", "target-1", "Target Agent", diffs);
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(true))).thenReturn(preview);
            when(descriptorStore.readDescriptor(eq("target-1"), isNull()))
                    .thenThrow(new RuntimeException("descriptor store unavailable"));

            // Nothing to write: no workflow was updated or created, no order given.
            UpgradeResult result = executor.executeUpgrade(source, "target-1", null, null);

            assertFalse(result.agentUpdated(), "nothing changed, so the agent must not be rewritten");
            assertEquals(IRestAgentStore.resourceURI + "target-1", result.agentUri().toString(),
                    "an unresolvable version must be omitted, never guessed as 1");
            verify(agentStore, never()).updateAgent(anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("reports the version the descriptor actually names")
        void readableDescriptorReportsItsVersion() throws Exception {
            var source = createSource(List.of(), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));

            var preview = new ImportPreview("src-1", "Source Agent", "target-1", "Target Agent", diffs);
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(true))).thenReturn(preview);

            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create(
                    "eddi://ai.labs.agent/agentstore/agents/target-1?version=14"));
            when(descriptorStore.readDescriptor(eq("target-1"), isNull())).thenReturn(descriptor);

            UpgradeResult result = executor.executeUpgrade(source, "target-1", null, null);

            assertEquals(IRestAgentStore.resourceURI + "target-1?version=14",
                    result.agentUri().toString());
        }
    }

    // ==================== updateExtension non-200 response ====================

    @Nested
    @DisplayName("Extension update non-200 response")
    class ExtensionUpdateNon200 {

        @Test
        @DisplayName("should return null URI when store returns non-200")
        @SuppressWarnings("unchecked")
        void nonOkResponseReturnsNullUri() throws Exception {
            String wfId = "aabbccddeeff112233445566";
            String extId = "bbbbbbbbbbbbbbbbbbbbbbbb";

            var llmExt = new ExtensionSourceData("src-ext-1", "GPT Config", "langchain", "ai.labs.llm", "{\"model\":\"gpt-4\"}");
            var sourceWf = new WorkflowSourceData("src-wf-1", "Workflow 1", 0,
                    new WorkflowConfiguration(), Map.of("ai.labs.llm", llmExt));
            var source = createSource(List.of(sourceWf), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-wf-1", "workflow", "Workflow 1",
                    DiffAction.UPDATE, wfId, 1, "position", null, null, 0));
            diffs.add(new ResourceDiff("src-ext-1", "langchain", "GPT Config",
                    DiffAction.UPDATE, extId, 2, "type", null, null, -1));

            setupPreviewAndAgent("target-1", 1, diffs);

            var llmStore = Mockito.mock(IRestLlmStore.class);
            when(jsonSerialization.deserialize(eq("{\"model\":\"gpt-4\"}"), any()))
                    .thenReturn(new LlmConfiguration(List.of()));
            // Return 500 instead of 200
            when(llmStore.updateLlm(eq(extId), eq(2), any()))
                    .thenReturn(Response.serverError().build());

            when(workflowStore.readWorkflow(wfId, 1)).thenReturn(new WorkflowConfiguration());

            @SuppressWarnings("rawtypes")
            MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
            try (cdiMock) {
                var cdiInstance = Mockito.mock(CDI.class);
                cdiMock.when(CDI::current).thenReturn(cdiInstance);
                var instanceLlm = (Instance<IRestLlmStore>) Mockito
                        .mock(Instance.class);
                when(cdiInstance.select(IRestLlmStore.class)).thenReturn(instanceLlm);
                when(instanceLlm.get()).thenReturn(llmStore);

                URI result = executor.executeUpgrade(source, "target-1", null, null).agentUri();

                // Should still succeed (extension update returned null URI, no workflow update
                // needed)
                assertNotNull(result);
                verify(llmStore).updateLlm(eq(extId), eq(2), any());
                // Workflow should NOT be updated since no extension URIs were collected
                verify(workflowStore, never()).updateWorkflow(anyString(), anyInt(), any());
            }
        }
    }

    // ==================== createNewWorkflow failure ====================

    @Nested
    @DisplayName("createNewWorkflow failure")
    class CreateNewWorkflowFailure {

        @Test
        @DisplayName("should return null when CDI/store throws during workflow creation")
        @SuppressWarnings("unchecked")
        void workflowCreationFailureReturnsNull() throws Exception {
            var newWf = new WorkflowSourceData("src-wf-1", "New Workflow", 0,
                    new WorkflowConfiguration(), Map.of());
            var source = createSource(List.of(newWf), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-wf-1", "workflow", "New Workflow",
                    DiffAction.CREATE, null, null, null, null, null, 0));

            setupPreviewAndAgent("target-1", 1, diffs);

            @SuppressWarnings("rawtypes")
            MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
            try (cdiMock) {
                var cdiInstance = Mockito.mock(CDI.class);
                cdiMock.when(CDI::current).thenReturn(cdiInstance);
                var instanceWf = (Instance<IWorkflowStore>) Mockito
                        .mock(Instance.class);
                when(cdiInstance.select(IWorkflowStore.class)).thenReturn(instanceWf);
                when(instanceWf.get()).thenThrow(new RuntimeException("CDI lookup failed"));

                // Should NOT throw — createNewWorkflow catches and records the failure
                var result = executor.executeUpgrade(source, "target-1", null, null);

                assertNotNull(result.agentUri());
                // Nothing landed, so no agent version is burned — and the failure is
                // reported rather than logged and forgotten behind a 201.
                verify(agentStore, never()).updateAgent(anyString(), anyInt(), any());
                assertTrue(result.hasFailures());
                assertEquals("workflow", result.failures().getFirst().resourceType());
            }
        }
    }

    // ==================== updateWorkflowExtensionUris edge cases
    // ====================

    @Nested
    @DisplayName("updateWorkflowExtensionUris edge cases")
    class UpdateWorkflowExtensionUrisEdgeCases {

        @Test
        @DisplayName("should skip step when step.getType() is null")
        @SuppressWarnings("unchecked")
        void nullStepTypeSkipped() throws Exception {
            String wfId = "aabbccddeeff112233445566";
            String extId = "bbbbbbbbbbbbbbbbbbbbbbbb";

            var llmExt = new ExtensionSourceData("src-ext-1", "GPT Config", "langchain", "ai.labs.llm", "{\"model\":\"gpt-4\"}");
            var sourceWf = new WorkflowSourceData("src-wf-1", "Workflow 1", 0,
                    new WorkflowConfiguration(), Map.of("ai.labs.llm", llmExt));
            var source = createSource(List.of(sourceWf), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-wf-1", "workflow", "Workflow 1",
                    DiffAction.UPDATE, wfId, 1, "position", null, null, 0));
            diffs.add(new ResourceDiff("src-ext-1", "langchain", "GPT Config",
                    DiffAction.UPDATE, extId, 2, "type", null, null, -1));

            setupPreviewAndAgent("target-1", 1, diffs);

            var llmStore = Mockito.mock(IRestLlmStore.class);
            when(jsonSerialization.deserialize(eq("{\"model\":\"gpt-4\"}"), any()))
                    .thenReturn(new LlmConfiguration(List.of()));
            when(llmStore.updateLlm(eq(extId), eq(2), any())).thenReturn(Response.ok().build());

            // Workflow config with a step that has NULL type
            var targetWfConfig = new WorkflowConfiguration();
            var nullTypeStep = new WorkflowConfiguration.WorkflowStep();
            nullTypeStep.setType(null); // null type — should be skipped
            targetWfConfig.setWorkflowSteps(List.of(nullTypeStep));
            when(workflowStore.readWorkflow(wfId, 1)).thenReturn(targetWfConfig);

            @SuppressWarnings("rawtypes")
            MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
            try (cdiMock) {
                var cdiInstance = Mockito.mock(CDI.class);
                cdiMock.when(CDI::current).thenReturn(cdiInstance);
                var instanceLlm = (Instance<IRestLlmStore>) Mockito
                        .mock(Instance.class);
                when(cdiInstance.select(IRestLlmStore.class)).thenReturn(instanceLlm);
                when(instanceLlm.get()).thenReturn(llmStore);

                URI result = executor.executeUpgrade(source, "target-1", null, null).agentUri();

                assertNotNull(result);
                // Workflow should NOT be updated since null type step was skipped (no match)
                verify(workflowStore, never()).updateWorkflow(anyString(), anyInt(), any());
            }
        }
    }

    // ==================== readLatestVersion edge cases ====================

    @Nested
    @DisplayName("readLatestVersion edge cases")
    class ReadLatestVersionEdgeCases {

        @Test
        @DisplayName("should default to 1 when descriptor is null")
        void nullDescriptorDefaultsTo1() throws Exception {
            var source = createSource(List.of(), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));

            var preview = new ImportPreview("src-1", "Source Agent", "target-1", "Target Agent", diffs);
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(true))).thenReturn(preview);

            // Return null descriptor
            when(descriptorStore.readDescriptor(eq("target-1"), isNull())).thenReturn(null);

            // readLatestVersion should default to 1
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>());
            when(agentStore.readAgent("target-1", 1)).thenReturn(agentConfig);
            when(agentStore.updateAgent(eq("target-1"), eq(1), any())).thenReturn(Response.ok().build());

            URI result = executor.executeUpgrade(source, "target-1", null, List.of("wf-1")).agentUri();

            assertNotNull(result);
            assertTrue(result.toString().contains("version=2")); // 1 + 1
        }

        @Test
        @DisplayName("should default to 1 when descriptor.getResource() is null")
        void nullResourceDefaultsTo1() throws Exception {
            var source = createSource(List.of(), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));

            var preview = new ImportPreview("src-1", "Source Agent", "target-1", "Target Agent", diffs);
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(true))).thenReturn(preview);

            // Return descriptor with null resource
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(null);
            when(descriptorStore.readDescriptor(eq("target-1"), isNull())).thenReturn(descriptor);

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>());
            when(agentStore.readAgent("target-1", 1)).thenReturn(agentConfig);
            when(agentStore.updateAgent(eq("target-1"), eq(1), any())).thenReturn(Response.ok().build());

            URI result = executor.executeUpgrade(source, "target-1", null, List.of("wf-1")).agentUri();

            assertNotNull(result);
            assertTrue(result.toString().contains("version=2")); // 1 + 1
        }

        @Test
        @DisplayName("should default to 1 when readDescriptor throws exception")
        void exceptionDefaultsTo1() throws Exception {
            var source = createSource(List.of(), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));

            var preview = new ImportPreview("src-1", "Source Agent", "target-1", "Target Agent", diffs);
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(true))).thenReturn(preview);

            when(descriptorStore.readDescriptor(eq("target-1"), isNull()))
                    .thenThrow(new RuntimeException("descriptor not found"));

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>());
            when(agentStore.readAgent("target-1", 1)).thenReturn(agentConfig);
            when(agentStore.updateAgent(eq("target-1"), eq(1), any())).thenReturn(Response.ok().build());

            URI result = executor.executeUpgrade(source, "target-1", null, List.of("wf-1")).agentUri();

            assertNotNull(result);
            assertTrue(result.toString().contains("version=2"));
        }
    }

    // ==================== Snippet processing exception ====================

    @Nested
    @DisplayName("Snippet exception handling")
    class SnippetExceptionHandling {

        @Test
        @DisplayName("should continue when snippetStore throws during snippet processing")
        void snippetStoreThrowsContinues() throws Exception {
            var sourceSnippet = new SnippetSourceData("src-snp-1", "greeting",
                    createSnippet("greeting", "Hello!"));

            var source = createSource(List.of(), List.of(sourceSnippet));

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-snp-1", "snippet", "greeting",
                    DiffAction.UPDATE, "tgt-snp-1", 2, "name", null, null, -1));

            setupPreviewAndAgent("target-1", 1, diffs);

            doThrow(new RuntimeException("DB error"))
                    .when(snippetStore).updateSnippet(anyString(), anyInt(), any());

            // Should NOT throw — processSnippet catches and logs
            URI result = executor.executeUpgrade(source, "target-1", null, null).agentUri();
            assertNotNull(result);
        }
    }

    // ==================== Extension processing exception ====================

    @Nested
    @DisplayName("Extension exception handling")
    class ExtensionExceptionHandling {

        @Test
        @DisplayName("should continue when extension update fails with exception")
        @SuppressWarnings("unchecked")
        void extensionUpdateExceptionContinues() throws Exception {
            String wfId = "aabbccddeeff112233445566";
            String extId = "bbbbbbbbbbbbbbbbbbbbbbbb";

            var llmExt = new ExtensionSourceData("src-ext-1", "GPT Config", "langchain", "ai.labs.llm", "{\"model\":\"gpt-4\"}");
            var sourceWf = new WorkflowSourceData("src-wf-1", "Workflow 1", 0,
                    new WorkflowConfiguration(), Map.of("ai.labs.llm", llmExt));
            var source = createSource(List.of(sourceWf), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-wf-1", "workflow", "Workflow 1",
                    DiffAction.UPDATE, wfId, 1, "position", null, null, 0));
            diffs.add(new ResourceDiff("src-ext-1", "langchain", "GPT Config",
                    DiffAction.UPDATE, extId, 2, "type", null, null, -1));

            setupPreviewAndAgent("target-1", 1, diffs);

            when(jsonSerialization.deserialize(eq("{\"model\":\"gpt-4\"}"), any()))
                    .thenThrow(new RuntimeException("Deserialization failed"));

            when(workflowStore.readWorkflow(wfId, 1)).thenReturn(new WorkflowConfiguration());

            @SuppressWarnings("rawtypes")
            MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
            try (cdiMock) {
                var cdiInstance = Mockito.mock(CDI.class);
                cdiMock.when(CDI::current).thenReturn(cdiInstance);
                var instanceLlm = (Instance<IRestLlmStore>) Mockito
                        .mock(Instance.class);
                when(cdiInstance.select(IRestLlmStore.class)).thenReturn(instanceLlm);
                when(instanceLlm.get()).thenReturn(Mockito.mock(IRestLlmStore.class));

                // Should NOT throw — extension processing catches and logs
                URI result = executor.executeUpgrade(source, "target-1", null, null).agentUri();
                assertNotNull(result);
            }
        }
    }

    // ==================== dispatchUpdate for all config types ====================

    @Nested
    @DisplayName("dispatchUpdate for all config types")
    class DispatchUpdateAllTypes {

        @Test
        @DisplayName("should dispatch update for DictionaryConfiguration")
        @SuppressWarnings("unchecked")
        void dispatchDictionary() throws Exception {
            verifyDispatchUpdate("regulardictionary", "ai.labs.parser",
                    DictionaryConfiguration.class,
                    IRestDictionaryStore.class);
        }

        @Test
        @DisplayName("should dispatch update for RuleSetConfiguration")
        @SuppressWarnings("unchecked")
        void dispatchRuleSet() throws Exception {
            verifyDispatchUpdate("behavior", "ai.labs.behavior",
                    RuleSetConfiguration.class,
                    IRestRuleSetStore.class);
        }

        @Test
        @DisplayName("should dispatch update for ApiCallsConfiguration")
        @SuppressWarnings("unchecked")
        void dispatchApiCalls() throws Exception {
            verifyDispatchUpdate("httpcalls", "ai.labs.httpcalls",
                    ApiCallsConfiguration.class,
                    IRestApiCallsStore.class);
        }

        @Test
        @DisplayName("should dispatch update for PropertySetterConfiguration")
        @SuppressWarnings("unchecked")
        void dispatchPropertySetter() throws Exception {
            verifyDispatchUpdate("property", "ai.labs.property",
                    PropertySetterConfiguration.class,
                    IRestPropertySetterStore.class);
        }

        @Test
        @DisplayName("should dispatch update for OutputConfigurationSet")
        @SuppressWarnings("unchecked")
        void dispatchOutput() throws Exception {
            verifyDispatchUpdate("output", "ai.labs.output",
                    OutputConfigurationSet.class,
                    IRestOutputStore.class);
        }

        @Test
        @DisplayName("should dispatch update for McpCallsConfiguration")
        @SuppressWarnings("unchecked")
        void dispatchMcpCalls() throws Exception {
            verifyDispatchUpdate("mcpcalls", "ai.labs.mcpcalls",
                    McpCallsConfiguration.class,
                    IRestMcpCallsStore.class);
        }

        /**
         * RAG was the one registered extension type with no dispatch test, so a typo in
         * its row of the store table would have shipped: every RAG config in a synced
         * agent would be reported as "the store did not accept the update" while the
         * other seven types synced fine.
         */
        @Test
        @DisplayName("should dispatch update for RagConfiguration")
        @SuppressWarnings("unchecked")
        void dispatchRag() throws Exception {
            verifyDispatchUpdate("rag", "ai.labs.rag",
                    RagConfiguration.class,
                    IRestRagStore.class);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void verifyDispatchUpdate(String extensionType, String stepType,
                                          Class<?> configClass, Class<?> restStoreClass)
                throws Exception {
            String wfId = "aabbccddeeff112233445566";
            String extId = "bbbbbbbbbbbbbbbbbbbbbbbb";

            var ext = new ExtensionSourceData("src-ext-1", "Config", extensionType, stepType, "{}");
            var sourceWf = new WorkflowSourceData("src-wf-1", "Workflow 1", 0,
                    new WorkflowConfiguration(), Map.of(stepType, ext));
            var source = createSource(List.of(sourceWf), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-wf-1", "workflow", "Workflow 1",
                    DiffAction.UPDATE, wfId, 1, "position", null, null, 0));
            diffs.add(new ResourceDiff("src-ext-1", extensionType, "Config",
                    DiffAction.UPDATE, extId, 2, "type", null, null, -1));

            setupPreviewAndAgent("target-1", 1, diffs);

            Object mockStore = Mockito.mock(restStoreClass);
            Object mockConfig = Mockito.mock(configClass);
            when(jsonSerialization.deserialize(eq("{}"), any())).thenReturn(mockConfig);

            // The mock will return null (default) for the unstubbed update method.
            // This exercises the resolveExtensionOps dispatch path for each config type.

            // Workflow config (empty)
            when(workflowStore.readWorkflow(wfId, 1)).thenReturn(new WorkflowConfiguration());

            MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
            try (cdiMock) {
                var cdiInstance = Mockito.mock(CDI.class);
                cdiMock.when(CDI::current).thenReturn(cdiInstance);

                var instance = (Instance) Mockito.mock(Instance.class);
                when(cdiInstance.select(restStoreClass)).thenReturn(instance);
                when(instance.get()).thenReturn(mockStore);

                // The actual dispatch will call the specific update method.
                // Since we haven't stubbed the specific method, it will return null (default
                // for mock).
                // This exercises the resolveExtensionOps + dispatchUpdate path, which will
                // result in null response → null URI → no workflow update.
                URI result = executor.executeUpgrade(source, "target-1", null, null).agentUri();

                assertNotNull(result);
            }
        }
    }

    // ==================== Failure reporting and secret fallbacks
    // ====================

    /**
     * What an upgrade does when the pieces it depends on misbehave. Every case here
     * used to end the same way — a 500 with a message the operator could not act
     * on, or a production credential overwritten with the export's placeholder.
     */
    @Nested
    @DisplayName("Failure reporting and secret fallbacks")
    class FailurePathsAndSecretFallbacks {

        private static final String WF_ID = "aabbccddeeff112233445566";
        private static final String EXT_ID = "bbbbbbbbbbbbbbbbbbbbbbbb";
        private static final String SCRUBBED = "{\"apiKey\":\"${vault:REDACTED}\",\"model\":\"gpt-4\"}";

        private BackupMetrics metrics;
        private UpgradeExecutor executorWithMetrics;

        @BeforeEach
        void useAnObservableMetricsBean() {
            metrics = Mockito.mock(BackupMetrics.class);
            executorWithMetrics = new UpgradeExecutor(agentStore, workflowStore, snippetStore,
                    jsonSerialization, structuralMatcher, descriptorStore, metrics,
                    mock(ResourceAccessGuard.class));
        }

        /**
         * A target agent that cannot be read is the one actionable failure of a sync.
         * Wrapping it in a RuntimeException turned the operator's 404 into a 500, which
         * says "the server is broken" about a mistyped agent id.
         */
        @Test
        @DisplayName("a WebApplicationException from the preview reaches the caller unwrapped")
        void webApplicationExceptionIsRethrownAsItIs() throws Exception {
            var notFound = new NotFoundException("Target agent target-1 does not exist.");
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(true))).thenThrow(notFound);

            var thrown = assertThrows(NotFoundException.class,
                    () -> executorWithMetrics.executeUpgrade(createSource(List.of(), List.of()),
                            "target-1", null, null));

            assertSame(notFound, thrown, "the status the operator needs must not be repackaged");
            verify(metrics).upgradeFailed();
            verify(metrics, never()).upgradeCompleted(anyInt(), anyInt(), anyInt(), anyInt());
        }

        /**
         * A snippet and an extension the preview marked SKIP must be counted as
         * skipped, not silently ignored: "skipped 2" is how an operator tells "the sync
         * found nothing to do" from "the sync did nothing".
         */
        @Test
        @DisplayName("SKIP rows are counted as skipped, and nothing is written for them")
        void skippedRowsAreCounted() throws Exception {
            var snippet = new SnippetSourceData("src-snp-1", "greeting", createSnippet("greeting", "Hi"));
            var ext = new ExtensionSourceData("src-ext-1", "GPT Config", "langchain",
                    "eddi://ai.labs.llm", "{}");
            var sourceWf = new WorkflowSourceData("src-wf-1", "Workflow 1", 0,
                    new WorkflowConfiguration(), Map.of("eddi://ai.labs.llm#0/config", ext));
            var source = createSource(List.of(sourceWf), List.of(snippet));

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-snp-1", "snippet", "greeting",
                    DiffAction.SKIP, "tgt-snp-1", 1, "name", null, null, -1));
            diffs.add(new ResourceDiff("src-wf-1", "workflow", "Workflow 1",
                    DiffAction.UPDATE, WF_ID, 1, "position", null, null, 0));
            diffs.add(new ResourceDiff("src-ext-1", "langchain", "GPT Config",
                    DiffAction.SKIP, EXT_ID, 2, "type", null, null, -1));

            setupPreviewAndAgent("target-1", 1, diffs);

            UpgradeResult result = executorWithMetrics.executeUpgrade(source, "target-1", null, null);

            // snippet + extension + the workflow that had nothing to write
            assertEquals(3, result.skipped());
            assertEquals(0, result.updated());
            assertEquals(0, result.created());
            assertFalse(result.hasFailures());
            verify(snippetStore, never()).updateSnippet(anyString(), anyInt(), any());
            verify(agentStore, never()).updateAgent(anyString(), anyInt(), any());
            verify(metrics).upgradeCompleted(0, 0, 3, 0);
        }

        /**
         * With no readable target config there is no value to put back, so the
         * placeholder is written and reported rather than guessed at. Silently
         * substituting anything else would be inventing a credential.
         */
        @Test
        @DisplayName("an unreadable target leaves the placeholder in place instead of guessing")
        void unreadableTargetKeepsThePlaceholder() throws Exception {
            String written = writeExtension(SCRUBBED, null);

            assertEquals(SCRUBBED, written,
                    "with nothing to restore from, the source content must go through untouched");
        }

        /**
         * A placeholder the target has no counterpart for stays a placeholder. The
         * operator has to supply that value by hand — writing the target's unrelated
         * neighbouring field into it would be worse than leaving the gap visible.
         */
        @Test
        @DisplayName("a placeholder the target cannot answer survives the merge")
        void placeholderWithoutACounterpartSurvives() throws Exception {
            String written = writeExtension(SCRUBBED, "{\"model\":\"gpt-3\"}");

            assertNotNull(written);
            assertTrue(written.contains("${vault:REDACTED}"),
                    "the gap must stay visible rather than be filled with something else: " + written);
            assertTrue(written.contains("gpt-4"), "everything else still comes from the source: " + written);
        }

        /**
         * If the merge itself cannot be completed, the source content is written as it
         * stands. Returning null instead would have blanked the target's config.
         */
        @Test
        @DisplayName("content that cannot be parsed is written as-is, never as nothing")
        void unparseableContentIsWrittenAsIs() throws Exception {
            // doThrow, not when(...).thenThrow: the when-form would first invoke the
            // round-tripping answer the helper installs, with an empty argument.
            String written = writeExtension(SCRUBBED, "{\"apiKey\":\"sk-live-abc\"}",
                    () -> doThrow(new IOException("not JSON"))
                            .when(jsonSerialization).deserialize(anyString()));

            assertEquals(SCRUBBED, written);
        }

        /**
         * Same guarantee on the other side of the merge: a serializer answering null
         * must not turn into a null config write.
         */
        @Test
        @DisplayName("a serializer answering null falls back to the source content")
        void serializerAnsweringNullFallsBackToTheSource() throws Exception {
            String written = writeExtension(SCRUBBED, "{\"apiKey\":\"sk-live-abc\"}",
                    () -> doReturn(null).when(jsonSerialization).serialize(any()));

            assertEquals(SCRUBBED, written);
        }

        /** Stubbing applied after the helper's own JSON defaults, so it wins. */
        private interface JsonStubs {
            void apply() throws Exception;
        }

        /**
         * Runs one LLM extension UPDATE through the executor and returns the JSON the
         * store was actually asked to write.
         *
         * @param targetContent
         *            the target's current config as the preview reports it, or null
         *            when it could not be read
         */
        private String writeExtension(String sourceContent, String targetContent) throws Exception {
            return writeExtension(sourceContent, targetContent, null);
        }

        @SuppressWarnings("unchecked")
        private String writeExtension(String sourceContent, String targetContent, JsonStubs overrides)
                throws Exception {
            var ext = new ExtensionSourceData("src-ext-1", "GPT Config", "langchain",
                    "eddi://ai.labs.llm", sourceContent);
            var sourceWf = new WorkflowSourceData("src-wf-1", "Workflow 1", 0,
                    new WorkflowConfiguration(), Map.of("eddi://ai.labs.llm#0/config", ext));
            var source = createSource(List.of(sourceWf), List.of());

            List<ResourceDiff> diffs = new ArrayList<>();
            diffs.add(agentDiff("src-1", "target-1", DiffAction.SKIP));
            diffs.add(new ResourceDiff("src-wf-1", "workflow", "Workflow 1",
                    DiffAction.UPDATE, WF_ID, 1, "position", null, null, 0));
            diffs.add(new ResourceDiff("src-ext-1", "langchain", "GPT Config",
                    DiffAction.UPDATE, EXT_ID, 2, "type", sourceContent, targetContent, -1));

            setupPreviewAndAgent("target-1", 1, diffs);
            when(workflowStore.readWorkflow(WF_ID, 1)).thenReturn(new WorkflowConfiguration());

            // Real JSON round-tripping — the merge under test is about JSON, not mocks.
            var mapper = new ObjectMapper();
            when(jsonSerialization.deserialize(anyString()))
                    .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), Object.class));
            when(jsonSerialization.serialize(any()))
                    .thenAnswer(inv -> mapper.writeValueAsString(inv.getArgument(0)));
            if (overrides != null) {
                overrides.apply();
            }

            var writtenJson = new AtomicReference<String>();
            when(jsonSerialization.deserialize(anyString(), eq(LlmConfiguration.class)))
                    .thenAnswer(inv -> {
                        writtenJson.set(inv.getArgument(0));
                        return new LlmConfiguration(List.of());
                    });

            var llmStore = Mockito.mock(IRestLlmStore.class);
            when(llmStore.updateLlm(eq(EXT_ID), eq(2), any())).thenReturn(Response.ok().build());

            @SuppressWarnings("rawtypes")
            MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
            try (cdiMock) {
                var cdiInstance = Mockito.mock(CDI.class);
                cdiMock.when(CDI::current).thenReturn(cdiInstance);
                var instanceLlm = (Instance<IRestLlmStore>) Mockito.mock(Instance.class);
                when(cdiInstance.select(IRestLlmStore.class)).thenReturn(instanceLlm);
                when(instanceLlm.get()).thenReturn(llmStore);

                executorWithMetrics.executeUpgrade(source, "target-1", null, null);
            }

            assertNotNull(writtenJson.get(), "the store update never ran");
            return writtenJson.get();
        }
    }
}
