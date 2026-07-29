/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.mongo;

import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.OngoingStubbing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for the user-memory recall and ownership semantics:
 * <ul>
 * <li>G5 — {@code most_accessed} recall must not issue one write per document
 * inside an open cursor, must be backed by an {@code accessCount} index, and
 * must leave room for freshly written entries to enter the recall window.</li>
 * <li>G7 — recalling and re-writing a {@code global} entry from a second agent
 * must not transfer ownership of that entry.</li>
 * </ul>
 */
@SuppressWarnings("unchecked")
class MongoUserMemoryStoreRecallScopeTest {

    private static final String TEST_USER = "user-1";
    private static final String AGENT_A = "agent-a";
    private static final String AGENT_B = "agent-b";

    private MongoCollection<Document> collection;
    private MongoUserMemoryStore store;

    /** Result batches, one per {@code find(...)} call, in order. */
    private final List<List<Document>> findResults = new ArrayList<>();
    private final List<Bson> capturedSorts = new ArrayList<>();
    private final List<Integer> capturedLimits = new ArrayList<>();

    /**
     * Ordered log of collection interactions: {@code read:<key>} per document
     * pulled off a find cursor, {@code updateMany} per batched write. The relative
     * order is what proves the increments are issued outside the cursors.
     */
    private final List<String> interactions = new ArrayList<>();

    private final UpdateResult batchedIncrementResult = mock(UpdateResult.class);

    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(database.getCollection("usermemories")).thenReturn(collection);
        store = new MongoUserMemoryStore(database);
    }

    /**
     * Wires {@code find(...).sort(...).limit(...)} so each successive call to
     * {@code find} returns the next queued batch, and records the sort/limit used.
     * <p>
     * The iterables are built <em>before</em> {@code when(collection.find(...))} is
     * opened: creating them inside a {@code thenReturn(...)} argument stubs one
     * mock while another stubbing is still in flight, which Mockito rejects with
     * {@code UnfinishedStubbingException}.
     */
    private void stubFind() {
        List<FindIterable<Document>> iterables = findResults.stream().map(this::stubIterable).toList();
        OngoingStubbing<FindIterable<Document>> stubbing = when(collection.find(any(Bson.class)));
        for (FindIterable<Document> iterable : iterables) {
            stubbing = stubbing.thenReturn(iterable);
        }
    }

    private FindIterable<Document> stubIterable(List<Document> batch) {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(iterable.sort(any())).thenAnswer(invocation -> {
            capturedSorts.add(invocation.getArgument(0));
            return iterable;
        });
        when(iterable.limit(anyInt())).thenAnswer(invocation -> {
            capturedLimits.add(invocation.getArgument(0));
            return iterable;
        });
        var remaining = new ArrayList<>(batch);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(cursor.hasNext()).thenAnswer(invocation -> !remaining.isEmpty());
        when(cursor.next()).thenAnswer(invocation -> {
            Document doc = remaining.remove(0);
            interactions.add("read:" + doc.getString("key"));
            return doc;
        });
        doReturn(cursor).when(iterable).iterator();
        return iterable;
    }

    /** Records every batched {@code $inc} write in {@link #interactions}. */
    private void stubBatchedIncrement() {
        when(collection.updateMany(any(Bson.class), any(Bson.class))).thenAnswer(invocation -> {
            interactions.add("updateMany");
            return batchedIncrementResult;
        });
    }

    /**
     * The {@code _id} values a captured {@code {_id: {$in: [...]}}} filter targets.
     */
    private static List<ObjectId> targetedIds(Bson filter) {
        return filter.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry())
                .getDocument("_id").getArray("$in").stream()
                .map(value -> value.asObjectId().getValue())
                .toList();
    }

    private Document memoryDoc(ObjectId oid, String key, String sourceAgentId, Instant updatedAt) {
        return new Document("_id", oid)
                .append("userId", TEST_USER)
                .append("key", key)
                .append("value", "v-" + key)
                .append("category", "fact")
                .append("visibility", Visibility.self.name())
                .append("sourceAgentId", sourceAgentId)
                .append("groupIds", List.of())
                .append("sourceConversationId", null)
                .append("conflicted", false)
                .append("accessCount", 0)
                .append("createdAt", updatedAt.toString())
                .append("updatedAt", updatedAt.toString());
    }

    @Test
    @DisplayName("G5 — a newly written entry reaches the recall window through the reserved recency slots")
    void mostAccessedReservesSlotsForRecentEntries() throws Exception {
        Instant old = Instant.parse("2024-01-01T00:00:00Z");
        Instant fresh = Instant.parse("2026-07-01T00:00:00Z");

        // The access-count query returns only long-established entries; the freshly
        // written entry (accessCount 0) is nowhere near the top of that ranking.
        List<Document> byAccessCount = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            byAccessCount.add(memoryDoc(new ObjectId(), "established-" + i, AGENT_A, old));
        }
        ObjectId freshId = new ObjectId();
        findResults.add(byAccessCount);
        findResults.add(List.of(memoryDoc(freshId, "brand-new", AGENT_A, fresh)));
        stubFind();
        stubBatchedIncrement();

        List<UserMemoryEntry> recalled = store.getVisibleEntries(TEST_USER, AGENT_A, null, "most_accessed", 10);

        assertTrue(recalled.stream().anyMatch(entry -> "brand-new".equals(entry.key())),
                "a newly written entry must be able to reach the most_accessed recall window");
        assertEquals(9, recalled.size(), "8 by access count + 1 reserved recency slot filled");
        // 10 entries, 1/5th reserved => 8 access slots + 2 recency slots
        assertEquals(List.of(8, 2), capturedLimits);
        assertEquals(2, capturedSorts.size(), "one access-count pass and one recency pass");

        // The newcomer must also be counted as accessed — otherwise it drops straight
        // back out of the window and most_accessed stays self-reinforcing.
        ArgumentCaptor<Bson> incrementFilter = ArgumentCaptor.forClass(Bson.class);
        verify(collection).updateMany(incrementFilter.capture(), any(Bson.class));
        assertTrue(targetedIds(incrementFilter.getValue()).contains(freshId),
                "the entry recalled through the reserved recency slot must get its accessCount incremented too");
    }

    @Test
    @DisplayName("G5 — the access-count increments are a single batched write, issued only after the cursors are drained")
    void mostAccessedBatchesIncrementsOutsideTheCursor() throws Exception {
        Instant old = Instant.parse("2024-01-01T00:00:00Z");
        ObjectId first = new ObjectId();
        ObjectId second = new ObjectId();
        findResults.add(List.of(memoryDoc(first, "a", AGENT_A, old), memoryDoc(second, "b", AGENT_A, old)));
        findResults.add(List.of());
        stubFind();
        stubBatchedIncrement();

        store.getVisibleEntries(TEST_USER, AGENT_A, null, "most_accessed", 10);

        verify(collection, never()).updateOne(any(Bson.class), any(Bson.class));
        verify(collection, never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
        verify(collection, times(1)).updateMany(any(Bson.class), any(Bson.class));

        // The finding is about ordering, not just call counts: every document is read
        // off the cursor BEFORE the single write is issued. A write interleaved with
        // the reads (the old per-document updateOne) would show up between the reads.
        assertEquals(List.of("read:a", "read:b", "updateMany"), interactions,
                "the $inc must be issued after both cursors are drained, never from inside one");

        ArgumentCaptor<Bson> incrementFilter = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> increment = ArgumentCaptor.forClass(Bson.class);
        verify(collection).updateMany(incrementFilter.capture(), increment.capture());

        assertEquals(List.of(first, second), targetedIds(incrementFilter.getValue()),
                "the one write must cover every recalled entry");
        BsonDocument rendered = increment.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
        assertEquals(1, rendered.getDocument("$inc").getInt32("accessCount").getValue(),
                "each recalled entry is counted exactly once per recall");
    }

    @Test
    @DisplayName("G5 — accessCount is indexed so most_accessed is not an in-memory top-k")
    void accessCountIsIndexed() {
        ArgumentCaptor<IndexOptions> options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(collection, atLeastOnce()).createIndex(any(Bson.class), options.capture());

        assertTrue(options.getAllValues().stream().anyMatch(opt -> "idx_user_access_count".equals(opt.getName())),
                "expected an index covering accessCount, got: " + options.getAllValues().stream().map(IndexOptions::getName).toList());
    }

    @Test
    @DisplayName("G7 — a global entry keeps its original owning agent when another agent writes it")
    void globalUpsertDoesNotTransferOwnership() throws Exception {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(memoryDoc(new ObjectId(), "shared", AGENT_A, Instant.now()));
        when(collection.updateOne(any(Bson.class), any(Bson.class), any())).thenReturn(mock(UpdateResult.class));

        var entry = new UserMemoryEntry(null, TEST_USER, "shared", "new value", "fact", Visibility.global, AGENT_B, List.of(), "conv-1", false, 0,
                Instant.now(), Instant.now());
        store.upsert(entry);

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection).updateOne(any(Bson.class), update.capture(), any());
        BsonDocument rendered = update.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());

        assertFalse(rendered.getDocument("$set").containsKey("sourceAgentId"),
                "$set on a global entry would overwrite the owning agent on every recall+write");
        assertEquals(AGENT_B, rendered.getDocument("$setOnInsert").getString("sourceAgentId").getValue(),
                "the writing agent only becomes the owner when the entry is newly inserted");
    }

    @Test
    @DisplayName("G7 — self/group entries are keyed per agent, so sourceAgentId stays part of $set")
    void selfUpsertStillSetsSourceAgent() throws Exception {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);
        when(collection.updateOne(any(Bson.class), any(Bson.class), any())).thenReturn(mock(UpdateResult.class));

        var entry = new UserMemoryEntry(null, TEST_USER, "private", "value", "fact", Visibility.self, AGENT_B, List.of(), "conv-1", false, 0,
                Instant.now(), Instant.now());
        store.upsert(entry);

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection).updateOne(any(Bson.class), update.capture(), any());
        BsonDocument rendered = update.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());

        assertEquals(AGENT_B, rendered.getDocument("$set").getString("sourceAgentId").getValue());
    }
}
