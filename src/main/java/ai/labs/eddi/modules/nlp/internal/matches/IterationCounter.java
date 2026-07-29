/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal.matches;

import java.util.*;

/**
 * @author ginccc
 */
public class IterationCounter implements Iterator<IterationCounter.IterationPlan> {
    private IterationPlan next;
    private Integer[] indexes;
    private Iterator<Integer[]> permutationIterator;
    /**
     * Membership set of the plans handed out so far. A list here meant every
     * duplicate check was a linear scan, making the enumeration quadratic in the
     * number of plans; {@link IterationPlan} hashes and compares by index content,
     * so a hash set gives the same answer in constant time.
     */
    private final Set<IterationPlan> previousIterationPlans = new HashSet<>();
    private Integer inputLength;
    private Integer[] resultLengths;
    private int overallIterations;
    private int index;
    private int counter;

    public IterationCounter(Integer inputLength, Integer[] resultLengths) {
        this.inputLength = inputLength;
        this.resultLengths = resultLengths;
        indexes = createIntegerArray(inputLength);
        next = new IterationPlan(indexes);
        previousIterationPlans.add(next);
        permutationIterator = new Permutation(indexes).iterator();

        overallIterations = 0;
        index = inputLength - 1;
        counter = 0;
    }

    private Integer[] createIntegerArray(Integer length) {
        Integer[] integerArray = new Integer[length];
        for (int i = 0; i < length; i++) {
            integerArray[i] = 0;
        }

        return integerArray;
    }

    @Override
    public boolean hasNext() {
        return next != null && index > -1;
    }

    @Override
    public IterationPlan next() {
        IterationPlan ret = next;

        next = calculateNextIterationPlan();

        if (ret != null) {
            return ret;
        }

        throw new NoSuchElementException();
    }

    private IterationPlan calculateNextIterationPlan() {
        IterationPlan iterationPlan = permuteNext();
        if (iterationPlan != null) {
            return iterationPlan;
        }

        iterationPlan = incrementThisIndex();
        if (iterationPlan != null) {
            return iterationPlan;
        }

        iterationPlan = incrementNextIndex();
        if (iterationPlan != null) {
            return iterationPlan;
        }

        while (overallIterations < inputLength) {
            overallIterations++;
            iterationPlan = incrementNextIndex();
            if (iterationPlan != null) {
                return iterationPlan;
            }
        }

        return null;
    }

    private IterationPlan permuteNext() {
        while (permutationIterator.hasNext()) {
            Integer[] indexes = permutationIterator.next();
            if (!contains(indexes)) {
                return returnNewIterationPlan(indexes);
            }
        }

        return null;
    }

    private IterationPlan incrementThisIndex() {
        if (counter <= resultLengths[index]) {
            while (counter <= resultLengths[index]) {
                indexes[0] = counter;
                counter++;
                if (!contains(indexes)) {
                    permutationIterator = new Permutation(indexes).iterator();
                    return permuteNext();
                }
            }
        } else {
            counter = 0;
        }

        return null;
    }

    private IterationPlan incrementNextIndex() {
        if (index > 0) {
            while (index > 0 && indexes[index] < resultLengths[index]) {
                indexes[index]++;
                index--;
                if (!contains(indexes)) {
                    return incrementThisIndex();
                }
            }
        } else {
            index = inputLength - 1;
        }

        return null;
    }

    private IterationPlan returnNewIterationPlan(Integer[] indexes) {
        IterationPlan iterationPlan = new IterationPlan(indexes);
        previousIterationPlans.add(iterationPlan);
        return iterationPlan;
    }

    private boolean contains(Integer[] indexes) {
        return previousIterationPlans.contains(new IterationPlan(indexes));
    }

    public class IterationPlan {
        private Integer[] indexes;

        private IterationPlan(Integer[] indexes) {
            this.indexes = new Integer[indexes.length];
            System.arraycopy(indexes, 0, this.indexes, 0, indexes.length);
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
            // Compare by content — hashCode() hashes the array contents, so reference
            // equality here would make IterationPlan unusable as a hash key.
            return Arrays.equals(indexes, that.indexes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(indexes);
        }
    }
}
