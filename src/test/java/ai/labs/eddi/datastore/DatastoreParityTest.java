/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.mongo.MongoUserMemoryStore;
import ai.labs.eddi.datastore.mongo.MongoResourceStorage;
import ai.labs.eddi.datastore.mongo.MongoTestBase;
import ai.labs.eddi.datastore.mongo.codec.JacksonProvider;
import ai.labs.eddi.datastore.postgres.PostgresConversationMemoryStore;
import ai.labs.eddi.datastore.postgres.PostgresResourceStorage;
import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.datastore.postgres.PostgresUserMemoryStore;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import org.bson.codecs.configuration.CodecRegistries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The same store contract, run against a real MongoDB and a real PostgreSQL.
 *
 * <p>
 * Each case here is a place where the two backends answered the same request
 * differently, found by running one 170-check live suite against both: unknown
 * and malformed ids (PostgreSQL 500 where MongoDB said 404), user-memory search
 * (PostgreSQL matched the value's JSON text), and the position of documents
 * that lack the sort field (first on PostgreSQL, last on MongoDB). Unit tests
 * pin each implementation; this pins that they agree — a new divergence fails
 * here, on both rows of a parameterised case, instead of in production on one.
 * </p>
 */
@DisplayName("Datastore parity — MongoDB and PostgreSQL answer alike")
class DatastoreParityTest extends MongoTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    static Stream<Arguments> resourceStorages() {
        Supplier<IResourceStorage<Map<String, Object>>> mongo = () -> new MongoResourceStorage<>(getDatabase(), "parity" + SEQ.incrementAndGet(),
                documentBuilder, mapType());
        Supplier<IResourceStorage<Map<String, Object>>> postgres = () -> new PostgresResourceStorage<>(
                PostgresTestBase.createDataSourceInstance().get(), "parity" + SEQ.incrementAndGet(), jsonSerialization, mapType());
        return Stream.of(Arguments.of("mongo", mongo), Arguments.of("postgres", postgres));
    }

    static Stream<Arguments> conversationStores() {
        // The production codec registry (PersistenceModule) maps the snapshot type; the
        // shared test client only has the driver defaults.
        Supplier<IConversationMemoryStore> mongo = () -> new ConversationMemoryStore(getDatabase().withCodecRegistry(
                CodecRegistries.fromRegistries(getDatabase().getCodecRegistry(),
                        CodecRegistries.fromProviders(new JacksonProvider(objectMapper)))));
        Supplier<IConversationMemoryStore> postgres = () -> new PostgresConversationMemoryStore(PostgresTestBase.createDataSourceInstance(),
                jsonSerialization);
        return Stream.of(Arguments.of("mongo", mongo), Arguments.of("postgres", postgres));
    }

    static Stream<Arguments> userMemoryStores() {
        Supplier<IUserMemoryStore> mongo = () -> new MongoUserMemoryStore(getDatabase());
        Supplier<IUserMemoryStore> postgres = () -> new PostgresUserMemoryStore(PostgresTestBase.createDataSourceInstance());
        return Stream.of(Arguments.of("mongo", mongo), Arguments.of("postgres", postgres));
    }

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    /**
     * Unknown on both, malformed on both, and each backend's native id on the
     * other.
     */
    private static final List<String> FOREIGN_OR_MALFORMED_IDS = List.of("000000000000000000000000", "00000000-0000-4000-8000-000000000000",
            "------------------", "xyz");

    // ─── ids ────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("resourceStorages")
    @DisplayName("resource storage: an unknown, foreign or malformed id is simply not found")
    void resourceStorageUnknownIds(String backend, Supplier<IResourceStorage<Map<String, Object>>> factory) throws Exception {
        var storage = factory.get();
        storage.store(storage.newResource(new HashMap<>(Map.of("name", "present"))));

        for (String id : FOREIGN_OR_MALFORMED_IDS) {
            assertNull(storage.read(id, 1), backend + " read " + id);
            assertNull(storage.readHistory(id, 1), backend + " readHistory " + id);
            assertNull(storage.readHistoryLatest(id), backend + " readHistoryLatest " + id);
            assertEquals(-1, storage.getCurrentVersion(id), backend + " getCurrentVersion " + id);
            storage.remove(id);
            storage.removeAllPermanently(id);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationStores")
    @DisplayName("conversation memory: an unknown, foreign or malformed id is simply not found")
    void conversationUnknownIds(String backend, Supplier<IConversationMemoryStore> factory) throws Exception {
        var store = factory.get();
        for (String id : FOREIGN_OR_MALFORMED_IDS) {
            assertNull(store.loadConversationMemorySnapshot(id), backend + " load " + id);
            assertNull(store.getConversationState(id), backend + " state " + id);
            store.deleteConversationMemorySnapshot(id);
        }
    }

    // ─── sort order ─────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("resourceStorages")
    @DisplayName("descending sort: a document without the sort field comes last")
    void documentsWithoutTheSortFieldComeLast(String backend, Supplier<IResourceStorage<Map<String, Object>>> factory) throws Exception {
        var storage = factory.get();
        String missing = store(storage, Map.of("name", "no timestamp"));
        String older = store(storage, Map.of("name", "older", "lastModifiedOn", "2026-01-01T00:00:00Z"));
        String newer = store(storage, Map.of("name", "newer", "lastModifiedOn", "2026-06-01T00:00:00Z"));

        // No filter groups at all: "everything" on both (MongoDB used to reject the
        // empty $and).
        var page = storage.findResources(new IResourceFilter.QueryFilters[0], "lastModifiedOn", 0, 10);

        assertEquals(List.of(newer, older, missing), page.stream().map(IResourceStore.IResourceId::getId).toList(), backend);
    }

    private static String store(IResourceStorage<Map<String, Object>> storage, Map<String, Object> content) throws Exception {
        var resource = storage.newResource(new HashMap<>(content));
        storage.store(resource);
        return resource.getId();
    }

    // ─── user-memory search ─────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("userMemoryStores")
    @DisplayName("user-memory search matches keys, string values and string array elements — nothing else")
    void userMemorySearchSemantics(String backend, Supplier<IUserMemoryStore> factory) throws Exception {
        var store = factory.get();
        String user = "parity-user-" + UUID.randomUUID();
        store.upsert(entry(user, "favourite_food", "Pizza"));
        store.upsert(entry(user, "subscribed", true));
        store.upsert(entry(user, "age", 42));
        store.upsert(entry(user, "address", Map.of("street", "Main", "zipcode", "1010")));
        store.upsert(entry(user, "hobbies", List.of("Chess", "Go")));

        assertEquals(List.of("favourite_food"), keys(store.filterEntries(user, "pizza")), backend + ": a string value, any case");
        assertEquals(List.of("hobbies"), keys(store.filterEntries(user, "chess")), backend + ": a string array element");
        assertEquals(List.of("address"), keys(store.filterEntries(user, "address")), backend + ": a key");
        assertEquals(List.of(), keys(store.filterEntries(user, "true")), backend + ": a boolean value is not text");
        assertEquals(List.of(), keys(store.filterEntries(user, "42")), backend + ": a number value is not text");
        assertEquals(List.of(), keys(store.filterEntries(user, "zipcode")), backend + ": an object value's field names are not content");
        assertEquals(List.of(), keys(store.filterEntries(user, "main")), backend + ": nor are its values");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("userMemoryStores")
    @DisplayName("user-memory search returns at most MAX_FILTER_RESULTS entries")
    void userMemorySearchIsCapped(String backend, Supplier<IUserMemoryStore> factory) throws Exception {
        var store = factory.get();
        String user = "parity-cap-" + UUID.randomUUID();
        for (int i = 0; i < IUserMemoryStore.MAX_FILTER_RESULTS + 5; i++) {
            store.upsert(entry(user, "note_" + i, "same word"));
        }

        assertEquals(IUserMemoryStore.MAX_FILTER_RESULTS, store.filterEntries(user, "word").size(), backend);
    }

    private static UserMemoryEntry entry(String user, String key, Object value) {
        return new UserMemoryEntry(null, user, key, value, "fact", Visibility.self, "parity-agent", List.of(), null, false, 0, null, null);
    }

    private static List<String> keys(List<UserMemoryEntry> entries) {
        return entries.stream().map(UserMemoryEntry::key).sorted().toList();
    }
}
