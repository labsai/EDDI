package io.sls.core.parser.internal.matches;

import io.sls.core.parser.model.IDictionary;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class Suggestion {
    private List<MatchingResult> rawSolution = new LinkedList<MatchingResult>();

    public void addMatchingResult(MatchingResult matchingResult) {
        rawSolution.add(matchingResult);
    }

    public List<IDictionary.IFoundWord> build() {
        List<IDictionary.IFoundWord> dictionaryEntries = new LinkedList<IDictionary.IFoundWord>();
        for (MatchingResult matchingResult : rawSolution) {
            dictionaryEntries.addAll(matchingResult.getResult());
        }

        return dictionaryEntries;
    }
}
