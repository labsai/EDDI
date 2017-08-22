package ai.labs.parser.internal.matches;

import ai.labs.parser.model.IDictionary;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class Suggestion {
    private List<MatchingResult> rawSolution = new LinkedList<>();

    void addMatchingResult(MatchingResult matchingResult) {
        rawSolution.add(matchingResult);
    }

    public List<IDictionary.IFoundWord> build() {
        List<IDictionary.IFoundWord> dictionaryEntries = new LinkedList<>();
        rawSolution.stream().map(MatchingResult::getResult).forEach(dictionaryEntries::addAll);
        return dictionaryEntries;
    }
}
