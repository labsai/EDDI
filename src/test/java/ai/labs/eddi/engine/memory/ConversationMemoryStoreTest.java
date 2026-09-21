/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class ConversationMemoryStoreTest {

    private static final String VALID_ID = "aabbccddeeff112233445566";

    private MongoCollection<Document> documentCollection;
    private MongoCollection<ConversationMemorySnapshot> objectCollection;
    private ConversationMemoryStore store;

    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        documentCollection = mock(MongoCollection.class);
        objectCollection = mock(MongoCollection.class);

        when(database.getCollection("conversationmemories", Document.class)).thenReturn(documentCollection);
        when(database.getCollection("conversationmemories", ConversationMemorySnapshot.class)).thenReturn(objectCollection);

        store = new ConversationMemoryStore(database);
    }

    // ==================== storeConversationMemorySnapshot ====================

    @Test
    @DisplayName("storeConversationMemorySnapshot — inserts new when conversationId is null")
    void storeSnapshotNew() throws Exception {
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();

        String id = store.storeConversationMemorySnapshot(snapshot);
        assertNotNull(id);
        verify(objectCollection).insertOne(any(ConversationMemorySnapshot.class));
    }

    @Test
    @DisplayName("storeConversationMemorySnapshot — replaces when conversationId exists")
    void storeSnapshotReplace() throws Exception {
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        snapshot.setId(VALID_ID);

        stubReplaceMatching(snapshot, 1L);

        assertEquals(VALID_ID, store.storeConversationMemorySnapshot(snapshot));
        verify(objectCollection).replaceOne(any(Document.class), eq(snapshot));
    }

    @Test
    @DisplayName("storeConversationMemorySnapshot — a conversation deleted mid-turn is reported, never silently dropped")
    void storeSnapshotReplaceMissingDocument() {
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        snapshot.setId(VALID_ID);

        // matchedCount 0 == the document vanished between load and store (GDPR
        // erasure, retention sweep). Swallowing this lost the whole turn while the
        // caller still saw a normal response.
        stubReplaceMatching(snapshot, 0L);

        var exception = assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.storeConversationMemorySnapshot(snapshot));

        assertTrue(exception.getMessage().contains(VALID_ID),
                "the failure must name the conversation whose turn was lost, got: " + exception.getMessage());
        // The turn must NOT be resurrected behind the erasure, neither by an upsert
        // nor by falling through to the insert branch.
        verify(objectCollection, never()).replaceOne(any(Document.class), eq(snapshot), any(ReplaceOptions.class));
        verify(objectCollection, never()).insertOne(any(ConversationMemorySnapshot.class));
    }

    private void stubReplaceMatching(ConversationMemorySnapshot snapshot, long matchedCount) {
        UpdateResult result = mock(UpdateResult.class);
        when(result.getMatchedCount()).thenReturn(matchedCount);
        when(objectCollection.replaceOne(any(Document.class), eq(snapshot))).thenReturn(result);
    }

    // ==================== loadConversationMemorySnapshot ====================

    @Test
    @DisplayName("loadConversationMemorySnapshot — returns null when not found")
    void loadSnapshotNotFound() {
        FindIterable<ConversationMemorySnapshot> iterable = mock(FindIterable.class);
        when(objectCollection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        assertNull(store.loadConversationMemorySnapshot(VALID_ID));
    }

    @Test
    @DisplayName("loadConversationMemorySnapshot — returns snapshot with conversationId set")
    void loadSnapshotFound() {
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationSteps(new ArrayList<>());

        FindIterable<ConversationMemorySnapshot> iterable = mock(FindIterable.class);
        when(objectCollection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(snapshot);

        ConversationMemorySnapshot result = store.loadConversationMemorySnapshot(VALID_ID);
        assertNotNull(result);
        assertEquals(VALID_ID, result.getConversationId());
    }

    // ==================== loadActiveConversationMemorySnapshot
    // ====================

    @Test
    @DisplayName("loadActiveConversationMemorySnapshot — returns active conversations")
    void loadActiveSnapshots() throws Exception {
        FindIterable<ConversationMemorySnapshot> iterable = mock(FindIterable.class);
        when(objectCollection.find(any(Document.class))).thenReturn(iterable);

        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        doAnswer(inv -> {
            Consumer<ConversationMemorySnapshot> consumer = inv.getArgument(0);
            consumer.accept(snapshot);
            return null;
        }).when(iterable).forEach(any(Consumer.class));

        List<ConversationMemorySnapshot> result = store.loadActiveConversationMemorySnapshot("agent-1", 1);
        assertEquals(1, result.size());
    }

    // ==================== setConversationState ====================

    @Test
    @DisplayName("setConversationState — updates state field")
    void setConversationState() {
        when(documentCollection.updateOne(any(Document.class), any(Document.class))).thenReturn(mock(UpdateResult.class));
        store.setConversationState(VALID_ID, ConversationState.ENDED);
        verify(documentCollection).updateOne(any(Document.class), any(Document.class));
    }

    // ==================== deleteConversationMemorySnapshot ====================

    @Test
    @DisplayName("deleteConversationMemorySnapshot — deletes by id")
    void deleteSnapshot() {
        when(documentCollection.deleteOne(any(Document.class))).thenReturn(mock(DeleteResult.class));
        store.deleteConversationMemorySnapshot(VALID_ID);
        verify(documentCollection).deleteOne(any(Document.class));
    }

    // ==================== getConversationState ====================

    @Test
    @DisplayName("getConversationState — returns state when found")
    void getConversationStateFound() {
        Document doc = new Document("conversationState", "ENDED");
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(documentCollection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.projection(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(doc);

        assertEquals(ConversationState.ENDED, store.getConversationState(VALID_ID));
    }

    @Test
    @DisplayName("getConversationState — returns null when not found")
    void getConversationStateNotFound() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(documentCollection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.projection(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        assertNull(store.getConversationState(VALID_ID));
    }

    @Test
    @DisplayName("getConversationState — returns null when no state field")
    void getConversationStateNoField() {
        Document doc = new Document();
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(documentCollection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.projection(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(doc);

        assertNull(store.getConversationState(VALID_ID));
    }

    // ==================== getActiveConversationCount ====================

    @Test
    @DisplayName("getActiveConversationCount — returns count")
    void getActiveConversationCount() {
        when(documentCollection.countDocuments(any(Bson.class))).thenReturn(7L);
        assertEquals(7L, store.getActiveConversationCount("agent-1", 1));
    }

    // ==================== getEndedConversationIds ====================

    @Test
    @DisplayName("getEndedConversationIds — returns IDs of ended conversations")
    void getEndedConversationIds() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(documentCollection.find(any(Bson.class))).thenReturn(iterable);

        ObjectId oid = new ObjectId(VALID_ID);
        doAnswer(inv -> {
            Consumer<Document> consumer = inv.getArgument(0);
            consumer.accept(new Document("_id", oid));
            return null;
        }).when(iterable).forEach(any(Consumer.class));

        List<String> ids = store.getEndedConversationIds();
        assertEquals(1, ids.size());
    }

    // ==================== getConversationIdsByUserId ====================

    @Test
    @DisplayName("getConversationIdsByUserId — returns conversation IDs")
    void getConversationIdsByUserId() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(documentCollection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.projection(any(Document.class))).thenReturn(iterable);

        ObjectId oid = new ObjectId(VALID_ID);
        doAnswer(inv -> {
            Consumer<Document> consumer = inv.getArgument(0);
            consumer.accept(new Document("_id", oid));
            return null;
        }).when(iterable).forEach(any(Consumer.class));

        List<String> ids = store.getConversationIdsByUserId("user-1");
        assertEquals(1, ids.size());
    }

    // ==================== deleteConversationsByUserId ====================

    @Test
    @DisplayName("deleteConversationsByUserId — returns deleted count")
    void deleteConversationsByUserId() {
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(3L);
        when(documentCollection.deleteMany(any(Document.class))).thenReturn(deleteResult);

        assertEquals(3L, store.deleteConversationsByUserId("user-1"));
    }

    // ==================== IResourceStore methods ====================

    @Test
    @DisplayName("create — returns IResourceId with version 0")
    void create() throws IResourceStore.ResourceStoreException {
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();

        IResourceStore.IResourceId resourceId = store.create(snapshot);
        assertNotNull(resourceId.getId());
        assertEquals(0, resourceId.getVersion());
    }

    @Test
    @DisplayName("getCurrentResourceId — returns id with version 0")
    void getCurrentResourceId() {
        IResourceStore.IResourceId resourceId = store.getCurrentResourceId("test-id");
        assertEquals("test-id", resourceId.getId());
        assertEquals(0, resourceId.getVersion());
    }

    @Test
    @DisplayName("update — delegates to store")
    void update() throws IResourceStore.ResourceStoreException {
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        snapshot.setId(VALID_ID);

        stubReplaceMatching(snapshot, 1L);

        Integer result = store.update(VALID_ID, 0, snapshot);
        assertEquals(0, result);
        verify(objectCollection).replaceOne(any(Document.class), eq(snapshot));
    }

    @Test
    @DisplayName("update — propagates the lost turn when the conversation was deleted mid-turn")
    void updateMissingDocument() {
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        snapshot.setId(VALID_ID);

        stubReplaceMatching(snapshot, 0L);

        // update() must not report success (return 0) for a write that matched nothing.
        var exception = assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.update(VALID_ID, 0, snapshot));
        assertTrue(exception.getMessage().contains(VALID_ID),
                "the failure must name the conversation whose turn was lost, got: " + exception.getMessage());
    }
}
