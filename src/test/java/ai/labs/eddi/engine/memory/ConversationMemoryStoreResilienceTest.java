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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

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
    private ConversationMemoryStore store;

    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> documentCollection = mock(MongoCollection.class);
        objectCollection = mock(MongoCollection.class);
        when(database.getCollection("conversationmemories", Document.class)).thenReturn(documentCollection);
        when(database.getCollection("conversationmemories", ConversationMemorySnapshot.class)).thenReturn(objectCollection);
        store = new ConversationMemoryStore(database);
    }

    // ==================== G12 ====================

    @Test
    @DisplayName("G12 — a concurrent delete during a turn surfaces as an error, not silent loss")
    void concurrentDeleteDuringTurnIsReported() {
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        snapshot.setId(VALID_ID);

        UpdateResult noMatch = mock(UpdateResult.class);
        when(noMatch.getMatchedCount()).thenReturn(0L);
        when(objectCollection.replaceOne(any(Document.class), eq(snapshot))).thenReturn(noMatch);

        var thrown = assertThrows(IResourceStore.ResourceStoreException.class, () -> store.storeConversationMemorySnapshot(snapshot));
        assertTrue(thrown.getMessage().contains(VALID_ID), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("NOT persisted"), thrown.getMessage());
    }

    @Test
    @DisplayName("G12 — a matched replace still returns the conversation id")
    void matchedReplaceSucceeds() throws Exception {
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        snapshot.setId(VALID_ID);

        UpdateResult matched = mock(UpdateResult.class);
        when(matched.getMatchedCount()).thenReturn(1L);
        when(objectCollection.replaceOne(any(Document.class), eq(snapshot))).thenReturn(matched);

        assertEquals(VALID_ID, store.storeConversationMemorySnapshot(snapshot));
    }

    // ==================== G11 ====================

    @Test
    @DisplayName("G11 — a context entry without a 'type' does not fail the whole conversation load")
    void contextWithoutTypeDoesNotFailTheLoad() throws Exception {
        assertLoadsWith(new LinkedHashMap<>(java.util.Map.of("value", "de")));
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
