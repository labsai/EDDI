package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.configs.properties.mongo.MongoUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link MongoUserMemoryStore} using Testcontainers.
 *
 * @since 6.0.0
 */
@DisplayName("MongoUserMemoryStore IT")
class MongoUserMemoryStoreIT extends MongoTestBase {

    private static MongoUserMemoryStore store;

    @BeforeAll
    static void init() {
        store = new MongoUserMemoryStore(getDatabase());
    }

    @BeforeEach
    void clean() {
        dropCollections("usermemories");
    }

    // ─── Flat Properties ────────────────────────────────────────

    @Nested
    @DisplayName("Flat Properties")
    class FlatProperties {

        @Test
        @DisplayName("mergeProperties + readProperties round-trip")
        void mergeAndRead() throws IResourceStore.ResourceStoreException {
            var props = new Properties();
            props.put("language", "en");
            props.put("timezone", "UTC");
            store.mergeProperties("user-1", props);

            Properties read = store.readProperties("user-1");
            assertNotNull(read);
            assertEquals("en", read.get("language"));
            assertEquals("UTC", read.get("timezone"));
        }

        @Test
        @DisplayName("readProperties non-existent — returns null")
        void readNonExistent() throws IResourceStore.ResourceStoreException {
            assertNull(store.readProperties("ghost"));
        }

        @Test
        @DisplayName("mergeProperties — upserts existing keys")
        void upsertExisting() throws IResourceStore.ResourceStoreException {
            var initial = new Properties();
            initial.put("lang", "en");
            store.mergeProperties("user-1", initial);

            var update = new Properties();
            update.put("lang", "de");
            store.mergeProperties("user-1", update);

            assertEquals("de", store.readProperties("user-1").get("lang"));
        }

        @Test
        @DisplayName("mergeProperties empty — no-op")
        void mergeEmpty() throws IResourceStore.ResourceStoreException {
            store.mergeProperties("user-1", new Properties());
            assertNull(store.readProperties("user-1"));
        }

        @Test
        @DisplayName("deleteProperties — removes all global entries")
        void deleteProps() throws IResourceStore.ResourceStoreException {
            var props = new Properties();
            props.put("key", "val");
            store.mergeProperties("user-1", props);

            store.deleteProperties("user-1");
            assertNull(store.readProperties("user-1"));
        }
    }

    // ─── Structured Entries ─────────────────────────────────────

    @Nested
    @DisplayName("Structured Entries")
    class StructuredEntries {

        @Test
        @DisplayName("upsert + getByKey round-trip")
        void upsertAndGet() throws IResourceStore.ResourceStoreException {
            var entry = new UserMemoryEntry(null, "user-1", "pref", "dark-mode",
                    "preferences", Visibility.self, "agent-1", List.of(), null,
                    false, 0, null, null);

            String id = store.upsert(entry);
            assertNotNull(id);

            Optional<UserMemoryEntry> found = store.getByKey("user-1", "pref");
            assertTrue(found.isPresent());
            assertEquals("dark-mode", found.get().value());
        }

        @Test
        @DisplayName("upsert — updates existing value")
        void upsertUpdate() throws IResourceStore.ResourceStoreException {
            var entry1 = new UserMemoryEntry(null, "user-1", "k1", "v1",
                    "cat", Visibility.self, "agent-1", List.of(), null,
                    false, 0, null, null);
            store.upsert(entry1);

            var entry2 = new UserMemoryEntry(null, "user-1", "k1", "v2",
                    "cat", Visibility.self, "agent-1", List.of(), null,
                    false, 0, null, null);
            store.upsert(entry2);

            assertEquals("v2", store.getByKey("user-1", "k1").orElseThrow().value());
        }

        @Test
        @DisplayName("deleteEntry — removes by ID")
        void deleteEntry() throws IResourceStore.ResourceStoreException {
            var entry = new UserMemoryEntry(null, "user-1", "del-key", "val",
                    "cat", Visibility.self, "agent-1", List.of(), null,
                    false, 0, null, null);
            String id = store.upsert(entry);

            store.deleteEntry(id);
            assertTrue(store.getByKey("user-1", "del-key").isEmpty());
        }

        @Test
        @DisplayName("getAllEntries — returns all for user")
        void getAll() throws IResourceStore.ResourceStoreException {
            store.upsert(entry("user-1", "k1", "v1"));
            store.upsert(entry("user-1", "k2", "v2"));
            store.upsert(entry("user-2", "k3", "v3"));

            assertEquals(2, store.getAllEntries("user-1").size());
        }
    }

    // ─── Queries ────────────────────────────────────────────────

    @Nested
    @DisplayName("Queries")
    class Queries {

        @Test
        @DisplayName("getEntriesByCategory")
        void byCategory() throws IResourceStore.ResourceStoreException {
            store.upsert(entry("user-1", "k1", "v1", "preferences"));
            store.upsert(entry("user-1", "k2", "v2", "facts"));

            assertEquals(1, store.getEntriesByCategory("user-1", "preferences").size());
        }

        @Test
        @DisplayName("filterEntries — regex search on key/value")
        void filter() throws IResourceStore.ResourceStoreException {
            store.upsert(entry("user-1", "favorite_color", "blue"));
            store.upsert(entry("user-1", "favorite_food", "pizza"));
            store.upsert(entry("user-1", "age", "25"));

            List<UserMemoryEntry> results = store.filterEntries("user-1", "favorite");
            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("getVisibleEntries — self visibility")
        void visibleSelf() throws IResourceStore.ResourceStoreException {
            store.upsert(new UserMemoryEntry(null, "user-1", "self-key", "self-val",
                    "cat", Visibility.self, "agent-A", List.of(), null,
                    false, 0, null, null));
            store.upsert(new UserMemoryEntry(null, "user-1", "other-key", "other-val",
                    "cat", Visibility.self, "agent-B", List.of(), null,
                    false, 0, null, null));

            List<UserMemoryEntry> visible = store.getVisibleEntries(
                    "user-1", "agent-A", List.of(), "most_recent", 100);
            assertTrue(visible.stream().anyMatch(e -> "self-key".equals(e.key())));
            assertTrue(visible.stream().noneMatch(e -> "other-key".equals(e.key())));
        }

        @Test
        @DisplayName("countEntries")
        void count() throws IResourceStore.ResourceStoreException {
            store.upsert(entry("user-1", "k1", "v1"));
            store.upsert(entry("user-1", "k2", "v2"));

            assertEquals(2, store.countEntries("user-1"));
            assertEquals(0, store.countEntries("ghost"));
        }
    }

    // ─── GDPR ───────────────────────────────────────────────────

    @Nested
    @DisplayName("GDPR")
    class Gdpr {

        @Test
        @DisplayName("deleteAllForUser — removes all entries")
        void deleteAll() throws IResourceStore.ResourceStoreException {
            store.upsert(entry("user-gdpr", "k1", "v1"));
            store.upsert(entry("user-gdpr", "k2", "v2"));

            store.deleteAllForUser("user-gdpr");
            assertEquals(0, store.countEntries("user-gdpr"));
        }
    }

    // ─── Helpers ────────────────────────────────────────────────

    private static UserMemoryEntry entry(String userId, String key, String value) {
        return entry(userId, key, value, "general");
    }

    private static UserMemoryEntry entry(String userId, String key, String value, String category) {
        return new UserMemoryEntry(null, userId, key, value, category,
                Visibility.self, "agent-test", List.of(), null, false, 0, null, null);
    }
}
