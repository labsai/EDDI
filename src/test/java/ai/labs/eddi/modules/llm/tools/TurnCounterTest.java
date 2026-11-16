/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link TurnCounter}: the count lives on the current step, so
 * two counters for one key in one turn share it and the next step starts from
 * zero.
 */
class TurnCounterTest {

    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("aabbccddeeff112233445566", "agent-1", 1, "user-1");
    }

    @Test
    void startsAtZero() {
        assertEquals(0, TurnCounter.onStep(memory, "tools:test").get());
    }

    @Test
    void incrementIsStoredOnTheCurrentStep() {
        var counter = TurnCounter.onStep(memory, "tools:test");

        assertEquals(1, counter.increment());
        assertEquals(2, counter.increment());

        assertEquals(2, (Integer) memory.getCurrentStep().getData("tools:test").getResult());
    }

    @Test
    void twoCountersForOneKeyShareTheCount() {
        TurnCounter.onStep(memory, "tools:test").increment();

        var later = TurnCounter.onStep(memory, "tools:test");

        assertEquals(1, later.get());
        assertEquals(2, later.increment());
    }

    @Test
    void theNextStepStartsAgainFromZero() {
        var counter = TurnCounter.onStep(memory, "tools:test");
        counter.increment();

        memory.startNextStep();

        assertEquals(0, counter.get(), "the counter reads whichever step is current");
        assertEquals(0, TurnCounter.onStep(memory, "tools:test").get());
    }

    @Test
    void keysAreMatchedExactly_notByPrefix() {
        TurnCounter.onStep(memory, "tools:delegations:ab").increment();

        assertEquals(0, TurnCounter.onStep(memory, "tools:delegations:a").get());
    }

    @Test
    void anyStoredNumberIsRead() {
        memory.getCurrentStep().storeData(new Data<>("tools:test", 4L));

        var counter = TurnCounter.onStep(memory, "tools:test");

        assertEquals(4, counter.get());
        assertEquals(5, counter.increment());
    }

    @Test
    void withoutACurrentStep_countsOnTheInstance() {
        var stepless = mock(IConversationMemory.class);
        var counter = TurnCounter.onStep(stepless, "tools:test");

        assertEquals(1, counter.increment());
        assertEquals(1, counter.get());
    }

    @Test
    void local_countsOnTheInstanceOnly() {
        var first = TurnCounter.local();
        var second = TurnCounter.local();

        first.increment();

        assertEquals(1, first.get());
        assertEquals(0, second.get());
    }

    @Test
    void onStep_requiresMemoryAndKey() {
        assertThrows(NullPointerException.class, () -> TurnCounter.onStep(null, "tools:test"));
        assertThrows(NullPointerException.class, () -> TurnCounter.onStep(memory, null));
    }
}
