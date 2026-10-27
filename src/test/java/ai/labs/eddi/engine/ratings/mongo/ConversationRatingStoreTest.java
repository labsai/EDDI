/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings.mongo;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig.Scale;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import ai.labs.eddi.engine.ratings.model.RatingFilter;
import ai.labs.eddi.engine.ratings.model.RatingKind;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.result.DeleteResult;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Java side of the MongoDB rating store against a mocked collection: what
 * the upsert writes, how it treats a duplicate key, and how filters render. The
 * database semantics run in {@code MongoConversationRatingStoreIT}.
 */
@SuppressWarnings("unchecked")
@DisplayName("ConversationRatingStore (MongoDB, mocked)")
class ConversationRatingStoreTest {

    private MongoCollection<Document> collection;
    private ConversationRatingStore store;

    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(database.getCollection(ConversationRatingStore.COLLECTION_RATINGS)).thenReturn(collection);
        store = new ConversationRatingStore(database);
    }

    private static ConversationRating rating() {
        return new ConversationRating(null, "conv", -1, "agent", 3, "production", "chat-ui", "user", Scale.FIVE_STAR, 4, 0.75,
                List.of("Helpful"), "fine", null, null);
    }

    private static Document storedDocument() {
        Date now = new Date();
        return new Document("_id", new ObjectId()).append("conversationId", "conv").append("stepIndex", -1).append("agentId", "agent")
                .append("agentVersion", 3).append("scale", "FIVE_STAR").append("score", 4).append("normalizedScore", 0.75)
                .append("tags", List.of("Helpful")).append("userId", "user").append("createdAt", now).append("updatedAt", now);
    }

    private static BsonDocument render(Bson bson) {
        return bson.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
    }

    @Test
    @DisplayName("creates a unique index on (conversationId, stepIndex) and the query indexes")
    void createsIndexes() {
        ArgumentCaptor<IndexOptions> options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(collection, times(5)).createIndex(any(Bson.class), options.capture());
        IndexOptions key = options.getAllValues().stream().filter(o -> "idx_rating_key".equals(o.getName())).findFirst().orElseThrow();
        assertTrue(key.isUnique());
    }

    @Test
    @DisplayName("upsert sets createdAt only on insert, so a re-rating keeps it")
    void upsertKeepsCreatedAt() {
        when(collection.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class))).thenReturn(storedDocument());
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<FindOneAndUpdateOptions> options = ArgumentCaptor.forClass(FindOneAndUpdateOptions.class);

        ConversationRating stored = store.upsert(rating());

        verify(collection).findOneAndUpdate(any(Bson.class), update.capture(), options.capture());
        BsonDocument rendered = render(update.getValue());
        assertTrue(rendered.getDocument("$setOnInsert").containsKey("createdAt"));
        assertFalse(rendered.getDocument("$set").containsKey("createdAt"), "createdAt must never be overwritten");
        assertTrue(rendered.getDocument("$set").containsKey("updatedAt"));
        assertEquals("NEW", rendered.getDocument("$set").getString("reviewStatus").getValue(), "a re-rating reopens the triage");
        assertTrue(rendered.getDocument("$set").isNull("reviewNote"));
        assertTrue(rendered.getDocument("$set").isNull("reviewedBy"));
        assertTrue(options.getValue().isUpsert());
        assertEquals(4, stored.score());
        assertEquals(List.of("Helpful"), stored.tags());
    }

    @Test
    @DisplayName("a duplicate key from a concurrent first rating is retried once as an update")
    void duplicateKeyIsRetried() {
        when(collection.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenThrow(new MongoException(11000, "E11000 duplicate key")).thenReturn(storedDocument());

        ConversationRating stored = store.upsert(rating());

        assertEquals("conv", stored.conversationId());
        verify(collection, times(2)).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    @DisplayName("any other write failure propagates")
    void otherFailuresPropagate() {
        MongoException failure = new MongoException(6, "host unreachable");
        when(collection.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class))).thenThrow(failure);

        assertSame(failure, assertThrows(MongoException.class, () -> store.upsert(rating())));
        verify(collection, times(1)).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    @DisplayName("deleteByUserId ignores a null user instead of deleting every unowned row")
    void deleteByNullUserIsNoOp() {
        assertEquals(0, store.deleteByUserId(null));
        verify(collection, never()).deleteMany(any(Bson.class));
    }

    @Test
    @DisplayName("deleteByUserId deletes by userId")
    void deleteByUser() {
        DeleteResult result = mock(DeleteResult.class);
        when(result.getDeletedCount()).thenReturn(3L);
        when(collection.deleteMany(any(Bson.class))).thenReturn(result);

        assertEquals(3, store.deleteByUserId("u1"));
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection).deleteMany(filter.capture());
        assertEquals("u1", render(filter.getValue()).getString("userId").getValue());
    }

    @Test
    @DisplayName("filters render every field, and kind maps to the step index")
    void filterRendering() {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        String json = render(ConversationRatingStore
                .toBson(new RatingFilter("a1", 2, "production", from, null, 0.5, null, RatingKind.CONVERSATION, "Helpful", true))).toJson();
        assertTrue(json.contains("\"agentId\": \"a1\""), json);
        assertTrue(json.contains("\"agentVersion\": 2"), json);
        assertTrue(json.contains("\"stepIndex\": -1"), json);
        assertTrue(json.contains("\"tags\": \"Helpful\""), json);
        assertTrue(json.contains("\"normalizedScore\": {\"$gte\": 0.5}"), json);
        assertTrue(json.contains("\"createdAt\": {\"$gte\""), json);

        String messages = render(ConversationRatingStore
                .toBson(new RatingFilter(null, null, null, null, null, null, null, RatingKind.MESSAGE, null, null))).toJson();
        assertTrue(messages.contains("\"stepIndex\": {\"$gte\": 0}"), messages);

        String byConversation = render(ConversationRatingStore
                .toBson(new RatingFilter(null, null, null, null, null, null, null, null, null, null, null, null, "c1"))).toJson();
        assertTrue(byConversation.contains("\"conversationId\": \"c1\""), byConversation);

        assertEquals(new BsonDocument(), render(ConversationRatingStore.toBson(RatingFilter.all())));
        assertEquals(new BsonDocument(), render(ConversationRatingStore.toBson(null)));
    }
}
