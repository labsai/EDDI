/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

    /**
     * Unordered, deliberately: an ordered {@code insertMany} persists the prefix
     * and abandons everything behind the first rejected document, which combined
     * with the ledger's whole-batch retry meant a single bad entry discarded three
     * flush windows of unrelated conversations' records.
     */
    @Test
    @DisplayName("appendBatch — inserts multiple documents, unordered")
    void appendBatch() {
        List<AuditEntry> entries = List.of(
                createEntry("conv-1", "agent-1"),
                createEntry("conv-2", "agent-2"));
        store.appendBatch(entries);

        var options = ArgumentCaptor.forClass(InsertManyOptions.class);
        verify(collection).insertMany(anyList(), options.capture());
        assertFalse(options.getValue().isOrdered(), "one rejected document must not stop the ones behind it");
    }

    @Test
    @DisplayName("appendBatch — skips null input")
    void appendBatchNull() {
        store.appendBatch(null);
        verify(collection, never()).insertMany(anyList(), any(InsertManyOptions.class));
    }

    @Test
    @DisplayName("appendBatch — skips empty input")
    void appendBatchEmpty() {
        store.appendBatch(List.of());
        verify(collection, never()).insertMany(anyList(), any(InsertManyOptions.class));
    }

    /**
     * The ledger retries a failed batch entry by entry, and a bulk write is atomic
     * in neither backend — so the retry necessarily re-offers documents that
     * already landed. Treating {@code E11000} as success is what makes that
     * possible; the PostgreSQL insert carries {@code ON CONFLICT (id) DO NOTHING}
     * for the same reason.
     */
    @Test
    @DisplayName("appendEntry — a duplicate id is accepted, not raised")
    void appendEntryToleratesDuplicates() {
        doThrow(writeException(11000)).when(collection).insertOne(any(Document.class));
        assertDoesNotThrow(() -> store.appendEntry(createEntry("conv-1", "agent-1")));
    }

    @Test
    @DisplayName("appendEntry — any other write error still propagates")
    void appendEntryPropagatesRealFailures() {
        doThrow(writeException(121)).when(collection).insertOne(any(Document.class));
        assertThrows(MongoWriteException.class, () -> store.appendEntry(createEntry("conv-1", "agent-1")));
    }

    @Test
    @DisplayName("appendBatch — duplicate-key errors are ignored, other errors are not")
    void appendBatchToleratesDuplicatesOnly() {
        List<AuditEntry> entries = List.of(createEntry("conv-1", "agent-1"));

        doThrow(bulkWriteException(11000)).when(collection).insertMany(anyList(), any(InsertManyOptions.class));
        assertDoesNotThrow(() -> store.appendBatch(entries));

        doThrow(bulkWriteException(121)).when(collection).insertMany(anyList(), any(InsertManyOptions.class));
        assertThrows(MongoBulkWriteException.class, () -> store.appendBatch(entries),
                "a genuinely broken store must not be hidden behind the duplicate tolerance");
    }

    /**
     * Finding 03: a sequence counter has to be seeded from the highest position the
     * store holds, not from a row count — with a dead-lettered gap the two are
     * different numbers, and the count re-issues positions already handed out.
     */
    @Test
    @DisplayName("maxSequence — reads the highest stored position, UNSEQUENCED when there is none")
    void maxSequenceReadsTheHighestPosition() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.sort(any(Bson.class))).thenReturn(iterable);
        when(iterable.projection(any(Bson.class))).thenReturn(iterable);
        when(iterable.limit(anyInt())).thenReturn(iterable);

        when(iterable.first()).thenReturn(new Document("sequence", 9L));
        assertEquals(9L, store.maxSequence("conv-1"));

        when(iterable.first()).thenReturn(null);
        assertEquals(AuditEntry.UNSEQUENCED, store.maxSequence("conv-1"));
    }

    /**
     * GDPR export and erasure both select on {@code userId}, and per-conversation
     * reads filter on {@code conversationId} and sort by timestamp — the ledger is
     * the largest never-pruned collection in the system, so both were full scans.
     * <p>
     * The third index is the one {@link AuditStore#maxSequence} needs, and it sits
     * on the submit hot path: seeding a conversation's chain counter now asks for
     * the highest stored sequence instead of a row count, and without an index that
     * descending sort is a collection scan on the largest collection in the system
     * — once per conversation, inline on the pipeline thread.
     */
    @Test
    @DisplayName("the constructor indexes userId, (conversationId, timestamp) and (conversationId, sequence)")
    void indexesCoverTheGdprAndConversationReads() {
        var indexes = ArgumentCaptor.forClass(Bson.class);
        verify(collection, atLeastOnce()).createIndex(indexes.capture());
        // Indexes.compoundIndex has no useful toString; render the key document.
        List<String> created = indexes.getAllValues().stream()
                .map(bson -> bson.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()).toJson())
                .toList();

        assertTrue(created.stream().anyMatch(i -> i.contains("userId")), "GDPR export/erasure scan by userId: " + created);
        assertTrue(created.stream().anyMatch(i -> i.contains("conversationId") && i.contains("timestamp")),
                "per-conversation reads filter and sort together: " + created);
        assertTrue(created.stream().anyMatch(i -> i.contains("conversationId") && i.contains("sequence")),
                "maxSequence sorts by sequence within a conversation on the submit path: " + created);
    }

    private static MongoWriteException writeException(int code) {
        return new MongoWriteException(new WriteError(code, "write error", new BsonDocument()), new ServerAddress(), Set.of());
    }

    private static MongoBulkWriteException bulkWriteException(int code) {
        return new MongoBulkWriteException(
                BulkWriteResult.acknowledged(0, 0, 0, 0, List.of(), List.of()),
                List.of(new BulkWriteError(code, "write error", new BsonDocument(), 0)),
                null, new ServerAddress(), Set.of());
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

    // ==================== G18: sequence round-trip ====================

    /**
     * The sequence is part of the signed payload, so a store that dropped it on
     * write would make every one of its rows verify as tampered. This store
     * advertises support for it — and must actually persist it.
     */
    @Test
    @DisplayName("supportsSequence — the sequence survives a write")
    void sequenceIsPersisted() {
        AuditEntry entry = createEntry("conv-1", "agent-1").withSequence(11);

        assertTrue(store.supportsSequence());

        store.appendEntry(entry);

        var captor = org.mockito.ArgumentCaptor.forClass(Document.class);
        verify(collection).insertOne(captor.capture());
        assertEquals(11L, ((Number) captor.getValue().get("sequence")).longValue());
    }

    @Test
    @DisplayName("a document written before sequencing reads back as unsequenced")
    void legacyDocumentReadsBackUnsequenced() {
        // createAuditDoc() has no "sequence" field — exactly a pre-upgrade row.
        setupQueryIteration(createAuditDoc());

        List<AuditEntry> result = store.getEntries("conv-1", 0, 10);

        assertEquals(1, result.size());
        assertEquals(AuditEntry.UNSEQUENCED, result.getFirst().sequence());
    }

    @Test
    @DisplayName("a stored sequence reads back unchanged")
    void storedSequenceReadsBack() {
        Document doc = createAuditDoc();
        doc.put("sequence", 11L);
        setupQueryIteration(doc);

        List<AuditEntry> result = store.getEntries("conv-1", 0, 10);

        assertEquals(11L, result.getFirst().sequence());
    }
}
