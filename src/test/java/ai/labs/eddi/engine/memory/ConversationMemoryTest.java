/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.ConversationState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;

/**
 * @author ginccc
 */
@DisplayName("ConversationMemory")
public class ConversationMemoryTest {
    private static ConversationMemory memory;

    @BeforeEach
    public void setUp() {
        memory = new ConversationMemory("", 0);
    }

    @Test
    public void testInitialization() {
        // assert
        Assertions.assertNotNull(memory.getCurrentStep());
        Assertions.assertEquals(0, memory.getPreviousSteps().size());
    }

    @Test
    public void testQueryOrder() {
        // setup
        IConversationMemory.IWritableConversationStep step1 = memory.getCurrentStep();
        IConversationMemory.IConversationStep step2 = memory.startNextStep();
        IConversationMemory.IConversationStep step3 = memory.startNextStep();

        // test
        IConversationMemory.IConversationStepStack steps = memory.getAllSteps();

        // assert
        Assertions.assertSame(step3, steps.get(0));
        Assertions.assertSame(step2, steps.get(1));
        Assertions.assertSame(step1, steps.get(2));
    }

    @Test
    public void testStartNextStep() {
        // setup
        IConversationMemory.IWritableConversationStep entry = memory.getCurrentStep();

        final Data<LinkedList<Object>> data = new Data<>("testkey", new LinkedList<>());
        entry.storeData(data);

        // test
        memory.startNextStep();

        // assert
        Assertions.assertEquals(2, memory.size());
        Assertions.assertEquals(1, memory.getPreviousSteps().size());
        Assertions.assertSame(entry, memory.getPreviousSteps().get(0));
    }

    @Test
    public void testUndo() {
        // setup
        IConversationMemory.IWritableConversationStep entry = memory.getCurrentStep();
        final Data<LinkedList<Object>> data = new Data<>("testkey", new LinkedList<>());
        entry.storeData(data);
        memory.startNextStep();

        // test
        memory.undoLastStep();

        // assert
        Assertions.assertEquals(1, memory.size());
        Assertions.assertTrue(memory.isRedoAvailable());
    }

    @Test
    public void testRedo() {
        // setup
        memory.startNextStep();

        IConversationMemory.IWritableConversationStep entry = memory.getCurrentStep();
        final Data<LinkedList<Object>> data = new Data<>("testkey", new LinkedList<>());
        entry.storeData(data);

        memory.undoLastStep();

        // test
        memory.redoLastStep();

        // assert
        Assertions.assertEquals(2, memory.size());
        Assertions.assertNotNull(memory.getCurrentStep().getData("testkey"));
        Assertions.assertFalse(memory.isRedoAvailable());

    }

    /**
     * M-E3: a new turn after an undo abandons the undone step. Keeping it in the
     * redo cache let a later redo push it on top of the new turn — a reply to input
     * the user withdrew, spliced after an answer that was never built on it.
     */
    @Test
    @DisplayName("M-E3: undo → new turn → redo is no longer available")
    public void testNewTurnClearsRedo() {
        memory.startNextStep();
        memory.getCurrentStep().storeData(new Data<>("withdrawn", "yes"));
        memory.undoLastStep();
        Assertions.assertTrue(memory.isRedoAvailable());

        memory.startNextStep();

        Assertions.assertFalse(memory.isRedoAvailable(), "a new turn must abandon the undone step");
        Assertions.assertThrows(IllegalStateException.class, () -> memory.redoLastStep());
    }

    /**
     * Loading a conversation rebuilds it step by step through the package-private
     * overload; that must not discard the redo cache the document holds.
     */
    @Test
    @DisplayName("M-E3: loading a conversation keeps its persisted redo cache")
    public void testLoadKeepsRedoCache() {
        memory.startNextStep();
        memory.getCurrentStep().storeData(new Data<>("undone", "yes"));
        memory.undoLastStep();

        var reloaded = ConversationMemoryUtilities.convertConversationMemorySnapshot(
                ConversationMemoryUtilities.convertConversationMemory(memory));

        Assertions.assertTrue(reloaded.isRedoAvailable(), "a loaded conversation must keep what it can still redo");
    }

    @Test
    public void testUndoThrowsWhenNoSteps() {
        Assertions.assertThrows(IllegalStateException.class, () -> memory.undoLastStep());
    }

    @Test
    public void testRedoThrowsWhenNoCache() {
        Assertions.assertThrows(IllegalStateException.class, () -> memory.redoLastStep());
    }

    @Test
    public void testConversationState() {
        memory.setConversationState(ConversationState.IN_PROGRESS);
        Assertions.assertEquals(ConversationState.IN_PROGRESS, memory.getConversationState());
    }

    @Test
    public void testUserIdAndConversationId() {
        var memoryFull = new ConversationMemory("conv-123", "agent-1", 5, "user-42");
        Assertions.assertEquals("conv-123", memoryFull.getConversationId());
        Assertions.assertEquals("agent-1", memoryFull.getAgentId());
        Assertions.assertEquals(5, memoryFull.getAgentVersion());
        Assertions.assertEquals("user-42", memoryFull.getUserId());
    }

    @Test
    public void testConversationOutputs() {
        // initial step has 1 output
        Assertions.assertEquals(1, memory.getConversationOutputs().size());

        memory.startNextStep();
        Assertions.assertEquals(2, memory.getConversationOutputs().size());
    }

    @Test
    public void testConversationProperties() {
        var props = memory.getConversationProperties();
        Assertions.assertNotNull(props);
    }

    @Test
    public void testRedoCache() {
        // Initially empty
        Assertions.assertTrue(memory.getRedoCache().isEmpty());

        memory.startNextStep();
        memory.undoLastStep();
        Assertions.assertFalse(memory.getRedoCache().isEmpty());
    }

    @Test
    public void testAllStepsIncludesCurrentStep() {
        Assertions.assertEquals(1, memory.getAllSteps().size());
        memory.startNextStep();
        Assertions.assertEquals(2, memory.getAllSteps().size());
    }

    @Test
    public void testIsUndoAvailable() {
        Assertions.assertFalse(memory.isUndoAvailable());
        memory.startNextStep();
        Assertions.assertTrue(memory.isUndoAvailable());
    }

    @Test
    public void testConversationStepStackLatestData() {
        memory.getCurrentStep().storeData(new Data<>("input", "hello"));
        memory.startNextStep();
        memory.getCurrentStep().storeData(new Data<>("input", "world"));

        // getAllSteps searches most recent first
        var allSteps = memory.getAllSteps();
        IData<String> latest = allSteps.getLatestData("input");
        Assertions.assertNotNull(latest);
        Assertions.assertEquals("world", latest.getResult());
    }

    @Test
    public void testConversationStepStackGetByIndex() {
        memory.getCurrentStep().storeData(new Data<>("input", "first"));
        memory.startNextStep();
        memory.getCurrentStep().storeData(new Data<>("input", "second"));

        var allSteps = memory.getAllSteps();
        // index 0 = most recent
        IData<String> mostRecent = allSteps.get(0).getData("input");
        Assertions.assertEquals("second", mostRecent.getResult());
    }
}
