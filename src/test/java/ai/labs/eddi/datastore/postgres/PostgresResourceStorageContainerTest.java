package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.serialization.JsonSerialization;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PostgresResourceStorage} using Testcontainers.
 *
 * @since 6.0.0
 */
@DisplayName("PostgresResourceStorage IT")
class PostgresResourceStorageContainerTest extends PostgresTestBase {

    private static DataSource ds;
    private PostgresResourceStorage<Map<String, Object>> storage;
    private static final String COLLECTION = "test_resources";

    @BeforeAll
    static void initDs() {
        ds = createDataSource();
    }

    @BeforeEach
    void initStorage() throws SQLException {
        var json = new JsonSerialization(new ObjectMapper());
        @SuppressWarnings("unchecked")
        Class<Map<String, Object>> type = (Class<Map<String, Object>>) (Class<?>) Map.class;
        storage = new PostgresResourceStorage<>(ds, COLLECTION, json, type);
        // Clean up from previous tests
        try {
            truncateTables(ds, "resources", "resources_history");
        } catch (SQLException ignored) {
        }
    }

    // ─── Core CRUD ──────────────────────────────────────────────

    @Nested
    @DisplayName("Store and Read")
    class StoreAndRead {

        @Test
        @DisplayName("newResource + store + read round-trip")
        void storeAndRead() throws IOException {
            Map<String, Object> content = Map.of("name", "test-agent", "version", 1);
            var resource = storage.newResource(content);
            assertNotNull(resource.getId());

            storage.store(resource);

            var found = storage.read(resource.getId(), resource.getVersion());
            assertNotNull(found);
            assertEquals(resource.getId(), found.getId());
            assertEquals(1, found.getVersion());

            Map<String, Object> data = found.getData();
            assertEquals("test-agent", data.get("name"));
        }

        @Test
        @DisplayName("newResource with explicit id and version")
        void storeWithExplicitId() throws IOException {
            var id = java.util.UUID.randomUUID().toString();
            var resource = storage.newResource(id, 3, Map.of("key", "value"));
            storage.store(resource);

            var found = storage.read(id, 3);
            assertNotNull(found);
            assertEquals(id, found.getId());
            assertEquals(3, found.getVersion());
        }

        @Test
        @DisplayName("read non-existent — returns null")
        void readNonExistent() {
            var id = java.util.UUID.randomUUID().toString();
            assertNull(storage.read(id, 1));
        }

        @Test
        @DisplayName("store upserts — updates existing resource")
        void upsertUpdates() throws IOException {
            var resource = storage.newResource(Map.of("status", "draft"));
            storage.store(resource);

            // Update
            var updated = storage.newResource(resource.getId(), resource.getVersion(), Map.of("status", "published"));
            storage.store(updated);

            var found = storage.read(resource.getId(), resource.getVersion());
            Map<String, Object> data = found.getData();
            assertEquals("published", data.get("status"));
        }
    }

    // ─── createNew ──────────────────────────────────────────────

    @Nested
    @DisplayName("createNew")
    class CreateNew {

        @Test
        @DisplayName("creates new resource")
        void createsNew() throws IOException {
            var resource = storage.newResource(Map.of("hello", "world"));
            storage.createNew(resource);

            var found = storage.read(resource.getId(), 1);
            assertNotNull(found);
        }

        @Test
        @DisplayName("duplicate createNew — throws")
        void duplicateThrows() throws IOException {
            var resource = storage.newResource(Map.of("hello", "world"));
            storage.createNew(resource);

            assertThrows(RuntimeException.class, () -> storage.createNew(resource));
        }
    }

    // ─── Remove ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Remove")
    class Remove {

        @Test
        @DisplayName("remove existing resource")
        void removeExisting() throws IOException {
            var resource = storage.newResource(Map.of("temp", true));
            storage.store(resource);

            storage.remove(resource.getId());
            assertNull(storage.read(resource.getId(), 1));
        }

        @Test
        @DisplayName("removeAllPermanently — removes from both tables")
        void removeAllPermanently() throws IOException {
            var resource = storage.newResource(Map.of("temp", true));
            storage.store(resource);

            // Also create a history entry
            var history = storage.newHistoryResourceFor(resource, false);
            storage.store(history);

            storage.removeAllPermanently(resource.getId());

            assertNull(storage.read(resource.getId(), 1));
            assertNull(storage.readHistory(resource.getId(), 1));
        }
    }

    // ─── History ────────────────────────────────────────────────

    @Nested
    @DisplayName("History")
    class History {

        @Test
        @DisplayName("history round-trip")
        void historyRoundTrip() throws IOException {
            var resource = storage.newResource(Map.of("v", 1));
            storage.store(resource);

            var history = storage.newHistoryResourceFor(resource, false);
            storage.store(history);

            var found = storage.readHistory(resource.getId(), 1);
            assertNotNull(found);
            assertFalse(found.isDeleted());
        }

        @Test
        @DisplayName("readHistoryLatest returns highest version")
        void readHistoryLatest() throws IOException {
            var id = java.util.UUID.randomUUID().toString();

            var v1 = storage.newResource(id, 1, Map.of("v", 1));
            var h1 = storage.newHistoryResourceFor(v1, false);
            storage.store(h1);

            var v2 = storage.newResource(id, 2, Map.of("v", 2));
            var h2 = storage.newHistoryResourceFor(v2, false);
            storage.store(h2);

            var latest = storage.readHistoryLatest(id);
            assertNotNull(latest);
            assertEquals(2, latest.getVersion());
        }

        @Test
        @DisplayName("deleted history flag preserved")
        void deletedFlag() throws IOException {
            var resource = storage.newResource(Map.of("d", true));
            storage.store(resource);

            var history = storage.newHistoryResourceFor(resource, true);
            storage.store(history);

            var found = storage.readHistory(resource.getId(), 1);
            assertTrue(found.isDeleted());
        }
    }

    // ─── Version ────────────────────────────────────────────────

    @Nested
    @DisplayName("getCurrentVersion")
    class GetCurrentVersion {

        @Test
        @DisplayName("returns version for existing resource")
        void existingResource() throws IOException {
            var resource = storage.newResource(Map.of("v", 1));
            storage.store(resource);

            assertEquals(1, storage.getCurrentVersion(resource.getId()));
        }

        @Test
        @DisplayName("returns -1 for non-existent resource")
        void nonExistent() {
            assertEquals(-1, storage.getCurrentVersion(java.util.UUID.randomUUID().toString()));
        }

        @Test
        @DisplayName("returns -1 for invalid UUID (MongoDB ObjectId format)")
        void invalidUuid() {
            // MongoDB ObjectId format should be treated as not found
            assertEquals(-1, storage.getCurrentVersion("507f1f77bcf86cd799439011"));
        }
    }
}
