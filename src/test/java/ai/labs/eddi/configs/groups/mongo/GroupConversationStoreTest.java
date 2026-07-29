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

    @Test
    @DisplayName("read — the not-found message never embeds the caller-supplied id")
    void readNotFound_messageDoesNotEmbedTheId() {
        // This message is surfaced to clients by ResourceNotFoundExceptionMapper (which
        // echoes getLocalizedMessage()) and by every REST handler that forwards it, so
        // an
        // id in here is a reflected-value sink (CodeQL) on every group endpoint.
        String payload = "<script>alert(1)</script>";
        when(storage.read(payload, 1)).thenReturn(null);

        var thrown = assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> store.read(payload));

        assertFalse(String.valueOf(thrown.getMessage()).contains(payload),
                "the caller-supplied id must not appear in the exception message");
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

    // ==================== compareAndSetState ====================

    @Test
    @DisplayName("compareAndSetState — transitions and returns true when state matches")
    void compareAndSetState_matches() throws Exception {
        GroupConversation gc = new GroupConversation();
        gc.setState(GroupConversation.GroupConversationState.COMPLETED);
        IResourceStorage.IResource<GroupConversation> readResource = mock(IResourceStorage.IResource.class);
        when(readResource.getData()).thenReturn(gc);
        when(storage.read("gc-1", 1)).thenReturn(readResource);
        IResourceStorage.IResource<GroupConversation> writeResource = mock(IResourceStorage.IResource.class);
        when(storage.newResource(eq("gc-1"), eq(1), any(GroupConversation.class))).thenReturn(writeResource);

        boolean result = store.compareAndSetState("gc-1",
                GroupConversation.GroupConversationState.COMPLETED,
                GroupConversation.GroupConversationState.IN_PROGRESS);

        assertTrue(result);
        // read() returns the same object that compareAndSetState mutates
        assertEquals(GroupConversation.GroupConversationState.IN_PROGRESS, gc.getState());
        assertNotNull(gc.getLastModified());
        // The transition must be a CONDITIONAL write (expected state still persisted),
        // not
        // an unconditional store — otherwise two racing callers could both pass the
        // read-check and both write.
        verify(storage).storeIfFieldEquals(writeResource, "state", "COMPLETED");
        verify(storage, never()).store(any());
    }

    @Test
    @DisplayName("compareAndSetState — returns false when the conditional write loses the race")
    void compareAndSetState_lostRace_returnsFalse() throws Exception {
        GroupConversation gc = new GroupConversation();
        gc.setState(GroupConversation.GroupConversationState.COMPLETED);
        IResourceStorage.IResource<GroupConversation> readResource = mock(IResourceStorage.IResource.class);
        when(readResource.getData()).thenReturn(gc);
        when(storage.read("gc-1", 1)).thenReturn(readResource);
        IResourceStorage.IResource<GroupConversation> writeResource = mock(IResourceStorage.IResource.class);
        when(storage.newResource(eq("gc-1"), eq(1), any(GroupConversation.class))).thenReturn(writeResource);
        // Another writer moved the state between our read and our conditional write.
        doThrow(new IResourceStore.ResourceModifiedException("state changed"))
                .when(storage).storeIfFieldEquals(eq(writeResource), eq("state"), anyString());

        boolean result = store.compareAndSetState("gc-1",
                GroupConversation.GroupConversationState.COMPLETED,
                GroupConversation.GroupConversationState.IN_PROGRESS);

        assertFalse(result, "a lost CAS must report false, not silently overwrite the winner");
    }

    @Test
    @DisplayName("compareAndSetState — returns false and does not update when state differs")
    void compareAndSetState_mismatch() throws Exception {
        GroupConversation gc = new GroupConversation();
        gc.setState(GroupConversation.GroupConversationState.FAILED);
        IResourceStorage.IResource<GroupConversation> readResource = mock(IResourceStorage.IResource.class);
        when(readResource.getData()).thenReturn(gc);
        when(storage.read("gc-1", 1)).thenReturn(readResource);

        boolean result = store.compareAndSetState("gc-1",
                GroupConversation.GroupConversationState.COMPLETED,
                GroupConversation.GroupConversationState.CLOSED);

        assertFalse(result);
        assertEquals(GroupConversation.GroupConversationState.FAILED, gc.getState());
        verify(storage, never()).store(any());
    }

    @Test
    @DisplayName("compareAndSetState — throws ResourceNotFoundException when missing")
    void compareAndSetState_notFound() {
        when(storage.read("missing", 1)).thenReturn(null);

        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> store.compareAndSetState("missing",
                        GroupConversation.GroupConversationState.COMPLETED,
                        GroupConversation.GroupConversationState.CLOSED));
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

    // ==================== G15: user-scoped erasure ====================

    private static IResourceStore.IResourceId resourceId(String id) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        };
    }

    private IResourceStorage.IResource<GroupConversation> resourceFor(String userId) throws IOException {
        GroupConversation gc = new GroupConversation();
        gc.setUserId(userId);
        gc.setOriginalQuestion("something the user actually typed");
        IResourceStorage.IResource<GroupConversation> resource = mock(IResourceStorage.IResource.class);
        when(resource.getData()).thenReturn(gc);
        return resource;
    }

    /**
     * A GroupConversation holds the user's id next to the verbatim transcript of
     * the discussion, and the erasure cascade had no way to reach it at all.
     */
    @Test
    @DisplayName("deleteAllForUser — removes the user's transcripts")
    void deleteAllForUserRemovesTranscripts() throws Exception {
        // Built up front: Mockito rejects a when(...) nested inside another when(...).
        var first = resourceFor("user-1");
        var second = resourceFor("user-1");
        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), eq("lastModified"), eq(0), anyInt()))
                .thenReturn(List.of(resourceId("gc-1"), resourceId("gc-2")));
        when(storage.read("gc-1", 1)).thenReturn(first);
        when(storage.read("gc-2", 1)).thenReturn(second);

        assertEquals(2, store.deleteAllForUser("user-1"));

        verify(storage).removeAllPermanently("gc-1");
        verify(storage).removeAllPermanently("gc-2");
    }

    /**
     * findResources caps a page at MAX_RESULT_LIMIT, and a limit &lt; 1 resolves to
     * that cap rather than meaning "unbounded". A single query would therefore
     * erase at most one page and report success, leaving the rest of the user's
     * transcripts in the store — a partial erasure claiming to be complete. The
     * erasure must page until a pass finds nothing.
     */
    @Test
    @DisplayName("deleteAllForUser — keeps paging until no transcripts remain")
    void deleteAllForUserPagesUntilExhausted() throws Exception {
        var a = resourceFor("user-1");
        var b = resourceFor("user-1");
        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), eq("lastModified"), eq(0), anyInt()))
                .thenReturn(List.of(resourceId("gc-1")))
                .thenReturn(List.of(resourceId("gc-2")))
                .thenReturn(List.of());
        when(storage.read("gc-1", 1)).thenReturn(a);
        when(storage.read("gc-2", 1)).thenReturn(b);

        assertEquals(2, store.deleteAllForUser("user-1"), "both pages must be erased, not just the first");

        verify(storage).removeAllPermanently("gc-1");
        verify(storage).removeAllPermanently("gc-2");
    }

    /**
     * A candidate that could not be processed must not be reported as a completed
     * erasure. The query always runs at offset 0, so an undeletable row stays in
     * the first page and would otherwise make a later pass look like "no new work"
     * — ending the sweep with a partial count that claims success.
     */
    @Test
    @DisplayName("deleteAllForUser — fails loudly when a matching transcript cannot be deleted")
    void deleteAllForUserFailsOnUndeletableRow() throws Exception {
        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), eq("lastModified"), eq(0), anyInt()))
                .thenReturn(List.of(resourceId("gc-1")));
        // The candidate's document cannot be deserialized, so we can never establish
        // whether it belonged to this user — and therefore cannot claim it was erased.
        IResourceStorage.IResource<GroupConversation> unreadable = mock(IResourceStorage.IResource.class);
        when(unreadable.getData()).thenThrow(new IOException("corrupt document"));
        when(storage.read("gc-1", 1)).thenReturn(unreadable);

        var thrown = assertThrows(IResourceStore.ResourceStoreException.class, () -> store.deleteAllForUser("user-1"));
        assertTrue(thrown.getMessage().contains("Erasure incomplete"), thrown.getMessage());
        verify(storage, never()).removeAllPermanently(anyString());
    }

    /**
     * If every candidate is rejected by the exact-match re-check, re-querying would
     * return the same page forever. The loop must stop rather than spin.
     */
    @Test
    @DisplayName("deleteAllForUser — stops instead of spinning when a pass deletes nothing")
    void deleteAllForUserStopsWhenNoProgress() throws Exception {
        var foreign = resourceFor("someone-else");
        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), eq("lastModified"), eq(0), anyInt()))
                .thenReturn(List.of(resourceId("gc-9")));
        when(storage.read("gc-9", 1)).thenReturn(foreign);

        assertEquals(0, store.deleteAllForUser("user-1"));

        verify(storage, never()).removeAllPermanently(anyString());
        verify(storage, atMost(3)).findResources(any(IResourceFilter.QueryFilters[].class), anyString(), anyInt(), anyInt());
    }

    /**
     * The storage layer turns a String filter into a regex whose metacharacter
     * handling differs between MongoDB and PostgreSQL. Deleting on the regex result
     * alone could wipe another user's transcripts, so the userId is re-checked with
     * an exact comparison first.
     */
    @Test
    @DisplayName("deleteAllForUser — never deletes a transcript whose userId is not an exact match")
    void deleteAllForUserSkipsInexactMatches() throws Exception {
        var mine = resourceFor("user-1");
        var somebodyElses = resourceFor("user-10"); // over-broad regex hit
        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), eq("lastModified"), eq(0), anyInt()))
                .thenReturn(List.of(resourceId("gc-1"), resourceId("gc-2")));
        when(storage.read("gc-1", 1)).thenReturn(mine);
        when(storage.read("gc-2", 1)).thenReturn(somebodyElses);

        assertEquals(1, store.deleteAllForUser("user-1"));

        verify(storage).removeAllPermanently("gc-1");
        verify(storage, never()).removeAllPermanently("gc-2");
    }

    @Test
    @DisplayName("deleteAllForUser — anchors and escapes the userId filter")
    void deleteAllForUserAnchorsTheFilter() throws Exception {
        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), eq("lastModified"), eq(0), anyInt()))
                .thenReturn(List.of());

        store.deleteAllForUser("user.1+x");

        ArgumentCaptor<IResourceFilter.QueryFilters[]> captor = ArgumentCaptor.forClass(IResourceFilter.QueryFilters[].class);
        // an explicit full page, not 0 — a limit < 1 resolves to the same cap but
        // reads like "unbounded", which is what made the erasure stop after one page
        verify(storage).findResources(captor.capture(), eq("lastModified"), eq(0), eq(IResourceStorage.MAX_RESULT_LIMIT));
        var queryFilter = captor.getValue()[0].getQueryFilters().getFirst();
        assertEquals("userId", queryFilter.getField());
        assertEquals("^user\\.1\\+x$", queryFilter.getFilter().toString(),
                "the filter must be anchored and its metacharacters escaped, or it matches other users");
    }

    @Test
    @DisplayName("deleteAllForUser — a blank user deletes nothing")
    void deleteAllForBlankUserDeletesNothing() throws Exception {
        assertEquals(0, store.deleteAllForUser(null));
        assertEquals(0, store.deleteAllForUser("   "));
        verify(storage, never()).removeAllPermanently(anyString());
    }
}
