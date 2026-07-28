/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal.matches;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Permutation Tests")
class PermutationTest {

    /**
     * Upper bound on how many permutations a single count walks, to keep the tests
     * fast.
     */
    private static final int CAP = 1_000;

    /**
     * Largest input size exercised — must stay above the point where {@code n!}
     * overflows an int.
     */
    private static final int MAX_SIZE = 18;

    @Nested
    @DisplayName("Single element")
    class SingleElementTests {

        @Test
        @DisplayName("single element yields one permutation")
        void singleElement() {
            var perm = new Permutation(new Integer[]{1});
            var results = collect(perm);
            assertEquals(1, results.size());
            assertArrayEquals(new Integer[]{1}, results.get(0));
        }
    }

    @Nested
    @DisplayName("Two elements")
    class TwoElementTests {

        @Test
        @DisplayName("two distinct elements yield 2 permutations")
        void twoDistinct() {
            var perm = new Permutation(new Integer[]{1, 2});
            var results = collect(perm);
            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("permutations contain both orderings")
        void bothOrderings() {
            var perm = new Permutation(new Integer[]{1, 2});
            var results = collect(perm);

            boolean has12 = results.stream().anyMatch(a -> a[0].intValue() == 1 && a[1].intValue() == 2);
            boolean has21 = results.stream().anyMatch(a -> a[0].intValue() == 2 && a[1].intValue() == 1);
            assertTrue(has12, "Should contain [1,2]");
            assertTrue(has21, "Should contain [2,1]");
        }
    }

    @Nested
    @DisplayName("Three elements")
    class ThreeElementTests {

        @Test
        @DisplayName("three distinct elements yield 6 permutations")
        void threeDistinct() {
            var perm = new Permutation(new Integer[]{1, 2, 3});
            var results = collect(perm);
            assertEquals(6, results.size());
        }

        @Test
        @DisplayName("all permutations are unique")
        void allUnique() {
            var perm = new Permutation(new Integer[]{1, 2, 3});
            var results = collect(perm);
            var strings = new HashSet<String>();
            for (var arr : results) {
                strings.add(arr[0] + "," + arr[1] + "," + arr[2]);
            }
            assertEquals(6, strings.size());
        }
    }

    @Nested
    @DisplayName("Unsorted input")
    class UnsortedTests {

        @Test
        @DisplayName("unsorted input is sorted before permuting")
        void unsortedInput() {
            var perm = new Permutation(new Integer[]{3, 1, 2});
            var results = collect(perm);
            // First permutation should be sorted: [1, 2, 3]
            assertArrayEquals(new Integer[]{1, 2, 3}, results.get(0));
            assertEquals(6, results.size());
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("next() throws NoSuchElementException when exhausted")
        void throwsWhenExhausted() {
            var perm = new Permutation(new Integer[]{1});
            var it = perm.iterator();
            it.next(); // consume only permutation
            assertThrows(NoSuchElementException.class, it::next);
        }

        @Test
        @DisplayName("for-each loop works")
        void forEachLoop() {
            int count = 0;
            for (Integer[] p : new Permutation(new Integer[]{1, 2})) {
                assertNotNull(p);
                count++;
            }
            assertEquals(2, count);
        }

        @Test
        @DisplayName("returned arrays are copies")
        void returnsCopies() {
            var perm = new Permutation(new Integer[]{1, 2});
            var it = perm.iterator();
            var first = it.next();
            var second = it.next();
            // Mutating first should not affect second
            first[0] = 99;
            assertNotEquals(99, second[0]);
        }

        @Test
        @DisplayName("duplicate values — still generates expected count")
        void duplicateValues() {
            var perm = new Permutation(new Integer[]{1, 1});
            var results = collect(perm);
            // With duplicates there is only one distinct ordering
            assertEquals(1, results.size());
        }

        @Test
        @DisplayName("empty input yields a single empty permutation")
        void emptyInput() {
            var results = collect(new Permutation(new Integer[0]));
            assertEquals(1, results.size());
            assertEquals(0, results.get(0).length);
        }
    }

    @Nested
    @DisplayName("Large inputs")
    class LargeInputTests {

        @Test
        @DisplayName("permutation count is monotonic non-decreasing in input size")
        void countIsMonotonicInInputSize() {
            long previous = 0;
            for (int size = 1; size <= MAX_SIZE; size++) {
                long count = countUpTo(size, CAP);
                assertTrue(count >= previous, "Permutation count dropped from " + previous + " (size " + (size - 1) + ") to " + count
                        + " (size " + size + ") — longer input must never yield fewer permutations");
                previous = count;
            }
        }

        @Test
        @DisplayName("17 elements still yield permutations (factorial counter no longer overflows)")
        void largeInputStillYieldsPermutations() {
            assertEquals(CAP, countUpTo(17, CAP));
        }
    }

    /**
     * Counts the permutations produced for an input of the given size, stopping
     * once {@code cap} permutations have been seen so the test stays fast.
     */
    private static long countUpTo(int size, int cap) {
        var values = new Integer[size];
        for (int i = 0; i < size; i++) {
            values[i] = i;
        }

        long count = 0;
        for (Integer[] permutation : new Permutation(values)) {
            assertEquals(size, permutation.length);
            if (++count == cap) {
                break;
            }
        }

        return count;
    }

    private List<Integer[]> collect(Permutation perm) {
        var results = new ArrayList<Integer[]>();
        perm.iterator().forEachRemaining(results::add);
        return results;
    }
}
