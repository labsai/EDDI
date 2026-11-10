/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.mongo;

import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.health.model.HealthTurn;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("MongoHealthTurnStore — guarded replace, first-recorded day, sticky discard")
class MongoHealthTurnStoreTest {

    private MongoCollection<Document> collection;
    private MongoHealthTurnStore store;

    static HealthTurn turn(boolean discarded) {
        return new HealthTurn("conv", 3, "exec", "agent", 2, 1, "user", "api", LocalDate.of(2026, 11, 10), Instant.parse("2026-11-10T10:00:00Z"),
                Instant.parse("2026-11-10T11:00:00Z"), 0.25, 0.05, 10L, 5L, 15L, 900L, "TURN_ERROR", true,
                List.of(new HealthFailure("TIMEOUT", "m1", "cascade")), true, 2, 1, 4, 3, true, true, true, List.of("greet", "$ask"), discarded);
    }

    private static BsonDocument render(Bson bson) {
        return bson.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        collection = mock(MongoCollection.class);
        store = new MongoHealthTurnStore(collection);
    }

    @Test
    @DisplayName("a document round-trips every field")
    void roundTrip() {
        HealthTurn turn = turn(true);
        assertEquals(turn, MongoHealthTurnStore.fromDocument(MongoHealthTurnStore.toDocument(turn)));
    }

    @Test
    @DisplayName("the upsert filter matches the row only while its updatedAt is not newer than the write")
    void guardedFilter() {
        String filter = render(MongoHealthTurnStore.guardedFilter(turn(false))).toJson();
        assertTrue(filter.contains("\"_id\": \"conv|exec\""), filter);
        assertTrue(filter.contains("\"updatedAt\": {\"$lte\""), filter);
        assertTrue(filter.contains("\"$exists\": false"), filter);
    }

    @Test
    @DisplayName("the pipeline sets literals, keeps the first day and timestamp, and ORs discarded — never an increment")
    void replacePipeline() {
        List<Bson> pipeline = MongoHealthTurnStore.replacePipeline(turn(false));
        assertEquals(1, pipeline.size());
        BsonDocument set = render(pipeline.get(0)).getDocument("$set");
        assertFalse(render(pipeline.get(0)).containsKey("$inc"));
        assertEquals(0.25, set.getDocument("costUsd").getDouble("$literal").getValue());
        assertEquals("$ask", set.getDocument("actions").getArray("$literal").get(1).asString().getValue(),
                "an action that looks like a field path stays a string");
        assertEquals("$day", set.getDocument("day").getArray("$ifNull").get(0).asString().getValue());
        assertEquals("$timestamp", set.getDocument("timestamp").getArray("$ifNull").get(0).asString().getValue());
        assertTrue(set.getDocument("updatedAt").containsKey("$literal"), "the capture time is replaced");
        assertTrue(set.getDocument("discarded").containsKey("$or"));
    }

    @Test
    @DisplayName("upsert sends the guarded pipeline with upsert on")
    void upsert() {
        store.upsert(turn(false));
        ArgumentCaptor<UpdateOptions> options = ArgumentCaptor.forClass(UpdateOptions.class);
        verify(collection).updateOne(any(Bson.class), anyList(), options.capture());
        assertTrue(options.getValue().isUpsert());
    }

    @Test
    @DisplayName("a duplicate key from a concurrent first insert is retried once, and the retry stores the row")
    void duplicateKeyRetried() {
        when(collection.updateOne(any(Bson.class), anyList(), any(UpdateOptions.class))).thenThrow(new MongoException(11000, "E11000 duplicate key"))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        assertDoesNotThrow(() -> store.upsert(turn(false)));
        verify(collection, times(2)).updateOne(any(Bson.class), anyList(), any(UpdateOptions.class));
    }

    @Test
    @DisplayName("a second duplicate key is a newer stored row: the stale write is ignored, not retried again")
    void duplicateKeyTwiceIgnored() {
        when(collection.updateOne(any(Bson.class), anyList(), any(UpdateOptions.class))).thenThrow(new MongoException(11000, "E11000 duplicate key"));
        assertDoesNotThrow(() -> store.upsert(turn(false)));
        verify(collection, times(2)).updateOne(any(Bson.class), anyList(), any(UpdateOptions.class));
    }

    @Test
    @DisplayName("a retry that fails otherwise is a HealthStoreException")
    void duplicateKeyThenOtherFailure() {
        when(collection.updateOne(any(Bson.class), anyList(), any(UpdateOptions.class))).thenThrow(new MongoException(11000, "E11000 duplicate key"))
                .thenThrow(new MongoException("down"));
        assertThrows(HealthStoreException.class, () -> store.upsert(turn(false)));
    }

    @Test
    @DisplayName("insertIfAbsent only sets on insert")
    void insertIfAbsent() {
        store.insertIfAbsent(turn(false));
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection).updateOne(any(Bson.class), update.capture(), any(UpdateOptions.class));
        BsonDocument rendered = render(update.getValue());
        assertEquals(1, rendered.size());
        assertTrue(rendered.containsKey("$setOnInsert"));
    }

    @Test
    @DisplayName("markDiscarded upserts one sticky marker per id; nothing to mark is no write")
    @SuppressWarnings("unchecked")
    void markDiscarded() {
        assertEquals(2L, store.markDiscarded(turn(false), List.of("a", "b", "a")));
        verify(collection).bulkWrite(anyList());

        MongoCollection<Document> untouched = mock(MongoCollection.class);
        MongoHealthTurnStore idle = new MongoHealthTurnStore(untouched);
        assertEquals(0L, idle.markDiscarded(turn(false), List.of()));
        assertEquals(0L, idle.markDiscarded(null, List.of("a")));
        verifyNoInteractions(untouched);
    }

    @Test
    @DisplayName("the day range is an inclusive string range on the agent")
    void dayRange() {
        String rendered = render(MongoHealthTurnStore.agentDayRange("agent", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10))).toJson();
        assertTrue(rendered.contains("\"agentId\": \"agent\""), rendered);
        assertTrue(rendered.contains("\"$gte\": \"2026-11-01\""), rendered);
        assertTrue(rendered.contains("\"$lte\": \"2026-11-10\""), rendered);
    }

    @Test
    @DisplayName("retention deletes earlier days and markers written before the cutoff day")
    void olderThan() {
        String rendered = render(MongoHealthTurnStore.olderThan(LocalDate.of(2026, 8, 12))).toJson();
        assertTrue(rendered.contains("\"day\": {\"$lt\": \"2026-08-12\"}"), rendered);
        assertTrue(rendered.contains("\"discardedAt\""), rendered);
    }

    @Test
    @DisplayName("deletes: by user, by conversation, and for retention")
    void deletes() {
        when(collection.deleteMany(any(Bson.class))).thenReturn(DeleteResult.acknowledged(4));
        assertEquals(4L, store.deleteByUserId("user"));
        assertEquals(4L, store.deleteByConversationId("conv"));
        assertEquals(4L, store.deleteOlderThan(LocalDate.of(2026, 8, 12)));
        ArgumentCaptor<Bson> filters = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(3)).deleteMany(filters.capture());
        assertEquals("user", render(filters.getAllValues().get(0)).getString("userId").getValue());
        assertEquals("conv", render(filters.getAllValues().get(1)).getString("conversationId").getValue());
    }

    @Test
    @DisplayName("any other driver failure becomes a HealthStoreException")
    void failures() {
        when(collection.updateOne(any(Bson.class), anyList(), any(UpdateOptions.class))).thenThrow(new MongoException("down"));
        assertThrows(HealthStoreException.class, () -> store.upsert(turn(false)));
        when(collection.deleteMany(any(Bson.class))).thenThrow(new MongoException("down"));
        assertThrows(HealthStoreException.class, () -> store.deleteByUserId("u"));
    }
}
