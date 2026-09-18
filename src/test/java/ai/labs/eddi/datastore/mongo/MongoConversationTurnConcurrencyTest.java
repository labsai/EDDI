/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.mongo.codec.JacksonProvider;
import ai.labs.eddi.engine.memory.ConcurrentConversationModificationException;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Deployment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import de.undercouch.bson4jackson.BsonFactory;
import de.undercouch.bson4jackson.BsonParser;
import org.bson.codecs.BsonValueCodecProvider;
import org.bson.codecs.DocumentCodecProvider;
import org.bson.codecs.IterableCodecProvider;
import org.bson.codecs.MapCodecProvider;
import org.bson.codecs.RawBsonDocumentCodec;
import org.bson.codecs.ValueCodecProvider;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.bson.types.ObjectId;

import java.util.List;

import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.convertConversationMemory;
import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.convertConversationMemorySnapshot;
import static org.bson.codecs.configuration.CodecRegistries.fromCodecs;
import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lost-update reproduction for the conversation document.
 * <p>
 * <b>The defect.</b> A turn loads the whole conversation memory, appends one
 * step, and writes the <em>whole document</em> back
 * ({@code ConversationService} → {@code ConversationStepRunner
 * .storeConversationMemory} → {@code storeConversationMemorySnapshot}). Two
 * turns whose load/save windows overlap therefore both start from the same
 * snapshot and the last writer wins — the earlier turn's step is gone, with no
 * error, no 409 and nothing in the log.
 * <p>
 * <b>Why a strictly sequential client still hits it.</b> The window is not
 * hypothetical and it is not narrow. {@code Conversation.runStep} calls
 * {@code outputProvider.renderOutput(memory)} in its {@code finally} block,
 * which is the lambda {@code ConversationService.say} passes to
 * {@code agent.continueConversation} — so {@code responseHandler.onComplete}
 * hands HTTP 200 to the caller from <em>inside</em> the pipeline callable,
 * while the persist happens afterwards in the runtime's own {@code onComplete}.
 * A client that waits for each 200 before posting the next turn can therefore
 * still issue the next {@code say} — whose first act is
 * {@code loadConversationMemory} on the REST thread, outside the
 * per-conversation coordinator — before the previous turn has been written.
 * That is exactly the observed symptom: three back-to-back turns, three 200s,
 * two turns stored.
 * <p>
 * These tests drive the real conversion path
 * ({@code convertConversationMemorySnapshot} → {@code startNextStep} →
 * {@code convertConversationMemory}) against a real MongoDB so the race is the
 * production one and not a mock's idea of it.
 */
@DisplayName("Conversation turn concurrency (MongoDB)")
class MongoConversationTurnConcurrencyTest extends MongoTestBase {

    private static final String DB_NAME = "eddi_turn_concurrency_test";
    private static final String COLLECTION = "conversationmemories";
    private static final String AGENT_ID = "agent-turn-concurrency";

    private static MongoClient codecAwareClient;
    private static MongoDatabase conversationDatabase;
    private static ConversationMemoryStore store;

    /**
     * Reuses {@link MongoTestBase}'s shared container but needs its own client: the
     * store reads and writes {@code ConversationMemorySnapshot} through
     * {@code getCollection(name, ConversationMemorySnapshot.class)}, which only
     * works with the production Jackson-backed codec registry. Mirrors
     * {@code MongoConversationMemoryStoreTest}.
     */
    @BeforeAll
    static void initStore() {
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
                .applyConnectionString(new ConnectionString(MONGO.getConnectionString()))
                .codecRegistry(codecRegistry)
                .build();

        codecAwareClient = MongoClients.create(settings);
        conversationDatabase = codecAwareClient.getDatabase(DB_NAME);
        store = new ConversationMemoryStore(conversationDatabase);
    }

    @AfterAll
    static void closeStore() {
        if (codecAwareClient != null) {
            codecAwareClient.close();
        }
    }

    @BeforeEach
    void clean() {
        conversationDatabase.getCollection(COLLECTION).drop();
    }

    /**
     * The reproduction, phrased as the invariant rather than as a specific
     * remediation, so it holds for either fix: a turn the store has already
     * accepted must never be discarded by a later turn that started from a stale
     * snapshot — the stale write is either refused or merged, never silently
     * applied on top.
     * <p>
     * Fails on {@code origin/main}: the second {@code replaceOne} matches on
     * {@code _id} alone, reports success, and turn A is gone.
     */
    @Test
    @DisplayName("an accepted turn is never silently discarded by a later overlapping turn")
    void overlappingTurnIsNotSilentlyLost() throws Exception {
        String conversationId = seedConversation();

        // Both turns load the same document — the real window described above.
        IConversationMemory turnA = loadMemory(conversationId);
        IConversationMemory turnB = loadMemory(conversationId);

        appendTurn(turnA, "turn A");
        appendTurn(turnB, "turn B");

        store.storeConversationMemorySnapshot(snapshotOf(turnA));

        boolean conflictReported = false;
        try {
            store.storeConversationMemorySnapshot(snapshotOf(turnB));
        } catch (IResourceStore.ResourceStoreException e) {
            conflictReported = true;
        }

        var reloaded = store.loadConversationMemorySnapshot(conversationId);
        assertNotNull(reloaded);
        boolean turnAStillStored = inputsOf(reloaded).contains("turn A");

        assertTrue(conflictReported || turnAStillStored,
                "Turn A was accepted by the store (its reply had already been handed to the caller as HTTP 200) "
                        + "and was then silently discarded by turn B's whole-document replace. Stored inputs: "
                        + inputsOf(reloaded));
    }

    /**
     * The same invariant for the shape the reporter measured: three consecutive
     * turns where each turn's load overlaps the previous turn's store, which is
     * what a client that waits for every 200 actually produces. On
     * {@code origin/main} the middle turn vanishes and both writes report success.
     */
    @Test
    @DisplayName("three sequential turns whose loads overlap the previous store keep every turn")
    void threeSequentialTurnsKeepEveryTurn() throws Exception {
        String conversationId = seedConversation();

        // Turn 1: loads the greeting, replies, stores.
        IConversationMemory turn1 = loadMemory(conversationId);
        appendTurn(turn1, "turn 1");
        store.storeConversationMemorySnapshot(snapshotOf(turn1));

        // Turn 2: loads after turn 1 landed, replies (200 to the client), and its
        // store is still pending when turn 3 arrives.
        IConversationMemory turn2 = loadMemory(conversationId);
        appendTurn(turn2, "turn 2");

        // Turn 3: loads BEFORE turn 2's store lands — the observed ordering.
        IConversationMemory turn3 = loadMemory(conversationId);
        appendTurn(turn3, "turn 3");

        store.storeConversationMemorySnapshot(snapshotOf(turn2));

        boolean conflictReported = false;
        try {
            store.storeConversationMemorySnapshot(snapshotOf(turn3));
        } catch (IResourceStore.ResourceStoreException e) {
            conflictReported = true;
        }

        var inputs = inputsOf(store.loadConversationMemorySnapshot(conversationId));
        assertTrue(conflictReported || inputs.contains("turn 2"),
                "turn 2 was accepted and then silently dropped by turn 3. Stored inputs: " + inputs);
    }

    @Test
    @DisplayName("a stale full replace is refused as a conflict and leaves the winner intact")
    void staleFullReplaceIsRefused() throws Exception {
        String conversationId = seedConversation();

        IConversationMemory winner = loadMemory(conversationId);
        IConversationMemory loser = loadMemory(conversationId);
        appendTurn(winner, "winner");
        appendTurn(loser, "loser");

        store.storeConversationMemorySnapshot(snapshotOf(winner));

        var staleSnapshot = snapshotOf(loser);
        long revisionBefore = staleSnapshot.getRevision();
        var conflict = assertThrows(ConcurrentConversationModificationException.class,
                () -> store.storeConversationMemorySnapshot(staleSnapshot));
        assertEquals(conversationId, conflict.getConversationId());
        assertEquals(revisionBefore, conflict.getExpectedRevision());
        assertEquals(revisionBefore, staleSnapshot.getRevision(),
                "a refused write must leave the snapshot on the revision it was derived from, so a retry re-presents it");

        var reloaded = store.loadConversationMemorySnapshot(conversationId);
        assertEquals(List.of("greeting", "winner"), inputsOf(reloaded));
    }

    @Test
    @DisplayName("a conversation deleted mid-turn is reported as gone, not as a conflict")
    void deletedConversationIsNotReportedAsConflict() throws Exception {
        String conversationId = seedConversation();
        IConversationMemory memory = loadMemory(conversationId);
        appendTurn(memory, "turn after deletion");

        store.deleteConversationMemorySnapshot(conversationId);

        var thrown = assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.storeConversationMemorySnapshot(snapshotOf(memory)));
        assertFalse(thrown instanceof ConcurrentConversationModificationException,
                "an erased conversation has nothing to retry against — it must not masquerade as a revision conflict");
        assertTrue(thrown.getMessage().contains("no longer exists"), thrown.getMessage());
    }

    /**
     * Every conversation already in a deployed instance's database was written
     * without {@code _rev}. It must remain writable — MongoDB's {@code {_rev: 0}}
     * does not match a document that has no {@code _rev}, so without the
     * {@code $exists} half of the filter every pre-upgrade conversation would be
     * bricked on its next turn.
     */
    @Test
    @DisplayName("a pre-upgrade document with no _rev upgrades on its next write")
    void legacyDocumentWithoutRevisionUpgrades() throws Exception {
        String conversationId = seedConversation();
        conversationDatabase.getCollection(COLLECTION).updateOne(
                new Document("_id", new ObjectId(conversationId)),
                new Document("$unset", new Document("_rev", "")));

        var legacy = store.loadConversationMemorySnapshot(conversationId);
        assertEquals(ConversationMemorySnapshot.UNVERSIONED_REVISION, legacy.getRevision(),
                "a document with no _rev must read as unversioned");

        IConversationMemory memory = loadMemory(conversationId);
        appendTurn(memory, "first turn after upgrade");
        store.storeConversationMemorySnapshot(snapshotOf(memory));

        var reloaded = store.loadConversationMemorySnapshot(conversationId);
        assertEquals(List.of("greeting", "first turn after upgrade"), inputsOf(reloaded));
        assertEquals(ConversationMemorySnapshot.UNVERSIONED_REVISION + 1, reloaded.getRevision());
    }

    /**
     * The undo/redo and HITL-resume path. Its existing state CAS cannot see this
     * race at all: a say turn that appends a step leaves the same READY state
     * behind, so the state filter matches and the undo's whole-document replace
     * erased the appended turn.
     */
    @Test
    @DisplayName("the conditional store refuses a stale revision even when the expected state still matches")
    void conditionalStoreRefusesStaleRevision() throws Exception {
        String conversationId = seedConversation();
        IConversationMemory firstTurn = loadMemory(conversationId);
        appendTurn(firstTurn, "turn 1");
        store.storeConversationMemorySnapshot(snapshotOf(firstTurn));

        // A say turn and an undo both load the same document. Undo runs on the REST
        // thread and is NOT serialized by the per-conversation coordinator, so this
        // ordering is reachable in production.
        IConversationMemory sayTurn = loadMemory(conversationId);
        IConversationMemory undoTurn = loadMemory(conversationId);

        appendTurn(sayTurn, "concurrent say");
        store.storeConversationMemorySnapshot(snapshotOf(sayTurn));

        undoTurn.undoLastStep();
        assertFalse(store.storeConversationMemorySnapshotIfState(snapshotOf(undoTurn), ConversationState.READY),
                "the state matches on both sides, so only the revision can arbitrate this race");

        assertEquals(List.of("greeting", "turn 1", "concurrent say"),
                inputsOf(store.loadConversationMemorySnapshot(conversationId)));
    }

    @Test
    @DisplayName("undo/redo still work when nothing raced them")
    void undoAndRedoStillWork() throws Exception {
        String conversationId = seedConversation();

        IConversationMemory memory = loadMemory(conversationId);
        appendTurn(memory, "turn 1");
        store.storeConversationMemorySnapshot(snapshotOf(memory));

        IConversationMemory toUndo = loadMemory(conversationId);
        assertTrue(toUndo.isUndoAvailable());
        toUndo.undoLastStep();
        assertTrue(store.storeConversationMemorySnapshotIfState(snapshotOf(toUndo), ConversationState.READY));
        assertEquals(List.of("greeting"), inputsOf(store.loadConversationMemorySnapshot(conversationId)));

        IConversationMemory toRedo = loadMemory(conversationId);
        assertTrue(toRedo.isRedoAvailable(), "the undone step must still be in the persisted redo cache");
        toRedo.redoLastStep();
        assertTrue(store.storeConversationMemorySnapshotIfState(snapshotOf(toRedo), ConversationState.READY));
        assertEquals(List.of("greeting", "turn 1"), inputsOf(store.loadConversationMemorySnapshot(conversationId)));
    }

    // ─── Helpers ────────────────────────────────────────────────

    /**
     * A fresh conversation holding only its greeting step, as {@code start} leaves
     * it.
     */
    private String seedConversation() throws IResourceStore.ResourceStoreException {
        var memory = new ConversationMemory(AGENT_ID, 1, "user-1");
        memory.setConversationState(ConversationState.READY);
        memory.getCurrentStep().storeData(new Data<>("input", "greeting"));
        memory.getCurrentStep().addConversationOutputString("input", "greeting");

        var snapshot = convertConversationMemory(memory);
        snapshot.setEnvironment(Deployment.Environment.test);
        return store.storeConversationMemorySnapshot(snapshot);
    }

    private static IConversationMemory loadMemory(String conversationId) {
        return convertConversationMemorySnapshot(store.loadConversationMemorySnapshot(conversationId));
    }

    /**
     * One user turn: a new step carrying the input, exactly as
     * {@code Conversation.runStep} leaves it.
     */
    private static void appendTurn(IConversationMemory memory, String input) {
        ((ConversationMemory) memory).startNextStep();
        memory.getCurrentStep().storeData(new Data<>("input", input));
        memory.getCurrentStep().addConversationOutputString("input", input);
        memory.setConversationState(ConversationState.READY);
    }

    private static ConversationMemorySnapshot snapshotOf(IConversationMemory memory) {
        var snapshot = convertConversationMemory(memory);
        snapshot.setEnvironment(Deployment.Environment.test);
        return snapshot;
    }

    /** The {@code input} value of every stored step, oldest first. */
    private static List<String> inputsOf(ConversationMemorySnapshot snapshot) {
        return snapshot.getConversationSteps().stream()
                .flatMap(step -> step.getWorkflows().stream())
                .flatMap(workflow -> workflow.getLifecycleTasks().stream())
                .filter(task -> "input".equals(task.getKey()))
                .map(task -> String.valueOf(task.getResult()))
                .toList();
    }

    /**
     * Guards the fixture itself: a load/append/store round trip must keep the
     * history growing.
     */
    @Test
    @DisplayName("fixture check — sequential non-overlapping turns accumulate")
    void nonOverlappingTurnsAccumulate() throws Exception {
        String conversationId = seedConversation();
        for (String input : List.of("turn 1", "turn 2", "turn 3")) {
            IConversationMemory memory = loadMemory(conversationId);
            appendTurn(memory, input);
            store.storeConversationMemorySnapshot(snapshotOf(memory));
        }

        assertEquals(List.of("greeting", "turn 1", "turn 2", "turn 3"),
                inputsOf(store.loadConversationMemorySnapshot(conversationId)),
                "non-overlapping turns must accumulate in order");
    }
}
