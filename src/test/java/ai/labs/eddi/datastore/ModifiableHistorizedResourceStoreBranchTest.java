/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("ModifiableHistorizedResourceStore — Branch Coverage Tests")
class ModifiableHistorizedResourceStoreBranchTest {

    @Mock
    private IResourceStorage<String> resourceStorage;

    private ModifiableHistorizedResourceStore<String> store;

    @BeforeEach
    void setUp() {
        openMocks(this);
        store = new ModifiableHistorizedResourceStore<>(resourceStorage);
    }

    @Nested
    @DisplayName("set method")
    class SetMethod {

        @Test
        @DisplayName("null id throws IllegalArgumentException")
        void nullIdThrows() {
            assertThrows(IllegalArgumentException.class, () -> store.set(null, 1, "content"));
        }

        @Test
        @DisplayName("null version throws IllegalArgumentException")
        void nullVersionThrows() {
            assertThrows(IllegalArgumentException.class, () -> store.set("id1", null, "content"));
        }

        @Test
        @DisplayName("null content throws IllegalArgumentException")
        void nullContentThrows() {
            assertThrows(IllegalArgumentException.class, () -> store.set("id1", 1, null));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("resource found — updates current resource")
        void resourceFoundUpdates() throws Exception {
            IResourceStorage.IResource<String> resource = mock(IResourceStorage.IResource.class);
            when(resourceStorage.read("id1", 1)).thenReturn(resource);

            IResourceStorage.IResource<String> newResource = mock(IResourceStorage.IResource.class);
            when(resourceStorage.newResource("id1", 1, "new-content")).thenReturn(newResource);

            Integer result = store.set("id1", 1, "new-content");

            assertEquals(1, result);
            verify(resourceStorage).store(newResource);
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("resource not found, historyLatest exists and not deleted — updates history")
        void resourceNotFoundHistoryExists() throws Exception {
            when(resourceStorage.read("id1", 1)).thenReturn(null);

            IResourceStorage.IHistoryResource<String> historyRes = mock(IResourceStorage.IHistoryResource.class);
            when(historyRes.isDeleted()).thenReturn(false);
            when(historyRes.getVersion()).thenReturn(2);
            when(resourceStorage.readHistoryLatest("id1")).thenReturn(historyRes);

            IResourceStorage.IResource<String> newResource = mock(IResourceStorage.IResource.class);
            when(resourceStorage.newResource("id1", 1, "content")).thenReturn(newResource);

            IResourceStorage.IHistoryResource<String> newHistoryRes = mock(IResourceStorage.IHistoryResource.class);
            when(resourceStorage.newHistoryResourceFor(newResource, false)).thenReturn(newHistoryRes);

            Integer result = store.set("id1", 1, "content");

            assertEquals(1, result);
            verify(resourceStorage).store(newHistoryRes);
        }

        @Test
        @DisplayName("resource not found, no history — throws ResourceNotFoundException")
        void resourceNotFoundNoHistory() {
            when(resourceStorage.read("id1", 1)).thenReturn(null);
            when(resourceStorage.readHistoryLatest("id1")).thenReturn(null);

            assertThrows(IResourceStore.ResourceNotFoundException.class, () -> store.set("id1", 1, "content"));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("resource not found, history deleted — throws ResourceNotFoundException")
        void resourceNotFoundHistoryDeleted() {
            when(resourceStorage.read("id1", 1)).thenReturn(null);

            IResourceStorage.IHistoryResource<String> historyRes = mock(IResourceStorage.IHistoryResource.class);
            when(historyRes.isDeleted()).thenReturn(true);
            when(resourceStorage.readHistoryLatest("id1")).thenReturn(historyRes);

            assertThrows(IResourceStore.ResourceNotFoundException.class, () -> store.set("id1", 1, "content"));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("resource not found, version > history version — throws ResourceNotFoundException")
        void versionGreaterThanHistory() {
            when(resourceStorage.read("id1", 5)).thenReturn(null);

            IResourceStorage.IHistoryResource<String> historyRes = mock(IResourceStorage.IHistoryResource.class);
            when(historyRes.isDeleted()).thenReturn(false);
            when(historyRes.getVersion()).thenReturn(3);
            when(resourceStorage.readHistoryLatest("id1")).thenReturn(historyRes);

            assertThrows(IResourceStore.ResourceNotFoundException.class, () -> store.set("id1", 5, "content"));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("IOException during resource creation wraps in ResourceStoreException")
        void ioExceptionWraps() throws Exception {
            when(resourceStorage.read("id1", 1)).thenReturn(null);

            IResourceStorage.IHistoryResource<String> historyRes = mock(IResourceStorage.IHistoryResource.class);
            when(historyRes.isDeleted()).thenReturn(false);
            when(historyRes.getVersion()).thenReturn(2);
            when(resourceStorage.readHistoryLatest("id1")).thenReturn(historyRes);

            when(resourceStorage.newResource("id1", 1, "content"))
                    .thenThrow(new IOException("io error"));

            assertThrows(IResourceStore.ResourceStoreException.class, () -> store.set("id1", 1, "content"));
        }
    }

    @Nested
    @DisplayName("create method")
    class CreateMethod {

        @Test
        @DisplayName("null id throws IllegalArgumentException")
        void nullIdThrows() {
            assertThrows(IllegalArgumentException.class, () -> store.create(null, 1, "content"));
        }

        @Test
        @DisplayName("null version throws IllegalArgumentException")
        void nullVersionThrows() {
            assertThrows(IllegalArgumentException.class, () -> store.create("id1", null, "content"));
        }

        @Test
        @DisplayName("null content throws IllegalArgumentException")
        void nullContentThrows() {
            assertThrows(IllegalArgumentException.class, () -> store.create("id1", 1, null));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("successful create stores resource and returns id")
        void successfulCreate() throws Exception {
            IResourceStorage.IResource<String> resource = mock(IResourceStorage.IResource.class);
            when(resource.getId()).thenReturn("id1");
            when(resource.getVersion()).thenReturn(1);
            when(resourceStorage.newResource("id1", 1, "content")).thenReturn(resource);

            IResourceStore.IResourceId result = store.create("id1", 1, "content");

            assertNotNull(result);
            assertEquals("id1", result.getId());
            verify(resourceStorage).store(resource);
        }

        @Test
        @DisplayName("IOException wraps in ResourceStoreException")
        void ioExceptionWraps() throws Exception {
            when(resourceStorage.newResource("id1", 1, "content"))
                    .thenThrow(new IOException("io error"));

            assertThrows(IResourceStore.ResourceStoreException.class, () -> store.create("id1", 1, "content"));
        }
    }

    @Nested
    @DisplayName("createNew method")
    class CreateNewMethod {

        @Test
        @DisplayName("null id throws IllegalArgumentException")
        void nullIdThrows() {
            assertThrows(IllegalArgumentException.class, () -> store.createNew(null, 1, "content"));
        }

        @Test
        @DisplayName("null version throws IllegalArgumentException")
        void nullVersionThrows() {
            assertThrows(IllegalArgumentException.class, () -> store.createNew("id1", null, "content"));
        }

        @Test
        @DisplayName("null content throws IllegalArgumentException")
        void nullContentThrows() {
            assertThrows(IllegalArgumentException.class, () -> store.createNew("id1", 1, null));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("successful createNew calls createNew on storage")
        void successfulCreateNew() throws Exception {
            IResourceStorage.IResource<String> resource = mock(IResourceStorage.IResource.class);
            when(resource.getId()).thenReturn("id1");
            when(resource.getVersion()).thenReturn(1);
            when(resourceStorage.newResource("id1", 1, "content")).thenReturn(resource);

            IResourceStore.IResourceId result = store.createNew("id1", 1, "content");

            assertNotNull(result);
            verify(resourceStorage).createNew(resource);
        }

        @Test
        @DisplayName("IOException wraps in ResourceStoreException")
        void ioExceptionWraps() throws Exception {
            when(resourceStorage.newResource("id1", 1, "content"))
                    .thenThrow(new IOException("io error"));

            assertThrows(IResourceStore.ResourceStoreException.class, () -> store.createNew("id1", 1, "content"));
        }
    }
}
