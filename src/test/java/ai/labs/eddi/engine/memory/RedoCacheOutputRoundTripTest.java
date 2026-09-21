/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A redone step must come back carrying the answer it had.
 * <p>
 * Undo/redo inside one live memory object always worked — the step holds its
 * own {@code ConversationOutput}. The loss happened across the store: the
 * snapshot serialised only {@code workflows/lifecycleTasks}, so a rehydrated
 * redo entry got a fresh empty output and {@code redoLastStep()} pushed
 * <em>that</em> over the restored turn. Because every request reloads memory
 * from the store, this fired on every real redo: the API returned 200, the step
 * reappeared, and its text was gone — from the transcript and, since
 * {@code conversationOutputs} feeds {@code ConversationHistoryBuilder}, from
 * the model's own history too. Asked afterwards what word it had just said, the
 * agent answered with its greeting.
 */
@DisplayName("redo cache survives a store round-trip with its output")
class RedoCacheOutputRoundTripTest {

    private static ConversationMemory memoryWithUndoneTurn() {
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        memory.getCurrentStep().storeData(new Data<>("input", "Say the single word BANANA."));
        memory.getCurrentStep().getConversationOutput().put("input", "Say the single word BANANA.");

        memory.startNextStep();
        memory.getCurrentStep().storeData(new Data<>("output", "BANANA."));
        memory.getCurrentStep().getConversationOutput().put("output", List.of(Map.of("text", "BANANA.")));

        memory.undoLastStep();
        return memory;
    }

    @Test
    @DisplayName("snapshot → memory → redo restores the turn's output, not an empty one")
    void redoAfterRoundTripKeepsTheAnswer() {
        var snapshot = ConversationMemoryUtilities.convertConversationMemory(memoryWithUndoneTurn());

        assertEquals(1, snapshot.getRedoCache().size(), "the undone step belongs in the redo cache");
        assertNotNull(snapshot.getRedoCache().peek().getConversationOutput(),
                "the redo entry must carry its own output — it is not in conversationOutputs, undo popped it");

        var restored = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);
        assertTrue(restored.isRedoAvailable());

        restored.redoLastStep();

        var outputs = restored.getConversationOutputs();
        assertEquals(2, outputs.size());
        assertEquals(List.of(Map.of("text", "BANANA.")), outputs.get(outputs.size() - 1).get("output"),
                "redo restored the step but blanked its answer");
    }

    /**
     * Two undone turns must redo in order, each with its own answer. If the cache's
     * order flipped anywhere across the round-trip, redo would restore the WRONG
     * turn's output — silently, since both steps and both outputs exist.
     */
    @Test
    @DisplayName("two undone turns redo in order, each with its own output")
    void multiEntryRedoPreservesOrderAndOutputs() {
        var memory = new ConversationMemory("conv-multi", "agent-1", 1, "user-1");
        memory.getCurrentStep().getConversationOutput().put("input", "start");

        memory.startNextStep();
        memory.getCurrentStep().getConversationOutput().put("output", List.of(Map.of("text", "FIRST.")));
        memory.startNextStep();
        memory.getCurrentStep().getConversationOutput().put("output", List.of(Map.of("text", "SECOND.")));

        memory.undoLastStep();
        memory.undoLastStep();

        var roundTripped = ConversationMemoryUtilities.convertConversationMemorySnapshot(
                ConversationMemoryUtilities.convertConversationMemory(memory));

        roundTripped.redoLastStep();
        assertEquals(List.of(Map.of("text", "FIRST.")),
                roundTripped.getConversationOutputs().getLast().get("output"),
                "the first redo must restore the FIRST undone turn's answer");

        roundTripped.redoLastStep();
        assertEquals(List.of(Map.of("text", "SECOND.")),
                roundTripped.getConversationOutputs().getLast().get("output"),
                "the second redo must restore the SECOND turn's answer");
    }

    @Test
    @DisplayName("ordinary conversation steps carry no output — it lives in conversationOutputs")
    void ordinaryStepsAreUnchanged() {
        var memory = new ConversationMemory("conv-2", "agent-1", 1, "user-1");
        memory.getCurrentStep().storeData(new Data<>("input", "hello"));

        var snapshot = ConversationMemoryUtilities.convertConversationMemory(memory);

        assertEquals(1, snapshot.getConversationSteps().size());
        assertNull(snapshot.getConversationSteps().getFirst().getConversationOutput(),
                "duplicating the output onto every step would double the stored document for no gain");
    }

    @Test
    @DisplayName("a legacy redo entry with no stored output still loads")
    void legacyDocumentsLoadUnchanged() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId("conv-3");
        snapshot.setAgentId("agent-1");
        snapshot.setAgentVersion(1);
        snapshot.setUserId("user-1");
        // Written before the field existed: conversationOutput is absent, so null.
        snapshot.getRedoCache().push(new ConversationMemorySnapshot.ConversationStepSnapshot());

        var restored = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);

        assertTrue(restored.isRedoAvailable());
        restored.redoLastStep();
        assertNotNull(restored.getConversationOutputs().getLast());
    }
}
