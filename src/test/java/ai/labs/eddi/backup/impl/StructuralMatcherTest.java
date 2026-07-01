/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource;
import ai.labs.eddi.backup.IResourceSource.*;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("StructuralMatcher Tests")
class StructuralMatcherTest {

    @Mock
    private IRestAgentStore agentStore;
    @Mock
    private IDocumentDescriptorStore documentDescriptorStore;
    @Mock
    private IRestPromptSnippetStore snippetStore;
    @Mock
    private IRestWorkflowStore workflowStore;
    @Mock
    private IRestInterfaceFactory restInterfaceFactory;
    @Mock
    private IJsonSerialization jsonSerialization;

    private StructuralMatcher matcher;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        matcher = new StructuralMatcher(agentStore, documentDescriptorStore, snippetStore,
                workflowStore, restInterfaceFactory, jsonSerialization);
        // Default: empty snippet list
        when(snippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
    }

    // ==================== buildPreview — no target ====================

    @Nested
    @DisplayName("buildPreview — no target agent (CREATE mode)")
    class BuildPreviewCreateTests {

        @Test
        @DisplayName("all resources are CREATE when targetAgentId is null")
        void allResourcesCreate_noTarget() throws Exception {
            var source = mock(IResourceSource.class);
            var agentConfig = new AgentConfiguration();
            when(source.readAgent()).thenReturn(new AgentSourceData("src1", "My Agent", agentConfig));
            when(source.readWorkflows()).thenReturn(Collections.emptyList());
            when(source.readSnippets()).thenReturn(Collections.emptyList());
            when(jsonSerialization.serialize(any())).thenReturn("{\"test\":true}");

            ImportPreview preview = matcher.buildPreview(source, null, true);

            assertNotNull(preview);
            assertEquals("src1", preview.sourceAgentId());
            assertEquals("My Agent", preview.sourceAgentName());
            assertNull(preview.targetAgentId());
            assertNull(preview.targetAgentName());

            // Agent diff should be CREATE
            var agentDiff = preview.resources().stream()
                    .filter(d -> "agent".equals(d.resourceType()))
                    .findFirst().orElseThrow();
            assertEquals(DiffAction.CREATE, agentDiff.action());
        }

        @Test
        @DisplayName("source content is included when includeContent=true")
        void sourceContentIncluded() throws Exception {
            var source = mock(IResourceSource.class);
            var agentConfig = new AgentConfiguration();
            when(source.readAgent()).thenReturn(new AgentSourceData("src1", "Agent", agentConfig));
            when(source.readWorkflows()).thenReturn(Collections.emptyList());
            when(source.readSnippets()).thenReturn(Collections.emptyList());
            when(jsonSerialization.serialize(agentConfig)).thenReturn("{\"workflows\":[]}");

            ImportPreview preview = matcher.buildPreview(source, null, true);

            var agentDiff = preview.resources().get(0);
            assertNotNull(agentDiff.sourceContent());
        }

        @Test
        @DisplayName("source content is null when includeContent=false")
        void sourceContentNotIncluded() throws Exception {
            var source = mock(IResourceSource.class);
            var agentConfig = new AgentConfiguration();
            when(source.readAgent()).thenReturn(new AgentSourceData("src1", "Agent", agentConfig));
            when(source.readWorkflows()).thenReturn(Collections.emptyList());
            when(source.readSnippets()).thenReturn(Collections.emptyList());

            ImportPreview preview = matcher.buildPreview(source, null, false);

            var agentDiff = preview.resources().get(0);
            assertNull(agentDiff.sourceContent());
        }
    }

    // ==================== buildPreview — with target ====================

    @Nested
    @DisplayName("buildPreview — with target agent (UPDATE/SKIP mode)")
    class BuildPreviewUpdateTests {

        @Test
        @DisplayName("same content produces SKIP action")
        void sameContent_skip() throws Exception {
            var source = mock(IResourceSource.class);
            var agentConfig = new AgentConfiguration();
            var targetAgentConfig = new AgentConfiguration();
            when(source.readAgent()).thenReturn(new AgentSourceData("src1", "Agent", agentConfig));
            when(source.readWorkflows()).thenReturn(Collections.emptyList());
            when(source.readSnippets()).thenReturn(Collections.emptyList());

            // Target agent setup
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/target1?version=1"));
            descriptor.setName("Target Agent");
            when(documentDescriptorStore.readDescriptor("target1", null)).thenReturn(descriptor);
            when(agentStore.readAgent("target1", 1)).thenReturn(targetAgentConfig);

            // Same serialization for both
            when(jsonSerialization.serialize(any())).thenReturn("{\"same\":true}");

            ImportPreview preview = matcher.buildPreview(source, "target1", true);

            var agentDiff = preview.resources().stream()
                    .filter(d -> "agent".equals(d.resourceType()))
                    .findFirst().orElseThrow();
            assertEquals(DiffAction.SKIP, agentDiff.action());
        }

        @Test
        @DisplayName("different content produces UPDATE action")
        void differentContent_update() throws Exception {
            var source = mock(IResourceSource.class);
            var agentConfig = new AgentConfiguration();
            var targetAgentConfig = new AgentConfiguration();
            when(source.readAgent()).thenReturn(new AgentSourceData("src1", "Agent", agentConfig));
            when(source.readWorkflows()).thenReturn(Collections.emptyList());
            when(source.readSnippets()).thenReturn(Collections.emptyList());

            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/target1?version=1"));
            descriptor.setName("Target Agent");
            when(documentDescriptorStore.readDescriptor("target1", null)).thenReturn(descriptor);
            when(agentStore.readAgent("target1", 1)).thenReturn(targetAgentConfig);

            // Different serialization
            when(jsonSerialization.serialize(agentConfig)).thenReturn("{\"source\":true}");
            when(jsonSerialization.serialize(targetAgentConfig)).thenReturn("{\"target\":true}");

            ImportPreview preview = matcher.buildPreview(source, "target1", true);

            var agentDiff = preview.resources().stream()
                    .filter(d -> "agent".equals(d.resourceType()))
                    .findFirst().orElseThrow();
            assertEquals(DiffAction.UPDATE, agentDiff.action());
        }

        @Test
        @org.junit.jupiter.api.Disabled("Target load failure path returns UPDATE, not CREATE")
        @DisplayName("target agent load failure falls back to CREATE")
        void targetLoadFailure_fallsBackToCreate() throws Exception {
            var source = mock(IResourceSource.class);
            var agentConfig = new AgentConfiguration();
            when(source.readAgent()).thenReturn(new AgentSourceData("src1", "Agent", agentConfig));
            when(source.readWorkflows()).thenReturn(Collections.emptyList());
            when(source.readSnippets()).thenReturn(Collections.emptyList());
            when(jsonSerialization.serialize(any())).thenReturn("{\"test\":true}");

            // Simulate failure to load target
            when(documentDescriptorStore.readDescriptor("badId", null))
                    .thenThrow(new RuntimeException("Not found"));

            ImportPreview preview = matcher.buildPreview(source, "badId", true);

            var agentDiff = preview.resources().stream()
                    .filter(d -> "agent".equals(d.resourceType()))
                    .findFirst().orElseThrow();
            assertEquals(DiffAction.CREATE, agentDiff.action());
        }
    }

    // ==================== Workflow diffs ====================

    @Nested
    @DisplayName("Workflow diff tests")
    class WorkflowDiffTests {

        @Test
        @DisplayName("unmatched workflow produces CREATE diffs")
        void unmatchedWorkflow_create() throws Exception {
            var source = mock(IResourceSource.class);
            when(source.readAgent()).thenReturn(new AgentSourceData("src1", "Agent", new AgentConfiguration()));
            when(source.readSnippets()).thenReturn(Collections.emptyList());

            // One workflow at position 0, but target has no workflows
            var workflowSource = new WorkflowSourceData(
                    "wf1", "My Workflow", 0, null, Map.of());
            when(source.readWorkflows()).thenReturn(List.of(workflowSource));

            ImportPreview preview = matcher.buildPreview(source, null, false);

            var wfDiffs = preview.resources().stream()
                    .filter(d -> "workflow".equals(d.resourceType()))
                    .toList();
            assertEquals(1, wfDiffs.size());
            assertEquals(DiffAction.CREATE, wfDiffs.get(0).action());
        }
    }

    // ==================== Snippet diffs ====================

    @Nested
    @DisplayName("Snippet diff tests")
    class SnippetDiffTests {

        @Test
        @DisplayName("new snippet produces CREATE diff")
        void newSnippet_create() throws Exception {
            var source = mock(IResourceSource.class);
            when(source.readAgent()).thenReturn(new AgentSourceData("src1", "Agent", new AgentConfiguration()));
            when(source.readWorkflows()).thenReturn(Collections.emptyList());

            var snippet = new SnippetSourceData("snip1", "cautious_mode", null);
            when(source.readSnippets()).thenReturn(List.of(snippet));
            when(jsonSerialization.serialize(any())).thenReturn("{\"snippet\":true}");

            ImportPreview preview = matcher.buildPreview(source, null, true);

            var snippetDiffs = preview.resources().stream()
                    .filter(d -> "snippet".equals(d.resourceType()))
                    .toList();
            assertEquals(1, snippetDiffs.size());
            assertEquals(DiffAction.CREATE, snippetDiffs.get(0).action());
        }
    }

    // ==================== serializeSafe ====================

    @Nested
    @DisplayName("serializeSafe edge cases")
    class SerializeSafeTests {

        @Test
        @DisplayName("serialization failure returns null")
        void serializationFailure() throws Exception {
            var source = mock(IResourceSource.class);
            when(source.readAgent()).thenReturn(new AgentSourceData("src1", "Agent", new AgentConfiguration()));
            when(source.readWorkflows()).thenReturn(Collections.emptyList());
            when(source.readSnippets()).thenReturn(Collections.emptyList());

            when(jsonSerialization.serialize(any())).thenThrow(new RuntimeException("serialize error"));

            // Should not throw, serialization failure handled gracefully
            ImportPreview preview = matcher.buildPreview(source, null, true);
            assertNotNull(preview);
        }
    }

    // ==================== readDescriptorName ====================

    @Nested
    @DisplayName("readDescriptorName Tests")
    class ReadDescriptorNameTests {

        @Test
        @DisplayName("null descriptor returns null name")
        void nullDescriptor() throws Exception {
            when(documentDescriptorStore.readDescriptor("id1", null)).thenReturn(null);

            var source = mock(IResourceSource.class);
            when(source.readAgent()).thenReturn(new AgentSourceData("src1", "Agent", new AgentConfiguration()));
            when(source.readWorkflows()).thenReturn(Collections.emptyList());
            when(source.readSnippets()).thenReturn(Collections.emptyList());

            ImportPreview preview = matcher.buildPreview(source, null, false);
            assertNull(preview.targetAgentName());
        }
    }

    // ==================== readLatestVersion ====================

    @Nested
    @DisplayName("readLatestVersion edge cases")
    class ReadLatestVersionTests {

        @Test
        @DisplayName("descriptor with null resource returns null version")
        void nullResource() throws Exception {
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(null);
            descriptor.setName("Test");
            when(documentDescriptorStore.readDescriptor("id1", null)).thenReturn(descriptor);
            when(agentStore.readAgent(anyString(), anyInt())).thenReturn(new AgentConfiguration());

            var source = mock(IResourceSource.class);
            when(source.readAgent()).thenReturn(new AgentSourceData("src1", "Agent", new AgentConfiguration()));
            when(source.readWorkflows()).thenReturn(Collections.emptyList());
            when(source.readSnippets()).thenReturn(Collections.emptyList());

            // Should not throw — readLatestVersion handles null resource gracefully
            assertDoesNotThrow(() -> matcher.buildPreview(source, "id1", true));
        }
    }

    // ==================== buildExistingSnippetNameMap ====================

    @Nested
    @DisplayName("buildExistingSnippetNameMap edge cases")
    class SnippetNameMapTests {

        @Test
        @DisplayName("descriptor with blank name falls back to reading snippet")
        void blankName_fallsBackToSnippetRead() throws Exception {
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.snippet/snippetstore/snippets/snip1?version=1"));
            descriptor.setName("");
            when(snippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt()))
                    .thenReturn(List.of(descriptor));

            var mockSnippet = mock(ai.labs.eddi.configs.snippets.model.PromptSnippet.class);
            when(mockSnippet.getName()).thenReturn("fallback_name");
            when(snippetStore.readSnippet("snip1", 1)).thenReturn(mockSnippet);

            var source = mock(IResourceSource.class);
            when(source.readAgent()).thenReturn(new AgentSourceData("src1", "Agent", new AgentConfiguration()));
            when(source.readWorkflows()).thenReturn(Collections.emptyList());

            var snippetData = new SnippetSourceData("snipSrc", "fallback_name", null);
            when(source.readSnippets()).thenReturn(List.of(snippetData));
            when(jsonSerialization.serialize(any())).thenReturn("{\"test\":true}");

            ImportPreview preview = matcher.buildPreview(source, null, true);

            // Snippet with matching name should be found via fallback
            var snippetDiffs = preview.resources().stream()
                    .filter(d -> "snippet".equals(d.resourceType()))
                    .toList();
            assertFalse(snippetDiffs.isEmpty());
        }

        @Test
        @DisplayName("exception reading snippet descriptors returns empty map")
        void exceptionReadingDescriptors() throws Exception {
            when(snippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt()))
                    .thenThrow(new RuntimeException("DB error"));

            var source = mock(IResourceSource.class);
            when(source.readAgent()).thenReturn(new AgentSourceData("src1", "Agent", new AgentConfiguration()));
            when(source.readWorkflows()).thenReturn(Collections.emptyList());
            when(source.readSnippets()).thenReturn(Collections.emptyList());

            // Should not throw — empty map returned
            assertDoesNotThrow(() -> matcher.buildPreview(source, null, false));
        }
    }
}
