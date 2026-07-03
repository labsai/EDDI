/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.IGroupConversationStore.GroupConversationGoneException;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

    @Test
    @DisplayName("listByGroupId — null groupId returns empty without NPE")
    void listByGroupIdNull() throws Exception {
        List<GroupConversation> result = store.listByGroupId(null, 0, 10);

        assertTrue(result.isEmpty());
        verify(storage, never()).findResources(any(IResourceFilter.QueryFilters[].class), anyString(), anyInt(), anyInt());
    }

    // ==================== findByState ====================

    @Test
    @DisplayName("findByState — state filter value is ANCHORED (^...$) so an id substring cannot match")
    void findByStateAnchorsStateFilter() throws Exception {
        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());

        store.findByState(GroupConversationState.AWAITING_APPROVAL);

        var captor = ArgumentCaptor.forClass(IResourceFilter.QueryFilters[].class);
        verify(storage).findResources(captor.capture(), anyString(), anyInt(), anyInt());
        var stateFilter = captor.getValue()[0].getQueryFilters().stream()
                .filter(f -> "state".equals(f.getField())).findFirst().orElseThrow();
        // Unanchored, a state name could be substring-matched inside another value —
        // the exact-match anchoring is the guarantee under test.
        assertEquals("^AWAITING_APPROVAL$", stateFilter.getFilter());
    }

    @Test
    @DisplayName("findByState — a valid groupId is added as an ANCHORED exact-match filter")
    void findByStateAnchorsGroupIdFilter() throws Exception {
        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());

        store.findByState(GroupConversationState.AWAITING_APPROVAL, "group-1", 100);

        var captor = ArgumentCaptor.forClass(IResourceFilter.QueryFilters[].class);
        verify(storage).findResources(captor.capture(), anyString(), anyInt(), anyInt());
        var groupIdFilter = captor.getValue()[0].getQueryFilters().stream()
                .filter(f -> "groupId".equals(f.getField())).findFirst().orElseThrow();
        // A raw groupId would substring-leak conversations of other groups whose id
        // contains it — anchoring prevents that cross-group leak.
        assertEquals("^group-1$", groupIdFilter.getFilter());
    }

    @Test
    @DisplayName("findByState — a non-id (SAFE_ID-rejected) groupId returns empty WITHOUT hitting the backend")
    void findByStateRejectsUnsafeGroupId() throws Exception {
        // A regex-injection / non-id value must never reach the backend's regex engine.
        List<GroupConversation> result = store.findByState(
                GroupConversationState.AWAITING_APPROVAL, "..*|evil regex", 100);

        assertTrue(result.isEmpty(), "unsafe groupId must yield an honest empty result");
        verify(storage, never()).findResources(any(IResourceFilter.QueryFilters[].class), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("findByState — a store-level read failure on one record is skipped, the scan continues")
    void findByStateContinuesPastStoreException() throws Exception {
        // Two matches: reading the first fails at the storage layer (IOException →
        // ResourceStoreException), the second reads fine. The bad record must not
        // abort the whole scan (backs crash recovery + pending-approvals listing).
        IResourceStore.IResourceId badId = mock(IResourceStore.IResourceId.class);
        when(badId.getId()).thenReturn("gc-bad");
        IResourceStore.IResourceId goodId = mock(IResourceStore.IResourceId.class);
        when(goodId.getId()).thenReturn("gc-good");

        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(badId, goodId));

        // read("gc-bad") wraps this IOException as a ResourceStoreException. The
        // IOException surfaces from IResource.getData() (which declares it) — NOT from
        // storage.read(), which declares no checked exception — so stub getData().
        IResourceStorage.IResource<GroupConversation> badResource = mock(IResourceStorage.IResource.class);
        when(badResource.getData()).thenThrow(new IOException("disk error"));
        when(storage.read("gc-bad", 1)).thenReturn(badResource);

        GroupConversation goodConversation = new GroupConversation();
        IResourceStorage.IResource<GroupConversation> goodResource = mock(IResourceStorage.IResource.class);
        when(goodResource.getData()).thenReturn(goodConversation);
        when(storage.read("gc-good", 1)).thenReturn(goodResource);

        List<GroupConversation> result = store.findByState(GroupConversationState.AWAITING_APPROVAL);

        assertEquals(1, result.size(), "the readable record must still be returned");
        assertEquals("gc-good", result.get(0).getId());
    }

    // ==================== updateIfState (conditional CAS) ====================

    @Test
    @DisplayName("updateIfState — storage ResourceNotFoundException maps to GroupConversationGoneException (404)")
    void updateIfStateGoneWhenDeleted() throws Exception {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        IResourceStorage.IResource<GroupConversation> resource = mock(IResourceStorage.IResource.class);
        when(storage.newResource(eq("gc-1"), anyInt(), eq(gc))).thenReturn(resource);
        // The storage-level CAS reports the row is gone.
        doThrow(new IResourceStore.ResourceNotFoundException("gone"))
                .when(storage).storeIfFieldEquals(eq(resource), eq("state"), anyString());

        assertThrows(GroupConversationGoneException.class,
                () -> store.updateIfState(gc, GroupConversationState.AWAITING_APPROVAL));
    }

    @Test
    @DisplayName("updateIfState — storage ResourceModifiedException propagates as-is (409, not Gone)")
    void updateIfStateModifiedOnMismatch() throws Exception {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        IResourceStorage.IResource<GroupConversation> resource = mock(IResourceStorage.IResource.class);
        when(storage.newResource(eq("gc-1"), anyInt(), eq(gc))).thenReturn(resource);
        doThrow(new IResourceStore.ResourceModifiedException("state changed"))
                .when(storage).storeIfFieldEquals(eq(resource), eq("state"), anyString());

        // A mismatch is a conflict (409), NOT a gone (404) — the distinction must
        // survive the store layer.
        assertThrows(IResourceStore.ResourceModifiedException.class,
                () -> store.updateIfState(gc, GroupConversationState.AWAITING_APPROVAL));
    }

    @Test
    @DisplayName("updateIfState — success delegates to storeIfFieldEquals with the expected state")
    void updateIfStateSuccess() throws Exception {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        IResourceStorage.IResource<GroupConversation> resource = mock(IResourceStorage.IResource.class);
        when(storage.newResource(eq("gc-1"), anyInt(), eq(gc))).thenReturn(resource);

        assertDoesNotThrow(() -> store.updateIfState(gc, GroupConversationState.IN_PROGRESS));
        verify(storage).storeIfFieldEquals(resource, "state", "IN_PROGRESS");
    }
}
