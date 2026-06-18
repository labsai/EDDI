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

/**
 * Additional branch coverage tests for {@link HistorizedResourceStore} focusing
 * on all branches in checkIfFoundAndLatest, read, readIncludingDeleted, update,
 * create, delete.
 */
@DisplayName("HistorizedResourceStore — Additional Branch Coverage")
@SuppressWarnings("unchecked")
class HistorizedResourceStoreBranchCoverageTest {

    @Mock
    private IResourceStorage<String> storage;

    private HistorizedResourceStore<String> store;

    @BeforeEach
    void setUp() {
        openMocks(this);
        store = new HistorizedResourceStore<>(storage);
    }

    // =========================================================
    // createResourceNotFoundException
    // =========================================================

    @Nested
    @DisplayName("createResourceNotFoundException")
    class CreateResourceNotFoundExceptionTests {

        @Test
        @DisplayName("static factory creates exception with formatted message")
        void factoryCreatesException() {
            var ex = HistorizedResourceStore.createResourceNotFoundException("id1", 2);
            assertNotNull(ex);
            assertTrue(ex.getMessage().contains("id1"));
            assertTrue(ex.getMessage().contains("2"));
        }
    }

    // =========================================================
    // update — branches
    // =========================================================

    @Nested
    @DisplayName("update method")
    class UpdateMethod {

        @Test
        @DisplayName("null id throws IllegalArgumentException")
        void nullIdThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> store.update(null, 1, "content"));
        }

        @Test
        @DisplayName("null version throws IllegalArgumentException")
        void nullVersionThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> store.update("id1", null, "content"));
        }

        @Test
        @DisplayName("null content throws IllegalArgumentException")
        void nullContentThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> store.update("id1", 1, null));
        }

        @Test
        @DisplayName("resource not found in current → history null → throws ResourceNotFoundException")
        void resourceNotFoundHistoryNull() {
            when(storage.read("id1", 1)).thenReturn(null);
            when(storage.readHistoryLatest("id1")).thenReturn(null);

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.update("id1", 1, "content"));
        }

        @Test
        @DisplayName("resource not found → history deleted → throws ResourceNotFoundException")
        void resourceNotFoundHistoryDeleted() {
            when(storage.read("id1", 1)).thenReturn(null);

            IResourceStorage.IHistoryResource<String> historyResource = mock(IResourceStorage.IHistoryResource.class);
            when(historyResource.isDeleted()).thenReturn(true);
            when(storage.readHistoryLatest("id1")).thenReturn(historyResource);

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.update("id1", 1, "content"));
        }

        @Test
        @DisplayName("resource not found → version > historyLatest version → throws ResourceNotFoundException")
        void versionGreaterThanHistory() {
            when(storage.read("id1", 5)).thenReturn(null);

            IResourceStorage.IHistoryResource<String> historyResource = mock(IResourceStorage.IHistoryResource.class);
            when(historyResource.isDeleted()).thenReturn(false);
            when(historyResource.getVersion()).thenReturn(3);
            when(storage.readHistoryLatest("id1")).thenReturn(historyResource);

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.update("id1", 5, "content"));
        }

        @Test
        @DisplayName("resource not found → history exists not deleted, version <= history → throws ResourceModifiedException")
        void resourceModified() {
            when(storage.read("id1", 1)).thenReturn(null);

            IResourceStorage.IHistoryResource<String> historyResource = mock(IResourceStorage.IHistoryResource.class);
            when(historyResource.isDeleted()).thenReturn(false);
            when(historyResource.getVersion()).thenReturn(2);
            when(storage.readHistoryLatest("id1")).thenReturn(historyResource);

            assertThrows(IResourceStore.ResourceModifiedException.class,
                    () -> store.update("id1", 1, "content"));
        }

        @Test
        @DisplayName("IOException during newResource wraps in ResourceStoreException")
        void ioExceptionWraps() throws Exception {
            IResourceStorage.IResource<String> currentResource = mock(IResourceStorage.IResource.class);
            when(storage.read("id1", 1)).thenReturn(currentResource);
            when(currentResource.getId()).thenReturn("id1");
            when(currentResource.getVersion()).thenReturn(1);

            IResourceStorage.IHistoryResource<String> historyResource = mock(IResourceStorage.IHistoryResource.class);
            when(storage.newHistoryResourceFor(currentResource, false)).thenReturn(historyResource);

            when(storage.newResource("id1", 2, "content")).thenThrow(new IOException("disk error"));

            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> store.update("id1", 1, "content"));
        }
    }

    // =========================================================
    // create — branches
    // =========================================================

    @Nested
    @DisplayName("create method")
    class CreateMethod {

        @Test
        @DisplayName("null content throws IllegalArgumentException")
        void nullContentThrows() {
            assertThrows(IllegalArgumentException.class, () -> store.create(null));
        }

        @Test
        @DisplayName("IOException during newResource wraps in ResourceStoreException")
        void ioExceptionWraps() throws Exception {
            when(storage.newResource("content")).thenThrow(new IOException("io error"));
            assertThrows(IResourceStore.ResourceStoreException.class, () -> store.create("content"));
        }
    }

    // =========================================================
    // delete — branches
    // =========================================================

    @Nested
    @DisplayName("delete method")
    class DeleteMethod {

        @Test
        @DisplayName("null id throws IllegalArgumentException")
        void nullIdThrows() {
            assertThrows(IllegalArgumentException.class, () -> store.delete(null, 1));
        }

        @Test
        @DisplayName("null version throws IllegalArgumentException")
        void nullVersionThrows() {
            assertThrows(IllegalArgumentException.class, () -> store.delete("id1", null));
        }

        @Test
        @DisplayName("resource not found → throws ResourceNotFoundException")
        void resourceNotFound() {
            when(storage.read("id1", 1)).thenReturn(null);
            when(storage.readHistoryLatest("id1")).thenReturn(null);

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.delete("id1", 1));
        }
    }

    // =========================================================
    // read — branches
    // =========================================================

    @Nested
    @DisplayName("read method")
    class ReadMethod {

        @Test
        @DisplayName("null id throws IllegalArgumentException")
        void nullIdThrows() {
            assertThrows(IllegalArgumentException.class, () -> store.read(null, 1));
        }

        @Test
        @DisplayName("null version throws IllegalArgumentException")
        void nullVersionThrows() {
            assertThrows(IllegalArgumentException.class, () -> store.read("id1", null));
        }

        @Test
        @DisplayName("current null, history null → throws ResourceNotFoundException")
        void currentNullHistoryNull() {
            when(storage.read("id1", 1)).thenReturn(null);
            when(storage.readHistory("id1", 1)).thenReturn(null);

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.read("id1", 1));
        }

        @Test
        @DisplayName("current exists but getData throws IOException → wraps in ResourceStoreException")
        void getDataThrowsIOException() throws Exception {
            IResourceStorage.IResource<String> currentResource = mock(IResourceStorage.IResource.class);
            when(storage.read("id1", 1)).thenReturn(currentResource);
            when(currentResource.getData()).thenThrow(new IOException("parse error"));

            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> store.read("id1", 1));
        }

        @Test
        @DisplayName("history not deleted, getData throws IOException → wraps in ResourceStoreException")
        void historyGetDataThrowsIOException() throws Exception {
            when(storage.read("id1", 1)).thenReturn(null);

            IResourceStorage.IHistoryResource<String> historyResource = mock(IResourceStorage.IHistoryResource.class);
            when(storage.readHistory("id1", 1)).thenReturn(historyResource);
            when(historyResource.isDeleted()).thenReturn(false);
            when(historyResource.getData()).thenThrow(new IOException("parse error"));

            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> store.read("id1", 1));
        }
    }

    // =========================================================
    // readIncludingDeleted — branches
    // =========================================================

    @Nested
    @DisplayName("readIncludingDeleted method")
    class ReadIncludingDeletedMethod {

        @Test
        @DisplayName("null id throws IllegalArgumentException")
        void nullIdThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> store.readIncludingDeleted(null, 1));
        }

        @Test
        @DisplayName("null version throws IllegalArgumentException")
        void nullVersionThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> store.readIncludingDeleted("id1", null));
        }

        @Test
        @DisplayName("current null, history null → throws ResourceNotFoundException")
        void currentNullHistoryNull() {
            when(storage.read("id1", 1)).thenReturn(null);
            when(storage.readHistory("id1", 1)).thenReturn(null);

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.readIncludingDeleted("id1", 1));
        }

        @Test
        @DisplayName("current exists → returns data directly")
        void currentExists() throws Exception {
            IResourceStorage.IResource<String> resource = mock(IResourceStorage.IResource.class);
            when(storage.read("id1", 1)).thenReturn(resource);
            when(resource.getData()).thenReturn("current-data");

            String result = store.readIncludingDeleted("id1", 1);
            assertEquals("current-data", result);
        }

        @Test
        @DisplayName("current exists but getData throws IOException → wraps in ResourceStoreException")
        void getDataThrowsIOException() throws Exception {
            IResourceStorage.IResource<String> resource = mock(IResourceStorage.IResource.class);
            when(storage.read("id1", 1)).thenReturn(resource);
            when(resource.getData()).thenThrow(new IOException("parse error"));

            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> store.readIncludingDeleted("id1", 1));
        }

        @Test
        @DisplayName("history deleted resource is still returned (includes deleted)")
        void deletedHistoryReturned() throws Exception {
            when(storage.read("id1", 1)).thenReturn(null);

            IResourceStorage.IHistoryResource<String> historyResource = mock(IResourceStorage.IHistoryResource.class);
            when(storage.readHistory("id1", 1)).thenReturn(historyResource);
            when(historyResource.isDeleted()).thenReturn(true);
            when(historyResource.getData()).thenReturn("deleted-data");

            String result = store.readIncludingDeleted("id1", 1);
            assertEquals("deleted-data", result);
        }
    }

    // =========================================================
    // getCurrentResourceId — branches
    // =========================================================

    @Nested
    @DisplayName("getCurrentResourceId method")
    class GetCurrentResourceIdMethod {

        @Test
        @DisplayName("null id throws IllegalArgumentException")
        void nullIdThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> store.getCurrentResourceId(null));
        }

        @Test
        @DisplayName("version -1 → throws ResourceNotFoundException")
        void versionNegativeOneThrows() {
            when(storage.getCurrentVersion("id1")).thenReturn(-1);
            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.getCurrentResourceId("id1"));
        }

        @Test
        @DisplayName("valid version → returns IResourceId with correct id and version")
        void validVersionReturnsResourceId() throws Exception {
            when(storage.getCurrentVersion("id1")).thenReturn(5);
            var result = store.getCurrentResourceId("id1");
            assertEquals("id1", result.getId());
            assertEquals(5, result.getVersion());
        }
    }
}
