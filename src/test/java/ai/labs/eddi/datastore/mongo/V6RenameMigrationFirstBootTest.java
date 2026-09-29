/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.configs.migration.IMigrationLogStore;
import ai.labs.eddi.configs.migration.V6RenameMigration;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.mongo.codec.JacksonProvider;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import de.undercouch.bson4jackson.BsonFactory;
import de.undercouch.bson4jackson.BsonParser;
import org.bson.Document;
import org.bson.codecs.BsonValueCodecProvider;
import org.bson.codecs.DocumentCodecProvider;
import org.bson.codecs.IterableCodecProvider;
import org.bson.codecs.MapCodecProvider;
import org.bson.codecs.RawBsonDocumentCodec;
import org.bson.codecs.ValueCodecProvider;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;

import static org.bson.codecs.configuration.CodecRegistries.fromCodecs;
import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rename migration and the conversation store against EDDI 5-shaped
 * documents in a real MongoDB, read back through the production codec.
 *
 * <p>
 * Two first-boot failures are covered here. EDDI 5 named the LLM workflow step
 * {@code eddi://ai.labs.langchain}, which 6.x does not register, so every agent
 * with an LLM step stayed in ERROR. And EDDI 5 stored a conversation step's
 * runs under {@code packages}, a key the 6.x snapshot silently ignored — every
 * old conversation loaded with empty steps, and the next save wrote them back.
 * </p>
 *
 * <p>
 * All ids and values are synthetic.
 * </p>
 */
@Testcontainers
@DisplayName("V6RenameMigration — a first boot against EDDI 5 workflows and conversations")
class V6RenameMigrationFirstBootTest {

    private static final String CONVERSATIONS = "conversationmemories";
    private static final ObjectId CONVERSATION_ID = new ObjectId("0000000000000000000000d1");
    private static final ObjectId WORKFLOW_ID = new ObjectId("0000000000000000000000d2");
    private static final String ORIGIN = "0000000000000000000000d2";

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    private static MongoClient mongoClient;
    private static MongoDatabase database;

    private IMigrationLogStore migrationLog;

    @BeforeAll
    static void init() {
        // The codec the production PersistenceModule builds, so the store reads
        // exactly as it does in a running EDDI.
        BsonFactory bsonFactory = new BsonFactory();
        bsonFactory.enable(BsonParser.Feature.HONOR_DOCUMENT_LENGTH);
        var bsonMapper = new ObjectMapper(bsonFactory);
        bsonMapper.registerModule(new JavaTimeModule());
        bsonMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        new SerializationCustomizer(false).customize(bsonMapper);

        CodecRegistry codecRegistry = fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
                fromCodecs(new RawBsonDocumentCodec()), fromProviders(new ValueCodecProvider(), new BsonValueCodecProvider(),
                        new DocumentCodecProvider(), new IterableCodecProvider(), new MapCodecProvider(), new JacksonProvider(bsonMapper)));

        mongoClient = MongoClients.create(MongoClientSettings.builder().applyConnectionString(new ConnectionString(MONGO.getConnectionString()))
                .codecRegistry(codecRegistry).build());
        database = mongoClient.getDatabase("eddi_first_boot_test");
    }

    @AfterAll
    static void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @BeforeEach
    void clean() {
        database.drop();
        migrationLog = mock(IMigrationLogStore.class);
        when(migrationLog.readMigrationLog(any())).thenReturn(null);
    }

    private void runMigration() {
        new V6RenameMigration(database, migrationLog, true).runIfNeeded();
    }

    private MongoCollection<Document> conversations() {
        return database.getCollection(CONVERSATIONS);
    }

    // --- fixtures -------------------------------------------------------------

    private static Document v5Result(String key, Object result) {
        return new Document("key", key).append("result", result).append("possibleResults", List.of(result))
                .append("timestamp", 1_700_000_000_000L).append("originPackageId", ORIGIN).append("public", true);
    }

    /**
     * A step as EDDI 5 stored it: its runs under {@code packages}, each result's
     * timestamp as epoch milliseconds (a BSON long, as in every v5 document seen).
     */
    private static Document v5Step(Document... results) {
        return new Document("packages", List.of(new Document("lifecycleTasks", List.of(results))));
    }

    /**
     * A conversation as EDDI 5 stored it, with two steps of two and three items.
     */
    private static Document v5Conversation() {
        return new Document("_id", CONVERSATION_ID).append("botId", "0000000000000000000000d3").append("botVersion", 1)
                .append("environment", "unrestricted").append("conversationState", "ENDED").append("userId", "synthetic-user")
                .append("conversationProperties", new Document())
                .append("conversationOutputs", List.of(new Document("input", "hello"), new Document("input", "and again")))
                .append("conversationSteps",
                        List.of(v5Step(v5Result("input:initial", "hello"), v5Result("actions", List.of("greet"))),
                                v5Step(v5Result("input:initial", "and again"), v5Result("actions", List.of("repeat")),
                                        v5Result("output:text:repeat", "said it"))))
                .append("redoCache", List.of(v5Step(v5Result("input:initial", "undone"))));
    }

    private static List<Integer> itemCounts(ConversationMemorySnapshot snapshot) {
        List<Integer> counts = new ArrayList<>();
        for (var step : snapshot.getConversationSteps()) {
            int items = 0;
            for (var run : step.getWorkflows()) {
                items += run.getLifecycleTasks().size();
            }
            counts.add(items);
        }
        return counts;
    }

    private static ConversationMemorySnapshot load() {
        var snapshot = new ConversationMemoryStore(database).loadConversationMemorySnapshot(CONVERSATION_ID.toHexString());
        assertNotNull(snapshot, "the conversation loads");
        return snapshot;
    }

    private static void assertLoadsWithEveryItem(ConversationMemorySnapshot snapshot) {
        assertEquals(List.of(2, 3), itemCounts(snapshot), "every step keeps the items EDDI 5 stored");
        var first = snapshot.getConversationSteps().getFirst().getWorkflows().getFirst().getLifecycleTasks().getFirst();
        assertEquals("input:initial", first.getKey());
        assertEquals("hello", first.getResult());
        assertEquals(ORIGIN, first.getOriginWorkflowId(), "the v5 origin field is carried over");
        assertEquals(1, snapshot.getRedoCache().size());
        assertEquals(1, snapshot.getRedoCache().getFirst().getWorkflows().getFirst().getLifecycleTasks().size());
    }

    // --- conversations --------------------------------------------------------

    @Test
    @DisplayName("the migration renames the stored step shape, and the conversation loads with every item")
    void migrationRenamesStepShape() {
        conversations().insertOne(v5Conversation());

        runMigration();

        verify(migrationLog).createMigrationLog(any());
        Document stored = conversations().find().first();
        assertNotNull(stored);
        for (String array : List.of("conversationSteps", "redoCache")) {
            for (Document step : stored.getList(array, Document.class)) {
                assertFalse(step.containsKey("packages"), array + " still holds 'packages'");
                Document run = step.getList("workflows", Document.class).getFirst();
                for (Document result : run.getList("lifecycleTasks", Document.class)) {
                    assertFalse(result.containsKey("originPackageId"));
                    assertEquals(ORIGIN, result.getString("originWorkflowId"));
                }
            }
        }
        assertLoadsWithEveryItem(load());
    }

    @Test
    @DisplayName("a migrated conversation saved without changes and reloaded keeps every item")
    void saveRoundTripAfterMigration() throws Exception {
        conversations().insertOne(v5Conversation());
        runMigration();

        var store = new ConversationMemoryStore(database);
        store.storeConversationMemorySnapshot(load());

        assertLoadsWithEveryItem(load());
    }

    @Test
    @DisplayName("a v5 conversation the migration has not reached still loads, and survives a save")
    void unmigratedConversationLoadsThroughTheAlias() throws Exception {
        conversations().insertOne(v5Conversation());

        var snapshot = load();
        assertLoadsWithEveryItem(snapshot);

        new ConversationMemoryStore(database).storeConversationMemorySnapshot(snapshot);
        assertLoadsWithEveryItem(load());
    }

    @Test
    @DisplayName("running the step rename twice changes nothing the second time")
    void stepRenameIsIdempotent() {
        conversations().insertOne(v5Conversation());
        runMigration();
        Document afterFirst = conversations().find().first();

        runMigration();

        assertEquals(afterFirst, conversations().find().first());
    }

    @Test
    @DisplayName("a step that holds neither key is left as it is and does not keep the migration incomplete")
    void stepWithNeitherKeyIsReportedNotFailed() {
        Document conversation = v5Conversation();
        List<Document> steps = new ArrayList<>(conversation.getList("conversationSteps", Document.class));
        steps.add(new Document("conversationOutput", new Document()));
        conversation.put("conversationSteps", steps);
        conversations().insertOne(conversation);

        runMigration();

        verify(migrationLog).createMigrationLog(any());
        List<Document> stored = conversations().find().first().getList("conversationSteps", Document.class);
        assertEquals(new Document("conversationOutput", new Document()), stored.get(2));
        assertTrue(stored.get(0).containsKey("workflows"));
    }

    @Test
    @DisplayName("a step holding both keys with non-empty workflows is left unchanged rather than guessed at")
    void stepWithBothKeysIsLeftAlone() {
        Document conversation = v5Conversation();
        Document ambiguous = v5Step(v5Result("input:initial", "old")).append("workflows",
                List.of(new Document("lifecycleTasks", List.of(new Document("key", "input:initial").append("result", "new")))));
        conversation.put("conversationSteps", List.of(ambiguous));
        conversations().insertOne(conversation);

        runMigration();

        Document step = conversations().find().first().getList("conversationSteps", Document.class).getFirst();
        assertTrue(step.containsKey("packages"));
        assertEquals("new", step.getList("workflows", Document.class).getFirst().getList("lifecycleTasks", Document.class).getFirst()
                .getString("result"));
    }

    @Test
    @DisplayName("a step with packages and a null workflows is renamed, like one without workflows")
    void stepWithNullWorkflowsIsRenamed() {
        Document conversation = v5Conversation();
        Document step = v5Step(v5Result("input:initial", "hello")).append("workflows", null);
        conversation.put("conversationSteps", List.of(step));
        conversations().insertOne(conversation);

        runMigration();

        Document stored = conversations().find().first().getList("conversationSteps", Document.class).getFirst();
        assertFalse(stored.containsKey("packages"));
        assertEquals(1, stored.getList("workflows", Document.class).size());
    }

    @Test
    @DisplayName("the v5 field names and environment are migrated server-side, and the revision is bumped")
    void conversationFieldsAreMigratedServerSide() {
        conversations().insertOne(v5Conversation());

        runMigration();

        Document stored = conversations().find().first();
        assertFalse(stored.containsKey("botId"));
        assertFalse(stored.containsKey("botVersion"));
        assertEquals("0000000000000000000000d3", stored.getString("agentId"));
        assertEquals(1, ((Number) stored.get("agentVersion")).intValue());
        assertEquals("production", stored.getString("environment"));
        long revision = ((Number) stored.get("_rev")).longValue();
        assertTrue(revision > 0, "the migration writes count as revisions");
        assertEquals(revision, ((Number) stored.get("_histRev")).longValue());
    }

    /**
     * The race the old whole-document replace had: a writer that loaded the
     * conversation before the migration must not be able to write its stale copy
     * over the migrated one. With the revision bumped, the store refuses it like
     * any other concurrent write.
     */
    @Test
    @DisplayName("a copy loaded before the migration cannot be written back over it")
    void staleWriteAfterMigrationIsRefused() {
        conversations().insertOne(v5Conversation());
        var loadedBeforeMigration = load();

        runMigration();

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> new ConversationMemoryStore(database).storeConversationMemorySnapshot(loadedBeforeMigration));
        assertEquals("0000000000000000000000d3", conversations().find().first().getString("agentId"));
    }

    @Test
    @DisplayName("a conversation holding both botId and a different agentId is left unchanged")
    void ambiguousConversationIsLeftAlone() {
        Document conversation = v5Conversation().append("agentId", "0000000000000000000000d9");
        conversation.put("conversationSteps", List.of());
        conversation.put("redoCache", List.of());
        conversations().insertOne(conversation);

        runMigration();

        Document stored = conversations().find().first();
        assertEquals("0000000000000000000000d3", stored.getString("botId"));
        assertEquals("0000000000000000000000d9", stored.getString("agentId"));
        verify(migrationLog).createMigrationLog(any());
    }

    @Test
    @DisplayName("the migration knows it ran in this process; an already-applied one does not claim to")
    void ranInThisProcessIsReported() {
        var migration = new V6RenameMigration(database, migrationLog, true);
        assertFalse(migration.ranInThisProcess());
        migration.runIfNeeded();
        assertTrue(migration.ranInThisProcess());

        IMigrationLogStore applied = mock(IMigrationLogStore.class);
        when(applied.readMigrationLog(any())).thenReturn(new MigrationLog("v6-rename-migration-complete"));
        var later = new V6RenameMigration(database, applied, true);
        later.runIfNeeded();
        assertFalse(later.ranInThisProcess());
    }

    // --- workflows ------------------------------------------------------------

    private static Document v5Package(Object id) {
        return new Document("_id", id).append("_version", 1).append("packageExtensions",
                List.of(new Document("type", "eddi://ai.labs.behavior").append("config",
                        new Document("uri", "eddi://ai.labs.behavior/behaviorstore/behaviorsets/0000000000000000000000d4?version=1")),
                        new Document("type", "eddi://ai.labs.langchain").append("config",
                                new Document("uri", "eddi://ai.labs.langchain/langchainstore/langchains/0000000000000000000000d5?version=1")),
                        new Document("type", "eddi://ai.labs.httpcalls")));
    }

    @Test
    @DisplayName("the LLM step type is renamed in current and history workflows; other step types and config URIs as before")
    void workflowStepTypesAreMigrated() {
        database.getCollection("packages").insertOne(v5Package(WORKFLOW_ID));
        database.getCollection("packages.history").insertOne(v5Package(new Document("_id", WORKFLOW_ID).append("_version", 1)));

        runMigration();

        verify(migrationLog).createMigrationLog(any());
        for (String collection : List.of("workflows", "workflows.history")) {
            Document workflow = database.getCollection(collection).find().first();
            assertNotNull(workflow, collection + " holds the renamed workflow");
            List<Document> steps = workflow.getList("packageExtensions", Document.class);
            assertEquals(List.of("eddi://ai.labs.behavior", "eddi://ai.labs.llm", "eddi://ai.labs.httpcalls"),
                    steps.stream().map(step -> step.getString("type")).toList(), collection);
            assertEquals("eddi://ai.labs.rules/rulestore/rulesets/0000000000000000000000d4?version=1",
                    steps.get(0).get("config", Document.class).getString("uri"));
            assertEquals("eddi://ai.labs.llm/llmstore/llms/0000000000000000000000d5?version=1",
                    steps.get(1).get("config", Document.class).getString("uri"));
        }
    }
}
