/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.model.Context;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * G12 — a turn must never be discarded silently when the conversation document
 * disappeared mid-turn; G11 — one malformed context entry must not fail the
 * load of the whole conversation.
 */
@SuppressWarnings("unchecked")
class ConversationMemoryStoreResilienceTest {

    private static final String VALID_ID = "aabbccddeeff112233445566";

    private MongoCollection<ConversationMemorySnapshot> objectCollection;
    private MongoCollection<Document> documentCollection;
    private ConversationMemoryStore store;

    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        documentCollection = mock(MongoCollection.class);
        objectCollection = mock(MongoCollection.class);
        when(database.getCollection("conversationmemories", Document.class)).thenReturn(documentCollection);
        when(database.getCollection("conversationmemories", ConversationMemorySnapshot.class)).thenReturn(objectCollection);
        store = new ConversationMemoryStore(database);
    }

    /**
     * Stubs the existence probe the store runs after a zero-match write to tell
     * "the conversation is gone" apart from "the conversation moved to another
     * revision".
     */
    private void stubConversationExists(boolean exists) {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(documentCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.projection(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(exists ? new Document("_id", new ObjectId(VALID_ID)) : null);
    }

    // ==================== G12 ====================

    @Test
    @DisplayName("G12 — a concurrent delete during a turn surfaces as an error, not silent loss")
    void concurrentDeleteDuringTurnIsReported() {
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        snapshot.setId(VALID_ID);

        UpdateResult noMatch = mock(UpdateResult.class);
        when(noMatch.getMatchedCount()).thenReturn(0L);
        when(objectCollection.replaceOne(any(Bson.class), eq(snapshot))).thenReturn(noMatch);
        stubConversationExists(false);

        var thrown = assertThrows(IResourceStore.ResourceStoreException.class, () -> store.storeConversationMemorySnapshot(snapshot));
        assertTrue(thrown.getMessage().contains(VALID_ID), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("NOT persisted"), thrown.getMessage());
        assertFalse(thrown instanceof ConcurrentConversationModificationException,
                "an erased conversation has nothing to retry against — it must not be reported as a revision conflict");
    }

    @Test
    @DisplayName("a zero-match write on a conversation that still exists is a revision conflict, not a deletion")
    void concurrentWriteDuringTurnIsReportedAsConflict() {
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        snapshot.setId(VALID_ID);
        snapshot.setRevision(7L);

        UpdateResult noMatch = mock(UpdateResult.class);
        when(noMatch.getMatchedCount()).thenReturn(0L);
        when(objectCollection.replaceOne(any(Bson.class), eq(snapshot))).thenReturn(noMatch);
        stubConversationExists(true);

        var thrown = assertThrows(ConcurrentConversationModificationException.class,
                () -> store.storeConversationMemorySnapshot(snapshot));
        assertEquals(VALID_ID, thrown.getConversationId());
        assertEquals(7L, thrown.getExpectedRevision());
        assertEquals(7L, snapshot.getRevision(), "a refused write must leave the snapshot on the revision it was derived from");
    }

    @Test
    @DisplayName("G12 — a matched replace still returns the conversation id")
    void matchedReplaceSucceeds() throws Exception {
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        snapshot.setId(VALID_ID);

        UpdateResult matched = mock(UpdateResult.class);
        when(matched.getMatchedCount()).thenReturn(1L);
        when(objectCollection.replaceOne(any(Bson.class), eq(snapshot))).thenReturn(matched);

        assertEquals(VALID_ID, store.storeConversationMemorySnapshot(snapshot));
        assertEquals(ConversationMemorySnapshot.UNVERSIONED_REVISION + 1, snapshot.getRevision(),
                "a successful write must stamp the revision it created, or the next write would present a superseded one");
    }

    // ==================== G11 ====================

    @Test
    @DisplayName("G11 — a context entry without a 'type' does not fail the whole conversation load")
    void contextWithoutTypeDoesNotFailTheLoad() throws Exception {
        assertLoadsWith(new LinkedHashMap<>(Map.of("value", "de")));
    }

    @Test
    @DisplayName("G11 — a context entry with an unknown ContextType does not fail the whole conversation load")
    void contextWithUnknownTypeDoesNotFailTheLoad() throws Exception {
        var raw = new LinkedHashMap<String, Object>();
        raw.put("type", "written_by_a_newer_version");
        raw.put("value", "de");
        assertLoadsWith(raw);
    }

    @Test
    @DisplayName("G11 — a well-formed context entry is still converted to a Context")
    void wellFormedContextIsStillConverted() throws Exception {
        var raw = new LinkedHashMap<String, Object>();
        raw.put("type", Context.ContextType.string.name());
        raw.put("value", "de");

        var snapshot = snapshotWithContext(raw);
        FindIterable<ConversationMemorySnapshot> iterable = mock(FindIterable.class);
        when(objectCollection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(snapshot);

        var loaded = store.loadConversationMemorySnapshot(VALID_ID);
        var result = loaded.getConversationSteps().get(0).getWorkflows().get(0).getLifecycleTasks().get(0).getResult();
        assertInstanceOf(Context.class, result);
        assertEquals("de", ((Context) result).getValue());
    }

    private void assertLoadsWith(LinkedHashMap<String, Object> rawContext) throws Exception {
        var snapshot = snapshotWithContext(rawContext);
        FindIterable<ConversationMemorySnapshot> iterable = mock(FindIterable.class);
        when(objectCollection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(snapshot);

        var loaded = store.loadConversationMemorySnapshot(VALID_ID);

        assertNotNull(loaded, "a malformed context entry must degrade, not abort the load");
        assertEquals(VALID_ID, loaded.getConversationId());
        // left unconverted rather than throwing
        assertSame(rawContext, loaded.getConversationSteps().get(0).getWorkflows().get(0).getLifecycleTasks().get(0).getResult());
    }

    private ConversationMemorySnapshot snapshotWithContext(LinkedHashMap<String, Object> rawContext) {
        var resultSnapshot = new ResultSnapshot();
        resultSnapshot.setKey("context:language");
        resultSnapshot.setResult(rawContext);

        var workflow = new WorkflowRunSnapshot();
        workflow.getLifecycleTasks().add(resultSnapshot);

        var step = new ConversationStepSnapshot();
        step.getWorkflows().add(workflow);

        var snapshot = new ConversationMemorySnapshot();
        List<ConversationStepSnapshot> steps = new ArrayList<>();
        steps.add(step);
        snapshot.setConversationSteps(steps);
        return snapshot;
    }
}
