/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal.matches;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Enumerates every combination of per-token match indexes — the cartesian
 * product the parser searches for phrases — <em>best first</em>.
 * <p>
 * A plan holds, for each input token, the index of the dictionary match chosen
 * for it. Index {@code 0} is the token's best match (the dictionaries and
 * corrections report their matches in order of preference), so the plans are
 * handed out by increasing <em>total rank</em>, the sum of the indexes: first
 * the plan that takes every token's best match, then every plan that takes one
 * second-best match, and so on. Plans of equal rank come in ascending
 * lexicographic order, which varies the last token first.
 * <p>
 * Every plan is in range for every token and is handed out exactly once. The
 * previous implementation walked permutations of index multisets and capped
 * each index by the <em>last</em> token's match count; when an earlier token
 * had more matches than the last one, its alternatives were never tried (two
 * tokens with two and one matches yielded one plan instead of two), while plans
 * out of range for some token were generated and thrown away by the caller. A
 * typo in the first word of a phrase, whose phrase reading was the correction's
 * second candidate, therefore never matched the phrase.
 * <p>
 * The iterator keeps one array and computes each successor in time linear in
 * the number of tokens; it allocates nothing but the plans it returns.
 */
public class IterationCounter implements Iterator<IterationCounter.IterationPlan> {
    private final int length;
    private final int[] maxIndexes;
    private final int maxRank;
    private final int[] current;
    private int rank;
    private boolean hasNext;

    /**
     * @param inputLength
     *            number of input tokens
     * @param resultLengths
     *            for each token, the highest valid match index (the number of
     *            matches minus one); a negative value means the token has no match
     *            and therefore no plan exists
     */
    public IterationCounter(Integer inputLength, Integer[] resultLengths) {
        this.length = inputLength;
        this.maxIndexes = new int[length];
        this.current = new int[length];
        int capacity = 0;
        boolean anyTokenWithoutMatch = false;
        for (int i = 0; i < length; i++) {
            int max = resultLengths[i] == null ? -1 : resultLengths[i];
            if (max < 0) {
                anyTokenWithoutMatch = true;
            }
            maxIndexes[i] = Math.max(0, max);
            capacity += maxIndexes[i];
        }
        this.maxRank = capacity;
        this.rank = 0;
        this.hasNext = length > 0 && !anyTokenWithoutMatch;
    }

    @Override
    public boolean hasNext() {
        return hasNext;
    }

    @Override
    public IterationPlan next() {
        if (!hasNext) {
            throw new NoSuchElementException();
        }
        IterationPlan plan = new IterationPlan(current);
        hasNext = advance();
        return plan;
    }

    /**
     * Moves {@link #current} to the next plan: the lexicographic successor with the
     * same rank, or — when there is none — the lexicographically smallest plan of
     * the next rank.
     */
    private boolean advance() {
        // Rightmost position that can take one unit from the positions after it:
        // that keeps the longest common prefix, i.e. gives the immediate successor.
        int tail = 0;
        for (int i = length - 1; i >= 0; i--) {
            if (tail > 0 && current[i] < maxIndexes[i]) {
                current[i]++;
                fillSmallest(i + 1, tail - 1);
                return true;
            }
            tail += current[i];
        }

        if (rank >= maxRank) {
            return false;
        }
        rank++;
        fillSmallest(0, rank);
        return true;
    }

    /**
     * Distributes {@code amount} over {@code current[from..]} so that the suffix is
     * lexicographically smallest: as much as each position takes, from the right.
     */
    private void fillSmallest(int from, int amount) {
        for (int j = length - 1; j >= from; j--) {
            int take = Math.min(maxIndexes[j], amount);
            current[j] = take;
            amount -= take;
        }
    }

    public static class IterationPlan {
        private final Integer[] indexes;

        private IterationPlan(int[] indexes) {
            this.indexes = new Integer[indexes.length];
            for (int i = 0; i < indexes.length; i++) {
                this.indexes[i] = indexes[i];
            }
        }

        public Integer[] getIndexes() {
            return indexes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            IterationPlan that = (IterationPlan) o;
            return Arrays.equals(indexes, that.indexes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(indexes);
        }

        @Override
        public String toString() {
            return Arrays.toString(indexes);
        }
    }
}
