/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStep;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;

/**
 * The execution-id mechanics, driven through the memory operations
 * {@code Conversation} performs: a turn stamps its step at the end, a resume or
 * rerun stamps the same step again, a say after an undo starts a new step.
 */
@DisplayName("TurnExecutions — one id per turn execution")
class TurnExecutionsTest {

    /** What {@code Conversation.runStep} does for a say. */
    private static List<String> say(ConversationMemory memory) {
        List<String> discarded = TurnExecutions.executionIdsOf(memory.getRedoCache());
        memory.startNextStep();
        TurnExecutions.stamp(memory.getCurrentStep(), discarded);
        return discarded;
    }

    @Test
    @DisplayName("the first execution mints an id; a HITL resume or rerun of the same step keeps it")
    void resumeKeepsTheId() {
        ConversationMemory memory = new ConversationMemory("c", "a", 1, "u");
        TurnExecutions.stamp(memory.getCurrentStep(), null);
        String first = TurnExecutions.executionId(memory.getCurrentStep());
        assertNotNull(first);

        // The resume (or a rerun) ends the same step again.
        TurnExecutions.stamp(memory.getCurrentStep(), null);
        assertEquals(first, TurnExecutions.executionId(memory.getCurrentStep()));
    }

    @Test
    @DisplayName("a say after an undo runs at the same step index with a new id and lists the undone one")
    void undoThenSay() {
        ConversationMemory memory = new ConversationMemory("c", "a", 1, "u");
        TurnExecutions.stamp(memory.getCurrentStep(), null);
        say(memory);
        String undone = TurnExecutions.executionId(memory.getCurrentStep());
        int index = memory.size() - 1;

        memory.undoLastStep();
        List<String> discarded = say(memory);

        String replacing = TurnExecutions.executionId(memory.getCurrentStep());
        assertEquals(index, memory.size() - 1, "the same step index");
        assertNotEquals(undone, replacing);
        assertEquals(List.of(undone), discarded);
        assertEquals(List.of(undone), TurnExecutions.discardedBy(memory.getCurrentStep()));
    }

    @Test
    @DisplayName("two undos then a say discard both; a plain say discards nothing")
    void multipleUndos() {
        ConversationMemory memory = new ConversationMemory("c", "a", 1, "u");
        TurnExecutions.stamp(memory.getCurrentStep(), null);
        say(memory);
        String one = TurnExecutions.executionId(memory.getCurrentStep());
        say(memory);
        String two = TurnExecutions.executionId(memory.getCurrentStep());
        memory.undoLastStep();
        memory.undoLastStep();

        List<String> discarded = say(memory);

        assertEquals(2, discarded.size());
        assertTrue(discarded.containsAll(List.of(one, two)));
        assertTrue(say(memory).isEmpty());
        assertTrue(TurnExecutions.discardedBy(memory.getCurrentStep()).isEmpty());
    }

    @Test
    @DisplayName("undo then redo keeps the step and its id — nothing is discarded")
    void undoRedo() {
        ConversationMemory memory = new ConversationMemory("c", "a", 1, "u");
        TurnExecutions.stamp(memory.getCurrentStep(), null);
        say(memory);
        String id = TurnExecutions.executionId(memory.getCurrentStep());
        memory.undoLastStep();
        memory.redoLastStep();
        assertEquals(id, TurnExecutions.executionId(memory.getCurrentStep()));
    }

    @Test
    @DisplayName("a step without an id has none; an id-less undone step is not listed")
    void legacy() {
        ConversationMemory memory = new ConversationMemory("c", "a", 1, "u");
        memory.startNextStep();
        assertEquals(null, TurnExecutions.executionId(memory.getCurrentStep()));
        memory.undoLastStep();
        assertTrue(TurnExecutions.executionIdsOf(memory.getRedoCache()).isEmpty());
        assertTrue(TurnExecutions.executionIdsOf(null).isEmpty());
    }

    @Test
    @DisplayName("an existing discarded list is never overwritten, and the list is capped")
    void discardedListStable() {
        ConversationMemory memory = new ConversationMemory("c", "a", 1, "u");
        TurnExecutions.stamp(memory.getCurrentStep(), List.of("x"));
        TurnExecutions.stamp(memory.getCurrentStep(), List.of("y"));
        assertEquals(List.of("x"), TurnExecutions.discardedBy(memory.getCurrentStep()));

        List<IConversationStep> many = new ArrayList<>();
        IntStream.range(0, 150).forEach(i -> {
            var step = new ConversationMemory("c", "a", 1, "u").getCurrentStep();
            step.storeData(new Data<>(MemoryKeys.HEALTH_EXECUTION_ID, "id-" + i));
            many.add(step);
        });
        assertEquals(TurnExecutions.MAX_DISCARDED, TurnExecutions.executionIdsOf(many).size());
    }

    @Test
    @DisplayName("a step that refuses the write never fails the turn")
    void neverThrows() {
        IWritableConversationStep step = mock(IWritableConversationStep.class);
        doThrow(new IllegalStateException("boom")).when(step).storeData(any());
        assertDoesNotThrow(() -> TurnExecutions.stamp(step, List.of("x")));
        assertDoesNotThrow(() -> TurnExecutions.stamp(null, null));
    }
}
