package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.mongo.codec.JacksonProvider;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import de.undercouch.bson4jackson.BsonFactory;
import de.undercouch.bson4jackson.BsonParser;
import org.bson.codecs.*;
import org.bson.codecs.configuration.CodecRegistry;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.bson.codecs.configuration.CodecRegistries.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ConversationMemoryStore} (MongoDB) using
 * Testcontainers.
 * <p>
 * Requires a custom codec registry (JacksonProvider) because the store uses
 * {@code getCollection(name, ConversationMemorySnapshot.class)}.
 *
 * @since 6.0.0
 */
@Testcontainers
@DisplayName("MongoConversationMemoryStore IT")
class MongoConversationMemoryStoreIT {

    private static final String DB_NAME = "eddi_conv_test";

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    private static MongoClient mongoClient;
    private static MongoDatabase database;
    private static ConversationMemoryStore store;

    @BeforeAll
    static void init() {
        // Build BSON-aware ObjectMapper matching production PersistenceModule
        BsonFactory bsonFactory = new BsonFactory();
        bsonFactory.enable(BsonParser.Feature.HONOR_DOCUMENT_LENGTH);
        var bsonMapper = new ObjectMapper(bsonFactory);
        bsonMapper.registerModule(new JavaTimeModule());
        bsonMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        new SerializationCustomizer(false).customize(bsonMapper);

        CodecRegistry codecRegistry = fromRegistries(
                MongoClientSettings.getDefaultCodecRegistry(),
                fromCodecs(new RawBsonDocumentCodec()),
                fromProviders(
                        new ValueCodecProvider(),
                        new BsonValueCodecProvider(),
                        new DocumentCodecProvider(),
                        new IterableCodecProvider(),
                        new MapCodecProvider(),
                        new JacksonProvider(bsonMapper)));

        var settings = MongoClientSettings.builder()
                .applyConnectionString(new com.mongodb.ConnectionString(MONGO.getConnectionString()))
                .codecRegistry(codecRegistry)
                .build();

        mongoClient = MongoClients.create(settings);
        database = mongoClient.getDatabase(DB_NAME);
        store = new ConversationMemoryStore(database);
    }

    @AfterAll
    static void closeMongo() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @BeforeEach
    void clean() {
        database.getCollection("conversationmemories").drop();
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
            assertNull(store.loadConversationMemorySnapshot("000000000000000000000000"));
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
            assertNull(store.getConversationState("000000000000000000000000"));
        }
    }

    // ─── Delete ─────────────────────────────────────────────────

    @Test
    @DisplayName("deleteConversationMemorySnapshot — removes snapshot")
    void deleteSnapshot() {
        String id = store.storeConversationMemorySnapshot(
                createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS));

        store.deleteConversationMemorySnapshot(id);
        assertNull(store.loadConversationMemorySnapshot(id));
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
                    createSnapshot(null, "agent1", 1, "u3", ConversationState.IN_PROGRESS));
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
                    createSnapshot(null, "agent2", 1, "u2", ConversationState.IN_PROGRESS));
            store.setConversationState(endedId, ConversationState.ENDED);

            assertEquals(1L, store.getActiveConversationCount("agent2", 1));
        }

        @Test
        @DisplayName("getEndedConversationIds — returns only ENDED")
        void endedIds() {
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS));
            String endedId = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS));
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
        void createAndRead() throws IResourceStore.ResourceNotFoundException {
            var snapshot = createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS);
            var resourceId = store.create(snapshot);
            assertNotNull(resourceId.getId());

            var loaded = store.read(resourceId.getId(), resourceId.getVersion());
            assertNotNull(loaded);
        }

        @Test
        @DisplayName("getCurrentResourceId — returns same ID")
        void getCurrentResourceId() {
            var resourceId = store.getCurrentResourceId("test-id");
            assertEquals("test-id", resourceId.getId());
            assertEquals(0, resourceId.getVersion());
        }
    }

    // ─── GDPR ───────────────────────────────────────────────────

    @Nested
    @DisplayName("GDPR Operations")
    class GdprOps {

        @Test
        @DisplayName("getConversationIdsByUserId — finds by userId")
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
