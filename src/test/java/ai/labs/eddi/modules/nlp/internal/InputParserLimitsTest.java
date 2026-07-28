/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal;

import ai.labs.eddi.modules.nlp.IInputParser;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The parser enumerates the cartesian product of all dictionary matches per
 * input token. Without bounds an agent configured with dictionaries that yield
 * two or more matches per token turns a mid-length sentence into an effectively
 * infinite amount of work. These tests pin the bounds down.
 */
@DisplayName("InputParser — solution enumeration limits")
class InputParserLimitsTest {

    private static final IInputParser.Config CONFIG = new IInputParser.Config();

    @Test
    @DisplayName("suggestion limit bounds how many match combinations are evaluated")
    void suggestionLimitBoundsEnumeration() throws Exception {
        var parser = parserWith(new Limits(200, 3, 100), denseDictionary(2));

        List<RawSolution> solutions = parser.parse(tokens(6), "en", Collections.emptyList());

        // 6 tokens x 2 matches = 64 combinations; without the cap every one of them
        // produces its own solution.
        assertFalse(solutions.isEmpty());
        assertTrue(solutions.size() <= 3, "expected at most 3 solutions but got " + solutions.size());
    }

    @Test
    @DisplayName("solution limit bounds how many solutions are collected")
    void solutionLimitBoundsCollectedSolutions() throws Exception {
        var parser = parserWith(new Limits(200, 1000, 2), denseDictionary(2));

        List<RawSolution> solutions = parser.parse(tokens(6), "en", Collections.emptyList());

        assertTrue(solutions.size() <= 2, "expected at most 2 solutions but got " + solutions.size());
    }

    @Test
    @DisplayName("token limit stops the parser from looking at oversized input")
    void tokenLimitTruncatesInput() throws Exception {
        IDictionary dictionary = denseDictionary(1);
        var parser = parserWith(new Limits(3, 1000, 100), dictionary);

        parser.parse("alpha beta gamma delta epsilon", "en", Collections.emptyList());

        verify(dictionary).lookupTerm("alpha");
        verify(dictionary).lookupTerm("gamma");
        verify(dictionary, never()).lookupTerm("delta");
        verify(dictionary, never()).lookupTerm("epsilon");
    }

    @Test
    @DisplayName("a 30 token input against a dense dictionary completes quickly with default limits")
    void denseDictionaryWithThirtyTokensCompletesQuickly() {
        var parser = parserWith(Limits.DEFAULT, denseDictionary(2));

        List<RawSolution> solutions = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            return parser.parse(tokens(30), "en", Collections.emptyList());
        });

        assertTrue(solutions.size() <= Limits.DEFAULT.maxSolutions(),
                "expected at most " + Limits.DEFAULT.maxSolutions() + " solutions but got " + solutions.size());
    }

    @Test
    @DisplayName("invalid limits fall back to the defaults instead of disabling the parser")
    void invalidLimitsFallBackToDefaults() {
        assertEquals(Limits.DEFAULT, new Limits(0, -1, 0));
    }

    // ==================== helpers ====================

    private static InputParser parserWith(Limits limits, IDictionary dictionary) {
        return new InputParser(Collections.emptyList(), List.of(dictionary), Collections.emptyList(), CONFIG, limits);
    }

    private static String tokens(int count) {
        return IntStream.range(0, count).mapToObj(i -> "token" + i).reduce((a, b) -> a + " " + b).orElseThrow();
    }

    /**
     * A dictionary that matches every single input token with the given number of
     * distinct entries — the pathological configuration the limits guard against.
     */
    private static IDictionary denseDictionary(int matchesPerToken) {
        List<IDictionary.IFoundWord> matches = new ArrayList<>();
        for (int i = 0; i < matchesPerToken; i++) {
            matches.add(foundWord());
        }

        IDictionary dictionary = mock(IDictionary.class);
        when(dictionary.getLanguageCode()).thenReturn(null);
        when(dictionary.getPhrases()).thenReturn(List.of());
        when(dictionary.lookupTerm(anyString())).thenReturn(matches);

        return dictionary;
    }

    private static IDictionary.IFoundWord foundWord() {
        IDictionary.IWord word = mock(IDictionary.IWord.class);
        when(word.isPartOfPhrase()).thenReturn(false);

        IDictionary.IFoundWord found = mock(IDictionary.IFoundWord.class);
        when(found.getFoundWord()).thenReturn(word);
        when(found.isPhrase()).thenReturn(false);

        return found;
    }
}
