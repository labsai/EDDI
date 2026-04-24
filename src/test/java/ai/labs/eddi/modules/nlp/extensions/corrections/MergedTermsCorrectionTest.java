/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.corrections;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.model.FoundWord;
import ai.labs.eddi.modules.nlp.model.Word;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MergedTermsCorrectionTest {

    private MergedTermsCorrection correction;
    private IDictionary dictionary;

    @BeforeEach
    void setUp() {
        correction = new MergedTermsCorrection();
        dictionary = mock(IDictionary.class);
        when(dictionary.lookupTerm(anyString())).thenReturn(IDictionary.NO_WORDS_FOUND);
        correction.init(List.of(dictionary));
    }

    @Test
    void lookupIfKnown_returnsFalse() {
        assertFalse(correction.lookupIfKnown());
    }

    @Test
    void correctWord_noMatch() {
        var result = correction.correctWord("unknownword", null, Collections.emptyList());
        assertTrue(result.isEmpty() || result == IDictionary.NO_WORDS_FOUND);
    }

    @Test
    void correctWord_singleWordMatch() {
        var word = new Word("hello", new Expressions(new Expression("greeting")), "test", 0, false);
        var foundWord = new FoundWord(word, false, 1.0);
        when(dictionary.lookupTerm("hello")).thenReturn(List.of(foundWord));

        var result = correction.correctWord("hello", null, Collections.emptyList());
        assertEquals(1, result.size());
    }

    @Test
    void correctWord_mergedWords() {
        var wordHello = new Word("hello", new Expressions(new Expression("g1")), "test", 0, false);
        var wordWorld = new Word("world", new Expressions(new Expression("g2")), "test", 0, false);
        when(dictionary.lookupTerm("hello")).thenReturn(List.of(new FoundWord(wordHello, false, 1.0)));
        when(dictionary.lookupTerm("world")).thenReturn(List.of(new FoundWord(wordWorld, false, 1.0)));

        var result = correction.correctWord("helloworld", null, Collections.emptyList());
        assertEquals(2, result.size());
    }

    @Test
    void correctWord_partialMatch() {
        var word = new Word("hello", new Expressions(new Expression("g1")), "test", 0, false);
        when(dictionary.lookupTerm("hello")).thenReturn(List.of(new FoundWord(word, false, 1.0)));

        var result = correction.correctWord("helloxyz", null, Collections.emptyList());
        assertNotNull(result);
    }

    @Test
    void correctWord_withTemporaryDictionary() {
        var tempDict = mock(IDictionary.class);
        var word = new Word("temp", new Expressions(new Expression("t1")), "test", 0, false);
        when(tempDict.lookupTerm("temp")).thenReturn(List.of(new FoundWord(word, false, 1.0)));

        var result = correction.correctWord("temp", null, List.of(tempDict));
        assertEquals(1, result.size());
    }
}
