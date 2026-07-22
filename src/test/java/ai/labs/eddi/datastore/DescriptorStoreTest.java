/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.ArrayList;
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

    // ==================== limit semantics ====================

    /**
     * The paging arguments {@code readDescriptors} actually hands to the storage
     * layer. These are what the contract in {@link IDescriptorStore} is about — a
     * caller asking for "everything" must not have its request quietly rewritten
     * into a 20-row page.
     */
    private record Paging(int skip, int limit) {
    }

    private Paging capturePaging(Integer index, Integer limit) throws Exception {
        when(resourceStorage.findResources(any(IResourceFilter.QueryFilters[].class), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());

        store.readDescriptors("agents", null, index, limit, false);

        ArgumentCaptor<Integer> skipCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(resourceStorage).findResources(any(IResourceFilter.QueryFilters[].class), anyString(), skipCaptor.capture(),
                limitCaptor.capture());
        return new Paging(skipCaptor.getValue(), limitCaptor.getValue());
    }

    @Nested
    @DisplayName("readDescriptors — limit semantics")
    class LimitSemantics {

        @Test
        @DisplayName("null limit — caller has no opinion, gets the default page")
        void nullLimitUsesDefault() throws Exception {
            assertEquals(IDescriptorStore.DEFAULT_LIMIT, capturePaging(0, null).limit());
        }

        @Test
        @DisplayName("NO_LIMIT — means unlimited, NOT the default page size")
        void noLimitMeansUnlimited() throws Exception {
            Paging paging = capturePaging(0, IDescriptorStore.NO_LIMIT);

            assertEquals(IResourceStorage.MAX_RESULT_LIMIT, paging.limit());
            assertEquals(0, paging.skip());
            // Regression guard: this used to silently resolve to 20, truncating
            // every caller that passed 0 meaning "give me everything".
            assertNotEquals(IDescriptorStore.DEFAULT_LIMIT, paging.limit());
        }

        @Test
        @DisplayName("negative limit — treated as unlimited, like NO_LIMIT")
        void negativeLimitMeansUnlimited() throws Exception {
            assertEquals(IResourceStorage.MAX_RESULT_LIMIT, capturePaging(0, -5).limit());
        }

        @Test
        @DisplayName("positive limit — honoured verbatim")
        void positiveLimitIsHonoured() throws Exception {
            assertEquals(50, capturePaging(0, 50).limit());
        }

        @Test
        @DisplayName("oversized limit — clamped to the safety ceiling")
        void oversizedLimitIsClamped() throws Exception {
            assertEquals(IResourceStorage.MAX_RESULT_LIMIT, capturePaging(0, IResourceStorage.MAX_RESULT_LIMIT + 1).limit());
        }

        @Test
        @DisplayName("index — skip is index * effective limit")
        void skipIsIndexTimesEffectiveLimit() throws Exception {
            assertEquals(100, capturePaging(2, 50).skip());
        }

        @Test
        @DisplayName("results are not post-truncated to the default page size")
        void returnsMoreThanDefaultPageSize() throws Exception {
            int count = IDescriptorStore.DEFAULT_LIMIT + 5;
            List<IResourceStore.IResourceId> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                IResourceStore.IResourceId id = mock(IResourceStore.IResourceId.class);
                when(id.getId()).thenReturn("res-" + i);
                when(id.getVersion()).thenReturn(1);
                ids.add(id);

                IResourceStorage.IResource<String> resource = mock(IResourceStorage.IResource.class);
                when(resource.getData()).thenReturn("data-" + i);
                when(resourceStorage.read("res-" + i, 1)).thenReturn(resource);
                when(resourceStorage.getCurrentVersion("res-" + i)).thenReturn(1);
            }
            when(resourceStorage.findResources(any(IResourceFilter.QueryFilters[].class), anyString(), anyInt(), anyInt()))
                    .thenReturn(ids);

            List<String> result = store.readDescriptors("agents", null, 0, IDescriptorStore.NO_LIMIT, false);

            assertEquals(count, result.size());
        }
    }

    @Nested
    @DisplayName("limit resolution helpers")
    class LimitResolution {

        @Test
        @DisplayName("resolveDescriptorLimit — null is the only value meaning 'default page'")
        void resolveDescriptorLimitNull() {
            assertEquals(IDescriptorStore.DEFAULT_LIMIT, IDescriptorStore.resolveDescriptorLimit(null));
            assertEquals(IResourceStorage.MAX_RESULT_LIMIT, IDescriptorStore.resolveDescriptorLimit(IDescriptorStore.NO_LIMIT));
            assertEquals(7, IDescriptorStore.resolveDescriptorLimit(7));
        }

        @Test
        @DisplayName("resolveLimit — never returns a non-positive or above-ceiling value")
        void resolveLimitIsAlwaysUsable() {
            assertEquals(IResourceStorage.MAX_RESULT_LIMIT, IResourceStorage.resolveLimit(0));
            assertEquals(IResourceStorage.MAX_RESULT_LIMIT, IResourceStorage.resolveLimit(-1));
            assertEquals(IResourceStorage.MAX_RESULT_LIMIT, IResourceStorage.resolveLimit(Integer.MIN_VALUE));
            assertEquals(IResourceStorage.MAX_RESULT_LIMIT, IResourceStorage.resolveLimit(Integer.MAX_VALUE));
            assertEquals(1, IResourceStorage.resolveLimit(1));
        }
    }

    @Test
    @DisplayName("readDescriptors — includeDeleted=false constrains `deleted` to false")
    void readDescriptorsExcludesDeleted() throws Exception {
        when(resourceStorage.findResources(any(IResourceFilter.QueryFilters[].class), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());

        store.readDescriptors("agents", null, 0, 20, false);

        var captor = ArgumentCaptor.forClass(IResourceFilter.QueryFilters[].class);
        verify(resourceStorage).findResources(captor.capture(), anyString(), anyInt(), anyInt());
        assertTrue(hasDeletedFilter(captor.getValue()), "expected a `deleted` constraint when includeDeleted=false");
    }

    @Test
    @DisplayName("readDescriptors — includeDeleted=true drops the `deleted` constraint entirely")
    void readDescriptorsIncludesDeleted() throws Exception {
        when(resourceStorage.findResources(any(IResourceFilter.QueryFilters[].class), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());

        store.readDescriptors("agents", null, 0, 20, true);

        // It previously added eq(deleted, true), which matched ONLY soft-deleted rows —
        // so a caller scanning with false and purging with true saw disjoint sets.
        var captor = ArgumentCaptor.forClass(IResourceFilter.QueryFilters[].class);
        verify(resourceStorage).findResources(captor.capture(), anyString(), anyInt(), anyInt());
        assertFalse(hasDeletedFilter(captor.getValue()), "includeDeleted=true must not constrain on `deleted` at all");
    }

    private static boolean hasDeletedFilter(IResourceFilter.QueryFilters[] filters) {
        for (IResourceFilter.QueryFilters group : filters) {
            for (IResourceFilter.QueryFilter f : group.getQueryFilters()) {
                if ("deleted".equals(f.getField())) {
                    return true;
                }
            }
        }
        return false;
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
