/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.ISharedArtifactStore.ArtifactGoneException;
import ai.labs.eddi.configs.groups.model.SharedArtifact;
import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * I17 — {@link SharedArtifactStore}: the version CAS goes through the numeric
 * {@code storeIfFieldEquals} overload (never an unconditional store), filters
 * are anchored and re-checked in Java, and the GDPR erasure sweep follows the
 * group-conversation store's page/re-check/fail-loud contract.
 *
 * @author tests
 */
@SuppressWarnings("unchecked")
class SharedArtifactStoreTest {

    private IResourceStorage<SharedArtifact> storage;
    private SharedArtifactStore store;

    @BeforeEach
    void setUp() {
        IResourceStorageFactory storageFactory = mock(IResourceStorageFactory.class);
        IDocumentBuilder documentBuilder = mock(IDocumentBuilder.class);
        storage = mock(IResourceStorage.class);
        // Must mirror the production create() call EXACTLY, index-hint varargs
        // included — a mismatch silently leaves the internal storage null.
        when(storageFactory.create(eq("sharedartifacts"), eq(documentBuilder), eq(SharedArtifact.class),
                eq("groupConversationId"), eq("ownerUserId"))).thenReturn(storage);
        store = new SharedArtifactStore(storageFactory, documentBuilder);
    }

    private static SharedArtifact artifact(String id, String gcId, String owner, long version) {
        var a = new SharedArtifact();
        a.setId(id);
        a.setGroupConversationId(gcId);
        a.setOwnerUserId(owner);
        a.setName("draft");
        a.setVersion(version);
        return a;
    }

    private IResourceStorage.IResource<SharedArtifact> resource(String id, SharedArtifact data) throws IOException {
        IResourceStorage.IResource<SharedArtifact> resource = mock(IResourceStorage.IResource.class);
        when(resource.getId()).thenReturn(id);
        when(resource.getData()).thenReturn(data);
        return resource;
    }

    private IResourceStore.IResourceId resourceId(String id) {
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

    // =================================================================
    // CAS
    // =================================================================

    @Test
    @DisplayName("updateIfVersion goes through the NUMERIC CAS overload, never an unconditional store")
    void updateIfVersion_usesNumericCas() throws Exception {
        var a = artifact("a-1", "gc-1", "user-1", 3);
        IResourceStorage.IResource<SharedArtifact> writeResource = resource("a-1", a);
        when(storage.newResource("a-1", 1, a)).thenReturn(writeResource);

        store.updateIfVersion(a, 2);

        verify(storage).storeIfFieldEquals(writeResource, "version", 2L);
        verify(storage, never()).store(any(IResourceStorage.IResource.class));
        verify(storage, never()).storeIfFieldEquals(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a CAS conflict propagates as ResourceModifiedException (retry), a gone document as ArtifactGoneException")
    void updateIfVersion_distinguishesConflictFromGone() throws Exception {
        var a = artifact("a-1", "gc-1", "user-1", 3);
        IResourceStorage.IResource<SharedArtifact> writeResource = resource("a-1", a);
        when(storage.newResource("a-1", 1, a)).thenReturn(writeResource);

        doThrow(new IResourceStore.ResourceModifiedException("conflict"))
                .when(storage).storeIfFieldEquals(writeResource, "version", 2L);
        assertThrows(IResourceStore.ResourceModifiedException.class, () -> store.updateIfVersion(a, 2));

        doThrow(new IResourceStore.ResourceNotFoundException("gone"))
                .when(storage).storeIfFieldEquals(writeResource, "version", 2L);
        assertThrows(ArtifactGoneException.class, () -> store.updateIfVersion(a, 2));
    }

    // =================================================================
    // read / create
    // =================================================================

    @Test
    @DisplayName("read maps a missing document to a not-found whose message does NOT embed the caller-supplied id")
    void readNotFound_messageDoesNotEmbedTheId() {
        when(storage.read("attacker<script>", 1)).thenReturn(null);

        var ex = assertThrows(IResourceStore.ResourceNotFoundException.class, () -> store.read("attacker<script>"));

        assertFalse(ex.getMessage().contains("attacker"), ex.getMessage());
    }

    @Test
    @DisplayName("create stores, adopts the assigned id, and returns it")
    void create_assignsId() throws Exception {
        var a = artifact(null, "gc-1", "user-1", 1);
        IResourceStorage.IResource<SharedArtifact> resource = resource("new-id", a);
        when(storage.newResource(a)).thenReturn(resource);

        String id = store.create(a);

        verify(storage).store(resource);
        assertEquals("new-id", id);
        assertEquals("new-id", a.getId());
    }

    // =================================================================
    // listing
    // =================================================================

    @Test
    @DisplayName("listByGroupConversationId anchors the filter and re-checks by exact match in Java")
    void list_anchorsAndRechecks() throws Exception {
        var mine = artifact("a-1", "gc-1", "user-1", 1);
        // The unanchored-regex nightmare: an id that CONTAINS the wanted id.
        var other = artifact("a-2", "gc-12", "user-2", 1);
        var mineResource = resource("a-1", mine);
        var otherResource = resource("a-2", other);
        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), eq("createdAt"), eq(0), anyInt()))
                .thenReturn(List.of(resourceId("a-1"), resourceId("a-2")));
        when(storage.read("a-1", 1)).thenReturn(mineResource);
        when(storage.read("a-2", 1)).thenReturn(otherResource);

        List<SharedArtifact> result = store.listByGroupConversationId("gc-1");

        assertEquals(1, result.size(), "the regex only narrows; equality decides");
        assertEquals("a-1", result.get(0).getId());

        var captor = ArgumentCaptor.forClass(IResourceFilter.QueryFilters[].class);
        verify(storage).findResources(captor.capture(), eq("createdAt"), eq(0), anyInt());
        assertEquals("^gc-1$", captor.getValue()[0].getQueryFilters().get(0).getFilter(), "filters must be anchored");
    }

    @Test
    @DisplayName("the listing honors the interface contract: oldest first, whatever order the backend returns")
    void list_sortsOldestFirst() throws Exception {
        var newer = artifact("a-new", "gc-1", "user-1", 1);
        newer.setCreatedAt(Instant.parse("2026-02-02T00:00:00Z"));
        var older = artifact("a-old", "gc-1", "user-1", 1);
        older.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        var newerResource = resource("a-new", newer);
        var olderResource = resource("a-old", older);
        // Both backends sort DESC on the sort field — the store must re-sort.
        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), eq("createdAt"), eq(0), anyInt()))
                .thenReturn(List.of(resourceId("a-new"), resourceId("a-old")));
        when(storage.read("a-new", 1)).thenReturn(newerResource);
        when(storage.read("a-old", 1)).thenReturn(olderResource);

        List<SharedArtifact> result = store.listByGroupConversationId("gc-1");

        assertEquals(List.of("a-old", "a-new"), result.stream().map(SharedArtifact::getId).toList());
    }

    @Test
    @DisplayName("an id that fails the SAFE_ID gate is never interpolated into a query")
    void list_unsafeId_neverQueries() throws Exception {
        assertTrue(store.listByGroupConversationId("gc.*injection").isEmpty());
        assertTrue(store.listByGroupConversationId(null).isEmpty());
        verify(storage, never()).findResources(any(), anyString(), anyInt(), anyInt());
    }

    // =================================================================
    // GDPR erasure
    // =================================================================

    @Test
    @DisplayName("deleteAllForUser pages from offset 0, re-checks ownership exactly, and skips near-miss users")
    void erasure_exactMatchDecides() throws Exception {
        var owned = artifact("a-1", "gc-1", "user-1", 1);
        var nearMiss = artifact("a-2", "gc-2", "user-10", 1);
        var ownedResource = resource("a-1", owned);
        var nearMissResource = resource("a-2", nearMiss);
        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), eq("createdAt"), eq(0), eq(IResourceStorage.MAX_RESULT_LIMIT)))
                .thenReturn(List.of(resourceId("a-1"), resourceId("a-2")))
                .thenReturn(List.of());
        when(storage.read("a-1", 1)).thenReturn(ownedResource);
        when(storage.read("a-2", 1)).thenReturn(nearMissResource);

        long deleted = store.deleteAllForUser("user-1");

        assertEquals(1, deleted);
        verify(storage).removeAllPermanently("a-1");
        verify(storage, never()).removeAllPermanently("a-2");
    }

    @Test
    @DisplayName("an owned row that cannot be read fails the erasure loudly — never a partial success")
    void erasure_unreadableRow_failsLoudly() throws Exception {
        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), eq("createdAt"), eq(0), eq(IResourceStorage.MAX_RESULT_LIMIT)))
                .thenReturn(List.of(resourceId("a-1")));
        IResourceStorage.IResource<SharedArtifact> broken = mock(IResourceStorage.IResource.class);
        when(broken.getId()).thenReturn("a-1");
        when(broken.getData()).thenThrow(new IOException("corrupt"));
        when(storage.read("a-1", 1)).thenReturn(broken);

        var thrown = assertThrows(IResourceStore.ResourceStoreException.class, () -> store.deleteAllForUser("user-1"));

        assertTrue(thrown.getMessage().contains("Erasure incomplete"), thrown.getMessage());
    }

    @Test
    @DisplayName("the erasure filter is anchored AND regex-escaped — a metacharacter user id cannot widen the sweep")
    void erasure_escapesTheUserId() throws Exception {
        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), eq("createdAt"), eq(0), eq(IResourceStorage.MAX_RESULT_LIMIT)))
                .thenReturn(List.of());

        store.deleteAllForUser("user.1+x");

        var captor = ArgumentCaptor.forClass(IResourceFilter.QueryFilters[].class);
        verify(storage).findResources(captor.capture(), eq("createdAt"), eq(0), eq(IResourceStorage.MAX_RESULT_LIMIT));
        assertEquals("^user\\.1\\+x$", captor.getValue()[0].getQueryFilters().get(0).getFilter());
    }

    // =================================================================
    // cascade delete
    // =================================================================

    @Test
    @DisplayName("deleteByGroupConversationId removes every artifact of the discussion and reports the count")
    void cascadeDelete_removesAll() throws Exception {
        var a1 = artifact("a-1", "gc-1", "user-1", 1);
        var a2 = artifact("a-2", "gc-1", "user-1", 1);
        var r1 = resource("a-1", a1);
        var r2 = resource("a-2", a2);
        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), eq("createdAt"), eq(0), anyInt()))
                .thenReturn(List.of(resourceId("a-1"), resourceId("a-2")))
                .thenReturn(List.of());
        when(storage.read("a-1", 1)).thenReturn(r1);
        when(storage.read("a-2", 1)).thenReturn(r2);

        assertEquals(2, store.deleteByGroupConversationId("gc-1"));

        verify(storage).removeAllPermanently("a-1");
        verify(storage).removeAllPermanently("a-2");
    }

    @Test
    @DisplayName("a row the backend will not dislodge is counted once and ends the loop — no spin, no inflated count")
    void cascadeDelete_undeletableRow_noSpinNoOvercount() throws Exception {
        var stuck = artifact("a-1", "gc-1", "user-1", 1);
        var r1 = resource("a-1", stuck);
        // removeAllPermanently "succeeds" but the row keeps appearing in listings
        // — the backend path that motivated the processed-set guard.
        when(storage.findResources(any(IResourceFilter.QueryFilters[].class), eq("createdAt"), eq(0), anyInt()))
                .thenReturn(List.of(resourceId("a-1")));
        when(storage.read("a-1", 1)).thenReturn(r1);

        assertEquals(1, store.deleteByGroupConversationId("gc-1"),
                "the count must reflect distinct artifacts, not delete attempts");

        verify(storage, times(1)).removeAllPermanently("a-1");
    }
}
