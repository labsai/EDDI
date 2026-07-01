/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
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
class GroupConversationStoreTest {

    private IResourceStorage<GroupConversation> storage;
    private GroupConversationStore store;

    @BeforeEach
    void setUp() {
        IResourceStorageFactory storageFactory = mock(IResourceStorageFactory.class);
        IDocumentBuilder documentBuilder = mock(IDocumentBuilder.class);
        storage = mock(IResourceStorage.class);

        when(storageFactory.create(eq("groupconversations"), eq(documentBuilder),
                eq(GroupConversation.class), eq("groupId"), eq("state"))).thenReturn(storage);

        store = new GroupConversationStore(storageFactory, documentBuilder);
    }

    // ==================== create ====================

    @Test
    @DisplayName("create — stores and returns ID")
    void create() throws Exception {
        GroupConversation conversation = new GroupConversation();
        IResourceStorage.IResource<GroupConversation> resource = mock(IResourceStorage.IResource.class);
        when(resource.getId()).thenReturn("gc-1");
        when(storage.newResource(conversation)).thenReturn(resource);

        String id = store.create(conversation);
        assertEquals("gc-1", id);
        assertEquals("gc-1", conversation.getId());
        verify(storage).store(resource);
    }

    @Test
    @DisplayName("create — wraps IOException in ResourceStoreException")
    void createError() throws Exception {
        GroupConversation conversation = new GroupConversation();
        when(storage.newResource(conversation)).thenThrow(new IOException("fail"));

        assertThrows(IResourceStore.ResourceStoreException.class, () -> store.create(conversation));
    }

    // ==================== read ====================

    @Test
    @DisplayName("read — returns conversation when found")
    void readFound() throws Exception {
        GroupConversation conversation = new GroupConversation();
        IResourceStorage.IResource<GroupConversation> resource = mock(IResourceStorage.IResource.class);
        when(resource.getData()).thenReturn(conversation);
        when(storage.read("gc-1", 1)).thenReturn(resource);

        GroupConversation result = store.read("gc-1");
        assertNotNull(result);
        assertEquals("gc-1", result.getId());
    }

    @Test
    @DisplayName("read — throws ResourceNotFoundException when not found")
    void readNotFound() {
        when(storage.read("missing", 1)).thenReturn(null);

        assertThrows(IResourceStore.ResourceNotFoundException.class, () -> store.read("missing"));
    }

    // ==================== update ====================

    @Test
    @DisplayName("update — stores updated conversation")
    void update() throws Exception {
        GroupConversation conversation = new GroupConversation();
        conversation.setId("gc-1");
        IResourceStorage.IResource<GroupConversation> resource = mock(IResourceStorage.IResource.class);
        when(storage.newResource("gc-1", 1, conversation)).thenReturn(resource);

        assertDoesNotThrow(() -> store.update(conversation));
        verify(storage).store(resource);
    }

    // ==================== delete ====================

    @Test
    @DisplayName("delete — removes permanently")
    void delete() throws Exception {
        assertDoesNotThrow(() -> store.delete("gc-1"));
        verify(storage).removeAllPermanently("gc-1");
    }

    // ==================== listByGroupId ====================

    @Test
    @DisplayName("listByGroupId — returns conversations for group")
    void listByGroupId() throws Exception {
        IResourceStore.IResourceId resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getId()).thenReturn("gc-1");
        when(resourceId.getVersion()).thenReturn(1);

        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(resourceId));

        GroupConversation conversation = new GroupConversation();
        IResourceStorage.IResource<GroupConversation> resource = mock(IResourceStorage.IResource.class);
        when(resource.getData()).thenReturn(conversation);
        when(storage.read("gc-1", 1)).thenReturn(resource);

        List<GroupConversation> result = store.listByGroupId("group-1", 0, 10);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("listByGroupId — returns empty for no matches")
    void listByGroupIdEmpty() throws Exception {
        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());

        List<GroupConversation> result = store.listByGroupId("group-1", 0, 10);
        assertTrue(result.isEmpty());
    }
}
