/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal;

import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.internal.matches.MatchMatrix;
import ai.labs.eddi.modules.nlp.internal.matches.MatchingResult;
import ai.labs.eddi.modules.nlp.internal.matches.Suggestion;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class InputHolder {
    public String[] input;
    public Integer index = 0;
    private MatchMatrix matchMatrix = new MatchMatrix();
    /** What the matches of the current token mean, to skip repeats. */
    private final Set<List<Object>> meaningsOfCurrentToken = new HashSet<>();

    void addMatch(int index, String inputTerm, MatchingResult matchingResult) {
        matchMatrix.addMatchingResult(index, inputTerm, matchingResult);
    }

    int getMatchingResultSize(int index) {
        List<MatchingResult> matchingResults = matchMatrix.getMatchingResults(index);
        return matchingResults != null ? matchingResults.size() : 0;
    }

    Iterator<Suggestion> createSolutionIterator() {
        return matchMatrix.iterator();
    }

    void startToken() {
        meaningsOfCurrentToken.clear();
    }

    /**
     * {@code true} the first time a match with this meaning — word value, phrase
     * role, phrase-ness and expressions — is offered for the current token.
     */
    boolean firstOfItsKind(IDictionary.IFoundWord foundWord) {
        IDictionary.IWord word = foundWord.getFoundWord();
        if (word == null || word.getValue() == null) {
            // nothing to compare by: keep it
            return true;
        }
        var expressions = foundWord.getExpressions();
        return meaningsOfCurrentToken.add(List.of(String.valueOf(word.getValue()), word.isPartOfPhrase(), foundWord.isPhrase(),
                String.valueOf(expressions)));
    }
}
