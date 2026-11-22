/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.matches;

import ai.labs.eddi.modules.nlp.internal.matches.IterationCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author ginccc
 */
public class IterationCounterTest {

    private static List<String> enumerate(Integer... maxIndexes) {
        var counter = new IterationCounter(maxIndexes.length, maxIndexes);
        List<String> plans = new ArrayList<>();
        while (counter.hasNext()) {
            plans.add(Arrays.toString(counter.next().getIndexes()));
        }
        return plans;
    }

    @Test
    @DisplayName("plans come by increasing total rank, equal ranks in ascending lexicographic order")
    public void testCreateIterationPlan() {
        assertEquals(List.of("[0, 0, 0]",
                "[0, 0, 1]", "[0, 1, 0]", "[1, 0, 0]",
                "[0, 0, 2]", "[0, 1, 1]", "[0, 2, 0]", "[1, 0, 1]", "[1, 1, 0]", "[2, 0, 0]",
                "[0, 1, 2]", "[0, 2, 1]", "[1, 0, 2]", "[1, 1, 1]", "[1, 2, 0]", "[2, 0, 1]", "[2, 1, 0]",
                "[0, 2, 2]", "[1, 1, 2]", "[1, 2, 1]", "[2, 0, 2]", "[2, 1, 1]", "[2, 2, 0]",
                "[1, 2, 2]", "[2, 1, 2]", "[2, 2, 1]",
                "[2, 2, 2]"), enumerate(2, 2, 2));
    }

    static Stream<Arguments> uneven() {
        return Stream.of(Arguments.of((Object) new Integer[]{1, 0}), Arguments.of((Object) new Integer[]{0, 1}),
                Arguments.of((Object) new Integer[]{2, 0, 0}), Arguments.of((Object) new Integer[]{0, 0, 2}),
                Arguments.of((Object) new Integer[]{3, 0, 1, 0}), Arguments.of((Object) new Integer[]{0, 4, 0, 2, 1}),
                Arguments.of((Object) new Integer[]{0}), Arguments.of((Object) new Integer[]{5}));
    }

    /**
     * Regression: the permutation-based counter capped every index by the LAST
     * token's match count, so {@code [1, 0]} produced only {@code [0, 0]} — the
     * first token's second match was never tried — while {@code [0, 1]} produced
     * the out-of-range plan {@code [1, 0]}.
     */
    @ParameterizedTest
    @MethodSource("uneven")
    @DisplayName("every in-range combination exactly once, nothing out of range, for uneven match counts")
    void completeAndInRange(Integer[] maxIndexes) {
        List<String> plans = enumerate(maxIndexes);
        int expected = Arrays.stream(maxIndexes).mapToInt(max -> max + 1).reduce(1, (a, b) -> a * b);

        assertEquals(expected, plans.size());
        assertEquals(expected, new HashSet<>(plans).size(), "no plan may be handed out twice");

        var counter = new IterationCounter(maxIndexes.length, maxIndexes);
        int previousRank = -1;
        while (counter.hasNext()) {
            Integer[] plan = counter.next().getIndexes();
            int rank = 0;
            for (int i = 0; i < plan.length; i++) {
                assertTrue(plan[i] >= 0 && plan[i] <= maxIndexes[i], "plan " + Arrays.toString(plan) + " is out of range");
                rank += plan[i];
            }
            assertTrue(rank >= previousRank, "plans must come best first");
            previousRank = rank;
        }
    }

    @Test
    @DisplayName("a token without any match (negative max index) means there is no plan")
    void tokenWithoutMatch() {
        assertFalse(new IterationCounter(2, new Integer[]{1, -1}).hasNext());
    }
}
