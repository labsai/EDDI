package ai.labs.eddi.datastore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests that HistorizedResourceStore correctly manages versioning lifecycle
 * (create, read, update, delete) against a mocked IResourceStorage.
 */
@SuppressWarnings("unchecked")
class HistorizedResourceStoreTest {

    private IResourceStorage<String> storage;
    private HistorizedResourceStore<String> store;

    @BeforeEach
    void setUp() {
        storage = mock(IResourceStorage.class);
        store = new HistorizedResourceStore<>(storage);
    }

    @Test
    void shouldCreateResourceViaStorage() throws Exception {
        IResourceStorage.IResource<String> mockResource = mock(IResourceStorage.IResource.class);
        when(storage.newResource("content")).thenReturn(mockResource);
        when(mockResource.getId()).thenReturn("id1");
        when(mockResource.getVersion()).thenReturn(1);

        IResourceStore.IResourceId result = store.create("content");

        verify(storage).newResource("content");
        verify(storage).store(mockResource);
        assertNotNull(result);
    }

    @Test
    void shouldReadCurrentVersion() throws Exception {
        IResourceStorage.IResource<String> mockResource = mock(IResourceStorage.IResource.class);
        when(storage.read("id1", 1)).thenReturn(mockResource);
        when(mockResource.getData()).thenReturn("data");

        String result = store.read("id1", 1);

        assertEquals("data", result);
        verify(storage).read("id1", 1);
        verify(storage, never()).readHistory(anyString(), anyInt());
    }

    @Test
    void shouldReadFromHistoryWhenCurrentNotFound() throws Exception {
        when(storage.read("id1", 1)).thenReturn(null);

        IResourceStorage.IHistoryResource<String> historyResource = mock(IResourceStorage.IHistoryResource.class);
        when(storage.readHistory("id1", 1)).thenReturn(historyResource);
        when(historyResource.isDeleted()).thenReturn(false);
        when(historyResource.getData()).thenReturn("history-data");

        String result = store.read("id1", 1);

        assertEquals("history-data", result);
        verify(storage).readHistory("id1", 1);
    }

    @Test
    void shouldThrowNotFoundWhenDeletedInHistory() {
        when(storage.read("id1", 1)).thenReturn(null);

        IResourceStorage.IHistoryResource<String> historyResource = mock(IResourceStorage.IHistoryResource.class);
        when(storage.readHistory("id1", 1)).thenReturn(historyResource);
        when(historyResource.isDeleted()).thenReturn(true);

        assertThrows(IResourceStore.ResourceNotFoundException.class, () -> store.read("id1", 1));
    }

    @Test
    void shouldUpdateResourceAndStoreHistory() throws Exception {
        IResourceStorage.IResource<String> currentResource = mock(IResourceStorage.IResource.class);
        when(storage.read("id1", 1)).thenReturn(currentResource);
        when(currentResource.getId()).thenReturn("id1");
        when(currentResource.getVersion()).thenReturn(1);

        IResourceStorage.IHistoryResource<String> historyResource = mock(IResourceStorage.IHistoryResource.class);
        when(storage.newHistoryResourceFor(currentResource, false)).thenReturn(historyResource);

        IResourceStorage.IResource<String> newResource = mock(IResourceStorage.IResource.class);
        when(storage.newResource("id1", 2, "updated")).thenReturn(newResource);

        Integer newVersion = store.update("id1", 1, "updated");

        assertEquals(2, newVersion);
        verify(storage).store(historyResource); // archived old version
        verify(storage).store(newResource);     // stored new version
    }

    @Test
    void shouldDeleteAndStoreHistoryMarkedDeleted() throws Exception {
        IResourceStorage.IResource<String> currentResource = mock(IResourceStorage.IResource.class);
        when(storage.read("id1", 1)).thenReturn(currentResource);

        IResourceStorage.IHistoryResource<String> deletedHistory = mock(IResourceStorage.IHistoryResource.class);
        when(storage.newHistoryResourceFor(currentResource, true)).thenReturn(deletedHistory);

        store.delete("id1", 1);

        verify(storage).store(deletedHistory);  // archived with deleted flag
        verify(storage).remove("id1");          // removed from current
    }

    @Test
    void shouldGetCurrentResourceIdFromStorage() throws Exception {
        when(storage.getCurrentVersion("id1")).thenReturn(3);

        IResourceStore.IResourceId result = store.getCurrentResourceId("id1");

        assertEquals("id1", result.getId());
        assertEquals(3, result.getVersion());
    }

    @Test
    void shouldThrowNotFoundWhenNoCurrentVersion() {
        when(storage.getCurrentVersion("id1")).thenReturn(-1);

        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> store.getCurrentResourceId("id1"));
    }

    @Test
    void shouldReadIncludingDeletedFromHistory() throws Exception {
        when(storage.read("id1", 1)).thenReturn(null);

        IResourceStorage.IHistoryResource<String> historyResource = mock(IResourceStorage.IHistoryResource.class);
        when(storage.readHistory("id1", 1)).thenReturn(historyResource);
        when(historyResource.isDeleted()).thenReturn(true);
        when(historyResource.getData()).thenReturn("deleted-data");

        // readIncludingDeleted should NOT throw even for deleted resources
        String result = store.readIncludingDeleted("id1", 1);

        assertEquals("deleted-data", result);
    }

    @Test
    void shouldDeleteAllPermanently() {
        store.deleteAllPermanently("id1");

        verify(storage).removeAllPermanently("id1");
    }
}
