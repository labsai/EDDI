package ai.labs.parser.internal.matches;

import java.util.*;

/**
 * @author ginccc
 */
class IterationCounter implements Iterator<IterationCounter.IterationPlan> {
    private IterationPlan next;
    private Integer[] indexes;
    private Iterator<Integer[]> permutationIterator;
    private List<IterationPlan> previousIterationPlans = new LinkedList<IterationPlan>();
    private Integer inputLength;
    private Integer[] resultLengths;
    private int overallIterations;
    private int index;
    private int counter;

    IterationCounter(Integer inputLength, Integer[] resultLengths) {
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
            if (!contains(previousIterationPlans, indexes)) {
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
                if (!contains(previousIterationPlans, indexes)) {
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
            while (index > 0) {
                indexes[index]++;
                index--;
                if (!contains(previousIterationPlans, indexes)) {
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

    private boolean contains(List<IterationPlan> listIndexes, Integer[] indexes) {
        for (IterationPlan listIndex : listIndexes) {
            if (Arrays.equals(listIndex.getIndexes(), indexes)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void remove() {
        //not implemented
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
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            IterationPlan that = (IterationPlan) o;

            return Arrays.equals(indexes, that.indexes);

        }

        @Override
        public int hashCode() {
            return indexes != null ? Arrays.hashCode(indexes) : 0;
        }
    }
}
