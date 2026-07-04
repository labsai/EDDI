/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.mongo.codec.JacksonProvider;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall;
import ai.labs.eddi.engine.model.Deployment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import de.undercouch.bson4jackson.BsonFactory;
import de.undercouch.bson4jackson.BsonParser;
import org.bson.codecs.*;
import org.bson.codecs.configuration.CodecRegistry;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.bson.codecs.configuration.CodecRegistries.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ConversationMemoryStore} (MongoDB) using
 * Testcontainers.
 * <p>
 * Requires a custom codec registry (JacksonProvider) because the store uses
 * {@code getCollection(name, ConversationMemorySnapshot.class)}.
 *
 * @since 6.0.0
 */
@Testcontainers
@DisplayName("MongoConversationMemoryStore IT")
class MongoConversationMemoryStoreTest {

    private static final String DB_NAME = "eddi_conv_test";

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    private static MongoClient mongoClient;
    private static MongoDatabase database;
    private static ConversationMemoryStore store;

    @BeforeAll
    static void init() {
        // Build BSON-aware ObjectMapper matching production PersistenceModule
        BsonFactory bsonFactory = new BsonFactory();
        bsonFactory.enable(BsonParser.Feature.HONOR_DOCUMENT_LENGTH);
        var bsonMapper = new ObjectMapper(bsonFactory);
        bsonMapper.registerModule(new JavaTimeModule());
        bsonMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        new SerializationCustomizer(false).customize(bsonMapper);

        CodecRegistry codecRegistry = fromRegistries(
                MongoClientSettings.getDefaultCodecRegistry(),
                fromCodecs(new RawBsonDocumentCodec()),
                fromProviders(
                        new ValueCodecProvider(),
                        new BsonValueCodecProvider(),
                        new DocumentCodecProvider(),
                        new IterableCodecProvider(),
                        new MapCodecProvider(),
                        new JacksonProvider(bsonMapper)));

        var settings = MongoClientSettings.builder()
                .applyConnectionString(new com.mongodb.ConnectionString(MONGO.getConnectionString()))
                .codecRegistry(codecRegistry)
                .build();

        mongoClient = MongoClients.create(settings);
        database = mongoClient.getDatabase(DB_NAME);
        store = new ConversationMemoryStore(database);
    }

    @AfterAll
    static void closeMongo() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @BeforeEach
    void clean() {
        database.getCollection("conversationmemories").drop();
    }

    // ─── Store + Load ───────────────────────────────────────────

    @Nested
    @DisplayName("Store and Load")
    class StoreAndLoad {

        @Test
        @DisplayName("store new snapshot — generates ID and round-trips")
        void storeNewSnapshot() {
            var snapshot = createSnapshot(null, "agent1", 1, "user1",
                    ConversationState.IN_PROGRESS);

            String id = store.storeConversationMemorySnapshot(snapshot);
            assertNotNull(id);

            var loaded = store.loadConversationMemorySnapshot(id);
            assertNotNull(loaded);
            assertEquals(id, loaded.getConversationId());
            assertEquals("agent1", loaded.getAgentId());
            assertEquals(1, loaded.getAgentVersion());
            assertEquals(ConversationState.IN_PROGRESS, loaded.getConversationState());
        }

        @Test
        @DisplayName("update existing snapshot — preserves ID, updates state")
        void updateExistingSnapshot() {
            var snapshot = createSnapshot(null, "agent1", 1, "user1",
                    ConversationState.IN_PROGRESS);
            String id = store.storeConversationMemorySnapshot(snapshot);

            snapshot.setConversationId(id);
            snapshot.setId(id);
            snapshot.setConversationState(ConversationState.ENDED);
            store.storeConversationMemorySnapshot(snapshot);

            var loaded = store.loadConversationMemorySnapshot(id);
            assertEquals(ConversationState.ENDED, loaded.getConversationState());
        }

        @Test
        @DisplayName("load non-existent — returns null")
        void loadNonExistent() {
            assertNull(store.loadConversationMemorySnapshot("000000000000000000000000"));
        }
    }

    // ─── State Management ───────────────────────────────────────

    @Nested
    @DisplayName("State Management")
    class StateManagement {

        @Test
        @DisplayName("setConversationState — updates state")
        void setConversationState() {
            String id = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS));

            store.setConversationState(id, ConversationState.ENDED);
            assertEquals(ConversationState.ENDED, store.getConversationState(id));
        }

        @Test
        @DisplayName("getConversationState — returns null for non-existent")
        void getStateNonExistent() {
            assertNull(store.getConversationState("000000000000000000000000"));
        }
    }

    // ─── Delete ─────────────────────────────────────────────────

    @Test
    @DisplayName("deleteConversationMemorySnapshot — removes snapshot")
    void deleteSnapshot() {
        String id = store.storeConversationMemorySnapshot(
                createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS));

        store.deleteConversationMemorySnapshot(id);
        assertNull(store.loadConversationMemorySnapshot(id));
    }

    // ─── Active Conversation Queries ────────────────────────────

    @Nested
    @DisplayName("Active Conversation Queries")
    class ActiveQueries {

        @Test
        @DisplayName("loadActiveConversationMemorySnapshot — excludes ENDED")
        void loadActive() throws IResourceStore.ResourceStoreException {
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "u1", ConversationState.IN_PROGRESS));
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "u2", ConversationState.IN_PROGRESS));
            String endedId = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "u3", ConversationState.IN_PROGRESS));
            store.setConversationState(endedId, ConversationState.ENDED);

            List<ConversationMemorySnapshot> active = store.loadActiveConversationMemorySnapshot("agent1", 1);
            assertEquals(2, active.size());
        }

        @Test
        @DisplayName("getActiveConversationCount — counts non-ENDED only")
        void activeCount() {
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent2", 1, "u1", ConversationState.IN_PROGRESS));
            String endedId = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent2", 1, "u2", ConversationState.IN_PROGRESS));
            store.setConversationState(endedId, ConversationState.ENDED);

            assertEquals(1L, store.getActiveConversationCount("agent2", 1));
        }

        @Test
        @DisplayName("getEndedConversationIds — returns only ENDED")
        void endedIds() {
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS));
            String endedId = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS));
            store.setConversationState(endedId, ConversationState.ENDED);

            List<String> ended = store.getEndedConversationIds();
            assertEquals(1, ended.size());
            assertEquals(endedId, ended.getFirst());
        }
    }

    // ─── IResourceStore Adapter ─────────────────────────────────

    @Nested
    @DisplayName("IResourceStore Adapter")
    class ResourceStoreAdapter {

        @Test
        @DisplayName("create + read round-trip")
        void createAndRead() throws IResourceStore.ResourceNotFoundException {
            var snapshot = createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS);
            var resourceId = store.create(snapshot);
            assertNotNull(resourceId.getId());

            var loaded = store.read(resourceId.getId(), resourceId.getVersion());
            assertNotNull(loaded);
        }

        @Test
        @DisplayName("getCurrentResourceId — returns same ID")
        void getCurrentResourceId() {
            var resourceId = store.getCurrentResourceId("test-id");
            assertEquals("test-id", resourceId.getId());
            assertEquals(0, resourceId.getVersion());
        }
    }

    // ─── GDPR ───────────────────────────────────────────────────

    @Nested
    @DisplayName("GDPR Operations")
    class GdprOps {

        @Test
        @DisplayName("getConversationIdsByUserId — finds by userId")
        void getByUserId() {
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "target_user", ConversationState.IN_PROGRESS));
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "target_user", ConversationState.ENDED));
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "other_user", ConversationState.IN_PROGRESS));

            List<String> ids = store.getConversationIdsByUserId("target_user");
            assertEquals(2, ids.size());
        }

        @Test
        @DisplayName("deleteConversationsByUserId — removes all for user")
        void deleteByUserId() {
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "delete_me", ConversationState.IN_PROGRESS));
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "delete_me", ConversationState.ENDED));
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "keep_me", ConversationState.IN_PROGRESS));

            long deleted = store.deleteConversationsByUserId("delete_me");
            assertEquals(2, deleted);
            assertTrue(store.getConversationIdsByUserId("delete_me").isEmpty());
            assertEquals(1, store.getConversationIdsByUserId("keep_me").size());
        }
    }

    // ─── HITL primitives ────────────────────────────────────────

    @Nested
    @DisplayName("HITL primitives")
    class HitlPrimitives {

        @Test
        @DisplayName("compareAndSetState succeeds only from the expected state")
        void compareAndSetState() {
            var snapshot = createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN);
            String id = store.storeConversationMemorySnapshot(snapshot);

            assertFalse(store.compareAndSetState(id, ConversationState.READY, ConversationState.IN_PROGRESS),
                    "CAS from a wrong expected state must fail");
            assertEquals(ConversationState.AWAITING_HUMAN, store.getConversationState(id));

            assertTrue(store.compareAndSetState(id, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS));
            assertEquals(ConversationState.IN_PROGRESS, store.getConversationState(id));

            assertFalse(store.compareAndSetState(id, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS),
                    "a second identical CAS must lose");
        }

        @Test
        @DisplayName("a no-op CAS (expected == target) succeeds even though nothing is modified")
        void compareAndSetStateNoOp() {
            var snapshot = createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN);
            String id = store.storeConversationMemorySnapshot(snapshot);

            // The filter matches (state is AWAITING_HUMAN) but the $set writes the same
            // value, so modifiedCount == 0. matchedCount-based CAS must still report
            // success — a false negative here would spuriously fail a caller's guard.
            assertTrue(store.compareAndSetState(id, ConversationState.AWAITING_HUMAN, ConversationState.AWAITING_HUMAN),
                    "a no-op CAS from the matching state must succeed (matchedCount, not modifiedCount)");
            assertEquals(ConversationState.AWAITING_HUMAN, store.getConversationState(id));
        }

        @Test
        @DisplayName("after a CAS the reloaded snapshot reports the new state (parity with the Postgres column-wins guarantee)")
        void loadReportsCasState() {
            var snapshot = createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN);
            String id = store.storeConversationMemorySnapshot(snapshot);

            assertTrue(store.compareAndSetState(id,
                    ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED));

            var loaded = store.loadConversationMemorySnapshot(id);
            assertNotNull(loaded);
            assertEquals(ConversationState.EXECUTION_INTERRUPTED, loaded.getConversationState(),
                    "load must not resurrect the terminally resolved pause");
        }

        @Test
        @DisplayName("storeConversationMemorySnapshotIfState persists only while the state matches (terminal writer wins)")
        void storeConversationMemorySnapshotIfState() {
            var snapshot = createSnapshot(null, "agent1", 1, "user1", ConversationState.IN_PROGRESS);
            String id = store.storeConversationMemorySnapshot(snapshot);

            // CAS-store from the matching state succeeds and flips the state to READY.
            var resumed = store.loadConversationMemorySnapshot(id);
            resumed.setConversationState(ConversationState.READY);
            assertTrue(store.storeConversationMemorySnapshotIfState(resumed, ConversationState.IN_PROGRESS),
                    "store must succeed while the persisted state still matches");
            assertEquals(ConversationState.READY, store.getConversationState(id));

            // A concurrent terminal writer flips it to ENDED; a CAS-store expecting
            // IN_PROGRESS must NOT overwrite the terminal state (the resume clobber the
            // fix guards against).
            store.setConversationState(id, ConversationState.ENDED);
            var stale = store.loadConversationMemorySnapshot(id);
            stale.setConversationState(ConversationState.READY);
            assertFalse(store.storeConversationMemorySnapshotIfState(stale, ConversationState.IN_PROGRESS),
                    "store must be rejected once a terminal writer moved the state off the expected value");
            assertEquals(ConversationState.ENDED, store.getConversationState(id),
                    "the terminal ENDED state must survive — no resurrection");
        }

        @Test
        @DisplayName("findPendingApprovalSummaries projects the bookmark incl. approvalTimeout and honors the limit")
        void findPendingApprovalSummaries() {
            var paused = createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN);
            paused.setHitlPausedAt(java.time.Instant.now());
            paused.setHitlPauseReason("needs review");
            paused.setHitlTimeoutPolicy("AUTO_REJECT");
            paused.setHitlApprovalTimeout("PT30M");
            String id = store.storeConversationMemorySnapshot(paused);
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "user2", ConversationState.AWAITING_HUMAN));
            // a READY conversation must never appear
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "user1", ConversationState.READY));

            var summaries = store.findPendingApprovalSummaries(10);
            assertEquals(2, summaries.size());
            var summary = summaries.stream().filter(s -> id.equals(s.getConversationId()))
                    .findFirst().orElseThrow();
            assertEquals("needs review", summary.getPauseReason());
            assertEquals("AUTO_REJECT", summary.getTimeoutPolicy());
            assertEquals("PT30M", summary.getApprovalTimeout());

            assertEquals(1, store.findPendingApprovalSummaries(1).size(), "limit must bound the result");
        }

        @Test
        @DisplayName("findPendingApprovalSummaries carries pauseType + toolNames (names only) for a TOOL_CALL pause")
        void findPendingApprovalSummariesCarriesPauseTypeAndToolNames() {
            var toolPaused = createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN);
            toolPaused.setHitlPauseType("TOOL_CALL");
            var batch = new PendingToolCallBatch();
            batch.setPauseEpoch("epoch-1");
            var call1 = new PendingToolCall();
            call1.setCallId("call-1");
            call1.setToolName("sendEmail");
            call1.setSource("mcp");
            call1.setArgumentsRaw("raw-secret-should-not-be-projected");
            call1.setArgumentsRedacted("redacted");
            var call2 = new PendingToolCall();
            call2.setCallId("call-2");
            call2.setToolName("chargeCard");
            call2.setSource("http");
            batch.setCalls(List.of(call1, call2));
            toolPaused.setHitlPendingToolCalls(batch);
            String toolPausedId = store.storeConversationMemorySnapshot(toolPaused);

            var rulePaused = createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN);
            rulePaused.setHitlPauseType("RULE");
            store.storeConversationMemorySnapshot(rulePaused);

            var summaries = store.findPendingApprovalSummaries(10);
            var toolSummary = summaries.stream().filter(s -> toolPausedId.equals(s.getConversationId()))
                    .findFirst().orElseThrow();
            assertEquals("TOOL_CALL", toolSummary.getPauseType());
            assertEquals(List.of("sendEmail", "chargeCard"), toolSummary.getToolNames());

            var ruleSummary = summaries.stream().filter(s -> !toolPausedId.equals(s.getConversationId()))
                    .findFirst().orElseThrow();
            assertEquals("RULE", ruleSummary.getPauseType());
            assertTrue(ruleSummary.getToolNames() == null || ruleSummary.getToolNames().isEmpty(),
                    "a RULE pause carries no tool names");
        }

        @Test
        @DisplayName("Task 14/6: a fully-populated PendingToolCallBatch survives a real BSON round-trip "
                + "(JacksonCodec) — every field, including nested traceSoFar maps")
        void pendingToolCallBatchFullRoundTrip() {
            // Unlike PendingToolCallBatchSnapshotTest (plain Jackson ObjectMapper, no
            // Mongo involved), this exercises the ACTUAL codec path production uses:
            // JacksonProvider/JacksonCodec over a real BSON document in a real MongoDB
            // instance. Object-typed fields like traceSoFar's nested Map<String,Object>
            // are exactly the kind of thing that can silently lose fidelity (e.g.
            // Integer <-> Double, or key ordering) when the wire format changes from
            // plain JSON to BSON.
            var snapshot = createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPauseType("TOOL_CALL");
            snapshot.setHitlPausedAt(java.time.Instant.now());
            snapshot.setHitlPauseReason("gated tool calls awaiting review");
            snapshot.setHitlTimeoutPolicy("AUTO_REJECT");
            snapshot.setHitlApprovalTimeout("PT30M");

            var batch = new PendingToolCallBatch();
            batch.setPauseEpoch("epoch-full-1");
            batch.setLlmTaskId("llm-task-1");
            batch.setLlmTaskIndex(2);
            batch.setWorkflowId("workflow-1");
            batch.setChatTranscriptJson("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            batch.setTranscriptOmitted(false);
            batch.setIterationIndex(3);
            batch.setActivatedToolNames(List.of("sendEmail", "chargeCard"));
            batch.setFingerprint("sha256:abc123");
            batch.setAutoApproveCount(2);
            batch.setPauseCountThisTurn(1);
            batch.setExecutedUngatedCallNames(List.of("readOnlyLookup"));

            var call1 = new PendingToolCall();
            call1.setCallId("call-1");
            call1.setToolName("sendEmail");
            call1.setSource("mcp");
            call1.setArgumentsRaw("{\"to\":\"user@example.com\",\"body\":\"hi\"}");
            call1.setArgsTruncated(false);
            call1.setArgumentsRedacted("{\"to\":\"[REDACTED]\",\"body\":\"hi\"}");
            call1.setGateReason("mcp:*");

            var call2 = new PendingToolCall();
            call2.setCallId("call-2");
            call2.setToolName("chargeCard");
            call2.setSource("http");
            call2.setArgumentsRaw("{\"amount\":100}");
            call2.setArgsTruncated(true);
            call2.setArgumentsRedacted("{\"amount\":100}");
            call2.setGateReason("http:chargeCard");
            batch.setCalls(List.of(call1, call2));

            // traceSoFar: a nested Map<String,Object> — the field most likely to lose
            // fidelity across a BSON round-trip (numeric widening, boolean/string
            // coercion, nested structures).
            var nested = new java.util.LinkedHashMap<String, Object>();
            nested.put("stringField", "value");
            nested.put("intField", 42);
            nested.put("boolField", true);
            nested.put("nestedList", List.of("a", "b", "c"));
            var traceEntry1 = new java.util.LinkedHashMap<String, Object>();
            traceEntry1.put("type", "tool_call");
            traceEntry1.put("tool", "readOnlyLookup");
            traceEntry1.put("detail", nested);
            var traceEntry2 = new java.util.LinkedHashMap<String, Object>();
            traceEntry2.put("type", "hitl_gate_tripped");
            traceEntry2.put("gatedCount", 2);
            batch.setTraceSoFar(List.of(traceEntry1, traceEntry2));

            snapshot.setHitlPendingToolCalls(batch);

            String id = store.storeConversationMemorySnapshot(snapshot);
            var loaded = store.loadConversationMemorySnapshot(id);

            assertNotNull(loaded);
            assertEquals("TOOL_CALL", loaded.getHitlPauseType());
            var loadedBatch = loaded.getHitlPendingToolCalls();
            assertNotNull(loadedBatch, "the pending batch itself must survive the round-trip");

            assertEquals("epoch-full-1", loadedBatch.getPauseEpoch());
            assertEquals("llm-task-1", loadedBatch.getLlmTaskId());
            assertEquals(2, loadedBatch.getLlmTaskIndex());
            assertEquals("workflow-1", loadedBatch.getWorkflowId());
            assertEquals("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", loadedBatch.getChatTranscriptJson());
            assertFalse(loadedBatch.isTranscriptOmitted());
            assertEquals(3, loadedBatch.getIterationIndex());
            assertEquals(List.of("sendEmail", "chargeCard"), loadedBatch.getActivatedToolNames());
            assertEquals("sha256:abc123", loadedBatch.getFingerprint());
            assertEquals(2, loadedBatch.getAutoApproveCount());
            assertEquals(1, loadedBatch.getPauseCountThisTurn());
            assertEquals(List.of("readOnlyLookup"), loadedBatch.getExecutedUngatedCallNames());

            assertNotNull(loadedBatch.getCalls());
            assertEquals(2, loadedBatch.getCalls().size());
            var loadedCall1 = loadedBatch.getCalls().get(0);
            assertEquals("call-1", loadedCall1.getCallId());
            assertEquals("sendEmail", loadedCall1.getToolName());
            assertEquals("mcp", loadedCall1.getSource());
            assertEquals("{\"to\":\"user@example.com\",\"body\":\"hi\"}", loadedCall1.getArgumentsRaw());
            assertFalse(loadedCall1.isArgsTruncated());
            assertEquals("{\"to\":\"[REDACTED]\",\"body\":\"hi\"}", loadedCall1.getArgumentsRedacted());
            assertEquals("mcp:*", loadedCall1.getGateReason());

            var loadedCall2 = loadedBatch.getCalls().get(1);
            assertEquals("call-2", loadedCall2.getCallId());
            assertEquals("chargeCard", loadedCall2.getToolName());
            assertTrue(loadedCall2.isArgsTruncated());

            assertNotNull(loadedBatch.getTraceSoFar());
            assertEquals(2, loadedBatch.getTraceSoFar().size());
            var loadedTrace1 = loadedBatch.getTraceSoFar().get(0);
            assertEquals("tool_call", loadedTrace1.get("type"));
            assertEquals("readOnlyLookup", loadedTrace1.get("tool"));
            @SuppressWarnings("unchecked")
            var loadedNested = (java.util.Map<String, Object>) loadedTrace1.get("detail");
            assertNotNull(loadedNested, "the nested Map<String,Object> inside traceSoFar must survive");
            assertEquals("value", loadedNested.get("stringField"));
            assertEquals(42, ((Number) loadedNested.get("intField")).intValue());
            assertEquals(true, loadedNested.get("boolField"));
            assertEquals(List.of("a", "b", "c"), loadedNested.get("nestedList"));

            var loadedTrace2 = loadedBatch.getTraceSoFar().get(1);
            assertEquals("hitl_gate_tripped", loadedTrace2.get("type"));
            assertEquals(2, ((Number) loadedTrace2.get("gatedCount")).intValue());
        }

        @Test
        @DisplayName("owner-filtered summaries push the filter into the query (limit applies after the restriction)")
        void findPendingApprovalSummariesByOwner() {
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN));
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN));
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "user2", ConversationState.AWAITING_HUMAN));

            var user1 = store.findPendingApprovalSummaries("user1", 10);
            assertEquals(2, user1.size());
            assertTrue(user1.stream().allMatch(s -> "user1".equals(s.getUserId())));

            assertEquals(1, store.findPendingApprovalSummaries("user2", 10).size());
            assertTrue(store.findPendingApprovalSummaries("nobody", 10).isEmpty());
            assertEquals(1, store.findPendingApprovalSummaries("user1", 1).size(),
                    "owner limit must bound the owner-filtered result");
        }

        @Test
        @DisplayName("clearHitlBookmark removes the bookmark fields but keeps the conversation")
        void clearHitlBookmark() {
            var snapshot = createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPausedAt(java.time.Instant.now());
            snapshot.setHitlPauseReason("needs review");
            snapshot.setHitlTimeoutPolicy("AUTO_REJECT");
            snapshot.setHitlApprovalTimeout("PT30M");
            String id = store.storeConversationMemorySnapshot(snapshot);

            store.clearHitlBookmark(id);

            var loaded = store.loadConversationMemorySnapshot(id);
            assertNotNull(loaded, "the conversation itself must survive");
            assertNull(loaded.getHitlPausedAt());
            assertNull(loaded.getHitlPauseReason());
            assertNull(loaded.getHitlTimeoutPolicy());
            assertNull(loaded.getHitlApprovalTimeout());
        }
    }

    // ─── Helpers ────────────────────────────────────────────────

    private static ConversationMemorySnapshot createSnapshot(String id, String agentId,
                                                             int agentVersion, String userId,
                                                             ConversationState state) {
        var snapshot = new ConversationMemorySnapshot();
        if (id != null) {
            snapshot.setId(id);
            snapshot.setConversationId(id);
        }
        snapshot.setAgentId(agentId);
        snapshot.setAgentVersion(agentVersion);
        snapshot.setUserId(userId);
        snapshot.setConversationState(state);
        snapshot.setEnvironment(Deployment.Environment.production);
        return snapshot;
    }
}
