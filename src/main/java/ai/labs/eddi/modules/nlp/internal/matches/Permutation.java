/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal.matches;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * @author ginccc
 */
public class Permutation implements Iterable<Integer[]> {
    private Integer[] values;

    public Permutation(Integer[] values) {
        this.values = values;
    }

    @Override
    public Iterator<Integer[]> iterator() {
        return new PermutationIterator(values);
    }

    public class PermutationIterator implements Iterator<Integer[]> {
        private Integer[] values;

        private Integer[] next = null;

        PermutationIterator(Integer[] values) {
            this.values = Arrays.copyOf(values, values.length);

            Arrays.sort(this.values);

            next = this.values;
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public Integer[] next() {
            Integer[] ret = null;
            if (next != null) {
                ret = new Integer[next.length];
                System.arraycopy(next, 0, ret, 0, next.length);
            }

            next = calculateNext();

            if (ret != null) {
                return ret;
            }

            throw new NoSuchElementException();
        }

        private Integer[] calculateNext() {
            int firstNonDecreasingIndex = -1;
            int swapPoint = -1;

            // from the end, find the first index where values[i - 1] < values[i]
            for (int i = values.length - 1; i > 0; i--) {
                if (values[i - 1].compareTo(values[i]) < 0) {
                    firstNonDecreasingIndex = i - 1;
                    break;
                }
            }

            // No pivot: the sequence is fully descending (the last permutation), or it is
            // too short to reorder at all. Either way there is no successor permutation.
            // Returning here also keeps the index arithmetic below in range.
            if (firstNonDecreasingIndex < 0) {
                return null;
            }

            for (int i = values.length - 1; i > firstNonDecreasingIndex; i--) {
                if (values[firstNonDecreasingIndex].compareTo(values[i]) < 0) {
                    swapPoint = i;
                    break;
                } // finding the first numthat arrayToPermute[swapPoint]>arrayToPermute[index]
            }
            Integer tmp = values[firstNonDecreasingIndex];
            values[firstNonDecreasingIndex] = values[swapPoint];
            values[swapPoint] = tmp;// swap arrayToPermute[index], arrayToPermute[swapPoint]

            // swap the index+1...end sequences
            for (int i = 0; i < (values.length - 1 - firstNonDecreasingIndex) / 2; i++) {
                tmp = values[firstNonDecreasingIndex + 1 + i];
                values[firstNonDecreasingIndex + 1 + i] = values[values.length - 1 - i];
                values[values.length - 1 - i] = tmp;
            }

            return values;
        }
    }
}
