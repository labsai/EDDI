/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows.mongo;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class WorkflowStoreTest {

    private IResourceStorage<WorkflowConfiguration> resourceStorage;
    private IDocumentDescriptorStore documentDescriptorStore;
    private WorkflowStore store;

    @BeforeEach
    void setUp() {
        IResourceStorageFactory storageFactory = mock(IResourceStorageFactory.class);
        IDocumentBuilder documentBuilder = mock(IDocumentBuilder.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        resourceStorage = mock(IResourceStorage.class);

        when(storageFactory.create(eq("workflows"), eq(documentBuilder), eq(WorkflowConfiguration.class),
                eq("WorkflowSteps.config.uri"), eq("WorkflowSteps.extensions.dictionaries.config.uri")))
                .thenReturn(resourceStorage);

        store = new WorkflowStore(storageFactory, documentBuilder, documentDescriptorStore);
    }

    // ==================== create ====================

    @Test
    @DisplayName("create — validates and delegates to parent")
    void create() throws Exception {
        WorkflowConfiguration config = new WorkflowConfiguration();
        config.setWorkflowSteps(new ArrayList<>());

        IResourceStorage.IResource<WorkflowConfiguration> resource = mock(IResourceStorage.IResource.class);
        when(resource.getId()).thenReturn("wf-1");
        when(resource.getVersion()).thenReturn(1);
        when(resourceStorage.newResource(config)).thenReturn(resource);
        when(resourceStorage.getCurrentVersion("wf-1")).thenReturn(1);

        IResourceStore.IResourceId result = store.create(config);
        assertNotNull(result);
    }

    // ==================== read ====================

    @Test
    @DisplayName("read — returns workflow config when found")
    void readFound() throws Exception {
        WorkflowConfiguration config = new WorkflowConfiguration();
        IResourceStorage.IResource<WorkflowConfiguration> resource = mock(IResourceStorage.IResource.class);
        when(resource.getData()).thenReturn(config);
        when(resourceStorage.read("wf-1", 1)).thenReturn(resource);
        when(resourceStorage.getCurrentVersion("wf-1")).thenReturn(1);

        WorkflowConfiguration result = store.read("wf-1", 1);
        assertSame(config, result);
    }

    @Test
    @DisplayName("read — throws ResourceNotFoundException when not found")
    void readNotFound() {
        when(resourceStorage.read("missing", 1)).thenReturn(null);
        when(resourceStorage.getCurrentVersion("missing")).thenReturn(-1);

        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> store.read("missing", 1));
    }

    // ==================== getWorkflowDescriptorsContainingResource
    // ====================

    @Test
    @DisplayName("getWorkflowDescriptorsContainingResource — finds descriptors")
    void getWorkflowDescriptorsContainingResource() throws Exception {
        String resourceURI = "eddi://ai.labs.behavior/behaviorId?version=1";

        IResourceStore.IResourceId workflowId = mock(IResourceStore.IResourceId.class);
        when(workflowId.getId()).thenReturn("111111111111111111111111");
        when(workflowId.getVersion()).thenReturn(1);

        when(resourceStorage.findResourceIdsContaining(anyString(), anyString()))
                .thenReturn(List.of(workflowId));
        when(resourceStorage.findHistoryResourceIdsContaining(anyString(), anyString()))
                .thenReturn(List.of());

        when(resourceStorage.getCurrentVersion("111111111111111111111111")).thenReturn(1);

        DocumentDescriptor descriptor = new DocumentDescriptor();
        descriptor.setResource(java.net.URI.create("eddi://ai.labs.workflow/workflowstore/workflows/111111111111111111111111?version=1"));
        when(documentDescriptorStore.readDescriptor("111111111111111111111111", 1)).thenReturn(descriptor);

        List<DocumentDescriptor> result = store.getWorkflowDescriptorsContainingResource(resourceURI, false);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("getWorkflowDescriptorsContainingResource — returns empty when no matches")
    void getWorkflowDescriptorsContainingResourceEmpty() throws Exception {
        when(resourceStorage.findResourceIdsContaining(anyString(), anyString()))
                .thenReturn(List.of());
        when(resourceStorage.findHistoryResourceIdsContaining(anyString(), anyString()))
                .thenReturn(List.of());

        List<DocumentDescriptor> result = store.getWorkflowDescriptorsContainingResource(
                "eddi://ai.labs.behavior/behaviorId?version=1", false);
        assertTrue(result.isEmpty());
    }

    // ==================== deleteAllPermanently ====================

    @Test
    @DisplayName("deleteAllPermanently — removes all versions")
    void deleteAllPermanently() {
        store.deleteAllPermanently("wf-1");
        verify(resourceStorage).removeAllPermanently("wf-1");
    }
}
