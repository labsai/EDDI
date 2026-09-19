/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.memory.ConcurrentConversationModificationException;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall;
import ai.labs.eddi.engine.model.Deployment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.convertConversationMemory;
import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.convertConversationMemorySnapshot;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PostgresConversationMemoryStore} using
 * Testcontainers.
 * <p>
 * Covers snapshot CRUD, state transitions, active conversation queries, and
 * GDPR operations.
 *
 * @since 6.0.0
 */
@DisplayName("PostgresConversationMemoryStore IT")
class PostgresConversationMemoryStoreTest extends PostgresTestBase {

    private static PostgresConversationMemoryStore store;
    private static DataSource ds;

    @BeforeAll
    static void init() {
        var dsInstance = createDataSourceInstance();
        ds = dsInstance.get();
        IJsonSerialization json = new JsonSerialization(
                SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false));
        store = new PostgresConversationMemoryStore(dsInstance, json);
    }

    @BeforeEach
    void clean() {
        try {
            truncateTables(ds, "conversation_memories");
        } catch (SQLException ignored) {
        }
    }

    // ─── Store + Load ───────────────────────────────────────────

    @Nested
    @DisplayName("Store and Load")
    class StoreAndLoad {

        @Test
        @DisplayName("store new snapshot — generates ID and round-trips")
        void storeNewSnapshot() throws Exception {
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
        void updateExistingSnapshot() throws Exception {
            var snapshot = createSnapshot(null, "agent1", 1, "user1",
                    ConversationState.IN_PROGRESS);
            String id = store.storeConversationMemorySnapshot(snapshot);

            // Update state
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
            assertNull(store.loadConversationMemorySnapshot(
                    "00000000-0000-0000-0000-000000000000"));
        }

        /**
         * G12 parity with {@code ConversationMemoryStore}: the UPDATE matches zero rows
         * when the conversation was deleted mid-turn (GDPR erasure, retention sweep).
         * Discarding the affected-row count made the turn's memory vanish while the
         * caller still saw a normal completion — and because the override dropped the
         * interface's {@code throws}, it could not even report it.
         */
        @Test
        @DisplayName("G12 — a conversation deleted mid-turn surfaces as an error, not silent loss")
        void concurrentDeleteDuringTurnIsReported() throws Exception {
            var snapshot = createSnapshot(null, "agent1", 1, "user1", ConversationState.IN_PROGRESS);
            String id = store.storeConversationMemorySnapshot(snapshot);
            snapshot.setId(id);
            snapshot.setConversationId(id);

            // the turn is still running when the row is erased
            store.deleteConversationMemorySnapshot(id);

            var thrown = assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> store.storeConversationMemorySnapshot(snapshot));
            assertTrue(thrown.getMessage().contains(id), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("NOT persisted"), thrown.getMessage());
            // and it must NOT have been silently re-created behind the caller's back
            assertNull(store.loadConversationMemorySnapshot(id));
        }

        @Test
        @DisplayName("G12 — a matched update still returns the conversation id")
        void matchedUpdateStillSucceeds() throws Exception {
            var snapshot = createSnapshot(null, "agent1", 1, "user1", ConversationState.IN_PROGRESS);
            String id = store.storeConversationMemorySnapshot(snapshot);
            snapshot.setId(id);
            snapshot.setConversationId(id);
            snapshot.setConversationState(ConversationState.READY);

            assertEquals(id, store.storeConversationMemorySnapshot(snapshot));
            assertEquals(ConversationState.READY, store.getConversationState(id));
        }
    }

    // ─── State Management ───────────────────────────────────────

    @Nested
    @DisplayName("State Management")
    class StateManagement {

        @Test
        @DisplayName("setConversationState — updates state")
        void setConversationState() throws Exception {
            String id = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS));

            store.setConversationState(id, ConversationState.ENDED);

            assertEquals(ConversationState.ENDED, store.getConversationState(id));
        }

        @Test
        @DisplayName("getConversationState — returns null for non-existent")
        void getStateNonExistent() {
            assertNull(store.getConversationState("00000000-0000-0000-0000-000000000000"));
        }
    }

    // ─── Delete ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Delete")
    class Delete {

        @Test
        @DisplayName("deleteConversationMemorySnapshot — removes snapshot")
        void deleteSnapshot() throws Exception {
            String id = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS));

            store.deleteConversationMemorySnapshot(id);

            assertNull(store.loadConversationMemorySnapshot(id));
        }
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
                    createSnapshot(null, "agent1", 1, "u3", ConversationState.ENDED));
            // Also set state via the dedicated method to match column
            store.setConversationState(endedId, ConversationState.ENDED);

            List<ConversationMemorySnapshot> active = store.loadActiveConversationMemorySnapshot("agent1", 1);
            assertEquals(2, active.size());
        }

        @Test
        @DisplayName("getActiveConversationCount — counts non-ENDED only")
        void activeCount() throws Exception {
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent2", 1, "u1", ConversationState.IN_PROGRESS));
            String endedId = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent2", 1, "u2", ConversationState.ENDED));
            store.setConversationState(endedId, ConversationState.ENDED);

            assertEquals(1L, store.getActiveConversationCount("agent2", 1));
        }

        @Test
        @DisplayName("getEndedConversationIds — returns only ENDED")
        void endedIds() throws Exception {
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS));
            String endedId = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "u", ConversationState.ENDED));
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
        void createAndRead() throws Exception {
            var snapshot = createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS);
            var resourceId = store.create(snapshot);
            assertNotNull(resourceId.getId());

            var loaded = store.read(resourceId.getId(), resourceId.getVersion());
            assertNotNull(loaded);
        }

        @Test
        @DisplayName("delete — removes via IResourceStore interface")
        void deleteViaAdapter() throws Exception {
            String id = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS));
            store.delete(id, 0);

            assertNull(store.read(id, 0));
        }

        @Test
        @DisplayName("deleteAllPermanently — removes via IResourceStore interface")
        void deleteAllPermanently() throws Exception {
            String id = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "a", 1, "u", ConversationState.IN_PROGRESS));
            store.deleteAllPermanently(id);

            assertNull(store.read(id, 0));
        }
    }

    // ─── GDPR ───────────────────────────────────────────────────

    @Nested
    @DisplayName("GDPR Operations")
    class GdprOps {

        @Test
        @DisplayName("getConversationIdsByUserId — finds by userId in JSONB")
        void getByUserId() throws Exception {
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
        void deleteByUserId() throws Exception {
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

    // ─── Helpers ────────────────────────────────────────────────

    // ─── HITL primitives ─────────────────────────────────────────

    @Nested
    @DisplayName("HITL primitives")
    class HitlPrimitives {

        @Test
        @DisplayName("compareAndSetState succeeds only from the expected state")
        void compareAndSetState() throws Exception {
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
        @DisplayName("storeConversationMemorySnapshotIfState persists only while the state matches (terminal writer wins)")
        void storeConversationMemorySnapshotIfState() throws Exception {
            var snapshot = createSnapshot(null, "agent1", 1, "user1", ConversationState.IN_PROGRESS);
            String id = store.storeConversationMemorySnapshot(snapshot);

            // CAS-store from the matching state succeeds and flips the state to READY.
            var resumed = store.loadConversationMemorySnapshot(id);
            resumed.setConversationState(ConversationState.READY);
            assertTrue(store.storeConversationMemorySnapshotIfState(resumed, ConversationState.IN_PROGRESS),
                    "store must succeed while the persisted state still matches");
            assertEquals(ConversationState.READY, store.getConversationState(id));

            // A concurrent terminal writer flips it to ENDED; a CAS-store expecting
            // IN_PROGRESS must NOT overwrite the terminal state (parity with MongoDB;
            // the resume clobber the fix guards against).
            store.setConversationState(id, ConversationState.ENDED);
            var stale = store.loadConversationMemorySnapshot(id);
            stale.setConversationState(ConversationState.READY);
            assertFalse(store.storeConversationMemorySnapshotIfState(stale, ConversationState.IN_PROGRESS),
                    "store must be rejected once a terminal writer moved the state off the expected value");
            assertEquals(ConversationState.ENDED, store.getConversationState(id),
                    "the terminal ENDED state must survive — no resurrection");
        }

        @Test
        @DisplayName("findConversationIdsByState returns only matching conversations")
        void findConversationIdsByState() throws Exception {
            String paused = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN));
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "user1", ConversationState.READY));

            List<String> ids = store.findConversationIdsByState(ConversationState.AWAITING_HUMAN);

            assertEquals(List.of(paused), ids);
        }

        @Test
        @DisplayName("findPendingApprovalSummaries projects the bookmark incl. approvalTimeout and honors the limit")
        void findPendingApprovalSummaries() throws Exception {
            var pausedSnapshot = createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN);
            pausedSnapshot.setHitlPausedAt(Instant.now());
            pausedSnapshot.setHitlPauseReason("needs review");
            pausedSnapshot.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            pausedSnapshot.setHitlApprovalTimeout("PT30M");
            String id = store.storeConversationMemorySnapshot(pausedSnapshot);
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "user2", ConversationState.AWAITING_HUMAN));

            var summaries = store.findPendingApprovalSummaries(10);
            assertEquals(2, summaries.size());
            var summary = summaries.stream()
                    .filter(s -> id.equals(s.getConversationId()))
                    .findFirst().orElseThrow();
            assertEquals("agent1", summary.getAgentId());
            assertEquals("user1", summary.getUserId());
            assertEquals("needs review", summary.getPauseReason());
            assertEquals("AUTO_REJECT", summary.getTimeoutPolicy());
            assertEquals("PT30M", summary.getApprovalTimeout());
            assertNotNull(summary.getPausedAt());

            assertEquals(1, store.findPendingApprovalSummaries(1).size(), "limit must bound the result");
        }

        @Test
        @DisplayName("findPendingApprovalSummaries carries pauseType + toolNames (names only) for a TOOL_CALL pause")
        void findPendingApprovalSummariesCarriesPauseTypeAndToolNames() throws Exception {
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
        @DisplayName("Task 14/6: a fully-populated PendingToolCallBatch survives a real JSONB round-trip "
                + "— every field, including nested traceSoFar maps")
        void pendingToolCallBatchFullRoundTrip() throws Exception {
            // Mirrors MongoConversationMemoryStoreTest#pendingToolCallBatchFullRoundTrip.
            // Unlike PendingToolCallBatchSnapshotTest (plain Jackson ObjectMapper, no DB
            // involved), this exercises the ACTUAL codec path production uses here:
            // IJsonSerialization -> JSONB column -> deserialize. Object-typed fields
            // like traceSoFar's nested Map<String,Object> are exactly the kind of thing
            // that can silently lose fidelity (numeric widening, key ordering) across a
            // real JSON<->JSONB round-trip.
            var snapshot = createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPauseType("TOOL_CALL");
            snapshot.setHitlPausedAt(Instant.now());
            snapshot.setHitlPauseReason("gated tool calls awaiting review");
            snapshot.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
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
            // fidelity across a real JSON round-trip (numeric widening, boolean/string
            // coercion, nested structures).
            var nested = new LinkedHashMap<String, Object>();
            nested.put("stringField", "value");
            nested.put("intField", 42);
            nested.put("boolField", true);
            nested.put("nestedList", List.of("a", "b", "c"));
            var traceEntry1 = new LinkedHashMap<String, Object>();
            traceEntry1.put("type", "tool_call");
            traceEntry1.put("tool", "readOnlyLookup");
            traceEntry1.put("detail", nested);
            var traceEntry2 = new LinkedHashMap<String, Object>();
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
            var loadedNested = (Map<String, Object>) loadedTrace1.get("detail");
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
        @DisplayName("clearHitlBookmark removes the bookmark fields but keeps the conversation")
        void clearHitlBookmark() throws Exception {
            var snapshot = createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPausedAt(Instant.now());
            snapshot.setHitlPauseReason("needs review");
            snapshot.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
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

        @Test
        @DisplayName("zombie regression: after a CAS flips the state column, load reports the COLUMN state, not the stale document AWAITING_HUMAN")
        void loadReconcilesColumnOverStaleDocumentState() throws Exception {
            // Store a paused conversation — the full document AND the column say
            // AWAITING_HUMAN.
            var snapshot = createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN);
            String id = store.storeConversationMemorySnapshot(snapshot);

            // Cancel via CAS: the arbiter column flips to EXECUTION_INTERRUPTED. The
            // JSONB document copy is patched in the same statement — but this test's
            // key guarantee is that LOAD reconciles the two.
            assertTrue(store.compareAndSetState(id,
                    ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED));

            var loaded = store.loadConversationMemorySnapshot(id);
            assertNotNull(loaded);
            // The regression this guards: loading MUST NOT resurrect the terminally
            // resolved pause. A stale AWAITING_HUMAN here would wedge say() and
            // re-arm a dead approval (the zombie the say-path guard exists to catch).
            assertEquals(ConversationState.EXECUTION_INTERRUPTED, loaded.getConversationState(),
                    "load must report the CAS'd column state, never the stale document state");
            assertEquals(ConversationState.EXECUTION_INTERRUPTED, store.getConversationState(id));
        }

        @Test
        @DisplayName("G8: forged divergence — load reports the COLUMN state even when the JSONB document says AWAITING_HUMAN")
        void loadReportsColumnStateOverForgedDivergentDocument() throws Exception {
            // Store a paused conversation, then forge TRUE divergence with raw SQL:
            // set the COLUMN to a terminal state but re-write the JSONB document's
            // conversationState BACK to AWAITING_HUMAN. Unlike compareAndSetState
            // (which patches both in one statement, so document and column never
            // actually diverge — making the sibling test vacuous), this leaves the
            // document genuinely stale. This is exactly the condition applyStateColumn
            // (the load-side reconciliation) exists to defend against: with it deleted,
            // the assertions below fail.
            var snapshot = createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN);
            String id = store.storeConversationMemorySnapshot(snapshot);

            try (var conn = ds.getConnection();
                    var stmt = conn.prepareStatement(
                            "UPDATE conversation_memories SET conversation_state = 'EXECUTION_INTERRUPTED', "
                                    + "data = jsonb_set(data, '{conversationState}', '\"AWAITING_HUMAN\"') WHERE id = ?::uuid")) {
                stmt.setString(1, id);
                assertEquals(1, stmt.executeUpdate());
            }

            // Sanity: the raw document really is stale (still AWAITING_HUMAN) while the
            // column carries the terminal state.
            try (var conn = ds.getConnection();
                    var stmt = conn.prepareStatement(
                            "SELECT data->>'conversationState' AS doc_state, conversation_state AS col_state "
                                    + "FROM conversation_memories WHERE id = ?::uuid")) {
                stmt.setString(1, id);
                try (var rs = stmt.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("AWAITING_HUMAN", rs.getString("doc_state"),
                            "the forged document must be stale (still AWAITING_HUMAN)");
                    assertEquals("EXECUTION_INTERRUPTED", rs.getString("col_state"),
                            "the column must be the terminal state");
                }
            }

            // The single-document load MUST report the COLUMN state, not the stale
            // document — deleting applyStateColumn would fail this assertion.
            var loaded = store.loadConversationMemorySnapshot(id);
            assertNotNull(loaded);
            assertEquals(ConversationState.EXECUTION_INTERRUPTED, loaded.getConversationState(),
                    "loadConversationMemorySnapshot must reconcile to the column over the stale document");

            // The active-conversation batch load reconciles too (separate query path
            // with its own applyStateColumn call).
            var active = store.loadActiveConversationMemorySnapshot("agent1", 1);
            var loadedActive = active.stream()
                    .filter(s -> id.equals(s.getConversationId()) || id.equals(s.getId()))
                    .findFirst().orElseThrow(() -> new AssertionError("active load must include the non-ENDED conversation"));
            assertEquals(ConversationState.EXECUTION_INTERRUPTED, loadedActive.getConversationState(),
                    "loadActiveConversationMemorySnapshot must reconcile to the column over the stale document");
        }

        @Test
        @DisplayName("setConversationState converges document and column — a reloaded snapshot matches the column")
        void setConversationStateConvergesDocumentAndColumn() throws Exception {
            var snapshot = createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN);
            String id = store.storeConversationMemorySnapshot(snapshot);

            // jsonb_set patches the document copy alongside the column, so no reader
            // (column projection OR full-document load) can observe divergence.
            store.setConversationState(id, ConversationState.ENDED);

            assertEquals(ConversationState.ENDED, store.getConversationState(id));
            var loaded = store.loadConversationMemorySnapshot(id);
            assertEquals(ConversationState.ENDED, loaded.getConversationState(),
                    "the reloaded full snapshot must match the column after setConversationState");
        }

        @Test
        @DisplayName("owner-filtered summaries: the filter is pushed into the query so the limit applies AFTER the restriction")
        void findPendingApprovalSummariesByOwner() throws Exception {
            // user1 has two pending approvals; user2 has one.
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN));
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN));
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "user2", ConversationState.AWAITING_HUMAN));
            // A READY conversation for user1 must never appear.
            store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "user1", ConversationState.READY));

            var user1 = store.findPendingApprovalSummaries("user1", 10);
            assertEquals(2, user1.size(), "only user1's PENDING approvals");
            assertTrue(user1.stream().allMatch(s -> "user1".equals(s.getUserId())));

            var user2 = store.findPendingApprovalSummaries("user2", 10);
            assertEquals(1, user2.size());
            assertEquals("user2", user2.get(0).getUserId());

            var none = store.findPendingApprovalSummaries("nobody", 10);
            assertTrue(none.isEmpty(), "an owner with no pending approvals sees an empty inbox");

            // Limit applies AFTER the owner restriction — user1's inbox is bounded,
            // not starved by another user's backlog.
            assertEquals(1, store.findPendingApprovalSummaries("user1", 1).size(),
                    "owner limit must bound the owner-filtered result");
        }
    }

    // ─── Turn concurrency (optimistic revision + append) ────────

    /**
     * Postgres parity for the lost-update fix. The MongoDB side of this lives in
     * {@code MongoConversationTurnConcurrencyTest}, which also documents the
     * mechanism; these tests exist because the SQL is a separate implementation of
     * the same contract and nothing else executes it.
     */
    @Nested
    @DisplayName("Turn concurrency")
    class TurnConcurrency {

        @Test
        @DisplayName("two overlapping turns both end up stored, in commit order")
        void overlappingTurnsBothSurvive() throws Exception {
            String id = seedConversation();

            var turnA = loadMemory(id);
            var turnB = loadMemory(id);
            appendTurn(turnA, "turn A");
            appendTurn(turnB, "turn B");

            store.storeConversationMemorySnapshot(snapshotOf(turnA));
            store.storeConversationMemorySnapshot(snapshotOf(turnB));

            var reloaded = store.loadConversationMemorySnapshot(id);
            assertEquals(List.of("greeting", "turn A", "turn B"), inputsOf(reloaded));
            assertEquals(reloaded.getConversationSteps().size(), reloaded.getConversationOutputs().size());
            assertEquals(3L, reloaded.getRevision());
        }

        /**
         * The jsonb merge has to reproduce the full replace exactly: the new body's
         * keys and nothing else. A field the turn cleared must not survive, and the
         * concatenated arrays must not be overwritten by the body's own copies.
         */
        @Test
        @DisplayName("an appending turn clears the fields it set to null and keeps the arrays merged")
        void appendClearsClearedFields() throws Exception {
            String id = seedConversation();
            var paused = loadMemory(id);
            paused.setHitlPauseReason("a pause that was resolved");
            paused.setHitlPauseType("TOOL_CALL");
            appendTurn(paused, "turn 1");
            store.storeConversationMemorySnapshot(snapshotOf(paused));
            assertEquals("a pause that was resolved",
                    store.loadConversationMemorySnapshot(id).getHitlPauseReason(), "fixture check");

            var cleared = loadMemory(id);
            cleared.setHitlPauseReason(null);
            cleared.setHitlPauseType(null);
            appendTurn(cleared, "turn 2");
            store.storeConversationMemorySnapshot(snapshotOf(cleared));

            var reloaded = store.loadConversationMemorySnapshot(id);
            assertNull(reloaded.getHitlPauseReason(), "a stale pause reason survived an appending turn");
            assertNull(reloaded.getHitlPauseType(), "a stale pause type survived an appending turn");
            assertEquals(List.of("greeting", "turn 1", "turn 2"), inputsOf(reloaded));
        }

        @Test
        @DisplayName("a stale non-append write is refused as a conflict and leaves the winner intact")
        void staleFullReplaceIsRefused() throws Exception {
            String id = seedConversation();

            var winner = loadMemory(id);
            var loser = loadMemory(id);
            appendTurn(winner, "winner");
            // No new step: a rerun-shaped write, which takes the full-row path.
            loser.getCurrentStep().storeData(new Data<>("output", "regenerated by a rerun"));

            store.storeConversationMemorySnapshot(snapshotOf(winner));

            var stale = snapshotOf(loser);
            assertThrows(ConcurrentConversationModificationException.class,
                    () -> store.storeConversationMemorySnapshot(stale));
            assertEquals(List.of("greeting", "winner"), inputsOf(store.loadConversationMemorySnapshot(id)));
        }

        @Test
        @DisplayName("a rerun-shaped turn (no new step) still persists its changes")
        void rerunShapedTurnStillPersists() throws Exception {
            String id = seedConversation();
            var first = loadMemory(id);
            appendTurn(first, "turn 1");
            store.storeConversationMemorySnapshot(snapshotOf(first));

            var rerun = loadMemory(id);
            rerun.getCurrentStep().storeData(new Data<>("output", "regenerated answer"));
            store.storeConversationMemorySnapshot(snapshotOf(rerun));

            var reloaded = store.loadConversationMemorySnapshot(id);
            assertEquals(List.of("greeting", "turn 1"), inputsOf(reloaded), "a rerun must not add a step");
            assertTrue(valuesOf(reloaded, "output").contains("regenerated answer"));
        }

        @Test
        @DisplayName("undo and redo still work through the full replace")
        void undoAndRedoStillWork() throws Exception {
            String id = seedConversation();
            var memory = loadMemory(id);
            appendTurn(memory, "turn 1");
            store.storeConversationMemorySnapshot(snapshotOf(memory));

            var toUndo = loadMemory(id);
            toUndo.undoLastStep();
            assertTrue(store.storeConversationMemorySnapshotIfState(snapshotOf(toUndo), ConversationState.READY));
            assertEquals(List.of("greeting"), inputsOf(store.loadConversationMemorySnapshot(id)));

            var toRedo = loadMemory(id);
            toRedo.redoLastStep();
            assertTrue(store.storeConversationMemorySnapshotIfState(snapshotOf(toRedo), ConversationState.READY));
            assertEquals(List.of("greeting", "turn 1"), inputsOf(store.loadConversationMemorySnapshot(id)));
        }

        @Test
        @DisplayName("a pre-upgrade row with no _rev upgrades on its next write")
        void legacyRowWithoutRevisionUpgrades() throws Exception {
            String id = seedConversation();
            try (var conn = ds.getConnection();
                    var st = conn.prepareStatement(
                            "UPDATE conversation_memories SET data = data - '_rev' - '_histRev' WHERE id = ?::uuid")) {
                st.setString(1, id);
                st.executeUpdate();
            }
            assertEquals(ConversationMemorySnapshot.UNVERSIONED_REVISION,
                    store.loadConversationMemorySnapshot(id).getRevision());

            var memory = loadMemory(id);
            appendTurn(memory, "first turn after upgrade");
            store.storeConversationMemorySnapshot(snapshotOf(memory));

            var reloaded = store.loadConversationMemorySnapshot(id);
            assertEquals(List.of("greeting", "first turn after upgrade"), inputsOf(reloaded));
            assertEquals(ConversationMemorySnapshot.UNVERSIONED_REVISION + 1, reloaded.getRevision());
        }

        @Test
        @DisplayName("an append does not merge over a pause committed after its load")
        void appendDoesNotMergeOverACommittedPause() throws Exception {
            String id = seedConversation();
            var pausingTurn = loadMemory(id);
            var sayTurn = loadMemory(id);

            appendTurn(pausingTurn, "turn that pauses");
            pausingTurn.setConversationState(ConversationState.AWAITING_HUMAN);
            pausingTurn.setHitlPauseReason("needs review");
            assertTrue(store.storeConversationMemorySnapshotIfState(snapshotOf(pausingTurn), ConversationState.READY));

            appendTurn(sayTurn, "overlapping say");
            assertThrows(ConcurrentConversationModificationException.class,
                    () -> store.storeConversationMemorySnapshot(snapshotOf(sayTurn)));

            var reloaded = store.loadConversationMemorySnapshot(id);
            assertEquals(ConversationState.AWAITING_HUMAN, reloaded.getConversationState());
            assertEquals("needs review", reloaded.getHitlPauseReason());
            assertEquals(List.of("greeting", "turn that pauses"), inputsOf(reloaded));
        }

        @Test
        @DisplayName("an append does not merge over an undo committed after its load")
        void appendDoesNotMergeOverAnUndo() throws Exception {
            String id = seedConversation();
            var first = loadMemory(id);
            appendTurn(first, "turn 1");
            store.storeConversationMemorySnapshot(snapshotOf(first));

            var sayTurn = loadMemory(id);
            var undo = loadMemory(id);
            undo.undoLastStep();
            assertTrue(store.storeConversationMemorySnapshotIfState(snapshotOf(undo), ConversationState.READY));

            appendTurn(sayTurn, "say built on turn 1");
            assertThrows(ConcurrentConversationModificationException.class,
                    () -> store.storeConversationMemorySnapshot(snapshotOf(sayTurn)));

            var reloaded = store.loadConversationMemorySnapshot(id);
            assertEquals(List.of("greeting"), inputsOf(reloaded));
            assertEquals(1, reloaded.getRedoCache().size(), "the undone step must still be redoable");
        }

        @Test
        @DisplayName("an append does not resurrect a conversation ended after its load")
        void appendDoesNotResurrectAnEndedConversation() throws Exception {
            String id = seedConversation();
            var sayTurn = loadMemory(id);
            store.setConversationState(id, ConversationState.ENDED);

            appendTurn(sayTurn, "turn after the end");
            assertThrows(ConcurrentConversationModificationException.class,
                    () -> store.storeConversationMemorySnapshot(snapshotOf(sayTurn)));
            assertEquals(ConversationState.ENDED, store.loadConversationMemorySnapshot(id).getConversationState());
        }

        @Test
        @DisplayName("the conditional store refuses a stale revision even when the expected state still matches")
        void conditionalStoreRefusesStaleRevision() throws Exception {
            String id = seedConversation();
            var first = loadMemory(id);
            appendTurn(first, "turn 1");
            store.storeConversationMemorySnapshot(snapshotOf(first));

            var sayTurn = loadMemory(id);
            var undo = loadMemory(id);
            appendTurn(sayTurn, "concurrent say");
            store.storeConversationMemorySnapshot(snapshotOf(sayTurn));

            undo.undoLastStep();
            assertFalse(store.storeConversationMemorySnapshotIfState(snapshotOf(undo), ConversationState.READY));
            assertEquals(List.of("greeting", "turn 1", "concurrent say"), inputsOf(store.loadConversationMemorySnapshot(id)));
        }

        @Test
        @DisplayName("an append on a row deleted mid-turn is reported as gone, not as a conflict")
        void appendOnDeletedRowIsReportedAsGone() throws Exception {
            String id = seedConversation();
            var sayTurn = loadMemory(id);
            appendTurn(sayTurn, "turn after deletion");
            store.deleteConversationMemorySnapshot(id);

            var thrown = assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> store.storeConversationMemorySnapshot(snapshotOf(sayTurn)));
            assertFalse(thrown instanceof ConcurrentConversationModificationException, thrown.getMessage());
            assertTrue(thrown.getMessage().contains("no longer exists"), thrown.getMessage());
        }

        private String seedConversation() throws Exception {
            var memory = new ConversationMemory("agent-pg-concurrency", 1, "user-1");
            memory.setConversationState(ConversationState.READY);
            memory.getCurrentStep().storeData(new Data<>("input", "greeting"));
            memory.getCurrentStep().addConversationOutputString("input", "greeting");
            return store.storeConversationMemorySnapshot(snapshotOf(memory));
        }

        private IConversationMemory loadMemory(String id) {
            return convertConversationMemorySnapshot(store.loadConversationMemorySnapshot(id));
        }

        private void appendTurn(IConversationMemory memory, String input) {
            ((ConversationMemory) memory).startNextStep();
            memory.getCurrentStep().storeData(new Data<>("input", input));
            memory.getCurrentStep().addConversationOutputString("input", input);
            memory.setConversationState(ConversationState.READY);
        }

        private ConversationMemorySnapshot snapshotOf(IConversationMemory memory) {
            var snapshot = convertConversationMemory(memory);
            snapshot.setEnvironment(Deployment.Environment.test);
            return snapshot;
        }

        private List<String> inputsOf(ConversationMemorySnapshot snapshot) {
            return valuesOf(snapshot, "input");
        }

        private List<String> valuesOf(ConversationMemorySnapshot snapshot, String key) {
            return snapshot.getConversationSteps().stream()
                    .flatMap(step -> step.getWorkflows().stream())
                    .flatMap(workflow -> workflow.getLifecycleTasks().stream())
                    .filter(task -> key.equals(task.getKey()))
                    .map(task -> String.valueOf(task.getResult()))
                    .toList();
        }
    }

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
