package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PostgresConversationMemoryStore} using
 * Testcontainers.
 * <p>
 * Covers snapshot CRUD, state transitions, active conversation queries, and
 * GDPR operations.
 *
 * @since 6.0.0
 */
@DisplayName("PostgresConversationMemoryStore IT")
class PostgresConversationMemoryStoreTest extends PostgresTestBase {

    private static PostgresConversationMemoryStore store;
    private static DataSource ds;

    @BeforeAll
    static void init() {
        var dsInstance = createDataSourceInstance();
        ds = dsInstance.get();
        IJsonSerialization json = new JsonSerialization(new ObjectMapper());
        store = new PostgresConversationMemoryStore(dsInstance, json);
    }

    @BeforeEach
    void clean() {
        try {
            truncateTables(ds, "conversation_memories");
        } catch (SQLException ignored) {
        }
    }

    // ─── Store + Load ───────────────────────────────────────────

    @Nested
    @DisplayName("Store and Load")
    class StoreAndLoad {

        @Test
        @DisplayName("store new snapshot — generates ID and round-trips")
        void storeNewSnapshot() {
            var snapshot = createSnapshot(null, "agent1", 1, "user1",
                    ConversationState.IN_PROGRESS);

            String id = store.storeConversationMemorySnapshot(snapshot);
            assertNotNull(id);

            var loaded = store.loadConversationMemorySnapshot(id);
            assertNotNull(loaded);
            assertEquals(id, loaded.getConversationId());
            assertEquals("agent1", loaded.getAgentId());
            assertEquals(1, loaded.getAgentVersion());
            assertEquals(ConversationState.IN_PROGRESS, loaded.getConversationState());
        }

        @Test
        @DisplayName("update existing snapshot — preserves ID, updates state")
        void updateExistingSnapshot() {
            var snapshot = createSnapshot(null, "agent1", 1, "user1",
                    ConversationState.IN_PROGRESS);
            String id = store.storeConversationMemorySnapshot(snapshot);

            // Update state
            snapshot.setConversationId(id);
            snapshot.setId(id);
            snapshot.setConversationState(ConversationState.ENDED);
            store.storeConversationMemorySnapshot(snapshot);

            var loaded = store.loadConversationMemorySnapshot(id);
            assertEquals(ConversationState.ENDED, loaded.getConversationState());
        }

        @Test
        @DisplayName("load non-existent — returns null")
        void loadNonExistent() {
            assertNull(store.loadConversationMemorySnapshot(
                    "00000000-0000-0000-0000-000000000000"));
        }
    }

    // ─── State Management ───────────────────────────────────────

    @Nested
    @DisplayName("State Management")
    class StateManagement {

        @Test
        @DisplayName("setConversationState — updates state")
        void setConversationState() {
            String id = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS));

            store.setConversationState(id, ConversationState.ENDED);

            assertEquals(ConversationState.ENDED, store.getConversationState(id));
        }

        @Test
        @DisplayName("getConversationState — returns null for non-existent")
        void getStateNonExistent() {
            assertNull(store.getConversationState("00000000-0000-0000-0000-000000000000"));
        }
    }

    // ─── Delete ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Delete")
    class Delete {

        @Test
        @DisplayName("deleteConversationMemorySnapshot — removes snapshot")
        void deleteSnapshot() {
            String id = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS));

            store.deleteConversationMemorySnapshot(id);

            assertNull(store.loadConversationMemorySnapshot(id));
        }
    }

    // ─── Active Conversation Queries ────────────────────────────

    @Nested
    @DisplayName("Active Conversation Queries")
    class ActiveQueries {

        @Test
        @DisplayName("loadActiveConversationMemorySnapshot — excludes ENDED")
        void loadActive() throws IResourceStore.ResourceStoreException {
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "u1", ConversationState.IN_PROGRESS));
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "u2", ConversationState.IN_PROGRESS));
            String endedId = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "u3", ConversationState.ENDED));
            // Also set state via the dedicated method to match column
            store.setConversationState(endedId, ConversationState.ENDED);

            List<ConversationMemorySnapshot> active = store.loadActiveConversationMemorySnapshot("agent1", 1);
            assertEquals(2, active.size());
        }

        @Test
        @DisplayName("getActiveConversationCount — counts non-ENDED only")
        void activeCount() {
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent2", 1, "u1", ConversationState.IN_PROGRESS));
            String endedId = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent2", 1, "u2", ConversationState.ENDED));
            store.setConversationState(endedId, ConversationState.ENDED);

            assertEquals(1L, store.getActiveConversationCount("agent2", 1));
        }

        @Test
        @DisplayName("getEndedConversationIds — returns only ENDED")
        void endedIds() {
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS));
            String endedId = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "u", ConversationState.ENDED));
            store.setConversationState(endedId, ConversationState.ENDED);

            List<String> ended = store.getEndedConversationIds();
            assertEquals(1, ended.size());
            assertEquals(endedId, ended.getFirst());
        }
    }

    // ─── IResourceStore Adapter ─────────────────────────────────

    @Nested
    @DisplayName("IResourceStore Adapter")
    class ResourceStoreAdapter {

        @Test
        @DisplayName("create + read round-trip")
        void createAndRead() {
            var snapshot = createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS);
            var resourceId = store.create(snapshot);
            assertNotNull(resourceId.getId());

            var loaded = store.read(resourceId.getId(), resourceId.getVersion());
            assertNotNull(loaded);
        }

        @Test
        @DisplayName("delete — removes via IResourceStore interface")
        void deleteViaAdapter() {
            String id = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS));
            store.delete(id, 0);

            assertNull(store.read(id, 0));
        }

        @Test
        @DisplayName("deleteAllPermanently — removes via IResourceStore interface")
        void deleteAllPermanently() {
            String id = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS));
            store.deleteAllPermanently(id);

            assertNull(store.read(id, 0));
        }
    }

    // ─── GDPR ───────────────────────────────────────────────────

    @Nested
    @DisplayName("GDPR Operations")
    class GdprOps {

        @Test
        @DisplayName("getConversationIdsByUserId — finds by userId in JSONB")
        void getByUserId() {
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "target_user", ConversationState.IN_PROGRESS));
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "target_user", ConversationState.ENDED));
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "other_user", ConversationState.IN_PROGRESS));

            List<String> ids = store.getConversationIdsByUserId("target_user");
            assertEquals(2, ids.size());
        }

        @Test
        @DisplayName("deleteConversationsByUserId — removes all for user")
        void deleteByUserId() {
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "delete_me", ConversationState.IN_PROGRESS));
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "delete_me", ConversationState.ENDED));
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "keep_me", ConversationState.IN_PROGRESS));

            long deleted = store.deleteConversationsByUserId("delete_me");
            assertEquals(2, deleted);
            assertTrue(store.getConversationIdsByUserId("delete_me").isEmpty());
            assertEquals(1, store.getConversationIdsByUserId("keep_me").size());
        }
    }

    // ─── Helpers ────────────────────────────────────────────────

    private static ConversationMemorySnapshot createSnapshot(String id, String agentId,
                                                             int agentVersion, String userId,
                                                             ConversationState state) {
        var snapshot = new ConversationMemorySnapshot();
        if (id != null) {
            snapshot.setId(id);
            snapshot.setConversationId(id);
        }
        snapshot.setAgentId(agentId);
        snapshot.setAgentVersion(agentVersion);
        snapshot.setUserId(userId);
        snapshot.setConversationState(state);
        snapshot.setEnvironment(Deployment.Environment.production);
        return snapshot;
    }
}
