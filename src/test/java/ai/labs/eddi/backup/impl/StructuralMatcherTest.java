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
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StructuralMatcher} covering all matching strategies
 * (position, type, name) and diff action resolution (CREATE, UPDATE, SKIP).
 */
class StructuralMatcherTest {

    private IRestAgentStore agentStore;
    private IDocumentDescriptorStore descriptorStore;
    private IRestPromptSnippetStore snippetStore;
    private IRestWorkflowStore workflowStore;
    private IRestInterfaceFactory restInterfaceFactory;
    private IJsonSerialization jsonSerialization;
    private StructuralMatcher matcher;

    @BeforeEach
    void setUp() {
        agentStore = Mockito.mock(IRestAgentStore.class);
        descriptorStore = Mockito.mock(IDocumentDescriptorStore.class);
        snippetStore = Mockito.mock(IRestPromptSnippetStore.class);
        workflowStore = Mockito.mock(IRestWorkflowStore.class);
        restInterfaceFactory = Mockito.mock(IRestInterfaceFactory.class);
        jsonSerialization = new FakeJsonSerialization();

        matcher = new StructuralMatcher(agentStore, descriptorStore, snippetStore,
                workflowStore, restInterfaceFactory, jsonSerialization);
    }

    // ==================== No Target (CREATE mode) ====================

    @Nested
    @DisplayName("When no target agent is specified")
    class NoTargetAgent {

        @Test
        @DisplayName("all resources should be CREATE")
        void allResourcesAreCreate() {
            var source = createMinimalSource("src-agent-1", "Test Agent", List.of(), List.of());

            ImportPreview preview = matcher.buildPreview(source, null, false);

            assertNotNull(preview);
            assertEquals("src-agent-1", preview.sourceAgentId());
            assertEquals("Test Agent", preview.sourceAgentName());
            assertNull(preview.targetAgentId());
            assertThat(preview.resources(), hasSize(1)); // agent only
            assertEquals(DiffAction.CREATE, preview.resources().get(0).action());
            assertEquals("agent", preview.resources().get(0).resourceType());
        }

        @Test
        @DisplayName("unmatched workflows should be CREATE with their extensions")
        void unmatchedWorkflowsAreCreate() {
            Map<String, ExtensionSourceData> extensions = Map.of(
                    "ai.labs.llm", new ExtensionSourceData("ext-1", "LLM Config", "langchain", "ai.labs.llm", "{\"model\":\"gpt-4\"}"));
            var wf = new WorkflowSourceData("wf-1", "Workflow 1", 0,
                    new WorkflowConfiguration(), extensions);

            var source = createMinimalSource("src-agent-1", "Agent", List.of(wf), List.of());

            ImportPreview preview = matcher.buildPreview(source, null, false);

            // 1 agent + 1 workflow + 1 extension = 3 diffs
            assertThat(preview.resources(), hasSize(3));
            assertTrue(preview.resources().stream().allMatch(d -> d.action() == DiffAction.CREATE));
        }

        @Test
        @DisplayName("snippets should be CREATE when no target")
        void snippetsAreCreate() {
            var snippet = new SnippetSourceData("snp-1", "cautious_mode",
                    createSnippet("cautious_mode", "Be careful."));

            var source = createMinimalSource("src-agent-1", "Agent", List.of(), List.of(snippet));

            // Empty snippet descriptors list since no existing snippets
            when(snippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt())).thenReturn(List.of());

            ImportPreview preview = matcher.buildPreview(source, null, false);

            // 1 agent + 1 snippet = 2 diffs
            assertThat(preview.resources(), hasSize(2));
            ResourceDiff snippetDiff = preview.resources().stream()
                    .filter(d -> "snippet".equals(d.resourceType())).findFirst().orElseThrow();
            assertEquals(DiffAction.CREATE, snippetDiff.action());
            assertEquals("cautious_mode", snippetDiff.name());
        }
    }

    // ==================== With Target (UPGRADE mode) ====================

    @Nested
    @DisplayName("When a target agent is specified")
    class WithTargetAgent {

        @Test
        @DisplayName("agent-level diff should be UPDATE when content differs")
        void agentDiffIsUpdateWhenContentDiffers() throws Exception {
            var sourceConfig = new AgentConfiguration();
            sourceConfig.setDescription("New description");
            var targetConfig = new AgentConfiguration();
            targetConfig.setDescription("Old description");

            var source = createMinimalSource("src-1", "Source Agent", List.of(), List.of());
            setupTargetAgent("target-1", 3, targetConfig);
            when(snippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt())).thenReturn(List.of());

            ImportPreview preview = matcher.buildPreview(source, "target-1", true);

            assertNotNull(preview);
            assertEquals("target-1", preview.targetAgentId());
            ResourceDiff agentDiff = preview.resources().get(0);
            assertEquals("agent", agentDiff.resourceType());
            // Content is serialized via FakeJsonSerialization and will differ
            assertEquals(DiffAction.UPDATE, agentDiff.action());
            assertEquals("target-1", agentDiff.targetId());
            assertEquals(3, agentDiff.targetVersion());
            assertEquals("targetAgent", agentDiff.matchStrategy());
        }

        @Test
        @DisplayName("agent-level diff should be SKIP when content identical")
        void agentDiffIsSkipWhenIdentical() throws Exception {
            var config = new AgentConfiguration();
            var source = new FakeResourceSource("src-1", "Agent", config, List.of(), List.of());
            setupTargetAgent("target-1", 2, config);
            when(snippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt())).thenReturn(List.of());

            ImportPreview preview = matcher.buildPreview(source, "target-1", true);

            ResourceDiff agentDiff = preview.resources().get(0);
            assertEquals(DiffAction.SKIP, agentDiff.action());
        }

        @Test
        @DisplayName("workflows should be matched by position index")
        void workflowsMatchedByPosition() throws Exception {
            var sourceWf = new WorkflowSourceData("src-wf-1", "Source Workflow", 0,
                    new WorkflowConfiguration(), Map.of());

            var targetConfig = new AgentConfiguration();
            targetConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aabb000011112222cccc?version=2")));

            var source = createMinimalSource("src-1", "Agent", List.of(sourceWf), List.of());
            setupTargetAgent("target-1", 1, targetConfig);
            when(snippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt())).thenReturn(List.of());
            when(workflowStore.readWorkflow("aabb000011112222cccc", 2)).thenReturn(new WorkflowConfiguration());

            ImportPreview preview = matcher.buildPreview(source, "target-1", false);

            ResourceDiff wfDiff = preview.resources().stream()
                    .filter(d -> "workflow".equals(d.resourceType())).findFirst().orElseThrow();
            assertEquals("aabb000011112222cccc", wfDiff.targetId());
            assertEquals(2, wfDiff.targetVersion());
            assertEquals("position", wfDiff.matchStrategy());
            assertEquals(0, wfDiff.workflowIndex());
        }

        @Test
        @DisplayName("new workflows beyond target count should be CREATE")
        void newWorkflowsBeyondTargetAreCreate() throws Exception {
            var sourceWf = new WorkflowSourceData("src-wf-2", "New Workflow", 1,
                    new WorkflowConfiguration(), Map.of());

            var targetConfig = new AgentConfiguration();
            // Only 1 workflow in target — source has index 1 (out of range)
            targetConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aabb000011112222cccc?version=1")));

            var source = createMinimalSource("src-1", "Agent", List.of(sourceWf), List.of());
            setupTargetAgent("target-1", 1, targetConfig);
            when(snippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt())).thenReturn(List.of());

            ImportPreview preview = matcher.buildPreview(source, "target-1", false);

            ResourceDiff wfDiff = preview.resources().stream()
                    .filter(d -> "workflow".equals(d.resourceType())).findFirst().orElseThrow();
            assertEquals(DiffAction.CREATE, wfDiff.action());
            assertNull(wfDiff.targetId());
        }

        @Test
        @DisplayName("snippets should be matched by name and show UPDATE when different")
        void snippetsMatchedByName() throws Exception {
            var sourceSnippet = new SnippetSourceData("src-snp-1", "persona",
                    createSnippet("persona", "New persona text"));

            var targetConfig = new AgentConfiguration();
            var source = createMinimalSource("src-1", "Agent", List.of(), List.of(sourceSnippet));
            setupTargetAgent("target-1", 1, targetConfig);

            // Existing snippet with same name
            var existingDescriptor = new DocumentDescriptor();
            existingDescriptor.setName("persona");
            existingDescriptor.setResource(URI.create("eddi://ai.labs.snippet/snippetstore/snippets/aabb000011113333dddd?version=1"));
            when(snippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt()))
                    .thenReturn(List.of(existingDescriptor));
            when(snippetStore.readSnippet("aabb000011113333dddd", 1))
                    .thenReturn(createSnippet("persona", "Old persona text"));

            ImportPreview preview = matcher.buildPreview(source, "target-1", true);

            ResourceDiff snippetDiff = preview.resources().stream()
                    .filter(d -> "snippet".equals(d.resourceType())).findFirst().orElseThrow();
            assertEquals(DiffAction.UPDATE, snippetDiff.action());
            assertEquals("aabb000011113333dddd", snippetDiff.targetId());
            assertEquals(1, snippetDiff.targetVersion());
            assertEquals("name", snippetDiff.matchStrategy());
            assertNotNull(snippetDiff.sourceContent());
            assertNotNull(snippetDiff.targetContent());
        }
    }

    // ==================== Content Diff Flags ====================

    @Nested
    @DisplayName("Content inclusion flag")
    class ContentInclusion {

        @Test
        @DisplayName("includeContent=false should not populate content fields")
        void noContentWhenFlagFalse() {
            var source = createMinimalSource("src-1", "Agent", List.of(), List.of());
            when(snippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt())).thenReturn(List.of());

            ImportPreview preview = matcher.buildPreview(source, null, false);

            ResourceDiff agentDiff = preview.resources().get(0);
            assertNull(agentDiff.sourceContent());
            assertNull(agentDiff.targetContent());
        }

        @Test
        @DisplayName("includeContent=true should populate source content for CREATE")
        void contentPopulatedForCreate() {
            var source = createMinimalSource("src-1", "Agent", List.of(), List.of());
            when(snippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt())).thenReturn(List.of());

            ImportPreview preview = matcher.buildPreview(source, null, true);

            ResourceDiff agentDiff = preview.resources().get(0);
            assertNotNull(agentDiff.sourceContent());
            assertNull(agentDiff.targetContent()); // no target for CREATE
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("invalid target agent ID should fall back to CREATE mode")
        void invalidTargetFallsBackToCreate() throws Exception {
            var source = createMinimalSource("src-1", "Agent", List.of(), List.of());
            // Target agent not found → should fall back
            when(descriptorStore.readDescriptor(eq("nonexistent"), isNull()))
                    .thenThrow(new RuntimeException("Not found"));
            when(agentStore.readAgent(eq("nonexistent"), anyInt()))
                    .thenThrow(new RuntimeException("Not found"));
            when(snippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt())).thenReturn(List.of());

            ImportPreview preview = matcher.buildPreview(source, "nonexistent", false);

            // Should still return a valid preview with CREATE actions
            assertNotNull(preview);
            assertNull(preview.targetAgentId()); // cleared because target not found
            assertEquals(DiffAction.CREATE, preview.resources().get(0).action());
        }

        @Test
        @DisplayName("empty source should produce agent-only preview")
        void emptySourceProducesMinimalPreview() {
            var source = createMinimalSource("src-1", "Empty Agent", List.of(), List.of());
            when(snippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt())).thenReturn(List.of());

            ImportPreview preview = matcher.buildPreview(source, null, false);

            assertThat(preview.resources(), hasSize(1));
            assertEquals("agent", preview.resources().get(0).resourceType());
        }
    }

    // ==================== Test Helpers ====================

    private IResourceSource createMinimalSource(String agentId, String agentName,
                                                List<WorkflowSourceData> workflows,
                                                List<SnippetSourceData> snippets) {
        return new FakeResourceSource(agentId, agentName, new AgentConfiguration(), workflows, snippets);
    }

    private void setupTargetAgent(String agentId, int version, AgentConfiguration config) throws Exception {
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + agentId + "?version=" + version));
        descriptor.setName("Target Agent");
        when(descriptorStore.readDescriptor(eq(agentId), isNull())).thenReturn(descriptor);
        when(agentStore.readAgent(agentId, version)).thenReturn(config);
    }

    private PromptSnippet createSnippet(String name, String content) {
        var snippet = new PromptSnippet();
        snippet.setName(name);
        snippet.setContent(content);
        return snippet;
    }

    /**
     * Simple in-memory IResourceSource for testing.
     */
    private static class FakeResourceSource implements IResourceSource {
        private final AgentSourceData agentData;
        private final List<WorkflowSourceData> workflows;
        private final List<SnippetSourceData> snippets;

        FakeResourceSource(String agentId, String agentName, AgentConfiguration config,
                List<WorkflowSourceData> workflows, List<SnippetSourceData> snippets) {
            this.agentData = new AgentSourceData(agentId, agentName, config);
            this.workflows = workflows;
            this.snippets = snippets;
        }

        @Override
        public AgentSourceData readAgent() {
            return agentData;
        }
        @Override
        public List<WorkflowSourceData> readWorkflows() {
            return workflows;
        }
        @Override
        public List<SnippetSourceData> readSnippets() {
            return snippets;
        }
    }

    /**
     * Fake JSON serializer that uses toString() — produces deterministic output for
     * content comparison tests without requiring a full Jackson setup.
     */
    private static class FakeJsonSerialization implements IJsonSerialization {
        @Override
        public String serialize(Object object) {
            return object != null ? object.toString() : null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String json) {
            return null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String json, Class<T> clazz) {
            // For tests that need deserialization, return a new default instance
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                return null;
            }
        }
    }
}
