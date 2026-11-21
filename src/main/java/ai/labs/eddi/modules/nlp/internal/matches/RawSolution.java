/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal.matches;

import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author ginccc
 */
public class RawSolution {
    /**
     * How well a solution matched the dictionaries' phrases, best first — the
     * parser keeps its solutions in this order.
     */
    public enum Match {
        FULLY, PARTLY, NOTHING
    }

    private Match match;
    private List<IDictionary.IFoundWord> dictionaryEntries;

    public RawSolution(Match match) {
        this.match = match;
        this.dictionaryEntries = new ArrayList<>();
    }

    public RawSolution(Match match, List<IDictionary.IFoundWord> dictionaryEntries) {
        this.match = match;
        this.dictionaryEntries = dictionaryEntries;
    }

    public Match getMatch() {
        return match;
    }

    public List<IDictionary.IFoundWord> getDictionaryEntries() {
        return dictionaryEntries;
    }

    public void setDictionaryEntries(List<IDictionary.IFoundWord> dictionaryEntries) {
        this.dictionaryEntries = dictionaryEntries;
    }

    /**
     * Content equality, so the parser can tell that two match combinations led to
     * the same solution. Without it every combination added another copy, and the
     * solution list filled up to its cap with duplicates.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        RawSolution that = (RawSolution) o;
        return match == that.match && Objects.equals(dictionaryEntries, that.dictionaryEntries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(match, dictionaryEntries);
    }

    @Override
    public String toString() {
        return "RawSolution(" + match + ", " + dictionaryEntries + ")";
    }
}
