/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class MongoResourceStorageTest {

    private static final String COLLECTION_NAME = "testcollection";
    private static final String VALID_ID = "aabbccddeeff112233445566";

    private MongoCollection<Document> currentCollection;
    private MongoCollection<Document> historyCollection;
    private IDocumentBuilder documentBuilder;
    private MongoResourceStorage<String> storage;

    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        currentCollection = mock(MongoCollection.class);
        historyCollection = mock(MongoCollection.class);
        documentBuilder = mock(IDocumentBuilder.class);

        when(database.getCollection(COLLECTION_NAME)).thenReturn(currentCollection);
        when(database.getCollection(COLLECTION_NAME + ".history")).thenReturn(historyCollection);

        storage = new MongoResourceStorage<>(database, COLLECTION_NAME, documentBuilder, String.class);
    }

    // ==================== newResource ====================

    @Test
    @DisplayName("newResource(content) — creates resource with version 1")
    void newResourceContent() throws Exception {
        when(documentBuilder.toString(any())).thenReturn("{\"data\":\"test\"}");

        IResourceStorage.IResource<String> resource = storage.newResource("test");
        assertNotNull(resource);
        assertEquals(1, resource.getVersion());
    }

    @Test
    @DisplayName("newResource(id, version, content) — creates resource with specified id/version")
    void newResourceIdVersion() throws Exception {
        when(documentBuilder.toString(any())).thenReturn("{\"data\":\"test\"}");

        IResourceStorage.IResource<String> resource = storage.newResource(VALID_ID, 5, "test");
        assertNotNull(resource);
        assertEquals(VALID_ID, resource.getId());
        assertEquals(5, resource.getVersion());
    }

    // ==================== store ====================

    @Test
    @DisplayName("store — insertOne when resource has no id")
    void storeNewResource() throws Exception {
        when(documentBuilder.toString(any())).thenReturn("{\"data\":\"test\"}");
        IResourceStorage.IResource<String> resource = storage.newResource("test");

        storage.store(resource);
        verify(currentCollection).insertOne(any(Document.class));
    }

    @Test
    @DisplayName("store — updateOne with upsert when resource has id")
    void storeExistingResource() throws Exception {
        when(documentBuilder.toString(any())).thenReturn("{\"data\":\"test\"}");
        IResourceStorage.IResource<String> resource = storage.newResource(VALID_ID, 1, "test");

        storage.store(resource);
        verify(currentCollection).updateOne(any(Bson.class), any(Document.class), any());
    }

    @Test
    @DisplayName("store — rejects external resource implementations")
    void storeExternalResource() {
        IResourceStorage.IResource<String> fakeResource = mock(IResourceStorage.IResource.class);
        assertThrows(IllegalArgumentException.class, () -> storage.store(fakeResource));
    }

    // ==================== createNew ====================

    @Test
    @DisplayName("createNew — always insertOne")
    void createNew() throws Exception {
        when(documentBuilder.toString(any())).thenReturn("{\"data\":\"test\"}");
        IResourceStorage.IResource<String> resource = storage.newResource("test");

        storage.createNew(resource);
        verify(currentCollection).insertOne(any(Document.class));
    }

    // ==================== read ====================

    @Test
    @DisplayName("read — returns resource when found")
    void readFound() {
        Document doc = new Document("_id", new ObjectId(VALID_ID))
                .append("_version", 1)
                .append("data", "test");
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(currentCollection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(doc);

        IResourceStorage.IResource<String> result = storage.read(VALID_ID, 1);
        assertNotNull(result);
        assertEquals(1, result.getVersion());
    }

    @Test
    @DisplayName("read — returns null when not found")
    void readNotFound() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(currentCollection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        IResourceStorage.IResource<String> result = storage.read(VALID_ID, 1);
        assertNull(result);
    }

    // ==================== remove ====================

    @Test
    @DisplayName("remove — deletes from current collection")
    void remove() {
        storage.remove(VALID_ID);
        verify(currentCollection).deleteOne(any(Document.class));
    }

    // ==================== removeAllPermanently ====================

    @Test
    @DisplayName("removeAllPermanently — deletes from both collections")
    void removeAllPermanently() {
        storage.removeAllPermanently(VALID_ID);
        verify(currentCollection).deleteOne(any(Document.class));
        verify(historyCollection).deleteMany(any(Document.class));
    }

    // ==================== readHistory ====================

    @Test
    @DisplayName("readHistory — returns history resource when found")
    void readHistoryFound() {
        Document idDoc = new Document("_id", new ObjectId(VALID_ID)).append("_version", 2);
        Document doc = new Document("_id", idDoc).append("data", "old");
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(historyCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(doc);

        IResourceStorage.IHistoryResource<String> result = storage.readHistory(VALID_ID, 2);
        assertNotNull(result);
        assertEquals(VALID_ID, result.getId());
        assertEquals(2, result.getVersion());
    }

    @Test
    @DisplayName("readHistory — returns null when not found")
    void readHistoryNotFound() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(historyCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        assertNull(storage.readHistory(VALID_ID, 2));
    }

    // ==================== readHistoryLatest ====================

    @Test
    @DisplayName("readHistoryLatest — returns latest history")
    void readHistoryLatest() {
        when(historyCollection.countDocuments(any(Document.class))).thenReturn(1L);

        Document idDoc = new Document("_id", new ObjectId(VALID_ID)).append("_version", 3);
        Document doc = new Document("_id", idDoc).append("data", "latest");
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(historyCollection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.sort(any(Document.class))).thenReturn(iterable);
        when(iterable.limit(1)).thenReturn(iterable);
        when(iterable.first()).thenReturn(doc);

        IResourceStorage.IHistoryResource<String> result = storage.readHistoryLatest(VALID_ID);
        assertNotNull(result);
    }

    @Test
    @DisplayName("readHistoryLatest — returns null when no history")
    void readHistoryLatestEmpty() {
        when(historyCollection.countDocuments(any(Document.class))).thenReturn(0L);

        assertNull(storage.readHistoryLatest(VALID_ID));
    }

    // ==================== newHistoryResourceFor ====================

    @Test
    @DisplayName("newHistoryResourceFor — creates history with deleted flag")
    void newHistoryResourceForDeleted() throws Exception {
        when(documentBuilder.toString(any())).thenReturn("{\"data\":\"test\"}");
        IResourceStorage.IResource<String> resource = storage.newResource(VALID_ID, 1, "test");

        IResourceStorage.IHistoryResource<String> history = storage.newHistoryResourceFor(resource, true);
        assertNotNull(history);
        assertTrue(history.isDeleted());
    }

    @Test
    @DisplayName("newHistoryResourceFor — creates history without deleted flag")
    void newHistoryResourceForNotDeleted() throws Exception {
        when(documentBuilder.toString(any())).thenReturn("{\"data\":\"test\"}");
        IResourceStorage.IResource<String> resource = storage.newResource(VALID_ID, 1, "test");

        IResourceStorage.IHistoryResource<String> history = storage.newHistoryResourceFor(resource, false);
        assertNotNull(history);
        assertFalse(history.isDeleted());
    }

    // ==================== getCurrentVersion ====================

    @Test
    @DisplayName("getCurrentVersion — returns version when found")
    void getCurrentVersionFound() {
        Document doc = new Document("_version", 3);
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(currentCollection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(doc);

        assertEquals(3, storage.getCurrentVersion(VALID_ID));
    }

    @Test
    @DisplayName("getCurrentVersion — returns -1 when not found")
    void getCurrentVersionNotFound() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(currentCollection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        assertEquals(-1, storage.getCurrentVersion(VALID_ID));
    }

    // ==================== store(IHistoryResource) ====================

    @Test
    @DisplayName("store(IHistoryResource) — inserts into history collection")
    void storeHistoryResource() throws Exception {
        when(documentBuilder.toString(any())).thenReturn("{\"data\":\"test\"}");
        IResourceStorage.IResource<String> resource = storage.newResource(VALID_ID, 1, "test");
        IResourceStorage.IHistoryResource<String> history = storage.newHistoryResourceFor(resource, false);

        storage.store(history);
        verify(historyCollection).insertOne(any(Document.class));
    }

    // ==================== findResourceIdsContaining ====================

    @Test
    @DisplayName("findResourceIdsContaining — returns matching resource IDs")
    void findResourceIdsContaining() {
        Document doc = new Document("_id", new ObjectId(VALID_ID)).append("_version", 1);
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(currentCollection.find(any(Document.class))).thenReturn(iterable);

        doAnswer(inv -> {
            java.util.function.Consumer<Document> consumer = inv.getArgument(0);
            consumer.accept(doc);
            return null;
        }).when(iterable).forEach(any(java.util.function.Consumer.class));

        List<IResourceStore.IResourceId> result = storage.findResourceIdsContaining("field.path", "value");
        assertEquals(1, result.size());
        assertEquals(VALID_ID, result.getFirst().getId());
    }

    // ==================== Resource.getData ====================

    @Test
    @DisplayName("Resource.getData — delegates to documentBuilder")
    void resourceGetData() throws Exception {
        when(documentBuilder.toString(any())).thenReturn("{\"data\":\"test\"}");
        when(documentBuilder.build(any(Document.class), eq(String.class))).thenReturn("parsed-value");

        IResourceStorage.IResource<String> resource = storage.newResource("test");
        assertEquals("parsed-value", resource.getData());
    }
}
