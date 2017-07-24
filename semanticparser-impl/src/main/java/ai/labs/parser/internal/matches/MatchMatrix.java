package ai.labs.parser.internal.matches;

import java.util.*;

/**
 * @author ginccc
 */
public class MatchMatrix implements Iterable<Suggestion> {
    private Map<String, List<MatchingResult>> mappedMatchMatrix = new LinkedHashMap<String, List<MatchingResult>>();

    public void addMatchingResult(String inputTerm, MatchingResult matchingResult) {
        if (!mappedMatchMatrix.containsKey(inputTerm)) {
            mappedMatchMatrix.put(inputTerm, new LinkedList<MatchingResult>());
        }

        mappedMatchMatrix.get(inputTerm).add(matchingResult);
    }

    public List<MatchingResult> getMatchingResults(String inputTerm) {
        if (mappedMatchMatrix.containsKey(inputTerm)) {
            return mappedMatchMatrix.get(inputTerm);
        }

        return null;
    }

    public List<MatchingResult> getMatchingResults(int index) {
        Collection<List<MatchingResult>> allMatchingResults = mappedMatchMatrix.values();
        if (index < allMatchingResults.size()) {
            return (List<MatchingResult>) allMatchingResults.toArray()[index];
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

        public SolutionIterator() {
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
            Suggestion nextSuggestion = null;
            while (iterationCounter.hasNext()) {
                IterationCounter.IterationPlan iterationPlan = iterationCounter.next();

                nextSuggestion = new Suggestion();
                for (int index = 0; index < mappedMatchMatrix.size(); index++) {
                    List<MatchingResult> listOfMatchingResults = getMatchingResults(index);
                    Integer iterationIndex = iterationPlan.getIndexes()[index];
                    if (iterationIndex >= listOfMatchingResults.size()) {
                        //iteration plan is out of bounds, so we skip it
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

            return nextSuggestion;
        }

        @Override
        public void remove() {
            //not implemented
        }
    }

}
