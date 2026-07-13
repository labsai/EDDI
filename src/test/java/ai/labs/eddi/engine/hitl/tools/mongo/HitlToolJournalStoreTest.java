/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools.mongo;

import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore.JournalEntry;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore.Status;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Mirrors the mock-based Mongo store test harness used by
 * {@code AgentTriggerStoreTest} and {@code MongoScheduleStoreTest} (see
 * {@code AgentTriggerStore}/{@code MongoScheduleStore}, both of which create
 * their indexes in the {@code @Inject} constructor and are tested against a
 * mocked {@link MongoCollection} rather than Testcontainers). The real
 * duplicate-key/TTL semantics are exercised in production by MongoDB itself;
 * here we verify the store's Java-side logic: what it inserts, what it updates,
 * and how it reacts to a duplicate-key failure from the driver.
 */
@SuppressWarnings("unchecked")
@DisplayName("HitlToolJournalStore")
class HitlToolJournalStoreTest {

    private static final String COLLECTION_NAME = "hitltoolexecutionjournal";

    @Mock
    private MongoDatabase database;

    @Mock
    private MongoCollection<Document> collection;

    @Mock
    private FindIterable<Document> findIterable;

    private HitlToolJournalStore store;

    @BeforeEach
    void setUp() {
        openMocks(this);
        doReturn(collection).when(database).getCollection(COLLECTION_NAME);
        UpdateResult updateResult = mock(UpdateResult.class);
        doReturn(1L).when(updateResult).getMatchedCount();
        doReturn(updateResult).when(collection).updateOne(any(Bson.class), any(Bson.class));
        store = new HitlToolJournalStore(database, Duration.ofDays(30));
    }

    @Test
    @DisplayName("constructor creates a unique compound index on (conversationId, pauseEpoch, callId)")
    void constructorCreatesIndexes() {
        // The at-most-once guarantee rests on the UNIQUE compound index — assert its
        // key composition and unique flag specifically (the exact number of
        // createIndex calls may change as the legacy TTL is dropped and a new TTL
        // index is created, so we do not pin times(N)).
        var keyCaptor = ArgumentCaptor.forClass(Bson.class);
        var optionsCaptor = ArgumentCaptor.forClass(IndexOptions.class);
        verify(collection, atLeastOnce()).createIndex(keyCaptor.capture(), optionsCaptor.capture());

        var keys = keyCaptor.getAllValues();
        var options = optionsCaptor.getAllValues();

        IndexOptions uniqueOptions = null;
        Bson uniqueKey = null;
        for (int i = 0; i < options.size(); i++) {
            if (Boolean.TRUE.equals(options.get(i).isUnique())) {
                uniqueOptions = options.get(i);
                uniqueKey = keys.get(i);
                break;
            }
        }

        assertNotNull(uniqueOptions, "a unique index must be created");
        assertTrue(uniqueOptions.isUnique(), "the key index must be unique");

        BsonDocument renderedKey = uniqueKey
                .toBsonDocument(Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry());
        assertEquals(List.of("conversationId", "pauseEpoch", "callId"),
                List.copyOf(renderedKey.keySet()),
                "unique index must compose exactly (conversationId, pauseEpoch, callId) in order");
    }

    @Nested
    @DisplayName("tryClaim")
    class TryClaim {

        @Test
        @DisplayName("inserts EXECUTING entry and returns true when no prior entry exists")
        void claimsWhenNoExistingEntry() {
            boolean claimed = store.tryClaim("conv-1", "epoch-1", "call-1", "toolA", "user-1");

            assertTrue(claimed);
            verify(collection).insertOne(any(Document.class));
        }

        @Test
        @DisplayName("returns false on duplicate-key without leaking MongoWriteException")
        void returnsFalseOnDuplicateKey() {
            MongoWriteException duplicateKeyEx = new MongoWriteException(
                    new WriteError(11000, "E11000 duplicate key error", new BsonDocument()),
                    new ServerAddress());
            doThrow(duplicateKeyEx).when(collection).insertOne(any(Document.class));

            boolean claimed = store.tryClaim("conv-1", "epoch-1", "call-1", "toolA", "user-1");

            assertFalse(claimed);
        }

        @Test
        @DisplayName("same callId under a different pauseEpoch claims independently")
        void differentPauseEpochClaimsIndependently() {
            boolean firstClaim = store.tryClaim("conv-1", "epoch-1", "call-1", "toolA", "user-1");
            boolean secondClaim = store.tryClaim("conv-1", "epoch-2", "call-1", "toolA", "user-1");

            assertTrue(firstClaim);
            assertTrue(secondClaim);

            // Capture both inserted documents and assert the pauseEpoch field is
            // actually written and distinguishes the two re-pauses — proving the key
            // includes pauseEpoch, not just callId.
            var docCaptor = ArgumentCaptor.forClass(Document.class);
            verify(collection, times(2)).insertOne(docCaptor.capture());
            var docs = docCaptor.getAllValues();
            assertEquals("epoch-1", docs.get(0).getString("pauseEpoch"));
            assertEquals("epoch-2", docs.get(1).getString("pauseEpoch"));
        }

        @Test
        @DisplayName("writes claimedAt as a java.util.Date so the TTL monitor honors it")
        void writesClaimedAtAsDate() {
            store.tryClaim("conv-1", "epoch-1", "call-1", "toolA", "user-1");

            var docCaptor = ArgumentCaptor.forClass(Document.class);
            verify(collection).insertOne(docCaptor.capture());
            Object claimedAt = docCaptor.getValue().get("claimedAt");
            assertNotNull(claimedAt, "every entry must carry claimedAt for TTL expiry");
            assertInstanceOf(Date.class, claimedAt,
                    "claimedAt must be a BSON Date (java.util.Date) — an int64 is ignored by the TTL monitor");
        }

        @Test
        @DisplayName("propagates non-duplicate MongoWriteException instead of returning false")
        void propagatesNonDuplicateWriteException() {
            MongoWriteException timeoutEx = new MongoWriteException(
                    new WriteError(50, "ExceededTimeLimit", new BsonDocument()),
                    new ServerAddress());
            doThrow(timeoutEx).when(collection).insertOne(any(Document.class));

            MongoWriteException thrown = assertThrows(MongoWriteException.class,
                    () -> store.tryClaim("conv-1", "epoch-1", "call-1", "toolA", "user-1"));

            assertSame(timeoutEx, thrown);
        }

        @Test
        @DisplayName("propagates generic MongoException instead of returning false")
        void propagatesGenericMongoException() {
            MongoException connectivityEx = new MongoException("connection reset");
            doThrow(connectivityEx).when(collection).insertOne(any(Document.class));

            MongoException thrown = assertThrows(MongoException.class,
                    () -> store.tryClaim("conv-1", "epoch-1", "call-1", "toolA", "user-1"));

            assertSame(connectivityEx, thrown);
        }
    }

    @Nested
    @DisplayName("markExecuted")
    class MarkExecuted {

        @Test
        @DisplayName("updates status to EXECUTED with capped result")
        void updatesStatusAndResult() {
            store.markExecuted("conv-1", "epoch-1", "call-1", "the result");

            verify(collection).updateOne(any(Bson.class), any(Bson.class));
        }

        @Test
        @DisplayName("caps result at 32 KB")
        void capsResultAt32Kb() {
            String oversized = "x".repeat(40_000);

            store.markExecuted("conv-1", "epoch-1", "call-1", oversized);

            var updateCaptor = org.mockito.ArgumentCaptor.forClass(Bson.class);
            verify(collection).updateOne(any(Bson.class), updateCaptor.capture());
            BsonDocument rendered = updateCaptor.getValue()
                    .toBsonDocument(Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry());
            String cappedResult = rendered.getDocument("$set").getString("resultCapped").getValue();
            assertEquals(32 * 1024, cappedResult.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }

        @Test
        @DisplayName("caps result at 32 KB without splitting a multi-byte UTF-8 code point")
        void capsResultAt32KbWithoutSplittingMultiByteCharacter() {
            // U+1F600 GRINNING FACE is a 4-byte UTF-8 code point (surrogate pair in
            // Java's UTF-16 String). Repeating it past the 32 KB cap forces the
            // capUtf8 back-trim loop to walk back over continuation bytes (0x80-0xBF).
            String emoji = "😀";
            String oversized = emoji.repeat(10_000);

            store.markExecuted("conv-1", "epoch-1", "call-1", oversized);

            var updateCaptor = org.mockito.ArgumentCaptor.forClass(Bson.class);
            verify(collection).updateOne(any(Bson.class), updateCaptor.capture());
            BsonDocument rendered = updateCaptor.getValue()
                    .toBsonDocument(Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry());
            String cappedResult = rendered.getDocument("$set").getString("resultCapped").getValue();

            byte[] cappedBytes = cappedResult.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(cappedBytes.length <= 32 * 1024, "capped result must be at or under the 32 KB byte cap");
            // A split code point would decode as the UTF-8 replacement character.
            assertFalse(cappedResult.contains("�"), "capped result must not contain a split code point");
            // Every emoji is a UTF-16 surrogate pair (2 chars); a clean cap means the
            // string is composed entirely of whole emoji, so its length is even and
            // its trailing char is a low surrogate (never a lone high surrogate).
            assertEquals(0, cappedResult.length() % 2, "capped result must not end mid surrogate-pair");
            assertFalse(Character.isHighSurrogate(cappedResult.charAt(cappedResult.length() - 1)),
                    "capped result must not end on a lone high surrogate");
            // Re-encoding to UTF-8 and back must round-trip exactly — proof the
            // cap landed on a whole-code-point boundary, not mid-sequence.
            String roundTripped = new String(cappedBytes, java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(cappedResult, roundTripped);
        }
    }

    @Nested
    @DisplayName("find")
    class Find {

        @Test
        @DisplayName("returns empty when no entry exists")
        void returnsEmptyWhenMissing() {
            doReturn(findIterable).when(collection).find(any(Bson.class));
            doReturn(null).when(findIterable).first();

            Optional<JournalEntry> result = store.find("conv-1", "epoch-1", "call-1");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns EXECUTING entry after tryClaim")
        void returnsExecutingAfterClaim() {
            Document doc = new Document()
                    .append("conversationId", "conv-1")
                    .append("pauseEpoch", "epoch-1")
                    .append("callId", "call-1")
                    .append("toolName", "toolA")
                    .append("status", Status.EXECUTING.name())
                    .append("decidedBy", "user-1");
            doReturn(findIterable).when(collection).find(any(Bson.class));
            doReturn(doc).when(findIterable).first();

            Optional<JournalEntry> result = store.find("conv-1", "epoch-1", "call-1");

            assertTrue(result.isPresent());
            assertEquals(Status.EXECUTING, result.get().status());
            assertEquals("toolA", result.get().toolName());
            assertNull(result.get().resultCapped());
        }

        @Test
        @DisplayName("returns EXECUTED entry with result after markExecuted")
        void returnsExecutedWithResult() {
            Document doc = new Document()
                    .append("conversationId", "conv-1")
                    .append("pauseEpoch", "epoch-1")
                    .append("callId", "call-1")
                    .append("toolName", "toolA")
                    .append("status", Status.EXECUTED.name())
                    .append("resultCapped", "the result")
                    .append("executedAt", Instant.now().toEpochMilli())
                    .append("decidedBy", "user-1");
            doReturn(findIterable).when(collection).find(any(Bson.class));
            doReturn(doc).when(findIterable).first();

            Optional<JournalEntry> result = store.find("conv-1", "epoch-1", "call-1");

            assertTrue(result.isPresent());
            assertEquals(Status.EXECUTED, result.get().status());
            assertEquals("the result", result.get().resultCapped());
            assertNotNull(result.get().executedAt());
        }

        @Test
        @DisplayName("reads executedAt stored as a BSON Date (the new format)")
        void readsExecutedAtStoredAsDate() {
            Instant when = Instant.now();
            Document doc = new Document()
                    .append("conversationId", "conv-1")
                    .append("pauseEpoch", "epoch-1")
                    .append("callId", "call-1")
                    .append("toolName", "toolA")
                    .append("status", Status.EXECUTED.name())
                    .append("resultCapped", "the result")
                    .append("executedAt", Date.from(when))
                    .append("decidedBy", "user-1");
            doReturn(findIterable).when(collection).find(any(Bson.class));
            doReturn(doc).when(findIterable).first();

            Optional<JournalEntry> result = store.find("conv-1", "epoch-1", "call-1");

            assertTrue(result.isPresent());
            assertNotNull(result.get().executedAt());
            assertEquals(when.toEpochMilli(), result.get().executedAt().toEpochMilli());
        }
    }

    @Nested
    @DisplayName("deleteByConversationId")
    class DeleteByConversationId {

        @Test
        @DisplayName("deletes all entries for the conversation and returns the count")
        void deletesAndReturnsCount() {
            DeleteResult deleteResult = mock(DeleteResult.class);
            doReturn(4L).when(deleteResult).getDeletedCount();
            doReturn(deleteResult).when(collection).deleteMany(any(Bson.class));

            long deleted = store.deleteByConversationId("conv-1");

            assertEquals(4L, deleted);
            verify(collection).deleteMany(any(Bson.class));
        }
    }
}
