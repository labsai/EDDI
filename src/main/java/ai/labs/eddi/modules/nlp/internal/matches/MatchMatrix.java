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

    private final Map<Match, List<MatchingResult>> mappedMatchMatrix = new LinkedHashMap<>();

    /**
     * Positional view on {@link #mappedMatchMatrix}. Rebuilding it on every
     * {@link #getMatchingResults(int)} call copied the whole collection per lookup,
     * which is quadratic in the hot solution-enumeration loop. The cache is dropped
     * whenever a new input term is added; the inner lists are shared, so appending
     * to an existing term needs no invalidation.
     */
    private List<List<MatchingResult>> matchingResultsByIndex;

    public void addMatchingResult(int index, String inputTerm, MatchingResult matchingResult) {
        Match match = new Match(index, inputTerm);
        List<MatchingResult> matchingResults = mappedMatchMatrix.get(match);
        if (matchingResults == null) {
            matchingResults = new LinkedList<>();
            mappedMatchMatrix.put(match, matchingResults);
            matchingResultsByIndex = null;
        }

        matchingResults.add(matchingResult);
    }

    public List<MatchingResult> getMatchingResults(int index) {
        List<List<MatchingResult>> allMatchingResults = matchingResultsByIndex();
        if (index < allMatchingResults.size()) {
            return allMatchingResults.get(index);
        }

        return null;
    }

    /**
     * The memoized positional view itself. Package-private so the caching can be
     * asserted directly: {@link #getMatchingResults(int)} hands out the shared
     * inner list either way, so only the identity of the <em>outer</em> view
     * distinguishes a cached lookup from one that copies the whole collection per
     * call.
     */
    List<List<MatchingResult>> matchingResultsByIndex() {
        if (matchingResultsByIndex == null) {
            matchingResultsByIndex = buildPositionalView();
        }

        return matchingResultsByIndex;
    }

    /**
     * The (expensive) rebuild the cache exists to avoid — copying the whole match
     * collection. Package-private and overridable so a test can count how often it
     * actually happens; an assertion on the returned value alone cannot tell a
     * cached lookup from a rebuilt one.
     */
    List<List<MatchingResult>> buildPositionalView() {
        return new ArrayList<>(mappedMatchMatrix.values());
    }

    @Override
    public Iterator<Suggestion> iterator() {
        return new SolutionIterator();
    }

    class SolutionIterator implements Iterator<Suggestion> {
        /**
         * Last-resort safety valve. Iteration plans whose index is out of bounds for a
         * given input term are silently skipped, so a caller that limits how many
         * <em>suggestions</em> it consumes still cannot bound the number of plans this
         * iterator walks through. The parser's configurable {@code maxSuggestions}
         * limit normally stops the enumeration long before this is reached; this
         * guarantees termination in reasonable time even when it does not.
         */
        private static final int MAX_ITERATION_PLANS = 100_000;

        private final IterationCounter iterationCounter;
        private Suggestion nextSuggestion = null;
        private final Integer[] resultLengths;
        private int consumedIterationPlans;

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
            while (iterationCounter.hasNext() && consumedIterationPlans < MAX_ITERATION_PLANS) {
                consumedIterationPlans++;
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
