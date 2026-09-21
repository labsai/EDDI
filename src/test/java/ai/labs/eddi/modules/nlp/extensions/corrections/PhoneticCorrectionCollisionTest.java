/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.corrections;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.model.Word;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phonetic codes are lossy on purpose — several dictionary words share one
 * code. All of them have to survive as correction candidates; keeping only the
 * last word written for a code silently throws most of the dictionary away.
 */
@DisplayName("PhoneticCorrection — colliding phonetic codes")
class PhoneticCorrectionCollisionTest {

    /**
     * RefinedSoundex maps every vowel to '0', so these three differ only in
     * characters that are erased by the encoding — they are guaranteed to collide.
     */
    private static final List<String> HOMOPHONES = List.of("nite", "note", "nate");

    @Test
    @DisplayName("every word sharing a phonetic code is returned as a candidate")
    void allCollidingWordsAreReturned() {
        var correction = correctionOver(HOMOPHONES);

        var candidates = correction.correctWord("nute", "en", Collections.emptyList());

        assertEquals(new HashSet<>(HOMOPHONES), valuesOf(candidates));
    }

    @Test
    @DisplayName("a candidate matching through both codes is offered only once")
    void candidatesAreNotDuplicated() {
        var correction = correctionOver(List.of("nite"));

        var candidates = correction.correctWord("nite", "en", Collections.emptyList());

        assertEquals(1, candidates.size());
    }

    @Test
    @DisplayName("a word with no phonetic match yields no candidates")
    void unmatchedWordYieldsNoCandidates() {
        var correction = correctionOver(HOMOPHONES);

        assertEquals(Set.of(), valuesOf(correction.correctWord("xylophone", "en", Collections.emptyList())));
    }

    private static PhoneticCorrection correctionOver(List<String> words) {
        var correction = new PhoneticCorrection(true);
        var dictionary = mock(IDictionary.class);
        when(dictionary.getWords()).thenReturn(words.stream()
                .map(word -> (IDictionary.IWord) new Word(word, new Expressions(new Expression("word", new Expression(word))), "en", 0, false))
                .collect(Collectors.toList()));
        correction.init(List.of(dictionary));

        return correction;
    }

    private static Set<String> valuesOf(List<IDictionary.IFoundWord> foundWords) {
        return foundWords.stream().map(IDictionary.IFoundWord::getValue).collect(Collectors.toSet());
    }
}
