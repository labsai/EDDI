/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal.matches;

import java.util.*;

/**
 * @author ginccc
 */
public class MatchMatrix implements Iterable<Suggestion> {
    class Match {
        int index;
        String inputTerm;

        public Match(int index, String inputTerm) {
            this.index = index;
            this.inputTerm = inputTerm;
        }

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public String getInputTerm() {
            return inputTerm;
        }

        public void setInputTerm(String inputTerm) {
            this.inputTerm = inputTerm;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            Match that = (Match) o;
            return index == that.index && java.util.Objects.equals(inputTerm, that.inputTerm);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(index, inputTerm);
        }
    }

    private Map<Match, List<MatchingResult>> mappedMatchMatrix = new LinkedHashMap<>();

    public void addMatchingResult(int index, String inputTerm, MatchingResult matchingResult) {
        Match match = new Match(index, inputTerm);
        if (!mappedMatchMatrix.containsKey(match)) {
            mappedMatchMatrix.put(match, new LinkedList<>());
        }

        mappedMatchMatrix.get(match).add(matchingResult);
    }

    public List<MatchingResult> getMatchingResults(int index) {
        Collection<List<MatchingResult>> allMatchingResults = mappedMatchMatrix.values();
        if (index < allMatchingResults.size()) {
            return new ArrayList<>(allMatchingResults).get(index);
        }

        return null;
    }

    @Override
    public Iterator<Suggestion> iterator() {
        return new SolutionIterator();
    }

    class SolutionIterator implements Iterator<Suggestion> {
        private final IterationCounter iterationCounter;
        private Suggestion nextSuggestion = null;
        private final Integer[] resultLengths;

        SolutionIterator() {
            resultLengths = createResultLengths(mappedMatchMatrix.values());
            iterationCounter = new IterationCounter(mappedMatchMatrix.size(), resultLengths);
            if (iterationCounter.hasNext()) {
                nextSuggestion = calculateNext();
            }
        }

        private Integer[] createResultLengths(Collection<List<MatchingResult>> values) {
            Integer[] ret = new Integer[values.size()];

            Iterator<List<MatchingResult>> iterator = values.iterator();
            int i = 0;
            while (iterator.hasNext()) {
                ret[i++] = iterator.next().size() - 1;
            }

            return ret;
        }

        @Override
        public boolean hasNext() {
            return nextSuggestion != null;
        }

        @Override
        public Suggestion next() {
            Suggestion ret = nextSuggestion;

            if (ret == null) {
                throw new NoSuchElementException();
            }

            nextSuggestion = calculateNext();

            return ret;
        }

        private Suggestion calculateNext() {
            Suggestion nextSuggestion;
            while (iterationCounter.hasNext()) {
                IterationCounter.IterationPlan iterationPlan = iterationCounter.next();

                nextSuggestion = new Suggestion();
                for (int index = 0; index < mappedMatchMatrix.size(); index++) {
                    List<MatchingResult> listOfMatchingResults = getMatchingResults(index);
                    Integer iterationIndex = iterationPlan.getIndexes()[index];
                    if (iterationIndex >= listOfMatchingResults.size()) {
                        // iteration plan is out of bounds, so we skip it
                        nextSuggestion = null;
                        break;
                    }

                    MatchingResult result = listOfMatchingResults.get(iterationIndex);
                    nextSuggestion.addMatchingResult(result);
                }

                if (nextSuggestion != null) {
                    return nextSuggestion;
                }
            }

            return null;
        }
    }
}
