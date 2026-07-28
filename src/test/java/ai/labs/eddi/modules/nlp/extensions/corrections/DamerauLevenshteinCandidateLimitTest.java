/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.corrections;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.model.FoundDictionaryEntry;
import ai.labs.eddi.modules.nlp.model.Word;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every correction candidate becomes an extra branch in the parser's match
 * matrix, so a correction that hands back every word within the edit distance
 * effectively returns the whole dictionary.
 */
@DisplayName("DamerauLevenshteinCorrection — candidate limit & accuracy contract")
class DamerauLevenshteinCandidateLimitTest {

    private static final List<String> CANDIDATE_WORDS = List.of("hallo", "hbllo", "hcllo", "hdllo", "hello", "hfllo", "hgllo", "hxylo");

    @Test
    @DisplayName("one typo yields at most the configured number of candidates")
    void candidatesAreTruncated() {
        var correction = correctionOver(CANDIDATE_WORDS, 2, DamerauLevenshteinCorrection.DEFAULT_MAX_CANDIDATES);

        var candidates = correction.correctWord("hallo", "en", Collections.emptyList());

        assertEquals(DamerauLevenshteinCorrection.DEFAULT_MAX_CANDIDATES, candidates.size());
    }

    @Test
    @DisplayName("the candidate limit is configurable")
    void candidateLimitIsConfigurable() {
        var correction = correctionOver(CANDIDATE_WORDS, 2, 2);

        assertEquals(2, correction.correctWord("hallo", "en", Collections.emptyList()).size());
    }

    @Test
    @DisplayName("the best matching words survive the truncation")
    void bestMatchesSurvive() {
        var correction = correctionOver(CANDIDATE_WORDS, 2, 2);

        var values = correction.correctWord("hallo", "en", Collections.emptyList()).stream().map(IDictionary.IFoundWord::getValue)
                .collect(Collectors.toList());

        assertEquals("hallo", values.getFirst(), "the exact match must be kept");
        assertFalse(values.contains("hxylo"), "a distance-2 match must not displace a distance-1 match");
    }

    @Test
    @DisplayName("matching accuracy stays within the documented 0..1 range")
    void matchingAccuracyStaysWithinRange() {
        var correction = correctionOver(CANDIDATE_WORDS, 2, CANDIDATE_WORDS.size());

        var candidates = correction.correctWord("hallo", "en", Collections.emptyList());

        assertFalse(candidates.isEmpty());
        for (var candidate : candidates) {
            double accuracy = ((FoundDictionaryEntry) candidate).getMatchingAccuracy();
            assertTrue(accuracy > 0.0 && accuracy <= 1.0, "accuracy out of range for '" + candidate.getValue() + "': " + accuracy);
        }
    }

    @Test
    @DisplayName("an exact match still scores the maximum accuracy")
    void exactMatchScoresOne() {
        var correction = correctionOver(List.of("hallo"), 2, 5);

        var candidate = correction.correctWord("hallo", "en", Collections.emptyList()).getFirst();

        assertEquals(1.0, ((FoundDictionaryEntry) candidate).getMatchingAccuracy(), 0.000001);
    }

    private static DamerauLevenshteinCorrection correctionOver(List<String> words, int maxDistance, int maxCandidates) {
        var correction = new DamerauLevenshteinCorrection(maxDistance, false, maxCandidates);
        var dictionary = mock(IDictionary.class);
        when(dictionary.getWords()).thenReturn(words.stream()
                .map(word -> (IDictionary.IWord) new Word(word, new Expressions(new Expression("word", new Expression(word))), "en", 0, false))
                .collect(Collectors.toList()));
        correction.init(List.of(dictionary));

        return correction;
    }
}
