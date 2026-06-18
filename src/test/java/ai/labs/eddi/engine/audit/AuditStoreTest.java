/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class AuditStoreTest {

    private MongoCollection<Document> collection;
    private AuditStore store;

    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(database.getCollection("audit_ledger")).thenReturn(collection);
        store = new AuditStore(database);
    }

    // ==================== appendEntry ====================

    @Test
    @DisplayName("appendEntry — inserts single document")
    void appendEntry() {
        AuditEntry entry = createEntry("conv-1", "agent-1");
        store.appendEntry(entry);
        verify(collection).insertOne(any(Document.class));
    }

    // ==================== appendBatch ====================

    @Test
    @DisplayName("appendBatch — inserts multiple documents")
    void appendBatch() {
        List<AuditEntry> entries = List.of(
                createEntry("conv-1", "agent-1"),
                createEntry("conv-2", "agent-2"));
        store.appendBatch(entries);
        verify(collection).insertMany(anyList());
    }

    @Test
    @DisplayName("appendBatch — skips null input")
    void appendBatchNull() {
        store.appendBatch(null);
        verify(collection, never()).insertMany(anyList());
    }

    @Test
    @DisplayName("appendBatch — skips empty input")
    void appendBatchEmpty() {
        store.appendBatch(List.of());
        verify(collection, never()).insertMany(anyList());
    }

    // ==================== getEntries ====================

    @Test
    @DisplayName("getEntries — returns entries for conversation")
    void getEntries() {
        setupQueryIteration(createAuditDoc());

        List<AuditEntry> result = store.getEntries("conv-1", 0, 10);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("getEntries — handles skip and limit")
    void getEntriesWithSkipLimit() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.sort(any(Document.class))).thenReturn(iterable);
        when(iterable.skip(5)).thenReturn(iterable);
        when(iterable.limit(10)).thenReturn(iterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(false);

        List<AuditEntry> result = store.getEntries("conv-1", 5, 10);
        assertTrue(result.isEmpty());
    }

    // ==================== getEntriesByAgent ====================

    @Test
    @DisplayName("getEntriesByAgent — without version filter")
    void getEntriesByAgentNoVersion() {
        setupQueryIteration(createAuditDoc());

        List<AuditEntry> result = store.getEntriesByAgent("agent-1", null, 0, 10);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("getEntriesByAgent — with version filter")
    void getEntriesByAgentWithVersion() {
        setupQueryIteration(createAuditDoc());

        List<AuditEntry> result = store.getEntriesByAgent("agent-1", 1, 0, 10);
        assertEquals(1, result.size());
    }

    // ==================== countByConversation ====================

    @Test
    @DisplayName("countByConversation — returns document count")
    void countByConversation() {
        when(collection.countDocuments(any(Document.class))).thenReturn(42L);
        assertEquals(42L, store.countByConversation("conv-1"));
    }

    // ==================== getEntriesByUserId ====================

    @Test
    @DisplayName("getEntriesByUserId — returns entries for user")
    void getEntriesByUserId() {
        setupQueryIteration(createAuditDoc());

        List<AuditEntry> result = store.getEntriesByUserId("user-1", 0, 10);
        assertEquals(1, result.size());
    }

    // ==================== pseudonymizeByUserId ====================

    @Test
    @DisplayName("pseudonymizeByUserId — returns modified count")
    void pseudonymizeByUserId() {
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getModifiedCount()).thenReturn(5L);
        when(collection.updateMany(any(Document.class), any(Document.class))).thenReturn(updateResult);

        assertEquals(5L, store.pseudonymizeByUserId("real-user", "pseudo-user"));
    }

    // ==================== Helpers ====================

    private AuditEntry createEntry(String conversationId, String agentId) {
        return new AuditEntry("entry-1", conversationId, agentId, 1, "user-1",
                "production", 0, "task-1", "LlmTask", 0, 100L,
                Map.of("text", "hello"), Map.of("response", "hi"),
                null, null, List.of("action1"), 0.01,
                Instant.now(), null, null);
    }

    private Document createAuditDoc() {
        Document doc = new Document();
        doc.put("_id", "entry-1");
        doc.put("conversationId", "conv-1");
        doc.put("agentId", "agent-1");
        doc.put("agentVersion", 1);
        doc.put("userId", "user-1");
        doc.put("environment", "production");
        doc.put("stepIndex", 0);
        doc.put("taskId", "task-1");
        doc.put("taskType", "LlmTask");
        doc.put("taskIndex", 0);
        doc.put("durationMs", 100L);
        doc.put("actions", List.of("action1"));
        doc.put("cost", 0.01);
        doc.put("timestamp", Date.from(Instant.now()));
        return doc;
    }

    private void setupQueryIteration(Document doc) {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.sort(any(Document.class))).thenReturn(iterable);
        when(iterable.skip(anyInt())).thenReturn(iterable);
        when(iterable.limit(anyInt())).thenReturn(iterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(doc);
    }
}
