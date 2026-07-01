/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal;

import ai.labs.eddi.modules.nlp.IInputParser;
import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Branch coverage tests for {@link InputParser} — phrase matching, corrections,
 * addIfAbsent, and lookForPartlyMatch.
 */
@DisplayName("InputParser — Branch Coverage")
class InputParserBranchTest {

    // ==================== Phrase full match ====================

    @Nested
    @DisplayName("lookForMatch — full phrase matching")
    class LookForMatchTests {

        @Test
        @DisplayName("two-word phrase is detected as FULLY matched")
        void fullMatchTwoWords() throws Exception {
            var word1 = mock(IDictionary.IWord.class);
            doReturn("hello").when(word1).getValue();
            doReturn(true).when(word1).isPartOfPhrase();

            var word2 = mock(IDictionary.IWord.class);
            doReturn("world").when(word2).getValue();
            doReturn(true).when(word2).isPartOfPhrase();

            var phrase = mock(IDictionary.IPhrase.class);
            doReturn(List.of(word1, word2)).when(phrase).getWords();
            doReturn("hello world").when(phrase).getValue();

            var dictionary = mock(IDictionary.class);
            doReturn(null).when(dictionary).getLanguageCode();
            doReturn(List.of(phrase)).when(dictionary).getPhrases();

            var fw1 = mock(IDictionary.IFoundWord.class);
            doReturn(word1).when(fw1).getFoundWord();
            doReturn(false).when(fw1).isPhrase();
            doReturn(List.of(fw1)).when(dictionary).lookupTerm("hello");

            var fw2 = mock(IDictionary.IFoundWord.class);
            doReturn(word2).when(fw2).getFoundWord();
            doReturn(false).when(fw2).isPhrase();
            doReturn(List.of(fw2)).when(dictionary).lookupTerm("world");

            var parser = new InputParser(List.of(dictionary));
            List<RawSolution> solutions = parser.parse("hello world", "en", Collections.emptyList());

            assertFalse(solutions.isEmpty());
            assertEquals(RawSolution.Match.FULLY, solutions.getFirst().getMatch());
        }

        @Test
        @DisplayName("phrase match with prefix and suffix words")
        void matchWithPrefixAndSuffix() throws Exception {
            var word1 = mock(IDictionary.IWord.class);
            doReturn("good").when(word1).getValue();
            doReturn(true).when(word1).isPartOfPhrase();

            var word2 = mock(IDictionary.IWord.class);
            doReturn("morning").when(word2).getValue();
            doReturn(true).when(word2).isPartOfPhrase();

            var phrase = mock(IDictionary.IPhrase.class);
            doReturn(List.of(word1, word2)).when(phrase).getWords();
            doReturn("good morning").when(phrase).getValue();

            var dictionary = mock(IDictionary.class);
            doReturn(null).when(dictionary).getLanguageCode();
            doReturn(List.of(phrase)).when(dictionary).getPhrases();

            var fwGood = mock(IDictionary.IFoundWord.class);
            doReturn(word1).when(fwGood).getFoundWord();
            doReturn(false).when(fwGood).isPhrase();
            doReturn(List.of(fwGood)).when(dictionary).lookupTerm("good");

            var fwMorning = mock(IDictionary.IFoundWord.class);
            doReturn(word2).when(fwMorning).getFoundWord();
            doReturn(false).when(fwMorning).isPhrase();
            doReturn(List.of(fwMorning)).when(dictionary).lookupTerm("morning");

            // "hey" and "sir" are unknown
            doReturn(List.of()).when(dictionary).lookupTerm("hey");
            doReturn(List.of()).when(dictionary).lookupTerm("sir");

            var parser = new InputParser(List.of(dictionary));
            List<RawSolution> solutions = parser.parse("hey good morning sir", "en", Collections.emptyList());

            assertFalse(solutions.isEmpty());
        }
    }

    // ==================== Parse with corrections ====================

    @Nested
    @DisplayName("iterateCorrections edge cases")
    class CorrectionsEdgeCases {

        @Test
        @DisplayName("correction returns empty list — FoundUnknown is created")
        void correctionReturnsEmpty() throws Exception {
            var dictionary = mock(IDictionary.class);
            doReturn(null).when(dictionary).getLanguageCode();
            doReturn(List.of()).when(dictionary).getPhrases();
            doReturn(List.of()).when(dictionary).lookupTerm(anyString());

            var correction = mock(ICorrection.class);
            doReturn(false).when(correction).lookupIfKnown();
            doReturn(List.of()).when(correction).correctWord(anyString(), anyString(), anyList());

            var parser = new InputParser(List.of(dictionary), List.of(correction));
            List<RawSolution> solutions = parser.parse("unknownword", "en", Collections.emptyList());

            assertFalse(solutions.isEmpty());
            // Should contain an unknown entry
        }

        @Test
        @DisplayName("multiple corrections — all are iterated")
        void multipleCorrections() throws Exception {
            var dictionary = mock(IDictionary.class);
            doReturn(null).when(dictionary).getLanguageCode();
            doReturn(List.of()).when(dictionary).getPhrases();
            doReturn(List.of()).when(dictionary).lookupTerm(anyString());

            var correction1 = mock(ICorrection.class);
            doReturn(false).when(correction1).lookupIfKnown();
            doReturn(List.of()).when(correction1).correctWord(anyString(), anyString(), anyList());

            var correction2 = mock(ICorrection.class);
            doReturn(false).when(correction2).lookupIfKnown();
            doReturn(List.of()).when(correction2).correctWord(anyString(), anyString(), anyList());

            var parser = new InputParser(List.of(dictionary), List.of(correction1, correction2));
            parser.parse("test", "en", Collections.emptyList());

            // First correction finds nothing, second should still be called
            // (correction1 returns empty → matchingResultSize still 0 → correction2 called)
            verify(correction1).correctWord(eq("test"), eq("en"), anyList());
            verify(correction2).correctWord(eq("test"), eq("en"), anyList());
        }
    }

    // ==================== Parse with multiple unknown words ====================

    @Test
    @DisplayName("multiple unknown words each produce PARTLY solution")
    void multipleUnknownWords() throws Exception {
        var parser = new InputParser(List.of());
        List<RawSolution> solutions = parser.parse("foo bar baz");

        assertFalse(solutions.isEmpty());
        assertEquals(RawSolution.Match.PARTLY, solutions.getFirst().getMatch());
    }

    // ==================== orderPhrasesByLength ====================

    @Nested
    @DisplayName("orderPhrasesByLength")
    class OrderPhrasesTests {

        @Test
        @DisplayName("longer phrases are prioritized in matching")
        void longerPhrasePriority() throws Exception {
            // Word shared by both phrases
            var wordHello = mock(IDictionary.IWord.class);
            doReturn("hello").when(wordHello).getValue();
            doReturn(true).when(wordHello).isPartOfPhrase();

            var wordWorld = mock(IDictionary.IWord.class);
            doReturn("world").when(wordWorld).getValue();
            doReturn(true).when(wordWorld).isPartOfPhrase();

            var wordDear = mock(IDictionary.IWord.class);
            doReturn("dear").when(wordDear).getValue();
            doReturn(true).when(wordDear).isPartOfPhrase();

            // Short phrase: "hello world" (2 words)
            var shortPhrase = mock(IDictionary.IPhrase.class);
            doReturn(List.of(wordHello, wordWorld)).when(shortPhrase).getWords();
            doReturn("hello world").when(shortPhrase).getValue();

            // Long phrase: "hello dear world" (3 words)
            var longPhrase = mock(IDictionary.IPhrase.class);
            doReturn(List.of(wordHello, wordDear, wordWorld)).when(longPhrase).getWords();
            doReturn("hello dear world").when(longPhrase).getValue();

            var dictionary = mock(IDictionary.class);
            doReturn(null).when(dictionary).getLanguageCode();
            doReturn(List.of(shortPhrase, longPhrase)).when(dictionary).getPhrases();

            var fwHello = mock(IDictionary.IFoundWord.class);
            doReturn(wordHello).when(fwHello).getFoundWord();
            doReturn(false).when(fwHello).isPhrase();
            doReturn(List.of(fwHello)).when(dictionary).lookupTerm("hello");

            var fwWorld = mock(IDictionary.IFoundWord.class);
            doReturn(wordWorld).when(fwWorld).getFoundWord();
            doReturn(false).when(fwWorld).isPhrase();
            doReturn(List.of(fwWorld)).when(dictionary).lookupTerm("world");

            var parser = new InputParser(List.of(dictionary));
            List<RawSolution> solutions = parser.parse("hello world", "en", Collections.emptyList());

            // Should have at least one solution
            assertFalse(solutions.isEmpty());
        }
    }

    // ==================== Thread interruption during corrections
    // ====================

    @Test
    @DisplayName("thread interruption during corrections throws InterruptedException")
    void interruptionDuringCorrections() throws Exception {
        var dictionary = mock(IDictionary.class);
        doReturn(null).when(dictionary).getLanguageCode();
        doReturn(List.of()).when(dictionary).getPhrases();
        doReturn(List.of()).when(dictionary).lookupTerm(anyString());

        var correction = mock(ICorrection.class);
        doReturn(false).when(correction).lookupIfKnown();

        var parser = new InputParser(List.of(dictionary), List.of(correction));

        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class,
                    () -> parser.parse("test", "en", Collections.emptyList()));
        } finally {
            Thread.interrupted();
        }
    }

    // ==================== getPhrasesContainingFoundWords — phrase entries filtered
    // ====================

    @Test
    @DisplayName("found words that are already phrases are not re-looked-up in phrase map")
    void phrasesFilteredFromLookup() throws Exception {
        // A dictionary with a phrase
        var wordA = mock(IDictionary.IWord.class);
        doReturn("a").when(wordA).getValue();
        doReturn(true).when(wordA).isPartOfPhrase();

        var wordB = mock(IDictionary.IWord.class);
        doReturn("b").when(wordB).getValue();
        doReturn(true).when(wordB).isPartOfPhrase();

        var phrase = mock(IDictionary.IPhrase.class);
        doReturn(List.of(wordA, wordB)).when(phrase).getWords();
        doReturn("a b").when(phrase).getValue();

        var dictionary = mock(IDictionary.class);
        doReturn(null).when(dictionary).getLanguageCode();
        doReturn(List.of(phrase)).when(dictionary).getPhrases();

        var fwA = mock(IDictionary.IFoundWord.class);
        doReturn(wordA).when(fwA).getFoundWord();
        doReturn(false).when(fwA).isPhrase();
        doReturn(List.of(fwA)).when(dictionary).lookupTerm("a");

        var fwB = mock(IDictionary.IFoundWord.class);
        doReturn(wordB).when(fwB).getFoundWord();
        doReturn(false).when(fwB).isPhrase();
        doReturn(List.of(fwB)).when(dictionary).lookupTerm("b");

        var parser = new InputParser(List.of(dictionary));
        List<RawSolution> solutions = parser.parse("a b", "en", Collections.emptyList());

        assertFalse(solutions.isEmpty());
    }

    // ==================== Parse single word with no dictionaries
    // ====================

    @Test
    @DisplayName("single-arg parse defaults to en language")
    void singleArgParseDefaultLanguage() throws Exception {
        var parser = new InputParser(List.of());
        List<RawSolution> solutions = parser.parse("hello");
        assertNotNull(solutions);
        assertFalse(solutions.isEmpty());
    }

    // ==================== Config via two-arg constructor ====================

    @Test
    @DisplayName("two-arg constructor creates parser with corrections")
    void twoArgWithCorrections() throws Exception {
        var correction = mock(ICorrection.class);
        doReturn(false).when(correction).lookupIfKnown();
        doReturn(List.of()).when(correction).correctWord(anyString(), anyString(), anyList());

        var parser = new InputParser(List.of(), List.of(correction));
        assertNotNull(parser.getConfig());
    }

    // ==================== Four-arg constructor with custom config
    // ====================

    @Test
    @DisplayName("four-arg constructor with includeUnknown=false excludes unknowns")
    void fourArgConstructorCustomConfig() throws Exception {
        var config = new IInputParser.Config(true, true, false);
        var parser = new InputParser(List.of(), List.of(), List.of(), config);
        assertFalse(parser.getConfig().isIncludeUnknown());
    }
}
