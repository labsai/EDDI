/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class DescriptorStoreTest {

    private IResourceStorage<String> resourceStorage;
    private DescriptorStore<String> store;

    @BeforeEach
    void setUp() throws Exception {
        IResourceStorageFactory storageFactory = mock(IResourceStorageFactory.class);
        IDocumentBuilder documentBuilder = mock(IDocumentBuilder.class);
        resourceStorage = mock(IResourceStorage.class);

        when(storageFactory.create(eq("descriptors"), eq(documentBuilder), eq(String.class))).thenReturn(resourceStorage);

        store = new DescriptorStore<>(storageFactory, documentBuilder, String.class);
    }

    // ==================== readDescriptor ====================

    @Test
    @DisplayName("readDescriptor — returns descriptor when found")
    void readDescriptorFound() throws Exception {
        IResourceStorage.IResource<String> resource = mock(IResourceStorage.IResource.class);
        when(resource.getData()).thenReturn("descriptor-data");
        when(resourceStorage.read("res-1", 1)).thenReturn(resource);
        when(resourceStorage.getCurrentVersion("res-1")).thenReturn(1);

        String result = store.readDescriptor("res-1", 1);
        assertEquals("descriptor-data", result);
    }

    @Test
    @DisplayName("readDescriptor — throws ResourceNotFoundException when not found")
    void readDescriptorNotFound() {
        when(resourceStorage.read("missing", 1)).thenReturn(null);
        when(resourceStorage.getCurrentVersion("missing")).thenReturn(-1);

        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> store.readDescriptor("missing", 1));
    }

    // ==================== readDescriptorWithHistory ====================

    @Test
    @DisplayName("readDescriptorWithHistory — reads including deleted versions")
    void readDescriptorWithHistory() throws Exception {
        IResourceStorage.IResource<String> resource = mock(IResourceStorage.IResource.class);
        when(resource.getData()).thenReturn("history-data");
        when(resourceStorage.read("res-1", 1)).thenReturn(resource);
        when(resourceStorage.getCurrentVersion("res-1")).thenReturn(1);

        IResourceStorage.IHistoryResource<String> historyResource = mock(IResourceStorage.IHistoryResource.class);
        when(historyResource.getData()).thenReturn("history-data");
        when(historyResource.isDeleted()).thenReturn(true);
        when(resourceStorage.readHistory("res-1", 1)).thenReturn(historyResource);

        // readDescriptorWithHistory calls readIncludingDeleted which checks both
        // current and history
        // When not in current, it reads from history
        when(resourceStorage.getCurrentVersion("res-1")).thenReturn(1);
        String result = store.readDescriptorWithHistory("res-1", 1);
        // Depending on whether it's in current or history, result varies
        assertNotNull(result);
    }

    // ==================== createDescriptor ====================

    @Test
    @DisplayName("createDescriptor — creates new descriptor")
    void createDescriptor() throws Exception {
        IResourceStorage.IResource<String> resource = mock(IResourceStorage.IResource.class);
        when(resource.getId()).thenReturn("new-id");
        when(resourceStorage.newResource(eq("res-1"), eq(1), eq("data"))).thenReturn(resource);

        assertDoesNotThrow(() -> store.createDescriptor("res-1", 1, "data"));
        verify(resourceStorage).newResource(eq("res-1"), eq(1), eq("data"));
    }

    // ==================== deleteAllDescriptor ====================

    @Test
    @DisplayName("deleteAllDescriptor — removes permanently")
    void deleteAllDescriptor() {
        store.deleteAllDescriptor("res-1");
        verify(resourceStorage).removeAllPermanently("res-1");
    }

    // ==================== getCurrentResourceId ====================

    @Test
    @DisplayName("getCurrentResourceId — returns current version")
    void getCurrentResourceId() throws Exception {
        when(resourceStorage.getCurrentVersion("res-1")).thenReturn(3);

        IResourceStore.IResourceId id = store.getCurrentResourceId("res-1");
        assertNotNull(id);
    }

    // ==================== readDescriptors ====================

    @Test
    @DisplayName("readDescriptors — returns list with filter and pagination")
    void readDescriptorsWithFilter() throws Exception {
        IResourceStore.IResourceId resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getId()).thenReturn("res-1");
        when(resourceId.getVersion()).thenReturn(1);

        when(resourceStorage.findResources(any(IResourceFilter.QueryFilters[].class), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(resourceId));

        IResourceStorage.IResource<String> resource = mock(IResourceStorage.IResource.class);
        when(resource.getData()).thenReturn("desc-data");
        when(resourceStorage.read("res-1", 1)).thenReturn(resource);
        when(resourceStorage.getCurrentVersion("res-1")).thenReturn(1);

        List<String> result = store.readDescriptors("agents", "search", 0, 20, false);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("readDescriptors — uses default limit when null")
    void readDescriptorsDefaultLimit() throws Exception {
        when(resourceStorage.findResources(any(IResourceFilter.QueryFilters[].class), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());

        List<String> result = store.readDescriptors("agents", null, null, null, false);
        assertTrue(result.isEmpty());
    }

    // ==================== findByOriginId ====================

    @Test
    @DisplayName("findByOriginId — finds descriptors by origin")
    void findByOriginId() throws Exception {
        IResourceStore.IResourceId resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getId()).thenReturn("res-1");
        when(resourceId.getVersion()).thenReturn(1);

        when(resourceStorage.findResources(any(IResourceFilter.QueryFilters[].class), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(resourceId));

        IResourceStorage.IResource<String> resource = mock(IResourceStorage.IResource.class);
        when(resource.getData()).thenReturn("found-data");
        when(resourceStorage.read("res-1", 1)).thenReturn(resource);
        when(resourceStorage.getCurrentVersion("res-1")).thenReturn(1);

        List<String> result = store.findByOriginId("origin-1");
        assertEquals(1, result.size());
    }
}
