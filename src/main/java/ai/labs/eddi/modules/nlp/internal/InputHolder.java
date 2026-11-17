/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal;

import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.internal.matches.MatchMatrix;
import ai.labs.eddi.modules.nlp.internal.matches.MatchingResult;
import ai.labs.eddi.modules.nlp.internal.matches.Suggestion;
import ai.labs.eddi.modules.nlp.model.FoundUnknown;
import ai.labs.eddi.modules.nlp.model.MatchQuality;

import java.util.Comparator;
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

    /**
     * Moves the token's matches that meet {@code minMatchingAccuracy} in front of
     * those that do not, keeping each group's order. The parser chooses each
     * token's first match, and the threshold only filters after that choice, so a
     * qualifying reading listed after a non-qualifying one (a phonetic guess before
     * a typo correction of the same word) was never chosen. Unknown words are not
     * matches and are left where they are.
     */
    void preferAccurateEnough(int index, double minMatchingAccuracy) {
        if (minMatchingAccuracy <= 0.0) {
            return;
        }
        List<MatchingResult> matchingResults = matchMatrix.getMatchingResults(index);
        if (matchingResults == null || matchingResults.size() < 2) {
            return;
        }
        matchingResults.sort(Comparator.comparingInt(result -> accurateEnough(result, minMatchingAccuracy) ? 0 : 1));
    }

    private static boolean accurateEnough(MatchingResult result, double minMatchingAccuracy) {
        return result.getResult().stream()
                .allMatch(foundWord -> foundWord instanceof FoundUnknown || MatchQuality.accuracy(foundWord) >= minMatchingAccuracy);
    }

    void startToken() {
        meaningsOfCurrentToken.clear();
    }

    /**
     * {@code true} the first time a match with this meaning — word value, phrase
     * role, phrase-ness, expressions and accuracy — is offered for the current
     * token. The accuracy is part of it: the configured order of corrections does
     * not guarantee the better reading comes first (a phonetic guess at 0.3 can
     * precede a typo correction at 0.67 of the same word), and both must reach the
     * {@code minMatchingAccuracy} filter, or the better one is lost with the worse.
     * Exact repeats still collapse.
     */
    boolean firstOfItsKind(IDictionary.IFoundWord foundWord) {
        IDictionary.IWord word = foundWord.getFoundWord();
        if (word == null || word.getValue() == null) {
            // nothing to compare by: keep it
            return true;
        }
        var expressions = foundWord.getExpressions();
        return meaningsOfCurrentToken.add(List.of(String.valueOf(word.getValue()), word.isPartOfPhrase(), foundWord.isPhrase(),
                String.valueOf(expressions), MatchQuality.accuracy(foundWord)));
    }
}
