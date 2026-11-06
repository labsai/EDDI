/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal;

import ai.labs.eddi.modules.nlp.DictionaryUtilities;
import ai.labs.eddi.modules.nlp.IInputParser;
import ai.labs.eddi.modules.nlp.Solution;
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.corrections.DamerauLevenshteinCorrection;
import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.RegularDictionary;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import ai.labs.eddi.modules.nlp.model.FoundPhrase;
import ai.labs.eddi.modules.nlp.model.MatchQuality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phrase matching, solution ordering and match quality of {@link InputParser}.
 */
@DisplayName("InputParser — phrase matching and match quality")
class InputParserMatchingTest {

    private static Expressions exp(String name) {
        return new Expressions(new Expression(name));
    }

    private static RegularDictionary phrases(String... phrasesAndExpressions) {
        if (phrasesAndExpressions.length % 2 != 0) {
            throw new IllegalArgumentException("phrases() takes (phrase, expression) pairs");
        }
        var dictionary = new RegularDictionary();
        for (int i = 0; i < phrasesAndExpressions.length; i += 2) {
            dictionary.addPhrase(phrasesAndExpressions[i], exp(phrasesAndExpressions[i + 1]));
        }
        return dictionary;
    }

    private static Solution first(List<RawSolution> raw) {
        return DictionaryUtilities.extractExpressions(raw, false, false).getFirst();
    }

    private static InputParser parser(IInputParser.Config config, List<IDictionary> dictionaries, List<ICorrection> corrections) {
        return new InputParser(List.of(), dictionaries, corrections, config);
    }

    @Nested
    @DisplayName("a phrase whose reading is not every token's first match")
    class SecondMatchOfAnEarlierToken {

        /**
         * "bok" has two readings: the regex of dictionary A (first) and the typo
         * correction "book" (second). Only the second completes the phrase "book
         * flight". The old enumerator capped every token's index by the LAST token's
         * match count, so the first token's second reading was never tried — and even
         * when a later combination completed a phrase, the solution was appended behind
         * the worse ones the first combination had produced.
         */
        @Test
        @DisplayName("is found and comes first")
        void completesThePhraseThroughACorrection() throws Exception {
            var regexes = new RegularDictionary();
            regexes.addRegex("bok", exp("code"));
            var flights = phrases("book flight", "book_flight");
            List<IDictionary> dictionaries = List.of(regexes, flights);
            var correction = new DamerauLevenshteinCorrection(1, true);
            correction.init(dictionaries);

            List<RawSolution> raw = parser(new IInputParser.Config(), dictionaries, List.of(correction)).parse("bok flight", "en", List.of());

            assertEquals(RawSolution.Match.FULLY, raw.getFirst().getMatch());
            Solution solution = first(raw);
            assertEquals("book_flight", solution.getExpressions().toString());
            assertTrue(solution.isCorrected(), "the phrase was assembled from a typo correction");
            assertTrue(solution.getConfidence() < 1.0, "a corrected phrase is not an exact match: " + solution.getConfidence());
        }
    }

    /**
     * Two dictionaries reporting the same reading of "bok" used to give the token
     * two identical matches, pushing the correction that completes the phrase to a
     * third place — out of reach of a small {@code maxSuggestions}. A repeated
     * reading is now dropped before the search.
     */
    @Test
    @DisplayName("a repeated reading does not crowd the right one out of the suggestion limit")
    void repeatedReadingsDoNotCrowdTheSearch() throws Exception {
        var regexes = new RegularDictionary();
        regexes.addRegex("bok", exp("code"));
        var sameRegexes = new RegularDictionary();
        sameRegexes.addRegex("bok", exp("code"));
        var flights = phrases("book flight", "book_flight");
        List<IDictionary> dictionaries = List.of(regexes, sameRegexes, flights);
        var correction = new DamerauLevenshteinCorrection(1, true);
        correction.init(dictionaries);
        var parser = new InputParser(List.of(), dictionaries, List.of(correction), new IInputParser.Config(), new Limits(200, 2, 100));

        assertEquals("book_flight", first(parser.parse("bok flight", "en", List.of())).getExpressions().toString());
    }

    @Nested
    @DisplayName("partial phrases")
    class PartialPhrases {

        private IInputParser.Config partial(boolean on) {
            var config = new IInputParser.Config();
            config.setMatchPartialPhrases(on);
            return config;
        }

        @Test
        @DisplayName("are off by default: a fragment yields nothing")
        void offByDefault() throws Exception {
            var raw = parser(partial(false), List.of(phrases("good morning sir", "greeting")), List.of()).parse("good morning", "en", List.of());

            assertEquals(RawSolution.Match.NOTHING, raw.getFirst().getMatch());
            assertTrue(first(raw).getExpressions().isEmpty());
        }

        /**
         * This never worked: it compared the phrase's words with the found words
         * (different classes, never equal) and mixed up the two lists' indexes.
         */
        @Test
        @DisplayName("when on, a fragment covering enough of the phrase yields it, scored by its coverage")
        void fragmentYieldsThePhrase() throws Exception {
            var raw = parser(partial(true), List.of(phrases("good morning sir", "greeting")), List.of()).parse("hi good morning", "en", List.of());

            assertEquals(RawSolution.Match.PARTLY, raw.getFirst().getMatch());
            Solution solution = DictionaryUtilities.extractExpressions(raw, false, true).getFirst();
            assertEquals("unknown(hi), greeting", solution.getExpressions().toString());
            assertEquals(2.0 / 3.0, solution.getConfidence(), 1e-9);
        }

        @Test
        @DisplayName("a fragment below the minimum coverage does not count")
        void belowCoverage() throws Exception {
            var config = partial(true);
            config.setMinPartialPhraseCoverage(0.75);

            var raw = parser(config, List.of(phrases("good morning sir", "greeting")), List.of()).parse("good morning", "en", List.of());

            assertTrue(first(raw).getExpressions().isEmpty());
        }

        @Test
        @DisplayName("a fragment that two phrases share equally is ambiguous and yields neither")
        void ambiguousFragment() throws Exception {
            var raw = parser(partial(true), List.of(phrases("good morning", "gm", "good night", "gn")), List.of()).parse("good", "en", List.of());

            assertTrue(first(raw).getExpressions().isEmpty(), "picked one arbitrarily: " + first(raw).getExpressions());
        }

        @Test
        @DisplayName("a full match still wins over a fragment")
        void fullMatchWins() throws Exception {
            var raw = parser(partial(true), List.of(phrases("good morning", "gm", "good morning sir", "gms")), List.of()).parse("good morning",
                    "en", List.of());

            assertEquals(RawSolution.Match.FULLY, raw.getFirst().getMatch());
            assertEquals("gm", first(raw).getExpressions().toString());
        }
    }

    @Test
    @DisplayName("a phrase of a dictionary in another language is not matched, even when other dictionaries know its words")
    void phraseLanguageGate() throws Exception {
        var german = new RegularDictionary();
        german.setLanguageCode("de");
        german.addPhrase("die bank", exp("bank_de"));
        var english = new RegularDictionary();
        english.setLanguageCode("en");
        english.addPhrase("die bank", exp("bank_en"));

        var raw = parser(new IInputParser.Config(), List.of(german, english), List.of()).parse("die bank", "en", List.of());

        assertEquals("bank_en", first(raw).getExpressions().toString());
    }

    @Test
    @DisplayName("a quick-reply phrase with the same text as a configured phrase yields the quick reply's expressions")
    void quickReplyPhraseWinsOverAnEqualConfiguredPhrase() throws Exception {
        var configured = parser(new IInputParser.Config(), List.of(phrases("yes please", "configured")), List.of());

        var raw = configured.parse("yes please", "en", List.of(phrases("yes please", "clicked")));

        assertEquals("clicked", first(raw).getExpressions().toString());
    }

    @Test
    @DisplayName("a phrase written with repeated spaces still matches")
    void phraseWithRepeatedSpaces() throws Exception {
        var raw = parser(new IInputParser.Config(), List.of(phrases("good   morning", "gm")), List.of()).parse("good morning", "en", List.of());

        assertEquals("gm", first(raw).getExpressions().toString());
    }

    @Test
    @DisplayName("an exact phrase match is fully confident and uncorrected")
    void exactPhraseConfidence() throws Exception {
        var raw = parser(new IInputParser.Config(), List.of(phrases("good morning", "gm")), List.of()).parse("good morning", "en", List.of());

        var phrase = raw.getFirst().getDictionaryEntries().getFirst();
        assertInstanceOf(FoundPhrase.class, phrase);
        assertEquals(1.0, MatchQuality.accuracy(phrase));
        assertFalse(MatchQuality.corrected(phrase));
        assertEquals(1.0, first(raw).getConfidence());
    }

    @Nested
    @DisplayName("minMatchingAccuracy")
    class MinMatchingAccuracy {

        private List<RawSolution> parseTypo() throws Exception {
            var dictionary = new RegularDictionary();
            dictionary.addWord("hello", exp("greeting"), 0);
            var correction = new DamerauLevenshteinCorrection(2, false);
            correction.init(List.of(dictionary));
            return parser(new IInputParser.Config(), List.of(dictionary), List.of(correction)).parse("helo", "en", List.of());
        }

        @Test
        @DisplayName("0 keeps a typo correction")
        void keepsCorrections() throws Exception {
            var solution = DictionaryUtilities.extractExpressions(parseTypo(), false, false, 0.0).getFirst();

            assertEquals("greeting", solution.getExpressions().toString());
            assertTrue(solution.isCorrected());
            assertTrue(solution.getConfidence() < 1.0);
            assertTrue(solution.getMatchDetails().getFirst().contains("corrected"), solution.getMatchDetails().toString());
        }

        @Test
        @DisplayName("above the correction's accuracy drops it")
        void dropsCorrections() throws Exception {
            var solution = DictionaryUtilities.extractExpressions(parseTypo(), false, false, 0.9).getFirst();

            assertTrue(solution.getExpressions().isEmpty(), solution.getExpressions().toString());
            assertNull(solution.getConfidence());
        }

        @Test
        @DisplayName("never drops unknown words: includeUnknown decides about those")
        void keepsUnknown() throws Exception {
            var raw = parser(new IInputParser.Config(), List.of(), List.of()).parse("xyz", "en", List.of());

            var solution = DictionaryUtilities.extractExpressions(raw, false, true, 1.0).getFirst();

            assertEquals("unknown(xyz)", solution.getExpressions().toString());
            assertFalse(solution.hasMeaningfulExpressions());
        }
    }

    @Nested
    @DisplayName("readings")
    class Readings {

        private InputParser ambiguous(int maxAlternatives) {
            var first = new RegularDictionary();
            first.addWord("bank", exp("finance"), 0);
            var second = new RegularDictionary();
            second.addWord("bank", exp("river"), 0);
            var config = new IInputParser.Config();
            config.setMaxAlternatives(maxAlternatives);
            return parser(config, List.of(first, second), List.of());
        }

        @Test
        @DisplayName("without phrases, parse stops at the chosen reading")
        void stopsAtTheChosenReading() throws Exception {
            var raw = ambiguous(0).parse("bank", "en", List.of());

            assertEquals(1, raw.size());
            assertEquals("finance", first(raw).getExpressions().toString());
        }

        @Test
        @DisplayName("maxAlternatives keeps that many more")
        void keepsAlternatives() throws Exception {
            var solutions = DictionaryUtilities.extractExpressions(ambiguous(1).parse("bank", "en", List.of()), false, false);

            assertEquals(List.of("finance", "river"), solutions.stream().map(solution -> solution.getExpressions().toString()).toList());
        }

        @Test
        @DisplayName("parseAllReadings lists every reading")
        void allReadings() throws Exception {
            assertEquals(2, ambiguous(0).parseAllReadings("bank", "en").size());
        }

        @Test
        @DisplayName("the same reading from two dictionaries is one reading, not two")
        void duplicateReadingsCollapse() throws Exception {
            var first = new RegularDictionary();
            first.addWord("bank", exp("finance"), 0);
            var second = new RegularDictionary();
            second.addWord("bank", exp("finance"), 0);

            assertEquals(1, parser(new IInputParser.Config(), List.of(first, second), List.of()).parseAllReadings("bank", "en").size());
        }
    }

    @Test
    @DisplayName("exceedsTokenLimit tells whether parse ignores part of the input")
    void exceedsTokenLimit() {
        var limits = new Limits(2, 10, 10);

        assertFalse(InputParser.exceedsTokenLimit("one  two", limits));
        assertTrue(InputParser.exceedsTokenLimit("one two three", limits));
        assertFalse(InputParser.exceedsTokenLimit(null, limits));
    }
}
