package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.audit.AuditStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link AuditStore} (MongoDB) using Testcontainers.
 *
 * @since 6.0.0
 */
@DisplayName("MongoAuditStore IT")
class MongoAuditStoreTest extends MongoTestBase {

    private static AuditStore store;

    @BeforeAll
    static void init() {
        store = new AuditStore(getDatabase());
    }

    @BeforeEach
    void clean() {
        dropCollections("audit_ledger");
    }

    // ─── Core CRUD ──────────────────────────────────────────────

    @Nested
    @DisplayName("appendEntry + query")
    class AppendAndQuery {

        @Test
        @DisplayName("single entry round-trip")
        void singleEntry() {
            AuditEntry entry = createEntry("conv1", "agent1", 1, "user1", "parser", "input", 0, 0, 42L);
            store.appendEntry(entry);

            List<AuditEntry> results = store.getEntries("conv1", 0, 10);
            assertEquals(1, results.size());
            assertEquals("conv1", results.getFirst().conversationId());
            assertEquals("agent1", results.getFirst().agentId());
            assertEquals(42L, results.getFirst().durationMs());
        }

        @Test
        @DisplayName("multiple entries — ordered by timestamp DESC")
        void multipleEntries() {
            store.appendEntry(createEntry("conv1", "agent1", 1, "user1", "parser", "input", 0, 0, 10L));
            store.appendEntry(createEntry("conv1", "agent1", 1, "user1", "behavior", "rules", 0, 1, 20L));
            store.appendEntry(createEntry("conv1", "agent1", 1, "user1", "llm", "langchain", 0, 2, 30L));

            List<AuditEntry> results = store.getEntries("conv1", 0, 10);
            assertEquals(3, results.size());
            var taskIds = results.stream().map(AuditEntry::taskId).toList();
            assertTrue(taskIds.contains("parser"));
            assertTrue(taskIds.contains("behavior"));
            assertTrue(taskIds.contains("llm"));
        }

        @Test
        @DisplayName("getEntries with skip and limit")
        void skipAndLimit() {
            for (int i = 0; i < 5; i++) {
                store.appendEntry(createEntry("conv2", "a", 1, "u", "task" + i, "t", 0, i, i));
            }

            List<AuditEntry> page1 = store.getEntries("conv2", 0, 2);
            assertEquals(2, page1.size());

            List<AuditEntry> page2 = store.getEntries("conv2", 2, 2);
            assertEquals(2, page2.size());
        }

        @Test
        @DisplayName("empty conversation — returns empty list")
        void emptyConversation() {
            List<AuditEntry> results = store.getEntries("nonexistent", 0, 10);
            assertTrue(results.isEmpty());
        }
    }

    // ─── Batch insert ───────────────────────────────────────────

    @Nested
    @DisplayName("appendBatch")
    class BatchInsert {

        @Test
        @DisplayName("batch insert multiple entries")
        void batchInsert() {
            var entries = List.of(
                    createEntry("conv3", "a", 1, "u", "t1", "t", 0, 0, 10),
                    createEntry("conv3", "a", 1, "u", "t2", "t", 0, 1, 20));
            store.appendBatch(entries);

            assertEquals(2, store.countByConversation("conv3"));
        }

        @Test
        @DisplayName("null batch — no-op")
        void nullBatch() {
            assertDoesNotThrow(() -> store.appendBatch(null));
        }

        @Test
        @DisplayName("empty batch — no-op")
        void emptyBatch() {
            assertDoesNotThrow(() -> store.appendBatch(List.of()));
        }
    }

    // ─── Query by agent/user ────────────────────────────────────

    @Nested
    @DisplayName("Query by agent and user")
    class QueryFilters {

        @Test
        @DisplayName("getEntriesByAgent — filters by agentId")
        void byAgent() {
            store.appendEntry(createEntry("c1", "agentA", 1, "u", "t", "t", 0, 0, 10));
            store.appendEntry(createEntry("c2", "agentB", 1, "u", "t", "t", 0, 0, 10));

            List<AuditEntry> results = store.getEntriesByAgent("agentA", null, 0, 10);
            assertEquals(1, results.size());
            assertEquals("agentA", results.getFirst().agentId());
        }

        @Test
        @DisplayName("getEntriesByAgent — filters by agentId + version")
        void byAgentAndVersion() {
            store.appendEntry(createEntry("c1", "agentA", 1, "u", "t", "t", 0, 0, 10));
            store.appendEntry(createEntry("c2", "agentA", 2, "u", "t", "t", 0, 0, 10));

            List<AuditEntry> results = store.getEntriesByAgent("agentA", 2, 0, 10);
            assertEquals(1, results.size());
            assertEquals(2, results.getFirst().agentVersion());
        }

        @Test
        @DisplayName("getEntriesByUserId — filters by userId")
        void byUser() {
            store.appendEntry(createEntry("c1", "a", 1, "user1", "t", "t", 0, 0, 10));
            store.appendEntry(createEntry("c2", "a", 1, "user2", "t", "t", 0, 0, 10));

            List<AuditEntry> results = store.getEntriesByUserId("user1", 0, 10);
            assertEquals(1, results.size());
        }

        @Test
        @DisplayName("countByConversation — returns correct count")
        void count() {
            store.appendEntry(createEntry("conv_count", "a", 1, "u", "t1", "t", 0, 0, 10));
            store.appendEntry(createEntry("conv_count", "a", 1, "u", "t2", "t", 0, 1, 20));
            store.appendEntry(createEntry("other", "a", 1, "u", "t3", "t", 0, 0, 30));

            assertEquals(2, store.countByConversation("conv_count"));
            assertEquals(1, store.countByConversation("other"));
            assertEquals(0, store.countByConversation("nonexistent"));
        }
    }

    // ─── GDPR ───────────────────────────────────────────────────

    @Nested
    @DisplayName("GDPR Pseudonymization")
    class Gdpr {

        @Test
        @DisplayName("pseudonymize replaces userId")
        void pseudonymize() {
            store.appendEntry(createEntry("c1", "a", 1, "real_user", "t", "t", 0, 0, 10));
            store.appendEntry(createEntry("c2", "a", 1, "real_user", "t", "t", 0, 0, 10));
            store.appendEntry(createEntry("c3", "a", 1, "other_user", "t", "t", 0, 0, 10));

            long updated = store.pseudonymizeByUserId("real_user", "anon_123");
            assertEquals(2, updated);

            // Verify pseudonymization
            List<AuditEntry> results = store.getEntriesByUserId("anon_123", 0, 10);
            assertEquals(2, results.size());
            assertTrue(store.getEntriesByUserId("real_user", 0, 10).isEmpty());
        }

        @Test
        @DisplayName("pseudonymize non-existent user — returns 0")
        void pseudonymizeNonExistent() {
            assertEquals(0, store.pseudonymizeByUserId("ghost", "anon"));
        }
    }

    // ─── JSONB data round-trip ──────────────────────────────────

    @Test
    @DisplayName("BSON data — input/output/actions round-trip")
    void bsonDataRoundTrip() {
        var entry = new AuditEntry(UUID.randomUUID().toString(), "conv_json", "agent1", 1,
                "user1", "test", 0, "llm_task", "langchain", 0, 100L,
                Map.of("userInput", "hello"), Map.of("output", "world"),
                Map.of("compiledPrompt", "You are...", "modelName", "gpt-4"),
                null, List.of("greet", "respond"), 0.05,
                Instant.now().truncatedTo(ChronoUnit.MILLIS), "hmac_val", null);

        store.appendEntry(entry);

        var results = store.getEntries("conv_json", 0, 10);
        assertEquals(1, results.size());
        var found = results.getFirst();
        assertEquals("hello", found.input().get("userInput"));
        assertNotNull(found.output());
        assertEquals(List.of("greet", "respond"), found.actions());
        assertEquals("hmac_val", found.hmac());
    }

    // ─── Helpers ────────────────────────────────────────────────

    private static AuditEntry createEntry(String convId, String agentId, int version,
                                          String userId, String taskId, String taskType,
                                          int stepIdx, int taskIdx, long durationMs) {
        return new AuditEntry(UUID.randomUUID().toString(), convId, agentId, version,
                userId, "test", stepIdx, taskId, taskType, taskIdx, durationMs,
                null, null, null, null, null, 0.0,
                Instant.now(), null, null);
    }
}
