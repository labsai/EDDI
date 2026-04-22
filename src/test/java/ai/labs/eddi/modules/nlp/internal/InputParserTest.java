package ai.labs.eddi.modules.nlp.internal;

import ai.labs.eddi.modules.nlp.IInputParser;
import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.extensions.normalizers.INormalizer;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("InputParser Tests")
class InputParserTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("default config is created when none provided")
        void defaultConfig() {
            var parser = new InputParser(List.of());
            assertNotNull(parser.getConfig());
            assertTrue(parser.getConfig().isAppendExpressions());
            assertTrue(parser.getConfig().isIncludeUnused());
            assertTrue(parser.getConfig().isIncludeUnknown());
        }

        @Test
        @DisplayName("custom config is stored")
        void customConfig() {
            var config = new IInputParser.Config(false, false, true);
            var parser = new InputParser(List.of(), List.of(), List.of(), config);
            assertEquals(config, parser.getConfig());
        }
    }

    @Nested
    @DisplayName("normalize")
    class NormalizeTests {

        @Test
        @DisplayName("trims and collapses whitespace")
        void trimAndCollapse() throws Exception {
            var parser = new InputParser(List.of());
            assertEquals("hello world", parser.normalize("  hello   world  ", "en"));
        }

        @Test
        @DisplayName("normalizer is invoked in order")
        void normalizerInvoked() throws Exception {
            var normalizer = mock(INormalizer.class);
            when(normalizer.normalize("hello", "en")).thenReturn("HELLO");

            var parser = new InputParser(List.of(normalizer), List.of(), List.of(), new IInputParser.Config());
            String result = parser.normalize("hello", "en");

            assertEquals("HELLO", result);
            verify(normalizer).normalize("hello", "en");
        }

        @Test
        @DisplayName("null language defaults to 'en'")
        void nullLanguageDefault() throws Exception {
            var normalizer = mock(INormalizer.class);
            when(normalizer.normalize("test", "en")).thenReturn("test");

            var parser = new InputParser(List.of(normalizer), List.of(), List.of(), new IInputParser.Config());
            parser.normalize("test", null);

            verify(normalizer).normalize("test", "en");
        }

        @Test
        @DisplayName("multiple normalizers are chained")
        void chainedNormalizers() throws Exception {
            var n1 = mock(INormalizer.class);
            when(n1.normalize("hello!", "en")).thenReturn("hello");

            var n2 = mock(INormalizer.class);
            when(n2.normalize("hello", "en")).thenReturn("hi");

            var parser = new InputParser(List.of(n1, n2), List.of(), List.of(), new IInputParser.Config());
            String result = parser.normalize("hello!", "en");

            assertEquals("hi", result);
        }
    }

    @Nested
    @DisplayName("parse")
    class ParseTests {

        @Test
        @DisplayName("unknown word returns solution with unknown entry")
        void unknownWord() throws Exception {
            var parser = new InputParser(List.of());
            List<RawSolution> solutions = parser.parse("xyz123");

            assertEquals(1, solutions.size());
            // With no dictionaries but includeUnknown=true (default), unknown words produce
            // PARTLY
            assertEquals(RawSolution.Match.PARTLY, solutions.get(0).getMatch());
        }

        @Test
        @DisplayName("empty string returns solution with NOTHING match")
        void emptyString() throws Exception {
            var parser = new InputParser(List.of());
            List<RawSolution> solutions = parser.parse("");

            // Empty string splits to [""], which is treated as an unknown word
            assertNotNull(solutions);
            assertFalse(solutions.isEmpty());
            assertEquals(RawSolution.Match.PARTLY, solutions.get(0).getMatch());
        }

        @Test
        @DisplayName("dictionary word is looked up correctly")
        void dictionaryLookup() throws Exception {
            var dictionary = mock(IDictionary.class);
            when(dictionary.getLanguageCode()).thenReturn("en");
            when(dictionary.getPhrases()).thenReturn(List.of());

            var foundWord = mock(IDictionary.IFoundWord.class);
            var word = mock(IDictionary.IWord.class);
            when(foundWord.getFoundWord()).thenReturn(word);
            when(word.isPartOfPhrase()).thenReturn(false);
            when(foundWord.isPhrase()).thenReturn(false);

            when(dictionary.lookupTerm("hello")).thenReturn(List.of(foundWord));

            var parser = new InputParser(List.of(dictionary));
            List<RawSolution> solutions = parser.parse("hello", "en", Collections.emptyList());

            assertFalse(solutions.isEmpty());
            verify(dictionary).lookupTerm("hello");
        }

        @Test
        @DisplayName("language mismatch — dictionary is skipped")
        void languageMismatch() throws Exception {
            var dictionary = mock(IDictionary.class);
            when(dictionary.getLanguageCode()).thenReturn("de");
            when(dictionary.getPhrases()).thenReturn(List.of());

            var parser = new InputParser(List.of(dictionary));
            parser.parse("hello", "en", Collections.emptyList());

            // Dictionary has language "de" but user language is "en" — skipped
            verify(dictionary, never()).lookupTerm(anyString());
        }

        @Test
        @DisplayName("correction is applied when word is unknown")
        void correctionApplied() throws Exception {
            var dictionary = mock(IDictionary.class);
            when(dictionary.getLanguageCode()).thenReturn(null);
            when(dictionary.getPhrases()).thenReturn(List.of());
            when(dictionary.lookupTerm(anyString())).thenReturn(List.of());

            var correction = mock(ICorrection.class);
            when(correction.lookupIfKnown()).thenReturn(false);

            var correctedWord = mock(IDictionary.IFoundWord.class);
            var word = mock(IDictionary.IWord.class);
            when(correctedWord.getFoundWord()).thenReturn(word);
            when(word.isPartOfPhrase()).thenReturn(false);
            when(correctedWord.isPhrase()).thenReturn(false);

            when(correction.correctWord(eq("helo"), eq("en"), anyList())).thenReturn(List.of(correctedWord));

            var parser = new InputParser(List.of(dictionary), List.of(correction));
            List<RawSolution> solutions = parser.parse("helo", "en", Collections.emptyList());

            assertFalse(solutions.isEmpty());
            verify(correction).correctWord(eq("helo"), eq("en"), anyList());
        }

        @Test
        @DisplayName("multiple words — each is looked up independently")
        void multipleWords() throws Exception {
            var dictionary = mock(IDictionary.class);
            when(dictionary.getLanguageCode()).thenReturn(null);
            when(dictionary.getPhrases()).thenReturn(List.of());
            when(dictionary.lookupTerm(anyString())).thenReturn(List.of());

            var parser = new InputParser(List.of(dictionary));
            parser.parse("hello world", "en", Collections.emptyList());

            verify(dictionary).lookupTerm("hello");
            verify(dictionary).lookupTerm("world");
        }
    }

    @Nested
    @DisplayName("Config")
    class ConfigTests {

        @Test
        @DisplayName("equals and hashCode")
        void equalsAndHashCode() {
            var c1 = new IInputParser.Config(true, true, true);
            var c2 = new IInputParser.Config(true, true, true);
            var c3 = new IInputParser.Config(false, true, true);

            assertEquals(c1, c2);
            assertEquals(c1.hashCode(), c2.hashCode());
            assertNotEquals(c1, c3);
        }

        @Test
        @DisplayName("toString contains field values")
        void testToString() {
            var config = new IInputParser.Config(true, false, true);
            String str = config.toString();
            assertTrue(str.contains("appendExpressions=true"));
            assertTrue(str.contains("includeUnused=false"));
            assertTrue(str.contains("includeUnknown=true"));
        }

        @Test
        @DisplayName("setters modify values")
        void setters() {
            var config = new IInputParser.Config();
            config.setAppendExpressions(false);
            config.setIncludeUnused(false);
            config.setIncludeUnknown(false);

            assertFalse(config.isAppendExpressions());
            assertFalse(config.isIncludeUnused());
            assertFalse(config.isIncludeUnknown());
        }
    }
}
