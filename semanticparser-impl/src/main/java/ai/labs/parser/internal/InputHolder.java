package ai.labs.parser.internal;

import ai.labs.parser.internal.matches.MatchMatrix;
import ai.labs.parser.internal.matches.MatchingResult;
import ai.labs.parser.internal.matches.Suggestion;
import ai.labs.parser.model.IDictionary;

import java.util.Iterator;
import java.util.List;

public class InputHolder {
    public String[] input;
    public Integer index = 0;
    private MatchMatrix matchMatrix = new MatchMatrix();

    void addMatch(String inputTerm, MatchingResult matchingResult) {
        matchMatrix.addMatchingResult(inputTerm, matchingResult);
    }

    boolean equalsMatchingTerm(String inputTerm, List<? extends IDictionary.IFoundDictionaryEntry> dictionaryEntries) {
        List<MatchingResult> matchingResults = matchMatrix.getMatchingResults(inputTerm);
        if (matchingResults != null) {
            for (MatchingResult matchingResult : matchingResults) {
                List<IDictionary.IFoundWord> result = matchingResult.getResult();
                if (result.size() != dictionaryEntries.size()) {
                    return false;
                }

                boolean isSame = false;
                for (int i = 0; i < result.size(); i++) {
                    IDictionary.IDictionaryEntry dictionaryEntry = result.get(i);
                    String dictionaryEntryValue = dictionaryEntry.getValue();
                    if (dictionaryEntryValue != null &&
                            dictionaryEntryValue.equals(dictionaryEntries.get(i).getValue())) {
                        isSame = true;
                        break;
                    }
                }

                if (isSame) {
                    return true;
                }
            }
        }

        return false;
    }

    int getMatchingResultSize(int index) {
        List<MatchingResult> matchingResults = matchMatrix.getMatchingResults(index);
        return matchingResults != null ? matchingResults.size() : 0;
    }

    Iterator<Suggestion> createSolutionIterator() {
        return matchMatrix.iterator();
    }
}
