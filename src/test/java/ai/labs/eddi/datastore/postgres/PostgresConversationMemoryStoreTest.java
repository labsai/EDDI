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
import ai.labs.eddi.engine.memory.model.ConversationGenerationCandidate;
import ai.labs.eddi.engine.memory.model.GenerationUpgradeOutcome;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import ai.labs.eddi.engine.memory.model.ConversationActivitySummary;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.ConversationFencedException;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.Date;
import java.util.ArrayList;
import java.time.temporal.ChronoUnit;
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
         * PostgreSQL parity for the MongoDB round trip: a HITL resume may re-supply a
         * secret only for a key the paused turn stored as one, and reads that flag from
         * the loaded snapshot.
         */
        @Test
        @DisplayName("a secret context entry keeps its secret flag through a round trip")
        void secretContextEntryKeepsItsFlag() throws Exception {
            var snapshot = createSnapshot(null, "agent1", 1, "user1",
                    ConversationState.AWAITING_HUMAN);
            snapshot.getConversationSteps().add(stepWithSecretAndOrdinaryContext());

            String id = store.storeConversationMemorySnapshot(snapshot);

            var results = store.loadConversationMemorySnapshot(id).getConversationSteps().get(0)
                    .getWorkflows().get(0).getLifecycleTasks();
            var token = assertInstanceOf(Context.class, results.get(0).getResult());
            assertEquals(Boolean.TRUE, token.getSecret());
            assertEquals(MemoryKeys.SECRET_CONTEXT_PLACEHOLDER, token.getValue());
            var language = assertInstanceOf(Context.class, results.get(1).getResult());
            assertNull(language.getSecret(), "an ordinary entry stays unmarked");
            assertEquals("de", language.getValue());
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
        @DisplayName("getConversationUsage — counts across versions, active only non-ended, distinct users")
        void usage() throws IResourceStore.ResourceStoreException {
            store.storeConversationMemorySnapshot(createSnapshot(null, "usageAgent", 1, "u1", ConversationState.IN_PROGRESS));
            store.storeConversationMemorySnapshot(createSnapshot(null, "usageAgent", 2, "u1", ConversationState.IN_PROGRESS));
            String endedId = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "usageAgent", 2, "u2", ConversationState.IN_PROGRESS));
            store.setConversationState(endedId, ConversationState.ENDED);
            store.storeConversationMemorySnapshot(createSnapshot(null, "otherAgent", 1, "u3", ConversationState.IN_PROGRESS));

            var usage = store.getConversationUsage("usageAgent");

            assertEquals(3, usage.total());
            assertEquals(2, usage.active());
            assertEquals(2, usage.distinctUsers());
        }

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
        @DisplayName("getActiveConversationCountsByGeneration — groups open conversations by their own generation, across versions")
        void activeCountsByGeneration() throws Exception {
            // Generation 2 spread over three agent versions, as after compatible saves:
            // the count is by what the conversation is, not by where it last ran
            store.storeConversationMemorySnapshot(withGeneration("gen-agent", 1, 2, ConversationState.IN_PROGRESS));
            store.storeConversationMemorySnapshot(withGeneration("gen-agent", 2, 2, ConversationState.READY));
            store.storeConversationMemorySnapshot(withGeneration("gen-agent", 3, 2, ConversationState.IN_PROGRESS));
            store.storeConversationMemorySnapshot(withGeneration("gen-agent", 1, 1, ConversationState.IN_PROGRESS));
            store.storeConversationMemorySnapshot(withGeneration("gen-agent", 1, null, ConversationState.IN_PROGRESS));
            // ended and paused conversations do not count, as in getActiveConversationCount
            String endedId = store.storeConversationMemorySnapshot(withGeneration("gen-agent", 3, 2, ConversationState.IN_PROGRESS));
            store.setConversationState(endedId, ConversationState.ENDED);
            store.storeConversationMemorySnapshot(withGeneration("gen-agent", 3, 2, ConversationState.AWAITING_HUMAN));
            // another agent is never mixed in
            store.storeConversationMemorySnapshot(withGeneration("other-agent", 1, 2, ConversationState.IN_PROGRESS));

            Map<Integer, Long> counts = store.getActiveConversationCountsByGeneration("gen-agent");

            assertEquals(3, counts.size(), counts.toString());
            assertEquals(3L, counts.get(2));
            assertEquals(1L, counts.get(1));
            assertEquals(1L, counts.get(null), "a conversation without a generation is counted under null");
            assertTrue(counts.containsKey(null));
            assertTrue(store.getActiveConversationCountsByGeneration("nobody").isEmpty());
        }

        private ConversationMemorySnapshot withGeneration(String agentId, int agentVersion, Integer generation,
                                                          ConversationState state) {
            var snapshot = createSnapshot(null, agentId, agentVersion, "u", state);
            snapshot.setCompatibilityGeneration(generation);
            return snapshot;
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

    // ─── Cluster fencing ────────────────────────────────────────

    @Nested
    @DisplayName("Cluster fencing (_fence)")
    class Fencing {

        private ConversationMemorySnapshot loadForTurn(String id) {
            var loaded = store.loadConversationMemorySnapshot(id);
            loaded.setPersistedStepCount(loaded.getConversationSteps().size());
            return loaded;
        }

        private void addTurn(ConversationMemorySnapshot snapshot) {
            snapshot.getConversationSteps().add(new ConversationMemorySnapshot.ConversationStepSnapshot());
            snapshot.getConversationOutputs().add(new ConversationOutput());
        }

        private String newConversation() throws IResourceStore.ResourceStoreException {
            var snapshot = createSnapshot(null, "agent1", 1, "user1", ConversationState.READY);
            return store.storeConversationMemorySnapshot(snapshot);
        }

        @Test
        @DisplayName("an unfenced (single-node) write never writes _fence")
        void unfencedWriteHasNoFence() throws IResourceStore.ResourceStoreException {
            String id = newConversation();
            var turn = loadForTurn(id);
            addTurn(turn);
            store.storeConversationMemorySnapshot(turn);
            var full = store.loadConversationMemorySnapshot(id);
            store.storeConversationMemorySnapshot(full); // full replace, no token
            assertNull(store.loadConversationMemorySnapshot(id).getFence());
        }

        @Test
        @DisplayName("a zombie append with an older token is refused and NOT merge-retried")
        void staleAppendIsFenced() throws IResourceStore.ResourceStoreException {
            String id = newConversation();
            var zombie = loadForTurn(id);
            var winner = loadForTurn(id);
            addTurn(winner);
            winner.setFenceToken(9L);
            store.storeConversationMemorySnapshot(winner);
            assertEquals(9L, store.loadConversationMemorySnapshot(id).getFence());

            addTurn(zombie);
            zombie.setFenceToken(5L);
            var thrown = assertThrows(ConversationFencedException.class, () -> store.storeConversationMemorySnapshot(zombie));
            assertEquals(9L, thrown.getStoredFence());
            var stored = store.loadConversationMemorySnapshot(id);
            assertEquals(1, stored.getConversationSteps().size(), "the zombie's step was not appended");
            assertEquals(9L, stored.getFence());
        }

        @Test
        @DisplayName("a fence raised when the successor starts refuses a predecessor that has not written yet")
        void raisedFenceRefusesThePredecessor() throws IResourceStore.ResourceStoreException {
            String id = newConversation();
            var zombie = loadForTurn(id);
            var successor = loadForTurn(id);
            store.raiseFence(id, 9L); // the successor's lease started; nothing has been written yet
            assertEquals(9L, store.loadConversationMemorySnapshot(id).getFence());

            addTurn(zombie);
            zombie.setFenceToken(5L);
            assertThrows(ConversationFencedException.class, () -> store.storeConversationMemorySnapshot(zombie));
            assertEquals(0, store.loadConversationMemorySnapshot(id).getConversationSteps().size());

            // the raise moved no revision: the successor writes on the one it loaded
            addTurn(successor);
            successor.setFenceToken(9L);
            store.storeConversationMemorySnapshot(successor);
            assertEquals(1, store.loadConversationMemorySnapshot(id).getConversationSteps().size());

            store.raiseFence(id, 3L); // never lowers
            assertEquals(9L, store.loadConversationMemorySnapshot(id).getFence());
        }

        @Test
        @DisplayName("without fencing the same race merges both appends (today's behaviour)")
        void unfencedRaceStillMerges() throws IResourceStore.ResourceStoreException {
            String id = newConversation();
            var first = loadForTurn(id);
            var second = loadForTurn(id);
            addTurn(first);
            store.storeConversationMemorySnapshot(first);
            addTurn(second);
            store.storeConversationMemorySnapshot(second);
            assertEquals(2, store.loadConversationMemorySnapshot(id).getConversationSteps().size());
        }

        @Test
        @DisplayName("a newer token raises the fence; an unfenced append keeps it")
        void fenceOnlyGrows() throws IResourceStore.ResourceStoreException {
            String id = newConversation();
            var a = loadForTurn(id);
            addTurn(a);
            a.setFenceToken(4L);
            store.storeConversationMemorySnapshot(a);
            var b = loadForTurn(id);
            addTurn(b);
            b.setFenceToken(11L);
            store.storeConversationMemorySnapshot(b);
            var c = loadForTurn(id);
            addTurn(c);
            store.storeConversationMemorySnapshot(c); // degraded-mode turn: no token
            var stored = store.loadConversationMemorySnapshot(id);
            assertEquals(11L, stored.getFence());
            assertEquals(3, stored.getConversationSteps().size());
        }

        @Test
        @DisplayName("a full-document write with an older token is refused as fenced")
        void staleFullWriteIsFenced() throws IResourceStore.ResourceStoreException {
            String id = newConversation();
            var winner = loadForTurn(id);
            addTurn(winner);
            winner.setFenceToken(9L);
            store.storeConversationMemorySnapshot(winner);
            var zombie = store.loadConversationMemorySnapshot(id); // current revision, unknown step count
            zombie.setFenceToken(3L);
            assertThrows(ConversationFencedException.class, () -> store.storeConversationMemorySnapshot(zombie));
            var fenced = store.loadConversationMemorySnapshot(id);
            fenced.setFenceToken(3L);
            assertFalse(store.storeConversationMemorySnapshotIfState(fenced, ConversationState.READY),
                    "the conditional write is refused too");
            var current = store.loadConversationMemorySnapshot(id);
            current.setFenceToken(9L);
            store.storeConversationMemorySnapshot(current); // the holder itself may write again
        }
    }

    // ─── Compatibility generation upgrade ───────────────────────

    @Nested
    @DisplayName("compatibility generation upgrade")
    class GenerationUpgrade {

        private static final String UPGRADE_AGENT = "upgradeAgent";
        private static final String MISSING_ID = "00000000-0000-4000-8000-000000000000";

        private String put(String agentId, int version, ConversationState state, Integer generation) throws Exception {
            var snapshot = createSnapshot(null, agentId, version, "u", state);
            snapshot.setCompatibilityGeneration(generation);
            return store.storeConversationMemorySnapshot(snapshot);
        }

        private ConversationMemorySnapshot loadForTurn(String id) {
            var loaded = store.loadConversationMemorySnapshot(id);
            loaded.setPersistedStepCount(loaded.getConversationSteps().size());
            return loaded;
        }

        private void addTurn(ConversationMemorySnapshot snapshot) {
            snapshot.getConversationSteps().add(new ConversationMemorySnapshot.ConversationStepSnapshot());
            snapshot.getConversationOutputs().add(new ConversationOutput());
        }

        private List<String> ids(List<ConversationGenerationCandidate> candidates) {
            return candidates.stream().map(ConversationGenerationCandidate::conversationId).toList();
        }

        private void removeRevisionMarkers(String id) throws SQLException {
            try (var conn = ds.getConnection();
                    var stmt = conn.prepareStatement("UPDATE conversation_memories SET data = data - '_rev' - '_histRev' WHERE id = ?::uuid")) {
                stmt.setString(1, id);
                assertEquals(1, stmt.executeUpdate());
            }
        }

        @Test
        @DisplayName("candidates are this agent's live conversations with no generation or a lower one — ended, current and higher ones are not")
        void candidatesAreLowerOrAbsent() throws Exception {
            String none = put(UPGRADE_AGENT, 1, ConversationState.READY, null);
            String low = put(UPGRADE_AGENT, 2, ConversationState.AWAITING_HUMAN, 1);
            put(UPGRADE_AGENT, 2, ConversationState.ENDED, 1);
            put(UPGRADE_AGENT, 3, ConversationState.READY, 3);
            put(UPGRADE_AGENT, 3, ConversationState.READY, 4);
            put("otherAgent", 1, ConversationState.READY, null);

            var candidates = store.loadGenerationUpgradeCandidates(UPGRADE_AGENT, null, 3, null, 100);

            assertEquals(new TreeSet<>(List.of(none, low)), new TreeSet<>(ids(candidates)),
                    "an ended conversation is never written, so it must not be scanned");
            var paused = candidates.stream().filter(c -> c.conversationId().equals(low)).findFirst().orElseThrow();
            assertEquals(ConversationState.AWAITING_HUMAN, paused.conversationState());
            assertEquals(2, paused.agentVersion());
            assertEquals(1, paused.compatibilityGeneration());
            var ungenerated = candidates.stream().filter(c -> c.conversationId().equals(none)).findFirst().orElseThrow();
            assertNull(ungenerated.compatibilityGeneration());
        }

        @Test
        @DisplayName("ended candidates are counted per stored version, with the same filters")
        void endedCandidatesAreCountedPerVersion() throws Exception {
            put(UPGRADE_AGENT, 1, ConversationState.ENDED, null);
            put(UPGRADE_AGENT, 1, ConversationState.ENDED, 2);
            put(UPGRADE_AGENT, 2, ConversationState.ENDED, 1);
            put(UPGRADE_AGENT, 2, ConversationState.ENDED, 3); // already current
            put(UPGRADE_AGENT, 2, ConversationState.READY, 1); // not ended
            put("otherAgent", 1, ConversationState.ENDED, null);

            assertEquals(Map.of(1, 2L, 2, 1L), store.countEndedGenerationUpgradeCandidates(UPGRADE_AGENT, null, 3));
            assertEquals(Map.of(2, 1L), store.countEndedGenerationUpgradeCandidates(UPGRADE_AGENT, List.of(2), 3));
            assertEquals(Map.of(), store.countEndedGenerationUpgradeCandidates("nobody", null, 3));
        }

        @Test
        @DisplayName("a page of live candidates is not crowded out by ended conversations ahead of it")
        void endedDoNotCrowdTheScan() throws Exception {
            for (int i = 0; i < 5; i++) {
                put(UPGRADE_AGENT, 1, ConversationState.ENDED, null);
            }
            String live = put(UPGRADE_AGENT, 1, ConversationState.READY, null);

            assertEquals(List.of(live), ids(store.loadGenerationUpgradeCandidates(UPGRADE_AGENT, null, 3, null, 2)));
        }

        @Test
        @DisplayName("fromVersions narrows the candidates to those stored versions")
        void candidatesCanBeNarrowedToVersions() throws Exception {
            put(UPGRADE_AGENT, 1, ConversationState.READY, null);
            String two = put(UPGRADE_AGENT, 2, ConversationState.READY, 1);
            String three = put(UPGRADE_AGENT, 3, ConversationState.READY, 2);

            assertEquals(List.of(two), ids(store.loadGenerationUpgradeCandidates(UPGRADE_AGENT, List.of(2), 4, null, 100)));
            assertEquals(new TreeSet<>(List.of(two, three)),
                    new TreeSet<>(ids(store.loadGenerationUpgradeCandidates(UPGRADE_AGENT, List.of(2, 3), 4, null, 100))));
        }

        @Test
        @DisplayName("candidates are keyset-paged in ascending id order — every one once")
        void candidatesPageByKeyset() throws Exception {
            var expected = new ArrayList<String>();
            for (int i = 0; i < 7; i++) {
                expected.add(put(UPGRADE_AGENT, 1, ConversationState.READY, i % 2 == 0 ? null : 1));
            }
            expected.sort(String::compareTo);

            var seen = new ArrayList<String>();
            String after = null;
            while (true) {
                var page = ids(store.loadGenerationUpgradeCandidates(UPGRADE_AGENT, null, 3, after, 3));
                if (page.isEmpty()) {
                    break;
                }
                assertTrue(page.size() <= 3);
                seen.addAll(page);
                after = page.getLast();
            }

            assertEquals(expected, seen);
        }

        @Test
        @DisplayName("upgrading writes the generation and moves the revision and history revision — nothing else changes")
        void upgradeWritesOnlyTheGeneration() throws Exception {
            String id = put(UPGRADE_AGENT, 4, ConversationState.READY, null);
            var turn = loadForTurn(id);
            addTurn(turn);
            store.storeConversationMemorySnapshot(turn);
            var before = store.loadConversationMemorySnapshot(id);

            assertEquals(GenerationUpgradeOutcome.UPGRADED, store.upgradeCompatibilityGeneration(id, UPGRADE_AGENT, 3));

            var after = store.loadConversationMemorySnapshot(id);
            assertEquals(3, after.getCompatibilityGeneration());
            assertEquals(before.getRevision() + 1, after.getRevision());
            assertEquals(before.getRevision() + 1, after.getHistoryRevision());
            assertEquals(before.getRevision() + 1, store.getRevision(id));
            assertEquals(4, after.getAgentVersion(), "the version is the next turn's to move");
            assertEquals(ConversationState.READY, after.getConversationState());
            assertEquals("u", after.getUserId());
            assertEquals(before.getConversationSteps().size(), after.getConversationSteps().size());
            assertEquals(before.getConversationOutputs().size(), after.getConversationOutputs().size());
        }

        @Test
        @DisplayName("a generation is never lowered, and one already at the target is left alone")
        void neverLowers() throws Exception {
            String id = put(UPGRADE_AGENT, 4, ConversationState.READY, 5);
            long revision = store.getRevision(id);

            assertEquals(GenerationUpgradeOutcome.ALREADY_CURRENT, store.upgradeCompatibilityGeneration(id, UPGRADE_AGENT, 3));
            assertEquals(GenerationUpgradeOutcome.ALREADY_CURRENT, store.upgradeCompatibilityGeneration(id, UPGRADE_AGENT, 5));

            assertEquals(5, store.loadConversationMemorySnapshot(id).getCompatibilityGeneration());
            assertEquals(revision, store.getRevision(id), "nothing was written, so the revision did not move");
        }

        @Test
        @DisplayName("only states a turn can run from are written; ended, paused and in-progress conversations are left untouched and named")
        void onlyUpgradableStatesAreWritten() throws Exception {
            var refused = Map.of(ConversationState.ENDED, GenerationUpgradeOutcome.ENDED, ConversationState.AWAITING_HUMAN,
                    GenerationUpgradeOutcome.AWAITING_HUMAN, ConversationState.IN_PROGRESS, GenerationUpgradeOutcome.IN_PROGRESS);
            for (var entry : refused.entrySet()) {
                String id = put(UPGRADE_AGENT, 1, entry.getKey(), null);
                long revision = store.getRevision(id);

                assertEquals(entry.getValue(), store.upgradeCompatibilityGeneration(id, UPGRADE_AGENT, 3), entry.getKey().name());

                assertNull(store.loadConversationMemorySnapshot(id).getCompatibilityGeneration(), entry.getKey().name());
                assertEquals(revision, store.getRevision(id), entry.getKey().name());
            }
            for (var state : List.of(ConversationState.READY, ConversationState.ERROR, ConversationState.EXECUTION_INTERRUPTED)) {
                String id = put(UPGRADE_AGENT, 1, state, null);

                assertEquals(GenerationUpgradeOutcome.UPGRADED, store.upgradeCompatibilityGeneration(id, UPGRADE_AGENT, 3), state.name());

                var after = store.loadConversationMemorySnapshot(id);
                assertEquals(3, after.getCompatibilityGeneration(), state.name());
                assertEquals(state, after.getConversationState(), "the state is not touched");
            }
        }

        @Test
        @DisplayName("another agent's conversation, a missing one and a malformed id are reported and not written")
        void wrongAgentMissingAndMalformed() throws Exception {
            String id = put(UPGRADE_AGENT, 1, ConversationState.READY, null);

            assertEquals(GenerationUpgradeOutcome.WRONG_AGENT, store.upgradeCompatibilityGeneration(id, "otherAgent", 3));
            assertNull(store.loadConversationMemorySnapshot(id).getCompatibilityGeneration());
            assertEquals(GenerationUpgradeOutcome.NOT_FOUND, store.upgradeCompatibilityGeneration(MISSING_ID, UPGRADE_AGENT, 3));
            assertEquals(GenerationUpgradeOutcome.NOT_FOUND, store.upgradeCompatibilityGeneration("not-an-id", UPGRADE_AGENT, 3));
        }

        @Test
        @DisplayName("a conversation written before revisions existed is upgraded too")
        void documentWithoutRevisionMarkers() throws Exception {
            String id = put(UPGRADE_AGENT, 1, ConversationState.READY, null);
            removeRevisionMarkers(id);

            assertEquals(GenerationUpgradeOutcome.UPGRADED, store.upgradeCompatibilityGeneration(id, UPGRADE_AGENT, 3));

            var after = store.loadConversationMemorySnapshot(id);
            assertEquals(3, after.getCompatibilityGeneration());
            assertEquals(1, after.getRevision());
        }

        // ── the interleavings: a turn that loaded the conversation BEFORE the upgrade
        // ──

        @Test
        @DisplayName("a turn that loaded before the upgrade cannot append over it — refused, and the generation stays")
        void staleAppendDoesNotRevertTheGeneration() throws Exception {
            String id = put(UPGRADE_AGENT, 1, ConversationState.READY, null);
            var staleTurn = loadForTurn(id);
            addTurn(staleTurn);

            assertEquals(GenerationUpgradeOutcome.UPGRADED, store.upgradeCompatibilityGeneration(id, UPGRADE_AGENT, 3));

            assertThrows(ConcurrentConversationModificationException.class, () -> store.storeConversationMemorySnapshot(staleTurn));
            var stored = store.loadConversationMemorySnapshot(id);
            assertEquals(3, stored.getCompatibilityGeneration(), "the stale turn's old generation must not be written back");
            assertEquals(0, stored.getConversationSteps().size(), "and its step was not half-applied");
        }

        @Test
        @DisplayName("a full-document write from before the upgrade is refused on the revision — the generation stays")
        void staleFullWriteDoesNotRevertTheGeneration() throws Exception {
            String id = put(UPGRADE_AGENT, 1, ConversationState.READY, null);
            var staleDocument = store.loadConversationMemorySnapshot(id); // append baseline unknown: takes the full-replace path

            assertEquals(GenerationUpgradeOutcome.UPGRADED, store.upgradeCompatibilityGeneration(id, UPGRADE_AGENT, 3));

            assertThrows(ConcurrentConversationModificationException.class, () -> store.storeConversationMemorySnapshot(staleDocument));
            assertEquals(3, store.loadConversationMemorySnapshot(id).getCompatibilityGeneration());
        }

        @Test
        @DisplayName("a turn that loaded after the upgrade appends normally and carries the new generation forward")
        void freshTurnKeepsTheGeneration() throws Exception {
            String id = put(UPGRADE_AGENT, 1, ConversationState.READY, null);
            assertEquals(GenerationUpgradeOutcome.UPGRADED, store.upgradeCompatibilityGeneration(id, UPGRADE_AGENT, 3));

            var turn = loadForTurn(id);
            addTurn(turn);
            store.storeConversationMemorySnapshot(turn);

            var stored = store.loadConversationMemorySnapshot(id);
            assertEquals(3, stored.getCompatibilityGeneration());
            assertEquals(1, stored.getConversationSteps().size());
        }

        @Test
        @DisplayName("an upgrade after a committed turn keeps that turn's step")
        void upgradeAfterACommittedTurn() throws Exception {
            String id = put(UPGRADE_AGENT, 1, ConversationState.READY, null);
            var first = loadForTurn(id);
            addTurn(first);
            store.storeConversationMemorySnapshot(first);

            assertEquals(GenerationUpgradeOutcome.UPGRADED, store.upgradeCompatibilityGeneration(id, UPGRADE_AGENT, 3));

            var stored = store.loadConversationMemorySnapshot(id);
            assertEquals(3, stored.getCompatibilityGeneration());
            assertEquals(1, stored.getConversationSteps().size(), "the committed turn survives the upgrade");
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

        /**
         * The limit used to apply to an arbitrary order, so past it the OLDEST pause
         * could be the one dropped: the retention sweep never reached it and
         * eddi_hitl_pending_oldest_age_seconds under-reported, keeping
         * EddiApprovalWaitingLong quiet. Inserted newest first and undated in the
         * middle, so insertion order cannot pass for the right answer.
         */
        @Test
        @DisplayName("findPendingApprovalSummaries returns the oldest pauses first, undated ones last, before the limit")
        void findPendingApprovalSummariesOldestFirst() throws Exception {
            Instant now = Instant.now();
            var newest = createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN);
            newest.setHitlPausedAt(now.minusSeconds(10));
            String newestId = store.storeConversationMemorySnapshot(newest);
            String undatedId = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN));
            var oldest = createSnapshot(null, "agent1", 1, "user1", ConversationState.AWAITING_HUMAN);
            oldest.setHitlPausedAt(now.minusSeconds(7200));
            String oldestId = store.storeConversationMemorySnapshot(oldest);

            assertEquals(List.of(oldestId), store.findPendingApprovalSummaries(1).stream()
                    .map(s -> s.getConversationId()).toList(), "the limit must keep the OLDEST pause");
            assertEquals(List.of(oldestId, newestId, undatedId), store.findPendingApprovalSummaries(10).stream()
                    .map(s -> s.getConversationId()).toList());
            assertEquals(List.of(oldestId, newestId), store.findPendingApprovalSummaries("user1", 2).stream()
                    .map(s -> s.getConversationId()).toList(), "the owner-filtered listing is ordered the same way");
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

    @Nested
    @DisplayName("loadListingSummaries")
    class ListingSummaries {

        @Test
        @DisplayName("one read returns, per stored conversation, exactly what a full load would show in a listing")
        void summariesMatchTheFullLoad() throws Exception {
            var withSteps = createSnapshot(null, "summaryAgent", 3, "summary-user", ConversationState.READY);
            withSteps.getConversationSteps().add(new ConversationMemorySnapshot.ConversationStepSnapshot());
            withSteps.getConversationSteps().add(new ConversationMemorySnapshot.ConversationStepSnapshot());
            String stepsId = store.storeConversationMemorySnapshot(withSteps);
            String endedId = store.storeConversationMemorySnapshot(
                    createSnapshot(null, "summaryAgent", 4, "other-user", ConversationState.IN_PROGRESS));
            // The narrow state write ending a conversation must show in the summary too.
            store.setConversationState(endedId, ConversationState.ENDED);

            Map<String, ConversationListingSummary> summaries = store.loadListingSummaries(
                    List.of(stepsId, endedId, "00000000-0000-0000-0000-0000000000aa", "not-an-id"));

            assertEquals(2, summaries.size(), "unknown and malformed ids have no entry: " + summaries.keySet());
            for (String id : List.of(stepsId, endedId)) {
                assertEquals(ConversationListingSummary.of(store.loadConversationMemorySnapshot(id), id), summaries.get(id));
            }
            var steps = summaries.get(stepsId);
            assertEquals(2, steps.conversationStepCount());
            assertEquals("summary-user", steps.userId());
            assertEquals("summaryAgent", steps.agentId());
            assertEquals(3, steps.agentVersion());
            assertEquals(Deployment.Environment.production, steps.environment());
            assertEquals(ConversationState.READY, steps.conversationState());
            assertEquals(ConversationState.ENDED, summaries.get(endedId).conversationState());
            assertEquals(0, summaries.get(endedId).conversationStepCount());
        }

        @Test
        @DisplayName("no ids, no query")
        void emptyRequest() {
            assertTrue(store.loadListingSummaries(List.of()).isEmpty());
        }
    }

    @Nested
    @DisplayName("idle conversation activity")
    class IdleActivity {

        private static final String SWEEP_AGENT = "idleAgent";

        /** A conversation whose step data carries exactly the given timestamps. */
        private ConversationMemorySnapshot withTimestamps(String agentId, ConversationState state, Instant... timestamps) {
            var snapshot = createSnapshot(null, agentId, 1, "idle-user", state);
            for (Instant timestamp : timestamps) {
                var result = new ConversationMemorySnapshot.ResultSnapshot();
                result.setKey("input");
                result.setTimestamp(Date.from(timestamp));
                var workflow = new ConversationMemorySnapshot.WorkflowRunSnapshot();
                workflow.setLifecycleTasks(List.of(result));
                var step = new ConversationMemorySnapshot.ConversationStepSnapshot();
                step.setWorkflows(List.of(workflow));
                snapshot.getConversationSteps().add(step);
            }
            return snapshot;
        }

        private String storeSnapshot(ConversationMemorySnapshot snapshot) throws Exception {
            return store.storeConversationMemorySnapshot(snapshot);
        }

        @Test
        @DisplayName("the projection reports the newest step timestamp, the state, the agent and its version — without a full load")
        void projectionReportsTheNewestTimestamp() throws Exception {
            Instant newest = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
            String id = storeSnapshot(withTimestamps(SWEEP_AGENT, ConversationState.READY, newest.minus(10, ChronoUnit.DAYS), newest,
                    newest.minus(5, ChronoUnit.DAYS)));

            ConversationActivitySummary activity = store.loadConversationActivity(id);

            assertEquals(new ConversationActivitySummary(id, ConversationState.READY, SWEEP_AGENT, 1, newest), activity);
            assertNull(store.loadConversationActivity("0123456789abcdef01234567"), "an unknown id has no summary");
            assertNull(store.loadConversationActivity("not-an-id"), "a malformed id has no summary");
        }

        @Test
        @DisplayName("a conversation without any timestamp has none to report")
        void noTimestamp() throws Exception {
            String id = storeSnapshot(withTimestamps(SWEEP_AGENT, ConversationState.READY));

            assertNull(store.loadConversationActivity(id).lastInteraction());
        }

        @Test
        @DisplayName("open conversations are paged in id order; ended ones and other agents' are not listed")
        void pagesOpenConversations() throws Exception {
            Instant old = Instant.now().minus(40, ChronoUnit.DAYS);
            var expected = new TreeSet<String>();
            for (int i = 0; i < 5; i++) {
                expected.add(storeSnapshot(withTimestamps(SWEEP_AGENT, ConversationState.READY, old)));
            }
            String ended = storeSnapshot(withTimestamps(SWEEP_AGENT, ConversationState.READY, old));
            store.setConversationState(ended, ConversationState.ENDED);
            storeSnapshot(withTimestamps("someOtherAgent", ConversationState.READY, old));

            List<String> seen = new ArrayList<>();
            String after = null;
            int batches = 0;
            while (true) {
                var batch = store.loadOpenConversationActivity(SWEEP_AGENT, 1, after, 2);
                if (batch.isEmpty()) {
                    break;
                }
                assertTrue(batch.size() <= 2, "a batch never exceeds the limit");
                batch.forEach(activity -> seen.add(activity.conversationId()));
                after = batch.getLast().conversationId();
                batches++;
            }

            assertEquals(3, batches);
            assertEquals(List.copyOf(expected), seen, "every open conversation exactly once, in id order");
            assertEquals(5, store.loadOpenConversationActivity(SWEEP_AGENT, null, null, 100).size(), "a null version means every version");
            assertEquals(0, store.loadOpenConversationActivity(SWEEP_AGENT, 2, null, 100).size());
        }

        @Test
        @DisplayName("endIdleConversations ends only READY conversations idle past the cutoff, records the reason, and leaves the revision alone")
        void endsOnlyIdleReadyConversations() throws Exception {
            Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
            String idle = storeSnapshot(withTimestamps(SWEEP_AGENT, ConversationState.READY, Instant.now().minus(90, ChronoUnit.DAYS)));
            // the NEWEST timestamp decides: an old first step does not make this idle
            String recentlyActive = storeSnapshot(withTimestamps(SWEEP_AGENT, ConversationState.READY,
                    Instant.now().minus(90, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.DAYS)));
            String paused = storeSnapshot(withTimestamps(SWEEP_AGENT, ConversationState.AWAITING_HUMAN, Instant.now().minus(90, ChronoUnit.DAYS)));
            String running = storeSnapshot(withTimestamps(SWEEP_AGENT, ConversationState.IN_PROGRESS, Instant.now().minus(90, ChronoUnit.DAYS)));
            // cannot be proven idle
            String untimestamped = storeSnapshot(withTimestamps(SWEEP_AGENT, ConversationState.READY));
            Long revisionBefore = store.getRevision(idle);

            long ended = store.endIdleConversations(cutoff, null, "idle", false);

            assertEquals(1, ended);
            assertEquals(ConversationState.ENDED, store.getConversationState(idle));
            assertEquals("idle", store.loadConversationMemorySnapshot(idle).getEndReason());
            assertEquals(revisionBefore, store.getRevision(idle), "a narrow write: the revision does not move");
            assertEquals(ConversationState.READY, store.getConversationState(recentlyActive));
            assertEquals(ConversationState.AWAITING_HUMAN, store.getConversationState(paused));
            assertEquals(ConversationState.IN_PROGRESS, store.getConversationState(running));
            assertEquals(ConversationState.READY, store.getConversationState(untimestamped));
            assertEquals(0, store.endIdleConversations(cutoff, null, "idle", false), "nothing left to end");
        }

        @Test
        @DisplayName("dryRun counts without ending anything")
        void dryRunChangesNothing() throws Exception {
            Instant old = Instant.now().minus(90, ChronoUnit.DAYS);
            String first = storeSnapshot(withTimestamps(SWEEP_AGENT, ConversationState.READY, old));
            String second = storeSnapshot(withTimestamps(SWEEP_AGENT, ConversationState.READY, old));

            assertEquals(2, store.endIdleConversations(Instant.now().minus(30, ChronoUnit.DAYS), null, "idle", true));

            assertEquals(ConversationState.READY, store.getConversationState(first));
            assertEquals(ConversationState.READY, store.getConversationState(second));
            assertNull(store.loadConversationMemorySnapshot(first).getEndReason());
        }

        @Test
        @DisplayName("an agentId restricts the bulk end to that agent")
        void agentFilter() throws Exception {
            Instant old = Instant.now().minus(90, ChronoUnit.DAYS);
            String mine = storeSnapshot(withTimestamps(SWEEP_AGENT, ConversationState.READY, old));
            String other = storeSnapshot(withTimestamps("someOtherAgent", ConversationState.READY, old));
            Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);

            assertEquals(1, store.endIdleConversations(cutoff, SWEEP_AGENT, null, true));
            assertEquals(1, store.endIdleConversations(cutoff, SWEEP_AGENT, null, false));

            assertEquals(ConversationState.ENDED, store.getConversationState(mine));
            assertNull(store.loadConversationMemorySnapshot(mine).getEndReason(), "a null reason records none");
            assertEquals(ConversationState.READY, store.getConversationState(other));
        }

        @Test
        @DisplayName("more idle conversations than one batch are all ended")
        void endsMoreThanOneBatch() throws Exception {
            Instant old = Instant.now().minus(90, ChronoUnit.DAYS);
            for (int i = 0; i < 12; i++) {
                storeSnapshot(withTimestamps(SWEEP_AGENT, ConversationState.READY, old));
            }

            assertEquals(12, store.endIdleConversations(Instant.now().minus(30, ChronoUnit.DAYS), SWEEP_AGENT, "idle", false));
            assertEquals(0, store.loadOpenConversationActivity(SWEEP_AGENT, null, null, 100).size());
        }
    }

    /**
     * The turn-start read: state and revision in one round trip. It must agree with
     * the two reads it replaces, also after a write moved the revision on.
     */
    @Test
    @DisplayName("getStateAndRevision agrees with getConversationState and getRevision")
    void stateAndRevisionAgreeWithTheSeparateReads() throws Exception {
        var snapshot = createSnapshot(null, "agent1", 1, "user1", ConversationState.READY);
        String id = store.storeConversationMemorySnapshot(snapshot);

        var first = store.getStateAndRevision(id);
        assertEquals(new IConversationMemoryStore.StateAndRevision(store.getConversationState(id), store.getRevision(id)), first);
        assertEquals(ConversationState.READY, first.state());

        var reloaded = store.loadConversationMemorySnapshot(id);
        reloaded.setConversationState(ConversationState.ENDED);
        store.storeConversationMemorySnapshot(reloaded);

        var second = store.getStateAndRevision(id);
        assertEquals(new IConversationMemoryStore.StateAndRevision(ConversationState.ENDED, store.getRevision(id)), second);
        assertTrue(second.revision() > first.revision(), "a write moves the revision on, and the combined read sees it");
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

    /**
     * A step as a paused turn stores it: a secret context entry, replaced by the
     * placeholder and marked secret, next to an ordinary one.
     */
    private static ConversationMemorySnapshot.ConversationStepSnapshot stepWithSecretAndOrdinaryContext() {
        var secret = new Context(Context.ContextType.string, MemoryKeys.SECRET_CONTEXT_PLACEHOLDER);
        secret.setSecret(true);
        var token = new ConversationMemorySnapshot.ResultSnapshot();
        token.setKey("context:userToken");
        token.setResult(secret);
        var language = new ConversationMemorySnapshot.ResultSnapshot();
        language.setKey("context:language");
        language.setResult(new Context(Context.ContextType.string, "de"));
        var workflow = new ConversationMemorySnapshot.WorkflowRunSnapshot();
        workflow.setLifecycleTasks(new ArrayList<>(List.of(token, language)));
        var step = new ConversationMemorySnapshot.ConversationStepSnapshot();
        step.setWorkflows(new ArrayList<>(List.of(workflow)));
        return step;
    }
}
