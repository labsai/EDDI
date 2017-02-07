package ai.labs.parser.internal.matches;

import ai.labs.parser.model.IDictionary;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class MatchingResult {
    private List<IDictionary.IFoundWord> result = new LinkedList<IDictionary.IFoundWord>();
    private boolean corrected;

    public MatchingResult() {
        this(false);
    }

    public MatchingResult(boolean corrected) {
        this.corrected = corrected;
    }

    public void addResult(List<IDictionary.IFoundWord> dictionaryEntries) {
        result.addAll(dictionaryEntries);
    }

    public void addResult(IDictionary.IFoundWord... dictionaryEntries) {
        addResult(Arrays.asList(dictionaryEntries));
    }

    public List<IDictionary.IFoundWord> getResult() {
        return result;
    }

    public boolean isCorrected() {
        return corrected;
    }
}
