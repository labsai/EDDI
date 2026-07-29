/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.dictionaries;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.internal.InputParser;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parser skips dictionaries whose language does not match the current user
 * language. That filter only works if a dictionary actually reports the
 * language it was configured for.
 */
@DisplayName("RegularDictionary — language code & word cache")
class RegularDictionaryLanguageCodeTest {

    @Test
    @DisplayName("language code defaults to null, meaning 'applies to every language'")
    void languageCodeDefaultsToNull() {
        assertNull(new RegularDictionary().getLanguageCode());
    }

    @Test
    @DisplayName("configured language code is reported back")
    void languageCodeIsReported() {
        var dictionary = new RegularDictionary();
        dictionary.setLanguageCode("de");

        assertEquals("de", dictionary.getLanguageCode());
    }

    @Test
    @DisplayName("words are stamped with the dictionary language, not the dictionary type")
    void wordsCarryTheDictionaryLanguage() {
        var dictionary = new RegularDictionary();
        dictionary.setLanguageCode("de");
        dictionary.addWord("hallo", greeting(), 0);

        assertEquals("de", dictionary.getWords().getFirst().getLanguageCode());
    }

    @Test
    @DisplayName("a German dictionary does not match an English turn")
    void germanDictionaryIsSkippedOnEnglishTurn() throws Exception {
        var parser = new InputParser(List.of(germanDictionary()));

        List<RawSolution> solutions = parser.parse("hallo", "en", Collections.emptyList());

        assertFalse(expressionNames(solutions).contains("greeting"), "the German dictionary must not be consulted for an English turn");
    }

    @Test
    @DisplayName("a German dictionary does match a German turn")
    void germanDictionaryMatchesGermanTurn() throws Exception {
        var parser = new InputParser(List.of(germanDictionary()));

        List<RawSolution> solutions = parser.parse("hallo", "de", Collections.emptyList());

        assertTrue(expressionNames(solutions).contains("greeting"));
    }

    @Test
    @DisplayName("getWords is cached and invalidated when the dictionary changes")
    void getWordsIsCachedAndInvalidated() {
        var dictionary = new RegularDictionary();
        dictionary.addWord("hello", greeting(), 0);

        var first = dictionary.getWords();
        assertSame(first, dictionary.getWords(), "getWords must not rebuild the whole dictionary on every call");

        dictionary.addWord("world", greeting(), 0);

        var afterChange = dictionary.getWords();
        assertNotSame(first, afterChange);
        assertEquals(2, afterChange.size());
    }

    private static RegularDictionary germanDictionary() {
        var dictionary = new RegularDictionary();
        dictionary.setLanguageCode("de");
        dictionary.addWord("hallo", greeting(), 0);
        return dictionary;
    }

    private static Expressions greeting() {
        return new Expressions(new Expression("greeting"));
    }

    private static List<String> expressionNames(List<RawSolution> solutions) {
        return solutions.stream().flatMap(solution -> solution.getDictionaryEntries().stream())
                .flatMap(entry -> entry.getExpressions().stream()).map(Expression::getExpressionName).toList();
    }
}
