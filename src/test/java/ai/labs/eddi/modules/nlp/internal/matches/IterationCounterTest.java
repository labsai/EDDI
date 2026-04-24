/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal.matches;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IterationCounter Tests")
class IterationCounterTest {

    @Nested
    @DisplayName("Basic iteration")
    class BasicTests {

        @Test
        @DisplayName("single input, single result — yields one plan")
        void singleInputSingleResult() {
            // 1 input term, resultLengths[0]=0 means 1 result (0-indexed max)
            var counter = new IterationCounter(1, new Integer[]{0});
            assertTrue(counter.hasNext());

            var plan = counter.next();
            assertNotNull(plan);
            assertArrayEquals(new Integer[]{0}, plan.getIndexes());
        }

        @Test
        @DisplayName("single input, two results — yields multiple plans")
        void singleInputTwoResults() {
            // 1 input term, resultLengths[0]=1 means 2 results (indices 0,1)
            var counter = new IterationCounter(1, new Integer[]{1});
            var plans = new ArrayList<IterationCounter.IterationPlan>();
            while (counter.hasNext()) {
                plans.add(counter.next());
            }
            assertTrue(plans.size() >= 2);
        }

        @Test
        @DisplayName("two inputs, one result each — yields combinatorial plans")
        void twoInputsOneResultEach() {
            var counter = new IterationCounter(2, new Integer[]{0, 0});
            var plans = new ArrayList<IterationCounter.IterationPlan>();
            while (counter.hasNext()) {
                plans.add(counter.next());
            }
            // At minimum should have 1 plan [0,0]
            assertTrue(plans.size() >= 1);
            assertArrayEquals(new Integer[]{0, 0}, plans.get(0).getIndexes());
        }

        @Test
        @DisplayName("two inputs, two results each — yields multiple permutations")
        void twoInputsTwoResults() {
            var counter = new IterationCounter(2, new Integer[]{1, 1});
            var plans = new ArrayList<IterationCounter.IterationPlan>();
            while (counter.hasNext()) {
                plans.add(counter.next());
            }
            // Should have multiple combinations
            assertTrue(plans.size() >= 2);
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("zero input length — no iterations")
        void zeroInputLength() {
            var counter = new IterationCounter(0, new Integer[]{});
            assertFalse(counter.hasNext());
        }

        @Test
        @DisplayName("next() throws when exhausted")
        void nextThrowsWhenExhausted() {
            var counter = new IterationCounter(1, new Integer[]{0});
            counter.next(); // consume the only plan
            assertThrows(NoSuchElementException.class, counter::next);
        }
    }

    @Nested
    @DisplayName("IterationPlan")
    class IterationPlanTests {

        @Test
        @DisplayName("getIndexes exposes internal array (mutation visible)")
        void getIndexesExposesInternalArray() {
            var counter = new IterationCounter(2, new Integer[]{0, 0});
            var plan = counter.next();
            Integer[] indexes = plan.getIndexes();

            // IterationPlan stores its own copy at construction time,
            // but getIndexes() returns the stored array directly — not a copy.
            indexes[0] = 99;
            assertEquals(99, plan.getIndexes()[0],
                    "getIndexes() returns the internal array, so mutations are visible");
        }

        @Test
        @DisplayName("plans with same indexes are equal")
        void plansEqual() {
            var counter = new IterationCounter(1, new Integer[]{0});
            var plan1 = counter.next();

            // Create another counter to get same plan
            var counter2 = new IterationCounter(1, new Integer[]{0});
            var plan2 = counter2.next();

            // Both should have index [0]
            assertArrayEquals(plan1.getIndexes(), plan2.getIndexes());
        }
    }
}
