/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal.matches;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link IterationCounter} de-duplicates the plans it hands out. That check
 * runs once per generated plan, so {@link IterationCounter.IterationPlan} has
 * to be usable as a hash key — which requires content based equality, not
 * reference equality on the index array.
 */
@DisplayName("IterationCounter.IterationPlan — content based equality")
class IterationPlanEqualityTest {

    @Test
    @DisplayName("plans holding the same indexes are equal and hash alike")
    void plansWithSameIndexesAreEqual() {
        var first = new IterationCounter(2, new Integer[]{0, 0}).next();
        var second = new IterationCounter(2, new Integer[]{0, 0}).next();

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());

        Set<IterationCounter.IterationPlan> plans = new HashSet<>();
        plans.add(first);
        plans.add(second);
        assertEquals(1, plans.size(), "equal plans must collapse to a single hash set entry");
    }

    @Test
    @DisplayName("plans holding different indexes are not equal")
    void plansWithDifferentIndexesAreNotEqual() {
        var counter = new IterationCounter(2, new Integer[]{1, 1});
        var first = counter.next();
        var second = counter.next();

        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("every plan of a full enumeration is unique")
    void enumerationYieldsOnlyUniquePlans() {
        var counter = new IterationCounter(3, new Integer[]{2, 2, 2});

        Set<IterationCounter.IterationPlan> plans = new HashSet<>();
        int produced = 0;
        while (counter.hasNext()) {
            plans.add(counter.next());
            produced++;
        }

        assertEquals(27, produced);
        assertEquals(produced, plans.size(), "the counter must never hand out the same plan twice");
    }
}
