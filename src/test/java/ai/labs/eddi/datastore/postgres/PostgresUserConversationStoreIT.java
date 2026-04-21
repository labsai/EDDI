package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PostgresUserConversationStore} using
 * Testcontainers.
 *
 * @since 6.0.0
 */
@DisplayName("PostgresUserConversationStore IT")
class PostgresUserConversationStoreIT extends PostgresTestBase {

    private static PostgresUserConversationStore store;
    private static DataSource ds;

    @BeforeAll
    static void init() {
        var dsInstance = createDataSourceInstance();
        ds = dsInstance.get();
        IJsonSerialization json = new JsonSerialization(new ObjectMapper());
        store = new PostgresUserConversationStore(dsInstance, json);
    }

    @BeforeEach
    void clean() {
        try {
            truncateTables(ds, "user_conversations");
        } catch (SQLException ignored) {
        }
    }

    // ─── CRUD ───────────────────────────────────────────────────

    @Nested
    @DisplayName("CRUD")
    class Crud {

        @Test
        @DisplayName("create + read round-trip")
        void createAndRead() throws Exception {
            var uc = createUserConversation("greet", "user1", "conv1", "agent1");
            store.createUserConversation(uc);

            var found = store.readUserConversation("greet", "user1");
            assertNotNull(found);
            assertEquals("greet", found.getIntent());
            assertEquals("user1", found.getUserId());
            assertEquals("conv1", found.getConversationId());
        }

        @Test
        @DisplayName("read non-existent — returns null")
        void readNonExistent() throws IResourceStore.ResourceStoreException {
            assertNull(store.readUserConversation("ghost", "nobody"));
        }

        @Test
        @DisplayName("create duplicate — throws ResourceAlreadyExistsException")
        void duplicateCreate() throws Exception {
            store.createUserConversation(
                    createUserConversation("dup", "user1", "c1", "a1"));

            assertThrows(IResourceStore.ResourceAlreadyExistsException.class,
                    () -> store.createUserConversation(
                            createUserConversation("dup", "user1", "c2", "a1")));
        }

        @Test
        @DisplayName("delete — removes by intent + userId")
        void delete() throws Exception {
            store.createUserConversation(
                    createUserConversation("del", "user1", "c1", "a1"));
            store.deleteUserConversation("del", "user1");

            assertNull(store.readUserConversation("del", "user1"));
        }

        @Test
        @DisplayName("same intent, different users — independent entries")
        void sameIntentDifferentUsers() throws Exception {
            store.createUserConversation(
                    createUserConversation("shared", "user1", "c1", "a1"));
            store.createUserConversation(
                    createUserConversation("shared", "user2", "c2", "a1"));

            assertNotNull(store.readUserConversation("shared", "user1"));
            assertNotNull(store.readUserConversation("shared", "user2"));
        }
    }

    // ─── GDPR ───────────────────────────────────────────────────

    @Nested
    @DisplayName("GDPR Operations")
    class GdprOps {

        @Test
        @DisplayName("getAllForUser — returns all conversations for user")
        void getAllForUser() throws Exception {
            store.createUserConversation(
                    createUserConversation("i1", "target", "c1", "a1"));
            store.createUserConversation(
                    createUserConversation("i2", "target", "c2", "a1"));
            store.createUserConversation(
                    createUserConversation("i3", "other", "c3", "a1"));

            List<UserConversation> result = store.getAllForUser("target");
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("deleteAllForUser — removes all for user")
        void deleteAllForUser() throws Exception {
            store.createUserConversation(
                    createUserConversation("i1", "delete_me", "c1", "a1"));
            store.createUserConversation(
                    createUserConversation("i2", "delete_me", "c2", "a1"));
            store.createUserConversation(
                    createUserConversation("i3", "keep_me", "c3", "a1"));

            long deleted = store.deleteAllForUser("delete_me");
            assertEquals(2, deleted);
            assertTrue(store.getAllForUser("delete_me").isEmpty());
            assertEquals(1, store.getAllForUser("keep_me").size());
        }

        @Test
        @DisplayName("deleteAllForUser non-existent — returns 0")
        void deleteNonExistent() {
            assertEquals(0, store.deleteAllForUser("ghost"));
        }
    }

    // ─── Helpers ────────────────────────────────────────────────

    private static UserConversation createUserConversation(String intent, String userId,
                                                           String conversationId, String agentId) {
        var uc = new UserConversation();
        uc.setIntent(intent);
        uc.setUserId(userId);
        uc.setConversationId(conversationId);
        uc.setAgentId(agentId);
        return uc;
    }
}
