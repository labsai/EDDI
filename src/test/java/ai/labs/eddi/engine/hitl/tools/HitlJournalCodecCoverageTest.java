/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore.JournalEntry;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore.Status;
import ai.labs.eddi.engine.hitl.tools.mongo.HitlToolJournalStore;
import com.mongodb.MongoCommandException;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Additive branch-coverage test for the HITL tool-journal store and the
 * transcript codec / approval gate. Mirrors the mock-based Mongo harness of
 * {@code HitlToolJournalStoreTest} (mocked {@link MongoCollection}/
 * {@link MongoDatabase}) and drives the uncovered error/branch arms: TTL-index
 * conflict retry, index-drop catch, epoch-millis/null readback, markExecuted
 * no-match, capUtf8 boundary/null, serialize failure/empty, and the gate's
 * exempt/cleared/reason paths.
 */
@SuppressWarnings("unchecked")
@DisplayName("HitlJournalCodecCoverage")
class HitlJournalCodecCoverageTest {

    private static final String COLLECTION_NAME = "hitltoolexecutionjournal";
    private static final String TTL_INDEX_NAME = "idx_journal_ttl_claimed";

    @Mock
    private MongoDatabase database;

    @Mock
    private MongoCollection<Document> collection;

    @Mock
    private FindIterable<Document> findIterable;

    @BeforeEach
    void setUp() {
        openMocks(this);
        doReturn(collection).when(database).getCollection(COLLECTION_NAME);
        UpdateResult updateResult = mock(UpdateResult.class);
        lenient().doReturn(1L).when(updateResult).getMatchedCount();
        lenient().doReturn(updateResult).when(collection).updateOne(any(Bson.class), any(Bson.class));
    }

    private HitlToolJournalStore newStore() {
        return new HitlToolJournalStore(database, Duration.ofDays(30));
    }

    /**
     * Builds a MongoCommandException with the given error code via a raw BSON
     * response.
     */
    private static MongoCommandException commandException(int code) {
        BsonDocument response = new BsonDocument()
                .append("ok", new org.bson.BsonDouble(0))
                .append("code", new BsonInt32(code))
                .append("errmsg", new org.bson.BsonString("simulated code " + code));
        return new MongoCommandException(response, new com.mongodb.ServerAddress());
    }

    @Nested
    @DisplayName("constructor index management")
    class ConstructorIndexes {

        @Test
        @DisplayName("dropIndexIfPresent swallows a RuntimeException when the index is absent")
        void dropIndexAbsentIsSwallowed() {
            // Legacy TTL index drop throws (absent on a fresh deployment) — the catch
            // block must swallow it so the constructor completes normally.
            doThrow(new RuntimeException("index not found: idx_journal_ttl"))
                    .when(collection).dropIndex(anyString());

            assertDoesNotThrow(this::buildStore);
        }

        @Test
        @DisplayName("TTL index conflict (code 85) drops and recreates the TTL index")
        void ttlConflictRecreatesIndex() {
            // First createIndex for the TTL key throws IndexOptionsConflict (85); the
            // retry path drops the TTL index and recreates it.
            doThrow(commandException(85))
                    .doReturn(TTL_INDEX_NAME)
                    .when(collection).createIndex(any(Bson.class), argThat(this::isTtlOptions));

            assertDoesNotThrow(this::buildStore);

            // The conflict-retry path must have dropped the TTL index by name and
            // then re-created it (two createIndex calls with the TTL options).
            verify(collection).dropIndex(TTL_INDEX_NAME);
            verify(collection, times(2)).createIndex(any(Bson.class), argThat(this::isTtlOptions));
        }

        @Test
        @DisplayName("non-conflict MongoCommandException (not code 85) propagates")
        void ttlNonConflictPropagates() {
            MongoCommandException other = commandException(26); // NamespaceNotFound
            doThrow(other).when(collection).createIndex(any(Bson.class), argThat(this::isTtlOptions));

            MongoCommandException thrown = assertThrows(MongoCommandException.class, this::buildStore);
            assertSame(other, thrown);
        }

        private boolean isTtlOptions(IndexOptions options) {
            return options != null && TTL_INDEX_NAME.equals(options.getName());
        }

        private HitlToolJournalStore buildStore() {
            return new HitlToolJournalStore(database, Duration.ofDays(30));
        }
    }

    @Nested
    @DisplayName("readEpochMillis backward-compat")
    class ReadEpochMillis {

        private Document baseDoc() {
            return new Document()
                    .append("conversationId", "conv-1")
                    .append("pauseEpoch", "epoch-1")
                    .append("callId", "call-1")
                    .append("toolName", "toolA")
                    .append("status", Status.EXECUTED.name())
                    .append("resultCapped", "the result")
                    .append("decidedBy", "user-1");
        }

        @Test
        @DisplayName("reads executedAt stored as an int64 epoch-millis Number (legacy format)")
        void readsEpochMillisAsNumber() {
            HitlToolJournalStore store = newStore();
            Instant when = Instant.now();
            Document doc = baseDoc().append("executedAt", when.toEpochMilli());
            doReturn(findIterable).when(collection).find(any(Bson.class));
            doReturn(doc).when(findIterable).first();

            Optional<JournalEntry> result = store.find("conv-1", "epoch-1", "call-1");

            assertTrue(result.isPresent());
            assertNotNull(result.get().executedAt());
            assertEquals(when.toEpochMilli(), result.get().executedAt().toEpochMilli());
        }

        @Test
        @DisplayName("returns null executedAt when the field is absent (neither Date nor Number)")
        void nullExecutedAtWhenFieldAbsent() {
            HitlToolJournalStore store = newStore();
            // No executedAt field at all — readEpochMillis returns null.
            Document doc = baseDoc();
            doReturn(findIterable).when(collection).find(any(Bson.class));
            doReturn(doc).when(findIterable).first();

            Optional<JournalEntry> result = store.find("conv-1", "epoch-1", "call-1");

            assertTrue(result.isPresent());
            assertNull(result.get().executedAt(), "absent executedAt must read back as null");
        }

        @Test
        @DisplayName("defaults status to EXECUTING when the stored status string is absent")
        void nullStatusDefaultsToExecuting() {
            HitlToolJournalStore store = newStore();
            Document doc = new Document()
                    .append("conversationId", "conv-1")
                    .append("pauseEpoch", "epoch-1")
                    .append("callId", "call-1")
                    .append("toolName", "toolA")
                    .append("decidedBy", "user-1");
            doReturn(findIterable).when(collection).find(any(Bson.class));
            doReturn(doc).when(findIterable).first();

            Optional<JournalEntry> result = store.find("conv-1", "epoch-1", "call-1");

            assertTrue(result.isPresent());
            assertEquals(Status.EXECUTING, result.get().status());
        }
    }

    @Nested
    @DisplayName("markExecuted / capUtf8 branches")
    class MarkExecutedBranches {

        @Test
        @DisplayName("logs (no throw) when updateOne matches no claimed entry")
        void noMatchedEntryIsHandled() {
            UpdateResult noMatch = mock(UpdateResult.class);
            doReturn(0L).when(noMatch).getMatchedCount();
            doReturn(noMatch).when(collection).updateOne(any(Bson.class), any(Bson.class));
            HitlToolJournalStore store = newStore();

            assertDoesNotThrow(() -> store.markExecuted("conv-1", "epoch-1", "call-1", "the result"));
            verify(collection).updateOne(any(Bson.class), any(Bson.class));
        }

        @Test
        @DisplayName("stores a small result unchanged (capUtf8 under-cap boundary)")
        void smallResultStoredUnchanged() {
            HitlToolJournalStore store = newStore();
            String small = "short result under the cap";

            store.markExecuted("conv-1", "epoch-1", "call-1", small);

            var updateCaptor = org.mockito.ArgumentCaptor.forClass(Bson.class);
            verify(collection).updateOne(any(Bson.class), updateCaptor.capture());
            BsonDocument rendered = updateCaptor.getValue()
                    .toBsonDocument(Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry());
            String cappedResult = rendered.getDocument("$set").getString("resultCapped").getValue();
            assertEquals(small, cappedResult, "an under-cap value must be stored verbatim");
        }

        @Test
        @DisplayName("stores a null result as null (capUtf8 null guard)")
        void nullResultStoredAsNull() {
            HitlToolJournalStore store = newStore();

            store.markExecuted("conv-1", "epoch-1", "call-1", null);

            var updateCaptor = org.mockito.ArgumentCaptor.forClass(Bson.class);
            verify(collection).updateOne(any(Bson.class), updateCaptor.capture());
            BsonDocument rendered = updateCaptor.getValue()
                    .toBsonDocument(Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry());
            assertTrue(rendered.getDocument("$set").isNull("resultCapped"),
                    "a null result must be stored as BSON null, not a string");
        }
    }

    @Nested
    @DisplayName("ChatTranscriptCodec branches")
    class Codec {

        @Test
        @DisplayName("deserialize of an empty JSON array throws (empty-list guard)")
        void deserializeEmptyListThrows() {
            var codec = new ChatTranscriptCodec();
            ChatTranscriptCodec.TranscriptCodecException ex = assertThrows(
                    ChatTranscriptCodec.TranscriptCodecException.class,
                    () -> codec.deserialize("[]"));
            assertTrue(ex.getMessage().contains("empty"), "message should flag the empty transcript");
        }

        @Test
        @DisplayName("serialize returns omitted (no json) when the cap is exceeded")
        void serializeOverCapOmits() {
            var codec = new ChatTranscriptCodec();
            List<ChatMessage> messages = List.of(UserMessage.from("hello world, this is a message"));
            var result = codec.serialize(messages, 4);
            assertTrue(result.omitted());
            assertNull(result.json());
        }

        @Test
        @DisplayName("serialize under the cap returns json and omitted=false")
        void serializeUnderCap() {
            var codec = new ChatTranscriptCodec();
            List<ChatMessage> messages = List.of(UserMessage.from("hi"));
            var result = codec.serialize(messages, 1_000_000);
            assertFalse(result.omitted());
            assertNotNull(result.json());
        }
    }

    @Nested
    @DisplayName("ToolApprovalGate branches")
    class Gate {

        private static dev.langchain4j.agent.tool.ToolExecutionRequest req(String id, String name) {
            return dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
        }

        private static ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig cfg(List<String> require, List<String> exempt) {
            var c = new ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig();
            c.setRequireApproval(require);
            c.setExempt(exempt);
            return c;
        }

        @Test
        @DisplayName("exempt precedence: an exempt-matching tool flows to allowed even when require matches")
        void exemptPrecedenceAllows() {
            var gate = new ToolApprovalGate();
            var batch = List.of(req("1", "read_file"));
            var sources = java.util.Map.of("read_file", "mcp");
            var result = gate.classify(batch, sources,
                    cfg(List.of("mcp:*"), List.of("mcp:read_*")), java.util.Set.of());
            assertTrue(result.gated().isEmpty(), "exempt must beat require");
            assertEquals(1, result.allowed().size());
        }

        @Test
        @DisplayName("cleared callId is never re-gated (approved by a human)")
        void clearedCallIdAllowed() {
            var gate = new ToolApprovalGate();
            var batch = List.of(req("1", "delete_account"));
            var result = gate.classify(batch, java.util.Map.of("delete_account", "http"),
                    cfg(List.of("delete_*"), null), java.util.Set.of("1"));
            assertTrue(result.gated().isEmpty());
            assertEquals(1, result.allowed().size());
        }

        @Test
        @DisplayName("gated call with a non-null id records the matching pattern as its reason")
        void gatedNonNullIdRecordsReason() {
            var gate = new ToolApprovalGate();
            var batch = List.of(req("call-9", "delete_account"));
            var result = gate.classify(batch, java.util.Map.of("delete_account", "http"),
                    cfg(List.of("delete_*"), null), java.util.Set.of());
            assertEquals(1, result.gated().size());
            assertEquals("delete_*", result.gateReasonByCallId().get("call-9"),
                    "the matched require-pattern must be recorded as the gate reason");
        }
    }
}
