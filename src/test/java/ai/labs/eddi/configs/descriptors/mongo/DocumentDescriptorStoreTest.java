/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors.mongo;

import ai.labs.eddi.datastore.DescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DocumentDescriptorStore} — verifying pure delegation to
 * internal DescriptorStore.
 */
@SuppressWarnings("unchecked")
@DisplayName("DocumentDescriptorStore")
class DocumentDescriptorStoreTest {

    private DocumentDescriptorStore store;
    private DescriptorStore<DocumentDescriptor> internalStore;

    @BeforeEach
    void setUp() throws Exception {
        IResourceStorageFactory storageFactory = mock(IResourceStorageFactory.class);
        IDocumentBuilder documentBuilder = mock(IDocumentBuilder.class);
        IResourceStorage<DocumentDescriptor> storage = mock(IResourceStorage.class);
        doReturn(storage).when(storageFactory).create(anyString(), any(), any());

        store = new DocumentDescriptorStore(storageFactory, documentBuilder);

        // Replace internal descriptorStore with a mock for test isolation
        internalStore = mock(DescriptorStore.class);
        var field = DocumentDescriptorStore.class.getDeclaredField("descriptorStore");
        field.setAccessible(true);
        field.set(store, internalStore);
    }

    private IResourceStore.IResourceId mockResourceId(String id, int version) {
        IResourceStore.IResourceId resourceId = mock(IResourceStore.IResourceId.class);
        doReturn(id).when(resourceId).getId();
        doReturn(version).when(resourceId).getVersion();
        return resourceId;
    }

    // ==================== readDescriptors ====================

    @Test
    @DisplayName("readDescriptors delegates to internal store")
    void readDescriptors() throws Exception {
        List<DocumentDescriptor> expected = List.of(new DocumentDescriptor());
        doReturn(expected).when(internalStore).readDescriptors("agents", "filter", 0, 10, false);

        List<DocumentDescriptor> result = store.readDescriptors("agents", "filter", 0, 10, false);

        assertSame(expected, result);
        verify(internalStore).readDescriptors("agents", "filter", 0, 10, false);
    }

    // ==================== readDescriptor ====================

    @Test
    @DisplayName("readDescriptor delegates to internal store")
    void readDescriptor() throws Exception {
        DocumentDescriptor expected = new DocumentDescriptor();
        doReturn(expected).when(internalStore).readDescriptor("res-1", 1);

        DocumentDescriptor result = store.readDescriptor("res-1", 1);

        assertSame(expected, result);
    }

    // ==================== readDescriptorWithHistory ====================

    @Test
    @DisplayName("readDescriptorWithHistory delegates to internal store")
    void readDescriptorWithHistory() throws Exception {
        DocumentDescriptor expected = new DocumentDescriptor();
        doReturn(expected).when(internalStore).readDescriptorWithHistory("res-1", 1);

        DocumentDescriptor result = store.readDescriptorWithHistory("res-1", 1);

        assertSame(expected, result);
    }

    // ==================== updateDescriptor ====================

    @Test
    @DisplayName("updateDescriptor delegates and returns new version")
    void updateDescriptor() throws Exception {
        DocumentDescriptor descriptor = new DocumentDescriptor();
        doReturn(2).when(internalStore).updateDescriptor("res-1", 1, descriptor);

        Integer version = store.updateDescriptor("res-1", 1, descriptor);

        assertEquals(2, version);
    }

    // ==================== setDescriptor ====================

    @Test
    @DisplayName("setDescriptor delegates to internal store")
    void setDescriptor() throws Exception {
        DocumentDescriptor descriptor = new DocumentDescriptor();

        store.setDescriptor("res-1", 1, descriptor);

        verify(internalStore).setDescriptor("res-1", 1, descriptor);
    }

    // ==================== createDescriptor ====================

    @Test
    @DisplayName("createDescriptor delegates to internal store")
    void createDescriptor() throws Exception {
        DocumentDescriptor descriptor = new DocumentDescriptor();

        store.createDescriptor("res-1", 1, descriptor);

        verify(internalStore).createDescriptor("res-1", 1, descriptor);
    }

    // ==================== getCurrentResourceId ====================

    @Test
    @DisplayName("getCurrentResourceId delegates to internal store")
    void getCurrentResourceId() throws Exception {
        IResourceStore.IResourceId expected = mockResourceId("res-1", 1);
        doReturn(expected).when(internalStore).getCurrentResourceId("res-1");

        IResourceStore.IResourceId result = store.getCurrentResourceId("res-1");

        assertSame(expected, result);
    }

    // ==================== deleteDescriptor ====================

    @Test
    @DisplayName("deleteDescriptor delegates to internal store")
    void deleteDescriptor() throws Exception {
        store.deleteDescriptor("res-1", 1);

        verify(internalStore).deleteDescriptor("res-1", 1);
    }

    // ==================== deleteAllDescriptor ====================

    @Test
    @DisplayName("deleteAllDescriptor delegates to internal store")
    void deleteAllDescriptor() throws Exception {
        store.deleteAllDescriptor("res-1");

        verify(internalStore).deleteAllDescriptor("res-1");
    }

    // ==================== findByOriginId ====================

    @Test
    @DisplayName("findByOriginId delegates to internal store")
    void findByOriginId() throws Exception {
        List<DocumentDescriptor> expected = List.of(new DocumentDescriptor());
        doReturn(expected).when(internalStore).findByOriginId("origin-1");

        List<DocumentDescriptor> result = store.findByOriginId("origin-1");

        assertSame(expected, result);
    }
}
