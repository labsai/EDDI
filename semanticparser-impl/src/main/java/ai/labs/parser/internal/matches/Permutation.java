package ai.labs.parser.internal.matches;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * @author ginccc
 */
public class Permutation implements Iterable<Integer[]> {
    private Integer[] values;

    Permutation(Integer[] values) {
        this.values = values;
    }

    @Override
    public Iterator<Integer[]> iterator() {
        return new PermutationIterator(values);
    }

    public class PermutationIterator implements Iterator<Integer[]> {
        private Integer[] values;

        private int factorial;
        private int factorialCounter;

        private Integer[] next = null;

        PermutationIterator(Integer[] values) {
            this.values = values;

            Arrays.sort(this.values);

            factorial = 1;
            for (int i = 0; i < this.values.length; i++) {
                factorial *= (i + 1);
            }
            factorialCounter = 0;

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
            factorialCounter++;
            if (factorialCounter < factorial) {
                int firstNonDecreasingIndex = -1, swapPoint = -1;

                for (int i = values.length - 1; i > 0; i--) {
                    if (values[i - 1].compareTo(values[i]) < 0) {
                        firstNonDecreasingIndex = i - 1;
                        break;
                    } else if (i == 1) {
                        return null;
                    }
                }//from the end, find first index that arrayToPermute[index]<arrayToPermute[index+1]

                for (int i = values.length - 1; i > firstNonDecreasingIndex; i--) {
                    if (values[firstNonDecreasingIndex].compareTo(values[i]) < 0) {
                        swapPoint = i;
                        break;
                    }//finding the first numthat arrayToPermute[swapPoint]>arrayToPermute[index]
                }
                Integer tmp = values[firstNonDecreasingIndex];
                values[firstNonDecreasingIndex] = values[swapPoint];
                values[swapPoint] = tmp;//swap arrayToPermute[index], arrayToPermute[swapPoint]

                //swap the index+1...end sequences
                for (int i = 0; i < (values.length - 1 - firstNonDecreasingIndex) / 2; i++) {
                    tmp = values[firstNonDecreasingIndex + 1 + i];
                    values[firstNonDecreasingIndex + 1 + i] = values[values.length - 1 - i];
                    values[values.length - 1 - i] = tmp;
                }

                return values;
            }

            return null;
        }
    }
}
