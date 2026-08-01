/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.corrections;

import ai.labs.eddi.modules.nlp.IInputParser;
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.RegularDictionary;
import ai.labs.eddi.modules.nlp.internal.InputParser;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parser skips dictionaries whose language does not match the current user
 * language. That gate is worthless if the corrections keep scanning every
 * dictionary: as soon as a token is otherwise unknown, the correction hands the
 * foreign-language word back at distance 0 (accuracy 1.0) and the mismatched
 * dictionary matches after all.
 * <p>
 * Every test here therefore configures the parser the way the docs recommend —
 * dictionaries <em>plus</em> a correction.
 */
@DisplayName("Corrections — dictionary language filter")
class CorrectionLanguageFilterTest {

    @Test
    @DisplayName("Damerau-Levenshtein does not correct into a mismatched-language dictionary")
    void levenshteinRespectsTheDictionaryLanguage() {
        var correction = initialized(new DamerauLevenshteinCorrection(), germanDictionary());

        assertTrue(correction.correctWord("hallo", "en", Collections.emptyList()).isEmpty(),
                "the German dictionary must not be scanned for an English turn");
        assertFalse(correction.correctWord("hallo", "de", Collections.emptyList()).isEmpty(),
                "the German dictionary must still be scanned for a German turn");
    }

    @Test
    @DisplayName("phonetic correction does not match into a mismatched-language dictionary")
    void phoneticRespectsTheDictionaryLanguage() {
        var correction = initialized(new PhoneticCorrection(true), germanDictionary());

        assertTrue(correction.correctWord("hallo", "en", Collections.emptyList()).isEmpty(),
                "the German dictionary must not be scanned for an English turn");
        assertFalse(correction.correctWord("hallo", "de", Collections.emptyList()).isEmpty(),
                "the German dictionary must still be scanned for a German turn");
    }

    @Test
    @DisplayName("merged-terms correction does not split into a mismatched-language dictionary")
    void mergedTermsRespectsTheDictionaryLanguage() {
        var correction = initialized(new MergedTermsCorrection(), germanDictionary());

        assertTrue(correction.correctWord("hallohallo", "en", Collections.emptyList()).isEmpty(),
                "the German dictionary must not be scanned for an English turn");
        assertEquals(2, correction.correctWord("hallohallo", "de", Collections.emptyList()).size(),
                "the German dictionary must still be scanned for a German turn");
    }

    @Test
    @DisplayName("a language-less dictionary is still scanned by every correction")
    void dictionariesWithoutLanguageApplyEverywhere() {
        var dictionary = new RegularDictionary();
        dictionary.addWord("hallo", greeting(), 0);

        assertFalse(initialized(new DamerauLevenshteinCorrection(), dictionary).correctWord("hallo", "en", Collections.emptyList()).isEmpty());
        assertFalse(initialized(new PhoneticCorrection(true), dictionary).correctWord("hallo", "en", Collections.emptyList()).isEmpty());
        assertFalse(initialized(new MergedTermsCorrection(), dictionary).correctWord("hallo", "en", Collections.emptyList()).isEmpty());
    }

    @Test
    @DisplayName("a parser configured with a correction still skips the mismatched dictionary")
    void parserWithCorrectionSkipsMismatchedDictionary() throws Exception {
        var dictionary = germanDictionary();
        var parser = parserOver(dictionary, initialized(new DamerauLevenshteinCorrection(), dictionary));

        assertFalse(expressionNames(parser.parse("hallo", "en", Collections.emptyList())).contains("greeting"),
                "the German dictionary must not be reachable through the corrections path either");
    }

    @Test
    @DisplayName("a parser configured with a correction still corrects typos in the matching language")
    void parserWithCorrectionStillCorrectsMatchingLanguage() throws Exception {
        var dictionary = germanDictionary();
        var parser = parserOver(dictionary, initialized(new DamerauLevenshteinCorrection(), dictionary));

        assertTrue(expressionNames(parser.parse("hallu", "de", Collections.emptyList())).contains("greeting"),
                "a typo must still be corrected when the dictionary language matches the turn");
        assertFalse(expressionNames(parser.parse("hallu", "en", Collections.emptyList())).contains("greeting"),
                "the very same typo must not be corrected against a mismatched-language dictionary");
    }

    private static <T extends ICorrection> T initialized(T correction, IDictionary dictionary) {
        correction.init(List.of(dictionary));
        return correction;
    }

    private static InputParser parserOver(IDictionary dictionary, ICorrection correction) {
        return new InputParser(Collections.emptyList(), List.of(dictionary), List.of(correction), new IInputParser.Config());
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
