/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.configs.properties.mongo.MongoUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore;
import org.bson.Document;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link MongoUserMemoryStore} using Testcontainers.
 *
 * @since 6.0.0
 */
@DisplayName("MongoUserMemoryStore IT")
class MongoUserMemoryStoreTest extends MongoTestBase {

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

    // ─── Recall ordering and ownership ──────────────────────────

    /**
     * Real-backend counterparts to {@code MongoUserMemoryStoreRecallScopeTest},
     * which asserts the same two guarantees against Mockito stubs only. A mocked
     * {@code updateOne} cannot show that {@code $setOnInsert} actually preserves
     * the owning agent, nor that the reserved recency slots actually pull a
     * {@code accessCount == 0} entry into a full window.
     */
    @Nested
    @DisplayName("Recall ordering and ownership")
    class RecallAndOwnership {

        @Test
        @DisplayName("G7 — a global entry keeps its original owning agent when another agent rewrites its value")
        void globalUpsertDoesNotTransferOwnership() throws IResourceStore.ResourceStoreException {
            store.upsert(globalEntry("user-1", "shared_pref", "de", "agent-a"));

            // agent-b legitimately changes the shared value
            store.upsert(globalEntry("user-1", "shared_pref", "en", "agent-b"));

            var found = store.getByKey("user-1", "shared_pref").orElseThrow();
            assertEquals("en", found.value(), "the value must be updated by the second writer");
            assertEquals("agent-a", found.sourceAgentId(),
                    "ownership of a shared global entry must stay with the agent that created it");
            assertEquals(1, store.getAllEntries("user-1").size(), "a global entry is one shared document, not one per agent");
        }

        @Test
        @DisplayName("G5 — a brand-new entry (accessCount 0) still reaches a full most_accessed window")
        void mostAccessedReservesSlotsForRecentEntries() throws IResourceStore.ResourceStoreException {
            // 12 long-established entries with high, strictly descending access counts,
            // plus one freshly written entry with accessCount 0 and the newest
            // updatedAt. Under a plain "sort(accessCount).limit(10)" the newcomer can
            // never enter the window — and, never being recalled, never accumulates a
            // count either.
            for (int i = 0; i < 12; i++) {
                insertRawEntry("user-1", "established_" + i, "agent-1", 100 - i, "2024-01-01T00:00:" + String.format("%02d", i) + "Z");
            }
            insertRawEntry("user-1", "brand_new", "agent-1", 0, "2026-07-01T00:00:00Z");

            var recalled = store.getVisibleEntries("user-1", "agent-1", null, "most_accessed", 10);

            List<String> keys = recalled.stream().map(UserMemoryEntry::key).toList();
            assertTrue(keys.contains("brand_new"),
                    "a newly written entry must reach the most_accessed recall window; got: " + keys);
            assertEquals(10, recalled.size(), "8 access slots + 2 reserved recency slots, de-duplicated");
            assertTrue(keys.contains("established_0"), keys.toString());
            assertFalse(keys.contains("established_8"), "slot 9/10 belongs to the recency reservation: " + keys);

            // Recalled entries — including the one that came in through the reservation
            // — are counted, so the newcomer can start climbing the ranking.
            assertEquals(1, rawAccessCount("user-1", "brand_new"));
            assertEquals(101, rawAccessCount("user-1", "established_0"));
            assertEquals(92, rawAccessCount("user-1", "established_8"), "an entry that was NOT recalled must not be incremented");
        }

        @Test
        @DisplayName("G5 — accessCount is indexed so most_accessed is not an in-memory top-k")
        void accessCountIsIndexed() {
            // The @BeforeEach drop removes the indexes too, so build a fresh store —
            // its constructor is what declares them.
            new MongoUserMemoryStore(getDatabase());

            List<String> indexNames = new ArrayList<>();
            for (Document index : getDatabase().getCollection("usermemories").listIndexes()) {
                indexNames.add(index.getString("name"));
            }
            assertTrue(indexNames.contains("idx_user_access_count"),
                    "expected an index covering accessCount, got: " + indexNames);
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
        @DisplayName("getVisibleEntries — self scoped to agent, global visible to all")
        void visibleSelf() throws IResourceStore.ResourceStoreException {
            store.upsert(new UserMemoryEntry(null, "user-1", "self-key", "self-val",
                    "cat", Visibility.self, "agent-A", List.of(), null,
                    false, 0, null, null));
            store.upsert(new UserMemoryEntry(null, "user-1", "other-key", "other-val",
                    "cat", Visibility.self, "agent-B", List.of(), null,
                    false, 0, null, null));
            // Global entry — visible regardless of agent
            store.upsert(new UserMemoryEntry(null, "user-1", "global-key", "shared",
                    "cat", Visibility.global, "agent-B", List.of(), null,
                    false, 0, null, null));

            List<UserMemoryEntry> visible = store.getVisibleEntries(
                    "user-1", "agent-A", List.of(), "most_recent", 100);
            // Self entries: only agent-A's
            assertTrue(visible.stream().anyMatch(e -> "self-key".equals(e.key())),
                    "Expected agent-A's self entry");
            assertTrue(visible.stream().noneMatch(e -> "other-key".equals(e.key())),
                    "agent-B's self entry should not be visible to agent-A");
            // Global entries: visible to all agents
            assertTrue(visible.stream().anyMatch(e -> "global-key".equals(e.key())),
                    "Global entries should be visible regardless of agent");
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

    // ─── Owner-conditional global write ─────────────────────────

    @Nested
    @DisplayName("upsertIfOwnedBy")
    class OwnerConditionalGlobalWrite {

        private MongoUserMemoryStore owned;

        /**
         * The outer @BeforeEach drops the collection, and its indexes with it, so build
         * the store again here: the unique global-key index is what these tests
         * exercise.
         */
        @BeforeEach
        void freshStoreWithIndexes() {
            owned = new MongoUserMemoryStore(getDatabase());
        }

        @Test
        @DisplayName("a free key is written and stamped with the writer as owner")
        void freeKeyIsClaimed() throws Exception {
            assertTrue(owned.upsertIfOwnedBy(ownedGlobal("u-own", "lang", "en", "agent-a"), "agent-a"));

            var stored = owned.getAllEntries("u-own");
            assertEquals(1, stored.size());
            assertEquals("agent-a", stored.getFirst().sourceAgentId());
            assertEquals("en", stored.getFirst().value());
        }

        @Test
        @DisplayName("the owner can update its own key")
        void ownerUpdates() throws Exception {
            owned.upsertIfOwnedBy(ownedGlobal("u-own", "lang", "en", "agent-a"), "agent-a");

            assertTrue(owned.upsertIfOwnedBy(ownedGlobal("u-own", "lang", "de", "agent-a"), "agent-a"));

            var stored = owned.getAllEntries("u-own");
            assertEquals(1, stored.size());
            assertEquals("de", stored.getFirst().value());
        }

        @Test
        @DisplayName("another agent's key is refused and left untouched")
        void otherAgentsKeyIsRefused() throws Exception {
            owned.upsertIfOwnedBy(ownedGlobal("u-own", "lang", "en", "agent-a"), "agent-a");

            assertFalse(owned.upsertIfOwnedBy(ownedGlobal("u-own", "lang", "fr", "agent-b"), "agent-b"));

            var stored = owned.getAllEntries("u-own");
            assertEquals(1, stored.size(), "a refused write must not add a second global entry");
            assertEquals("en", stored.getFirst().value());
            assertEquals("agent-a", stored.getFirst().sourceAgentId());
        }

        @Test
        @DisplayName("a key with no recorded owner is refused")
        void ownerlessKeyIsRefused() throws Exception {
            // mergeProperties writes global entries without an owning agent
            var props = new Properties();
            props.put("lang", "en");
            owned.mergeProperties("u-own", props);

            assertFalse(owned.upsertIfOwnedBy(ownedGlobal("u-own", "lang", "fr", "agent-a"), "agent-a"));
            assertEquals("en", owned.readProperties("u-own").get("lang"));
        }

        @Test
        @DisplayName("a key with a blank owner is refused")
        void blankOwnerIsRefused() throws Exception {
            owned.upsert(ownedGlobal("u-own", "lang", "en", ""));

            assertFalse(owned.upsertIfOwnedBy(ownedGlobal("u-own", "lang", "fr", "agent-a"), "agent-a"));
            assertEquals("en", owned.getAllEntries("u-own").getFirst().value());
        }

        @Test
        @DisplayName("agents racing for a fresh key: exactly one wins, and its value and ownership stick")
        void concurrentClaimsHaveOneWinner() throws Exception {
            for (int round = 0; round < 10; round++) {
                String key = "race-" + round;
                List<String> winners = raceForFreshKey(owned::upsertIfOwnedBy, "u-race", key, 8);

                assertEquals(1, winners.size(), "round " + round + ": winners " + winners);
                List<UserMemoryEntry> stored = owned.getAllEntries("u-race").stream().filter(e -> key.equals(e.key())).toList();
                assertEquals(1, stored.size(), "round " + round + ": one global entry per key");
                assertEquals(winners.getFirst(), stored.getFirst().sourceAgentId());
                assertEquals("value-of-" + winners.getFirst(), stored.getFirst().value(),
                        "the loser's value must not overwrite the winner's");
            }
        }

        @Test
        @DisplayName("concurrent plain upserts of a fresh global key leave one entry and do not fail")
        void concurrentPlainUpsertsDoNotDuplicate() throws Exception {
            List<String> applied = raceForFreshKey((entry, agent) -> owned.upsert(entry) != null, "u-plain", "shared", 8);

            assertEquals(8, applied.size(), "every plain upsert must succeed, the losers by updating");
            assertEquals(1, owned.getAllEntries("u-plain").size());
        }

        @Test
        @DisplayName("the store builds the unique partial index on global keys")
        void uniqueGlobalKeyIndexExists() {
            Document index = null;
            for (Document candidate : getDatabase().getCollection("usermemories").listIndexes()) {
                if ("idx_um_upsert_global".equals(candidate.getString("name"))) {
                    index = candidate;
                }
            }
            assertNotNull(index);
            assertEquals(Boolean.TRUE, index.getBoolean("unique"));
            assertEquals(new Document("visibility", "global"), index.get("partialFilterExpression"));
        }
    }

    /**
     * Starts {@code agents} writers on the same fresh global key at once and
     * returns which of them the store reported as applied.
     */
    private static List<String> raceForFreshKey(Store target, String userId, String key, int agents) throws Exception {
        var pool = Executors.newFixedThreadPool(agents);
        try {
            var start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < agents; i++) {
                String agent = "agent-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return target.write(ownedGlobal(userId, key, "value-of-" + agent, agent), agent) ? agent : null;
                }));
            }
            start.countDown();
            List<String> winners = new ArrayList<>();
            for (Future<String> result : results) {
                String winner = result.get(30, TimeUnit.SECONDS);
                if (winner != null) {
                    winners.add(winner);
                }
            }
            return winners;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface Store {
        boolean write(UserMemoryEntry entry, String agentId) throws Exception;
    }

    private static UserMemoryEntry ownedGlobal(String userId, String key, String value, String agentId) {
        return new UserMemoryEntry(null, userId, key, value, "fact", Visibility.global, agentId, List.of(), "conv-own", false, 0, null,
                null);
    }

    // ─── Helpers ────────────────────────────────────────────────

    private static UserMemoryEntry globalEntry(String userId, String key, String value, String agentId) {
        return new UserMemoryEntry(null, userId, key, value, "preference",
                Visibility.global, agentId, List.of(), null, false, 0, null, null);
    }

    /**
     * Writes a document directly, because {@code accessCount} and {@code updatedAt}
     * are not settable through {@link MongoUserMemoryStore#upsert}.
     */
    private static void insertRawEntry(String userId, String key, String agentId, int accessCount, String updatedAt) {
        getDatabase().getCollection("usermemories").insertOne(new Document()
                .append("userId", userId)
                .append("key", key)
                .append("value", "v-" + key)
                .append("category", "fact")
                .append("visibility", Visibility.self.name())
                .append("sourceAgentId", agentId)
                .append("groupIds", List.of())
                .append("conflicted", false)
                .append("accessCount", accessCount)
                .append("createdAt", updatedAt)
                .append("updatedAt", updatedAt));
    }

    private static int rawAccessCount(String userId, String key) {
        Document doc = getDatabase().getCollection("usermemories")
                .find(new Document("userId", userId).append("key", key)).first();
        assertNotNull(doc, "expected a document for key " + key);
        return doc.getInteger("accessCount", 0);
    }

    private static UserMemoryEntry entry(String userId, String key, String value) {
        return entry(userId, key, value, "general");
    }

    private static UserMemoryEntry entry(String userId, String key, String value, String category) {
        return new UserMemoryEntry(null, userId, key, value, category,
                Visibility.self, "agent-test", List.of(), null, false, 0, null, null);
    }
}
