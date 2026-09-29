/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.DescriptorStore;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The descriptor store against a database whose indexes an earlier EDDI built.
 *
 * <p>
 * Databases created before 6.3 hold {@code descriptors.resource_1} with
 * {@code unique: true}; 6.x asks for the same key non-unique. MongoDB refuses
 * that ({@code IndexKeySpecsConflict}, 86), and the exception used to escape
 * the store's constructor, so on every such database every deployed agent ended
 * in ERROR. No mock can show this: the refusal is the server's.
 * </p>
 */
@DisplayName("MongoResourceStorage — an index an earlier EDDI built with other options")
class MongoResourceStorageIndexConflictTest extends MongoTestBase {

    private static final String DESCRIPTORS = "descriptors";
    private static final String DESCRIPTORS_HISTORY = "descriptors.history";
    private static final String AGENT_ID = "0000000000000000000000b1";

    @BeforeEach
    void clean() {
        dropCollections(DESCRIPTORS, DESCRIPTORS_HISTORY);
    }

    private static DescriptorStore<DocumentDescriptor> constructStore() {
        return new DescriptorStore<>(new MongoResourceStorageFactory(getDatabase()), documentBuilder, DocumentDescriptor.class);
    }

    private static List<Document> indexesOn(MongoCollection<Document> collection, Document key) {
        List<Document> matching = new ArrayList<>();
        for (Document index : collection.listIndexes()) {
            if (key.equals(index.get("key", Document.class))) {
                matching.add(index);
            }
        }
        return matching;
    }

    private static Document indexNamed(MongoCollection<Document> collection, String name) {
        for (Document index : collection.listIndexes()) {
            if (name.equals(index.getString("name"))) {
                return index;
            }
        }
        return null;
    }

    /** A descriptor can be written and then found by its resource URI. */
    private static void assertDescriptorRoundTrip(DescriptorStore<DocumentDescriptor> store) throws Exception {
        var descriptor = new DocumentDescriptor();
        descriptor.setName("synthetic agent");
        descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID + "?version=1"));
        store.createDescriptor(AGENT_ID, 1, descriptor);

        var found = store.readDescriptors("ai.labs.agent", null, 0, 10, false);
        assertEquals(1, found.size());
        assertEquals("synthetic agent", found.getFirst().getName());
    }

    @Test
    @DisplayName("a unique resource_1 from before 6.3 (error 86) is replaced by the non-unique index, and the store works")
    void uniqueResourceIndexFromEarlierEddi() throws Exception {
        var descriptors = getDatabase().getCollection(DESCRIPTORS);
        var history = getDatabase().getCollection(DESCRIPTORS_HISTORY);
        descriptors.createIndex(Indexes.ascending("resource"), new IndexOptions().unique(true));
        history.createIndex(Indexes.ascending("resource"), new IndexOptions().unique(true));

        var store = assertDoesNotThrow(MongoResourceStorageIndexConflictTest::constructStore);

        for (var collection : List.of(descriptors, history)) {
            var onResource = indexesOn(collection, new Document("resource", 1));
            assertEquals(1, onResource.size(), "exactly one index on {resource: 1} in " + collection.getNamespace());
            assertEquals("resource_1", onResource.getFirst().getString("name"));
            assertFalse(onResource.getFirst().getBoolean("unique", false), "the 6.x index is not unique");
        }
        assertDescriptorRoundTrip(store);
    }

    /**
     * The server accepts a non-unique index beside a unique one on the same key, so
     * this raises no error at all — which is exactly why it has to be looked for:
     * left alone, the stricter index goes on refusing writes 6.x expects to
     * succeed.
     */
    @Test
    @DisplayName("a unique index on {resource: 1} under another name is replaced, although the server raises no error for it")
    void uniqueIndexOnSameKeyUnderAnotherName() throws Exception {
        var descriptors = getDatabase().getCollection(DESCRIPTORS);
        descriptors.createIndex(Indexes.ascending("resource"), new IndexOptions().name("legacy_resource").unique(true));

        var store = assertDoesNotThrow(MongoResourceStorageIndexConflictTest::constructStore);

        var onResource = indexesOn(descriptors, new Document("resource", 1));
        assertEquals(1, onResource.size(), "the unique index is gone, one non-unique index is left");
        assertEquals("resource_1", onResource.getFirst().getString("name"));
        assertFalse(onResource.getFirst().getBoolean("unique", false));
        assertDescriptorRoundTrip(store);
    }

    @Test
    @DisplayName("an equivalent index on {resource: 1} under another name (error 85) is kept, and the store works")
    void equivalentIndexUnderAnotherName() throws Exception {
        var descriptors = getDatabase().getCollection(DESCRIPTORS);
        descriptors.createIndex(Indexes.ascending("resource"), new IndexOptions().name("legacy_resource"));

        var store = assertDoesNotThrow(MongoResourceStorageIndexConflictTest::constructStore);

        var onResource = indexesOn(descriptors, new Document("resource", 1));
        assertEquals(1, onResource.size(), "no duplicate index was built");
        assertEquals("legacy_resource", onResource.getFirst().getString("name"));
        assertDescriptorRoundTrip(store);
    }

    @Test
    @DisplayName("an unrelated index that holds the name resource_1 on another key is left alone, and the store still starts")
    void foreignIndexHoldingTheName() throws Exception {
        var descriptors = getDatabase().getCollection(DESCRIPTORS);
        descriptors.createIndex(Indexes.ascending("somethingElse"), new IndexOptions().name("resource_1"));

        var store = assertDoesNotThrow(MongoResourceStorageIndexConflictTest::constructStore);

        Document holder = indexNamed(descriptors, "resource_1");
        assertNotNull(holder, "the foreign index was not dropped");
        assertEquals(new Document("somethingElse", 1), holder.get("key", Document.class));
        var onResource = indexesOn(descriptors, new Document("resource", 1));
        assertEquals(1, onResource.size());
        assertEquals("resource_1_eddi", onResource.getFirst().getString("name"), "ours is built under another name");
        assertDescriptorRoundTrip(store);
    }

    @Test
    @DisplayName("a foreign index holding the name does not keep a legacy unique index on {resource: 1} in place")
    void foreignNameHolderAndLegacyUniqueIndex() throws Exception {
        var descriptors = getDatabase().getCollection(DESCRIPTORS);
        descriptors.createIndex(Indexes.ascending("somethingElse"), new IndexOptions().name("resource_1"));
        descriptors.createIndex(Indexes.ascending("resource"), new IndexOptions().name("legacy_resource").unique(true));

        var store = assertDoesNotThrow(MongoResourceStorageIndexConflictTest::constructStore);

        assertEquals(new Document("somethingElse", 1), indexNamed(descriptors, "resource_1").get("key", Document.class));
        var onResource = indexesOn(descriptors, new Document("resource", 1));
        assertEquals(1, onResource.size(), "the unique legacy index is gone: " + onResource);
        assertFalse(onResource.getFirst().getBoolean("unique", false));
        assertDescriptorRoundTrip(store);
    }

    /**
     * A partial index on the key serves only queries that carry its filter, and
     * this store's queries do not, so it is no stand-in for the full index.
     */
    @Test
    @DisplayName("a partial index on the key is replaced by the full one, not taken for an equivalent")
    void partialIndexIsNotEquivalent() throws Exception {
        var history = getDatabase().getCollection(DESCRIPTORS_HISTORY);
        var nestedId = Indexes.ascending("_id._id", "_id._version");
        history.createIndex(nestedId, new IndexOptions().partialFilterExpression(new Document("_id._version", new Document("$gt", 0))));

        var store = assertDoesNotThrow(MongoResourceStorageIndexConflictTest::constructStore);

        var onKey = indexesOn(history, new Document("_id._id", 1).append("_id._version", 1));
        assertEquals(1, onKey.size());
        assertFalse(onKey.getFirst().containsKey("partialFilterExpression"), "the full index replaced the partial one");
        assertDescriptorRoundTrip(store);
    }

    @Test
    @DisplayName("the generated name matches what the server generates, dotted and compound keys included")
    void generatedNameMatchesTheServer() {
        var collection = getDatabase().getCollection(DESCRIPTORS_HISTORY);
        var key = Indexes.ascending("_id._id", "_id._version");
        String serverName = collection.createIndex(key);
        assertEquals(serverName, MongoResourceStorage.generatedIndexName(key.toBsonDocument()));
    }
}
