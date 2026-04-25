package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link MongoResourceStorage} using Testcontainers.
 *
 * @since 6.0.0
 */
@DisplayName("MongoResourceStorage IT")
class MongoResourceStorageTest extends MongoTestBase {

    private static MongoResourceStorage<Map<String, Object>> storage;

    @BeforeAll
    @SuppressWarnings("unchecked") // Map.class → Class<Map> erasure
    static void init() {
        storage = new MongoResourceStorage<>(getDatabase(), "test_resources",
                documentBuilder, (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    @BeforeEach
    void clean() {
        dropCollections("test_resources", "test_resources.history");
    }

    @Test
    @DisplayName("newResource + store — creates with auto-generated ID")
    void storeNew() throws IOException {
        IResourceStorage.IResource<Map<String, Object>> resource = storage.newResource(Map.of("name", "test"));
        storage.store(resource);

        assertNotNull(resource.getId());
        assertEquals(1, resource.getVersion());
    }

    @Test
    @DisplayName("store + read round-trip")
    void storeAndRead() throws IOException {
        IResourceStorage.IResource<Map<String, Object>> resource = storage.newResource(Map.of("name", "round-trip"));
        storage.store(resource);

        IResourceStorage.IResource<Map<String, Object>> read = storage.read(resource.getId(), 1);
        assertNotNull(read);
        Map<String, Object> data = read.getData();
        assertEquals("round-trip", data.get("name"));
    }

    @Test
    @DisplayName("read non-existent — returns null")
    void readNonExistent() {
        IResourceStorage.IResource<Map<String, Object>> read = storage.read(new ObjectId().toString(), 1);
        assertNull(read);
    }

    @Test
    @DisplayName("newResource with explicit ID and version")
    void newResourceWithId() throws IOException {
        String id = new ObjectId().toString();
        IResourceStorage.IResource<Map<String, Object>> resource = storage.newResource(id, 3, Map.of("key", "val"));

        assertEquals(id, resource.getId());
        assertEquals(3, resource.getVersion());
    }

    @Test
    @DisplayName("createNew — inserts without upsert")
    void createNew() throws IOException {
        IResourceStorage.IResource<Map<String, Object>> resource = storage.newResource(Map.of("mode", "create"));
        storage.createNew(resource);

        assertNotNull(resource.getId());
    }

    @Test
    @DisplayName("store with existing ID — upserts")
    void storeUpsert() throws IOException {
        IResourceStorage.IResource<Map<String, Object>> resource = storage.newResource(Map.of("version", "1"));
        storage.store(resource);
        String id = resource.getId();

        IResourceStorage.IResource<Map<String, Object>> updated = storage.newResource(id, 2, Map.of("version", "2"));
        storage.store(updated);

        IResourceStorage.IResource<Map<String, Object>> read = storage.read(id, 2);
        assertNotNull(read);
        assertEquals("2", read.getData().get("version"));
    }

    @Test
    @DisplayName("getCurrentVersion — returns version number")
    void getCurrentVersion() throws IOException {
        IResourceStorage.IResource<Map<String, Object>> resource = storage.newResource(Map.of("x", "y"));
        storage.store(resource);

        Integer version = storage.getCurrentVersion(resource.getId());
        assertEquals(1, version);
    }

    @Test
    @DisplayName("getCurrentVersion non-existent — returns -1")
    void getCurrentVersionNonExistent() {
        assertEquals(-1, storage.getCurrentVersion(new ObjectId().toString()));
    }

    @Test
    @DisplayName("remove — deletes from current collection")
    void remove() throws IOException {
        IResourceStorage.IResource<Map<String, Object>> resource = storage.newResource(Map.of("del", "me"));
        storage.store(resource);
        String id = resource.getId();

        storage.remove(id);
        assertNull(storage.read(id, 1));
    }

    @Test
    @DisplayName("history resource — store and read")
    void historyResource() throws IOException {
        IResourceStorage.IResource<Map<String, Object>> resource = storage.newResource(Map.of("historic", "data"));
        storage.store(resource);

        IResourceStorage.IHistoryResource<Map<String, Object>> history = storage.newHistoryResourceFor(resource, false);
        storage.store(history);

        IResourceStorage.IHistoryResource<Map<String, Object>> readHistory = storage.readHistory(resource.getId(), 1);
        assertNotNull(readHistory);
        assertFalse(readHistory.isDeleted());
    }

    @Test
    @DisplayName("history resource deleted flag")
    void historyResourceDeleted() throws IOException {
        IResourceStorage.IResource<Map<String, Object>> resource = storage.newResource(Map.of("about_to_delete", "yes"));
        storage.store(resource);

        IResourceStorage.IHistoryResource<Map<String, Object>> history = storage.newHistoryResourceFor(resource, true);
        storage.store(history);

        IResourceStorage.IHistoryResource<Map<String, Object>> readHistory = storage.readHistory(resource.getId(), 1);
        assertNotNull(readHistory);
        assertTrue(readHistory.isDeleted());
    }

    @Test
    @DisplayName("removeAllPermanently — deletes current + history")
    void removeAllPermanently() throws IOException {
        IResourceStorage.IResource<Map<String, Object>> resource = storage.newResource(Map.of("purge", "all"));
        storage.store(resource);

        IResourceStorage.IHistoryResource<Map<String, Object>> history = storage.newHistoryResourceFor(resource, false);
        storage.store(history);

        storage.removeAllPermanently(resource.getId());
        assertNull(storage.read(resource.getId(), 1));
        assertNull(storage.readHistory(resource.getId(), 1));
    }

    @Test
    @DisplayName("readHistoryLatest — returns most recent version")
    void readHistoryLatest() throws IOException {
        IResourceStorage.IResource<Map<String, Object>> resource = storage.newResource(Map.of("v", "1"));
        storage.store(resource);
        String id = resource.getId();

        // Store version 1 history
        IResourceStorage.IHistoryResource<Map<String, Object>> h1 = storage.newHistoryResourceFor(resource, false);
        storage.store(h1);

        // Store version 2 history
        IResourceStorage.IResource<Map<String, Object>> v2 = storage.newResource(id, 2, Map.of("v", "2"));
        IResourceStorage.IHistoryResource<Map<String, Object>> h2 = storage.newHistoryResourceFor(v2, false);
        storage.store(h2);

        IResourceStorage.IHistoryResource<Map<String, Object>> latest = storage.readHistoryLatest(id);
        assertNotNull(latest);
        assertEquals(2, latest.getVersion());
    }

    @Test
    @DisplayName("readHistoryLatest non-existent — returns null")
    void readHistoryLatestNonExistent() {
        assertNull(storage.readHistoryLatest(new ObjectId().toString()));
    }

    @Test
    @DisplayName("findResourceIdsContaining — searches by JSON path")
    void findResourceIds() throws IOException {
        storage.store(storage.newResource(Map.of("tags", List.of("ai", "chatbot"))));
        storage.store(storage.newResource(Map.of("tags", List.of("web"))));

        List<IResourceStore.IResourceId> results = storage.findResourceIdsContaining("tags", "ai");
        assertEquals(1, results.size());
    }
}
