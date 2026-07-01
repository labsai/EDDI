/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.mongo;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.mongo.DocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Unit tests for {@link AgentStore}.
 */
class AgentStoreTest {

    @Mock
    private IResourceStorageFactory storageFactory;

    @Mock
    private IDocumentBuilder documentBuilder;

    @Mock
    private DocumentDescriptorStore documentDescriptorStore;

    @Mock
    @SuppressWarnings("rawtypes")
    private IResourceStorage resourceStorage;

    private AgentStore agentStore;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        openMocks(this);
        doReturn(resourceStorage).when(storageFactory)
                .create(anyString(), any(IDocumentBuilder.class), any(Class.class), any(String[].class));
        agentStore = new AgentStore(storageFactory, documentBuilder, documentDescriptorStore);
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("should throw when workflows list contains null element")
        void throwsWhenWorkflowsContainNull() {
            var config = new AgentConfiguration();
            List<URI> workflows = new ArrayList<>();
            workflows.add(null);
            config.setWorkflows(workflows);

            assertThrows(RuntimeException.class, () -> agentStore.create(config));
        }

        @Test
        @DisplayName("should accept empty workflows list")
        void acceptsEmptyWorkflows() throws Exception {
            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());

            @SuppressWarnings("unchecked")
            IResourceStorage.IResource<AgentConfiguration> mockResource = mock(IResourceStorage.IResource.class);
            when(mockResource.getId()).thenReturn("aabbccdd11223344eeff5566");
            when(mockResource.getVersion()).thenReturn(1);
            doReturn(mockResource).when(resourceStorage).newResource(any());

            agentStore.create(config);

            verify(resourceStorage).newResource(any());
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("should throw when workflows list contains null element")
        void throwsWhenWorkflowsContainNull() {
            var config = new AgentConfiguration();
            List<URI> workflows = new ArrayList<>();
            workflows.add(null);
            config.setWorkflows(workflows);

            assertThrows(RuntimeException.class,
                    () -> agentStore.update("aabbccdd11223344eeff5566", 1, config));
        }
    }

    @Nested
    @DisplayName("getAgentDescriptorsContainingWorkflow")
    class GetAgentDescriptorsContainingWorkflow {

        private static final String WORKFLOW_ID = "aabbccdd11223344eeff5566";
        private static final String AGENT_ID_1 = "112233445566778899aabbcc";
        private static final String AGENT_ID_2 = "ffeeddccbbaa998877665544";

        @Test
        @DisplayName("should return descriptors for matching current resources")
        @SuppressWarnings("unchecked")
        void returnsDescriptorsForCurrentResources() throws Exception {
            String workflowUri = "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1";
            var resourceId = createResourceId(AGENT_ID_1, 1);

            doReturn(List.of(resourceId)).when(resourceStorage)
                    .findResourceIdsContaining("workflows", workflowUri);
            doReturn(Collections.emptyList()).when(resourceStorage)
                    .findHistoryResourceIdsContaining("workflows", workflowUri);
            doReturn(1).when(resourceStorage).getCurrentVersion(AGENT_ID_1);

            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID_1 + "?version=1"));
            when(documentDescriptorStore.readDescriptor(AGENT_ID_1, 1)).thenReturn(descriptor);

            var result = agentStore.getAgentDescriptorsContainingWorkflow(WORKFLOW_ID, 1, false);

            assertEquals(1, result.size());
            assertSame(descriptor, result.get(0));
        }

        @Test
        @DisplayName("should return empty when no matches found")
        @SuppressWarnings("unchecked")
        void returnsEmptyWhenNoMatches() throws Exception {
            String workflowUri = "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1";

            doReturn(Collections.emptyList()).when(resourceStorage)
                    .findResourceIdsContaining("workflows", workflowUri);
            doReturn(Collections.emptyList()).when(resourceStorage)
                    .findHistoryResourceIdsContaining("workflows", workflowUri);

            var result = agentStore.getAgentDescriptorsContainingWorkflow(WORKFLOW_ID, 1, false);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should include history resources")
        @SuppressWarnings("unchecked")
        void includesHistoryResources() throws Exception {
            String workflowUri = "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1";
            var historyResourceId = createResourceId(AGENT_ID_1, 1);

            doReturn(Collections.emptyList()).when(resourceStorage)
                    .findResourceIdsContaining("workflows", workflowUri);
            doReturn(List.of(historyResourceId)).when(resourceStorage)
                    .findHistoryResourceIdsContaining("workflows", workflowUri);
            doReturn(1).when(resourceStorage).getCurrentVersion(AGENT_ID_1);

            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID_1 + "?version=1"));
            when(documentDescriptorStore.readDescriptor(AGENT_ID_1, 1)).thenReturn(descriptor);

            var result = agentStore.getAgentDescriptorsContainingWorkflow(WORKFLOW_ID, 1, false);

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("should skip resources with version below current")
        @SuppressWarnings("unchecked")
        void skipsOldVersions() throws Exception {
            String workflowUri = "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1";
            // Resource found at version 1, but current version is 2
            var resourceId = createResourceId(AGENT_ID_1, 1);

            doReturn(List.of(resourceId)).when(resourceStorage)
                    .findResourceIdsContaining("workflows", workflowUri);
            doReturn(Collections.emptyList()).when(resourceStorage)
                    .findHistoryResourceIdsContaining("workflows", workflowUri);
            // Current version is 2, so version 1 result should be skipped
            doReturn(2).when(resourceStorage).getCurrentVersion(AGENT_ID_1);

            var result = agentStore.getAgentDescriptorsContainingWorkflow(WORKFLOW_ID, 1, false);

            assertTrue(result.isEmpty());
            verify(documentDescriptorStore, never()).readDescriptor(anyString(), anyInt());
        }

        @Test
        @DisplayName("should deduplicate by resource ID")
        @SuppressWarnings("unchecked")
        void deduplicatesByResourceId() throws Exception {
            String workflowUri = "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1";
            // Same agent ID appears in both current and history
            var currentId = createResourceId(AGENT_ID_1, 1);
            var historyId = createResourceId(AGENT_ID_1, 1);

            doReturn(List.of(currentId)).when(resourceStorage)
                    .findResourceIdsContaining("workflows", workflowUri);
            doReturn(List.of(historyId)).when(resourceStorage)
                    .findHistoryResourceIdsContaining("workflows", workflowUri);
            doReturn(1).when(resourceStorage).getCurrentVersion(AGENT_ID_1);

            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID_1 + "?version=1"));
            when(documentDescriptorStore.readDescriptor(AGENT_ID_1, 1)).thenReturn(descriptor);

            var result = agentStore.getAgentDescriptorsContainingWorkflow(WORKFLOW_ID, 1, false);

            // Should only have one entry despite two IDs matching
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("should handle ResourceNotFoundException gracefully by skipping deleted resources")
        @SuppressWarnings("unchecked")
        void skipsDeletedResources() throws Exception {
            String workflowUri = "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1";
            var resourceId = createResourceId(AGENT_ID_1, 1);

            doReturn(List.of(resourceId)).when(resourceStorage)
                    .findResourceIdsContaining("workflows", workflowUri);
            doReturn(Collections.emptyList()).when(resourceStorage)
                    .findHistoryResourceIdsContaining("workflows", workflowUri);
            doReturn(1).when(resourceStorage).getCurrentVersion(AGENT_ID_1);

            when(documentDescriptorStore.readDescriptor(AGENT_ID_1, 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("deleted"));

            var result = agentStore.getAgentDescriptorsContainingWorkflow(WORKFLOW_ID, 1, false);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("with includePreviousVersions should iterate through previous workflow versions")
        @SuppressWarnings("unchecked")
        void iteratesPreviousVersions() throws Exception {
            // Version 2 matches
            String workflowUriV2 = "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=2";
            var resourceIdV2 = createResourceId(AGENT_ID_1, 1);
            doReturn(List.of(resourceIdV2)).when(resourceStorage)
                    .findResourceIdsContaining("workflows", workflowUriV2);
            doReturn(Collections.emptyList()).when(resourceStorage)
                    .findHistoryResourceIdsContaining("workflows", workflowUriV2);

            // Version 1 matches a different agent
            String workflowUriV1 = "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1";
            var resourceIdV1 = createResourceId(AGENT_ID_2, 1);
            doReturn(List.of(resourceIdV1)).when(resourceStorage)
                    .findResourceIdsContaining("workflows", workflowUriV1);
            doReturn(Collections.emptyList()).when(resourceStorage)
                    .findHistoryResourceIdsContaining("workflows", workflowUriV1);

            doReturn(1).when(resourceStorage).getCurrentVersion(AGENT_ID_1);
            doReturn(1).when(resourceStorage).getCurrentVersion(AGENT_ID_2);

            var descriptor1 = new DocumentDescriptor();
            descriptor1.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID_1 + "?version=1"));
            when(documentDescriptorStore.readDescriptor(AGENT_ID_1, 1)).thenReturn(descriptor1);

            var descriptor2 = new DocumentDescriptor();
            descriptor2.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID_2 + "?version=1"));
            when(documentDescriptorStore.readDescriptor(AGENT_ID_2, 1)).thenReturn(descriptor2);

            var result = agentStore.getAgentDescriptorsContainingWorkflow(WORKFLOW_ID, 2, true);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("should return multiple descriptors for different agents")
        @SuppressWarnings("unchecked")
        void returnsMultipleDescriptors() throws Exception {
            String workflowUri = "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1";
            var resourceId1 = createResourceId(AGENT_ID_1, 1);
            var resourceId2 = createResourceId(AGENT_ID_2, 1);

            doReturn(List.of(resourceId1, resourceId2)).when(resourceStorage)
                    .findResourceIdsContaining("workflows", workflowUri);
            doReturn(Collections.emptyList()).when(resourceStorage)
                    .findHistoryResourceIdsContaining("workflows", workflowUri);
            doReturn(1).when(resourceStorage).getCurrentVersion(AGENT_ID_1);
            doReturn(1).when(resourceStorage).getCurrentVersion(AGENT_ID_2);

            var descriptor1 = new DocumentDescriptor();
            descriptor1.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID_1 + "?version=1"));
            when(documentDescriptorStore.readDescriptor(AGENT_ID_1, 1)).thenReturn(descriptor1);

            var descriptor2 = new DocumentDescriptor();
            descriptor2.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID_2 + "?version=1"));
            when(documentDescriptorStore.readDescriptor(AGENT_ID_2, 1)).thenReturn(descriptor2);

            var result = agentStore.getAgentDescriptorsContainingWorkflow(WORKFLOW_ID, 1, false);

            assertEquals(2, result.size());
        }
    }

    private IResourceStore.IResourceId createResourceId(String id, int version) {
        return new IResourceStore.IResourceId() {
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
}
