/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    @DisplayName("insertIfAbsent")
    class InsertIfAbsent {

        private UserMemoryEntry entry(String key, Object value, Visibility visibility, String agent) {
            return new UserMemoryEntry(null, "user-1", key, value, "fact", visibility, agent, List.of(), null, false, 0, null, null);
        }

        @Test
        @DisplayName("inserts when absent and returns the new id")
        void insertsWhenAbsent() throws Exception {
            String id = store.insertIfAbsent(entry("lang", "de", Visibility.global, null));

            assertNotNull(id);
            assertEquals("de", store.getAllEntries("user-1").getFirst().value());
        }

        @Test
        @DisplayName("an existing entry at the identity is left exactly as it is, and no id comes back")
        void neverReplaces() throws Exception {
            store.upsert(entry("lang", "en", Visibility.global, "agent-a"));

            assertNull(store.insertIfAbsent(entry("lang", "OLD-v5", Visibility.global, null)));

            var all = store.getAllEntries("user-1");
            assertEquals(1, all.size());
            assertEquals("en", all.getFirst().value());
        }

        @Test
        @DisplayName("identity, not key: a scoped entry does not block the global one, nor another agent's scoped one")
        void identityNotKey() throws Exception {
            store.upsert(entry("lang", "fr", Visibility.self, "agent-a"));

            assertNotNull(store.insertIfAbsent(entry("lang", "de", Visibility.global, null)));
            assertNotNull(store.insertIfAbsent(entry("lang", "it", Visibility.self, "agent-b")));
            assertNull(store.insertIfAbsent(entry("lang", "es", Visibility.self, "agent-a")));
            assertEquals(3, store.getAllEntries("user-1").size());
        }

        @Test
        @DisplayName("refuses a reserved _gdpr_ key like upsert does")
        void refusesReservedKeys() {
            assertThrows(IUserMemoryStore.ReservedMemoryKeyException.class,
                    () -> store.insertIfAbsent(entry("_gdpr_processing_restricted", "false", Visibility.global, null)));
        }
    }

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

        @Test
        @DisplayName("getVisibleEntries — non-empty groupIds does not cause JDBC parameter error")
        void groupIdsDoNotBreakQuery() throws IResourceStore.ResourceStoreException {
            // Regression test: PostgreSQL's ?| operator was misinterpreted by JDBC as a
            // bind parameter, causing "No value specified for parameter 5" when groupIds
            // was non-empty. This broke ALL group conversations on PostgreSQL.
            store.upsert(createEntry("user1", "self_key", "v1", "fact", Visibility.self, "agentA"));
            store.upsert(createEntry("user1", "global_key", "v2", "fact", Visibility.global, "agentA"));

            // Must not throw — before the fix, this threw PSQLException
            List<UserMemoryEntry> visible = store.getVisibleEntries("user1", "agentA",
                    List.of("group-123"), "most_recent", 50);

            assertTrue(visible.stream().anyMatch(e -> "self_key".equals(e.key())));
            assertTrue(visible.stream().anyMatch(e -> "global_key".equals(e.key())));
        }

        @Test
        @DisplayName("getVisibleEntries — group-scoped entries visible when groupIds match")
        void groupScopedEntriesVisible() throws IResourceStore.ResourceStoreException {
            var groupEntry = new UserMemoryEntry(null, "user1", "group_fact", "shared-value",
                    "fact", Visibility.group, "agentA", List.of("group-abc", "group-xyz"),
                    "conv1", false, 0, Instant.now(), Instant.now());
            store.upsert(groupEntry);
            store.upsert(createEntry("user1", "self_key", "v1", "fact", Visibility.self, "agentA"));

            // Query with a matching groupId
            List<UserMemoryEntry> visible = store.getVisibleEntries("user1", "agentA",
                    List.of("group-abc"), "most_recent", 50);

            assertTrue(visible.stream().anyMatch(e -> "group_fact".equals(e.key())),
                    "Group-scoped entry should be visible when groupIds overlap");
            assertTrue(visible.stream().anyMatch(e -> "self_key".equals(e.key())));

            // Query with a non-matching groupId — group entry should NOT appear
            List<UserMemoryEntry> noMatch = store.getVisibleEntries("user1", "agentA",
                    List.of("group-other"), "most_recent", 50);

            assertFalse(noMatch.stream().anyMatch(e -> "group_fact".equals(e.key())),
                    "Group-scoped entry should NOT be visible when groupIds don't overlap");
            assertTrue(noMatch.stream().anyMatch(e -> "self_key".equals(e.key())));
        }
    }

    // ─── Recall ordering and ownership (MongoDB parity) ─────────

    /**
     * Cross-backend parity with {@code MongoUserMemoryStoreRecallScopeTest}: these
     * two guarantees (G5 recency reservation, G7 global ownership) were implemented
     * on the MongoDB store only. Unlike the mocked Mongo tests these run against
     * the real schema, so they also prove the DDL and the SQL agree.
     */
    @Nested
    @DisplayName("Recall ordering and ownership")
    class RecallAndOwnership {

        @Test
        @DisplayName("G7 — a global entry keeps its original owning agent when another agent rewrites its value")
        void globalUpsertDoesNotTransferOwnership() throws IResourceStore.ResourceStoreException {
            store.upsert(createEntry("user1", "shared_pref", "de", "preference", Visibility.global, "agentA"));

            // agentB legitimately changes the shared value
            store.upsert(createEntry("user1", "shared_pref", "en", "preference", Visibility.global, "agentB"));

            var found = store.getByKey("user1", "shared_pref").orElseThrow();
            assertEquals("en", found.value(), "the value must be updated by the second writer");
            assertEquals("agentA", found.sourceAgentId(),
                    "ownership of a shared global entry must stay with the agent that created it");
        }

        @Test
        @DisplayName("G7 — a self entry is keyed per agent, so each agent keeps its own row")
        void selfEntriesRemainPerAgent() throws IResourceStore.ResourceStoreException {
            store.upsert(createEntry("user1", "note", "a-value", "fact", Visibility.self, "agentA"));
            store.upsert(createEntry("user1", "note", "b-value", "fact", Visibility.self, "agentB"));

            assertEquals(2, store.getAllEntries("user1").size(), "self entries are per-agent rows, not one shared row");
        }

        @Test
        @DisplayName("G5 — a brand-new entry (accessCount 0) still reaches a full most_accessed window")
        void mostAccessedReservesSlotsForRecentEntries() throws Exception {
            // 12 long-established entries with high, strictly descending access counts,
            // plus one freshly written entry with accessCount 0 and the newest
            // updated_at. With a plain "ORDER BY access_count DESC LIMIT 10" the
            // newcomer can never enter the window — and, never being recalled, never
            // accumulates a count either.
            store.countEntries("user1"); // force schema creation before raw SQL
            for (int i = 0; i < 12; i++) {
                insertRow("user1", "established_" + i, "agentA", 100 - i, 1000 - i);
            }
            insertRow("user1", "brand_new", "agentA", 0, 0);

            List<UserMemoryEntry> recalled = store.getVisibleEntries("user1", "agentA", null, "most_accessed", 10);

            List<String> keys = recalled.stream().map(UserMemoryEntry::key).toList();
            assertTrue(keys.contains("brand_new"),
                    "a newly written entry must reach the most_accessed recall window; got: " + keys);
            assertEquals(10, recalled.size(), "8 access slots + 2 reserved recency slots, de-duplicated");
            // 10 entries, 1/5th reserved => 8 by access count
            assertTrue(keys.contains("established_0"), keys.toString());
            assertFalse(keys.contains("established_8"), "slot 9/10 belongs to the recency reservation: " + keys);

            // The newcomer must also be counted as accessed, otherwise it drops straight
            // back out of the window and most_accessed stays self-reinforcing.
            assertEquals(1, accessCount("user1", "brand_new"),
                    "the entry recalled through the reserved recency slot must be counted as accessed too");
            assertEquals(101, accessCount("user1", "established_0"));
            assertEquals(92, accessCount("user1", "established_8"),
                    "an entry that was NOT recalled must not be incremented");
        }

        @Test
        @DisplayName("G5 — access_count is indexed so most_accessed is not a full scan + sort")
        void accessCountIsIndexed() throws Exception {
            store.countEntries("user1"); // force schema creation
            try (var conn = ds.getConnection();
                    var ps = conn.prepareStatement("SELECT indexdef FROM pg_indexes WHERE tablename = 'usermemories' AND indexname = ?")) {
                ps.setString(1, "idx_um_user_access_count");
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "expected an index on (user_id, access_count) for the most_accessed top-k");
                    assertTrue(rs.getString("indexdef").contains("access_count"), rs.getString("indexdef"));
                }
            }
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

    /**
     * Inserts a row with an explicit {@code access_count} and an {@code updated_at}
     * {@code secondsAgo} in the past — neither is settable through
     * {@link PostgresUserMemoryStore#upsert}.
     */
    private static void insertRow(String userId, String key, String agentId, int accessCount, int secondsAgo) throws SQLException {
        String sql = """
                INSERT INTO usermemories (user_id, key, value, category, visibility, source_agent_id,
                    group_ids, access_count, updated_at)
                VALUES (?, ?, ?::jsonb, 'fact', 'self', ?, '[]'::jsonb, ?, CURRENT_TIMESTAMP - INTERVAL '1 second' * ?)
                """;
        try (var conn = ds.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, key);
            ps.setString(3, "\"v-" + key + "\"");
            ps.setString(4, agentId);
            ps.setInt(5, accessCount);
            ps.setInt(6, secondsAgo);
            ps.executeUpdate();
        }
    }

    private static int accessCount(String userId, String key) throws SQLException {
        try (var conn = ds.getConnection();
                var ps = conn.prepareStatement("SELECT access_count FROM usermemories WHERE user_id = ? AND key = ?")) {
            ps.setString(1, userId);
            ps.setString(2, key);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a row for key " + key);
                return rs.getInt(1);
            }
        }
    }

    // ─── Owner-conditional global write ─────────────────────────

    @Nested
    @DisplayName("upsertIfOwnedBy")
    class OwnerConditionalGlobalWrite {

        @Test
        @DisplayName("a free key is written and stamped with the writer as owner")
        void freeKeyIsClaimed() throws Exception {
            assertTrue(store.upsertIfOwnedBy(ownedGlobal("u-own", "lang", "en", "agent-a"), "agent-a"));

            var stored = store.getAllEntries("u-own");
            assertEquals(1, stored.size());
            assertEquals("agent-a", stored.getFirst().sourceAgentId());
            assertEquals("en", stored.getFirst().value());
        }

        @Test
        @DisplayName("the owner can update its own key")
        void ownerUpdates() throws Exception {
            store.upsertIfOwnedBy(ownedGlobal("u-own", "lang", "en", "agent-a"), "agent-a");

            assertTrue(store.upsertIfOwnedBy(ownedGlobal("u-own", "lang", "de", "agent-a"), "agent-a"));

            var stored = store.getAllEntries("u-own");
            assertEquals(1, stored.size());
            assertEquals("de", stored.getFirst().value());
        }

        @Test
        @DisplayName("another agent's key is refused and left untouched")
        void otherAgentsKeyIsRefused() throws Exception {
            store.upsertIfOwnedBy(ownedGlobal("u-own", "lang", "en", "agent-a"), "agent-a");

            assertFalse(store.upsertIfOwnedBy(ownedGlobal("u-own", "lang", "fr", "agent-b"), "agent-b"));

            var stored = store.getAllEntries("u-own");
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
            store.mergeProperties("u-own", props);

            assertFalse(store.upsertIfOwnedBy(ownedGlobal("u-own", "lang", "fr", "agent-a"), "agent-a"));
            assertEquals("en", store.readProperties("u-own").get("lang"));
        }

        @Test
        @DisplayName("a key with a blank owner is refused")
        void blankOwnerIsRefused() throws Exception {
            store.upsert(ownedGlobal("u-own", "lang", "en", ""));

            assertFalse(store.upsertIfOwnedBy(ownedGlobal("u-own", "lang", "fr", "agent-a"), "agent-a"));
            assertEquals("en", store.getAllEntries("u-own").getFirst().value());
        }

        @Test
        @DisplayName("agents racing for a fresh key: exactly one wins, and its value and ownership stick")
        void concurrentClaimsHaveOneWinner() throws Exception {
            for (int round = 0; round < 10; round++) {
                String key = "race-" + round;
                List<String> winners = raceForFreshKey(store::upsertIfOwnedBy, "u-race", key, 8);

                assertEquals(1, winners.size(), "round " + round + ": winners " + winners);
                List<UserMemoryEntry> stored = store.getAllEntries("u-race").stream().filter(e -> key.equals(e.key())).toList();
                assertEquals(1, stored.size(), "round " + round + ": one global entry per key");
                assertEquals(winners.getFirst(), stored.getFirst().sourceAgentId());
                assertEquals("value-of-" + winners.getFirst(), stored.getFirst().value(),
                        "the loser's value must not overwrite the winner's");
            }
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

    private static UserMemoryEntry createEntry(String userId, String key, Object value,
                                               String category, Visibility visibility,
                                               String agentId) {
        return new UserMemoryEntry(null, userId, key, value, category, visibility,
                agentId, List.of(), "conv1", false, 0,
                Instant.now(), Instant.now());
    }
}
