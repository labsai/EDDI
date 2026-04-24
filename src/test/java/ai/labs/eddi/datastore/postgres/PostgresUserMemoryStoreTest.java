/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PostgresUserMemoryStore} using Testcontainers.
 *
 * @since 6.0.0
 */
@DisplayName("PostgresUserMemoryStore IT")
class PostgresUserMemoryStoreTest extends PostgresTestBase {

    private static PostgresUserMemoryStore store;
    private static DataSource ds;

    @BeforeAll
    static void init() {
        var dsInstance = createDataSourceInstance();
        ds = dsInstance.get();
        store = new PostgresUserMemoryStore(dsInstance);
    }

    @BeforeEach
    void clean() {
        try {
            truncateTables(ds, "usermemories");
        } catch (SQLException ignored) {
        }
    }

    // ─── Flat property view ─────────────────────────────────────

    @Nested
    @DisplayName("Flat Properties")
    class FlatProperties {

        @Test
        @DisplayName("readProperties — returns null when no properties")
        void readEmpty() throws IResourceStore.ResourceStoreException {
            assertNull(store.readProperties("user1"));
        }

        @Test
        @DisplayName("mergeProperties + readProperties round-trip")
        void mergeAndRead() throws IResourceStore.ResourceStoreException {
            Properties props = new Properties();
            props.put("lang", "en");
            props.put("tier", "premium");

            store.mergeProperties("user1", props);

            Properties result = store.readProperties("user1");
            assertNotNull(result);
            assertEquals("en", result.get("lang"));
            assertEquals("premium", result.get("tier"));
        }

        @Test
        @DisplayName("mergeProperties upserts existing values")
        void upserts() throws IResourceStore.ResourceStoreException {
            Properties v1 = new Properties();
            v1.put("lang", "en");
            store.mergeProperties("user1", v1);

            Properties v2 = new Properties();
            v2.put("lang", "de");
            store.mergeProperties("user1", v2);

            Properties result = store.readProperties("user1");
            assertEquals("de", result.get("lang"));
        }

        @Test
        @DisplayName("mergeProperties skips _id and userId keys")
        void skipsReservedKeys() throws IResourceStore.ResourceStoreException {
            Properties props = new Properties();
            props.put("_id", "ignore");
            props.put("userId", "ignore");
            props.put("real_key", "value");
            store.mergeProperties("user1", props);

            Properties result = store.readProperties("user1");
            assertNotNull(result);
            assertFalse(result.containsKey("_id"));
            assertFalse(result.containsKey("userId"));
            assertEquals("value", result.get("real_key"));
        }

        @Test
        @DisplayName("deleteProperties removes all global entries")
        void deleteProperties() throws IResourceStore.ResourceStoreException {
            Properties props = new Properties();
            props.put("key1", "val1");
            store.mergeProperties("user1", props);

            store.deleteProperties("user1");
            assertNull(store.readProperties("user1"));
        }

        @Test
        @DisplayName("mergeProperties with null — no-op")
        void mergeNull() throws IResourceStore.ResourceStoreException {
            assertDoesNotThrow(() -> store.mergeProperties("user1", null));
        }

        @Test
        @DisplayName("mergeProperties with empty — no-op")
        void mergeEmpty() throws IResourceStore.ResourceStoreException {
            assertDoesNotThrow(() -> store.mergeProperties("user1", new Properties()));
        }
    }

    // ─── Structured Entries ─────────────────────────────────────

    @Nested
    @DisplayName("Structured Entries")
    class StructuredEntries {

        @Test
        @DisplayName("upsert + getByKey round-trip (self visibility)")
        void upsertAndGetByKey() throws IResourceStore.ResourceStoreException {
            var entry = createEntry("user1", "fav_color", "blue", "preference",
                    Visibility.self, "agent1");
            String id = store.upsert(entry);
            assertNotNull(id);

            Optional<UserMemoryEntry> found = store.getByKey("user1", "fav_color");
            assertTrue(found.isPresent());
            assertEquals("blue", found.get().value());
            assertEquals("preference", found.get().category());
            assertEquals(Visibility.self, found.get().visibility());
        }

        @Test
        @DisplayName("upsert global — updates on second call")
        void upsertGlobalUpdates() throws IResourceStore.ResourceStoreException {
            var e1 = createEntry("user1", "city", "Vienna", "fact", Visibility.global, "agent1");
            store.upsert(e1);

            var e2 = createEntry("user1", "city", "Berlin", "fact", Visibility.global, "agent2");
            store.upsert(e2);

            Optional<UserMemoryEntry> found = store.getByKey("user1", "city");
            assertTrue(found.isPresent());
            assertEquals("Berlin", found.get().value());
        }

        @Test
        @DisplayName("deleteEntry — removes by ID")
        void deleteEntry() throws IResourceStore.ResourceStoreException {
            String id = store.upsert(createEntry("user1", "temp", "val", "fact",
                    Visibility.self, "agent1"));

            store.deleteEntry(id);
            assertTrue(store.getByKey("user1", "temp").isEmpty());
        }

        @Test
        @DisplayName("getAllEntries — returns all for user")
        void getAllEntries() throws IResourceStore.ResourceStoreException {
            store.upsert(createEntry("user1", "k1", "v1", "fact", Visibility.self, "a"));
            store.upsert(createEntry("user1", "k2", "v2", "fact", Visibility.global, "a"));
            store.upsert(createEntry("user2", "k3", "v3", "fact", Visibility.self, "a"));

            List<UserMemoryEntry> user1 = store.getAllEntries("user1");
            assertEquals(2, user1.size());
        }
    }

    // ─── Visibility Queries ─────────────────────────────────────

    @Nested
    @DisplayName("Visibility and Scoping")
    class VisibilityTests {

        @Test
        @DisplayName("getVisibleEntries — self + global visible, other agent's self NOT visible")
        void selfAndGlobalVisible() throws IResourceStore.ResourceStoreException {
            store.upsert(createEntry("user1", "self_key", "v1", "fact", Visibility.self, "agentA"));
            store.upsert(createEntry("user1", "global_key", "v2", "fact", Visibility.global, "agentA"));
            store.upsert(createEntry("user1", "other_self", "v3", "fact", Visibility.self, "agentB"));

            List<UserMemoryEntry> visible = store.getVisibleEntries("user1", "agentA",
                    null, "most_recent", 50);

            // Should see self_key (own agent) + global_key, NOT other_self
            assertTrue(visible.stream().anyMatch(e -> "self_key".equals(e.key())));
            assertTrue(visible.stream().anyMatch(e -> "global_key".equals(e.key())));
            assertFalse(visible.stream().anyMatch(e -> "other_self".equals(e.key())));
        }

        @Test
        @DisplayName("getVisibleEntries respects maxEntries limit")
        void maxEntries() throws IResourceStore.ResourceStoreException {
            for (int i = 0; i < 10; i++) {
                store.upsert(createEntry("user1", "key_" + i, "v" + i, "fact",
                        Visibility.global, "agent1"));
            }

            List<UserMemoryEntry> limited = store.getVisibleEntries("user1", "agent1",
                    null, "most_recent", 3);
            assertEquals(3, limited.size());
        }
    }

    // ─── Filter and Category ────────────────────────────────────

    @Nested
    @DisplayName("Filter and Category Queries")
    class FilterTests {

        @Test
        @DisplayName("filterEntries — matches key and value")
        void filterMatches() throws IResourceStore.ResourceStoreException {
            store.upsert(createEntry("user1", "favorite_food", "pizza", "preference",
                    Visibility.self, "a"));
            store.upsert(createEntry("user1", "hobby", "chess", "fact",
                    Visibility.self, "a"));

            List<UserMemoryEntry> results = store.filterEntries("user1", "pizza");
            assertEquals(1, results.size());
            assertEquals("favorite_food", results.getFirst().key());
        }

        @Test
        @DisplayName("filterEntries with null query — returns all")
        void filterNullQuery() throws IResourceStore.ResourceStoreException {
            store.upsert(createEntry("user1", "k1", "v1", "fact", Visibility.self, "a"));
            store.upsert(createEntry("user1", "k2", "v2", "fact", Visibility.self, "a"));

            List<UserMemoryEntry> results = store.filterEntries("user1", null);
            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("getEntriesByCategory — filters correctly")
        void byCategory() throws IResourceStore.ResourceStoreException {
            store.upsert(createEntry("user1", "k1", "v1", "preference", Visibility.self, "a"));
            store.upsert(createEntry("user1", "k2", "v2", "fact", Visibility.self, "a"));
            store.upsert(createEntry("user1", "k3", "v3", "preference", Visibility.self, "a"));

            List<UserMemoryEntry> prefs = store.getEntriesByCategory("user1", "preference");
            assertEquals(2, prefs.size());
        }
    }

    // ─── GDPR ───────────────────────────────────────────────────

    @Nested
    @DisplayName("GDPR Operations")
    class GdprOps {

        @Test
        @DisplayName("deleteAllForUser — removes everything")
        void deleteAllForUser() throws IResourceStore.ResourceStoreException {
            store.upsert(createEntry("user1", "k1", "v1", "fact", Visibility.self, "a"));
            store.upsert(createEntry("user1", "k2", "v2", "fact", Visibility.global, "a"));

            store.deleteAllForUser("user1");
            assertEquals(0, store.countEntries("user1"));
        }

        @Test
        @DisplayName("countEntries — returns correct count")
        void countEntries() throws IResourceStore.ResourceStoreException {
            store.upsert(createEntry("user1", "k1", "v1", "fact", Visibility.self, "a"));
            store.upsert(createEntry("user1", "k2", "v2", "fact", Visibility.self, "a"));

            assertEquals(2, store.countEntries("user1"));
            assertEquals(0, store.countEntries("nonexistent"));
        }
    }

    // ─── Helpers ────────────────────────────────────────────────

    private static UserMemoryEntry createEntry(String userId, String key, Object value,
                                               String category, Visibility visibility,
                                               String agentId) {
        return new UserMemoryEntry(null, userId, key, value, category, visibility,
                agentId, List.of(), "conv1", false, 0,
                Instant.now(), Instant.now());
    }
}
