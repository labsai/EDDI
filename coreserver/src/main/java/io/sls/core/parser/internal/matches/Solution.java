package io.sls.core.parser.internal.matches;

import io.sls.core.parser.model.IDictionary;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * User: jarisch
 * Date: 25.01.13
 * Time: 16:43
 */
public class Solution {
    public enum Match {
        FULLY,
        PARTLY,
        NOTHING
    }
    private Match match;

    private List<IDictionary.IFoundWord> dictionaryEntries;
    public Solution(Match match) {
        this.match = match;
        this.dictionaryEntries = new LinkedList<IDictionary.IFoundWord>();
    }

    public Match getMatch() {
        return match;
    }

    public void setDictionaryEntries(List<IDictionary.IFoundWord> dictionaryEntries) {
        this.dictionaryEntries = dictionaryEntries;
    }

    public void addDictionaryEntries(IDictionary.IFoundWord... dictionaryEntries) {
        this.dictionaryEntries.addAll(Arrays.asList(dictionaryEntries));
    }

    public List<IDictionary.IFoundWord> getDictionaryEntries() {
        return dictionaryEntries;
    }
}
