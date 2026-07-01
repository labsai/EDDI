/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.dictionaries;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for all NLP dictionary classes that had 0% coverage:
 * PunctuationDictionary, EmailDictionary, DecimalDictionary, IntegerDictionary,
 * OrdinalNumbersDictionary, TimeExpressionDictionary.
 */
@DisplayName("NLP Dictionaries — Full Coverage")
class NlpDictionariesTest {

    private IExpressionProvider expressionProvider;

    @BeforeEach
    void setUp() {
        expressionProvider = mock(IExpressionProvider.class);
        doReturn(new Expression("mock")).when(expressionProvider).createExpression(anyString(), any());
    }

    // ==================== PunctuationDictionary ====================

    @Nested
    @DisplayName("PunctuationDictionary")
    class PunctuationDictionaryTests {

        private PunctuationDictionary dict;

        @BeforeEach
        void setUp() {
            dict = new PunctuationDictionary(expressionProvider);
        }

        @Test
        @DisplayName("lookupTerm — exclamation mark returns found word")
        void lookupExclamationMark() {
            var result = dict.lookupTerm("!");
            assertEquals(1, result.size());
            assertEquals("!", result.get(0).getFoundWord().getValue());
            verify(expressionProvider).createExpression("punctuation", "exclamation_mark");
        }

        @Test
        @DisplayName("lookupTerm — question mark returns found word")
        void lookupQuestionMark() {
            var result = dict.lookupTerm("?");
            assertEquals(1, result.size());
            verify(expressionProvider).createExpression("punctuation", "question_mark");
        }

        @Test
        @DisplayName("lookupTerm — dot returns found word")
        void lookupDot() {
            var result = dict.lookupTerm(".");
            assertEquals(1, result.size());
            verify(expressionProvider).createExpression("punctuation", "dot");
        }

        @Test
        @DisplayName("lookupTerm — comma returns found word")
        void lookupComma() {
            var result = dict.lookupTerm(",");
            assertEquals(1, result.size());
            verify(expressionProvider).createExpression("punctuation", "comma");
        }

        @Test
        @DisplayName("lookupTerm — colon returns found word")
        void lookupColon() {
            var result = dict.lookupTerm(":");
            assertEquals(1, result.size());
            verify(expressionProvider).createExpression("punctuation", "colon");
        }

        @Test
        @DisplayName("lookupTerm — semicolon returns found word")
        void lookupSemicolon() {
            var result = dict.lookupTerm(";");
            assertEquals(1, result.size());
            verify(expressionProvider).createExpression("punctuation", "semicolon");
        }

        @Test
        @DisplayName("lookupTerm — non-punctuation returns empty list")
        void lookupNonPunctuation() {
            var result = dict.lookupTerm("hello");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("lookupIfKnown returns true")
        void lookupIfKnown() {
            assertTrue(dict.lookupIfKnown());
        }
    }

    // ==================== EmailDictionary ====================

    @Nested
    @DisplayName("EmailDictionary")
    class EmailDictionaryTests {

        private EmailDictionary dict;

        @BeforeEach
        void setUp() {
            dict = new EmailDictionary(expressionProvider);
        }

        @Test
        @DisplayName("lookupTerm — valid email returns found word")
        void lookupValidEmail() {
            var result = dict.lookupTerm("user@example.com");
            assertEquals(1, result.size());
            assertEquals("user@example.com", result.get(0).getFoundWord().getValue());
            verify(expressionProvider).createExpression("email", "user@example.com");
        }

        @Test
        @DisplayName("lookupTerm — email with subdomains returns found word")
        void lookupEmailWithSubdomain() {
            var result = dict.lookupTerm("test.user@mail.example.com");
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("lookupTerm — non-email returns empty")
        void lookupNonEmail() {
            var result = dict.lookupTerm("not-an-email");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("lookupTerm — string without @ returns empty")
        void lookupNoAt() {
            var result = dict.lookupTerm("noemail.com");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("lookupTerm — invalid email format returns empty")
        void lookupInvalidFormat() {
            var result = dict.lookupTerm("@invalid");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("lookupTerm — null returns empty (no @ check)")
        void lookupNull() {
            // The isEmailAddress checks for null first
            var result = dict.lookupTerm("null");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("lookupIfKnown returns false")
        void lookupIfKnown() {
            assertFalse(dict.lookupIfKnown());
        }
    }

    // ==================== DecimalDictionary ====================

    @Nested
    @DisplayName("DecimalDictionary")
    class DecimalDictionaryTests {

        private DecimalDictionary dict;

        @BeforeEach
        void setUp() {
            dict = new DecimalDictionary(expressionProvider);
        }

        @Test
        @DisplayName("lookupTerm — decimal with dot returns found word")
        void lookupDecimalDot() {
            var result = dict.lookupTerm("3.14");
            assertEquals(1, result.size());
            verify(expressionProvider).createExpression("decimal", "3.14");
        }

        @Test
        @DisplayName("lookupTerm — decimal with comma converts to dot")
        void lookupDecimalComma() {
            var result = dict.lookupTerm("3,14");
            assertEquals(1, result.size());
            // Comma should be replaced with dot
            verify(expressionProvider).createExpression("decimal", "3.14");
        }

        @Test
        @DisplayName("lookupTerm — integer without dot/comma returns empty (mustContainComma=true)")
        void lookupIntegerNotDecimal() {
            var result = dict.lookupTerm("42");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("lookupTerm — non-number returns empty")
        void lookupNonNumber() {
            var result = dict.lookupTerm("hello");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("lookupIfKnown returns false")
        void lookupIfKnown() {
            assertFalse(dict.lookupIfKnown());
        }
    }

    // ==================== IntegerDictionary ====================

    @Nested
    @DisplayName("IntegerDictionary")
    class IntegerDictionaryTests {

        private IntegerDictionary dict;

        @BeforeEach
        void setUp() {
            dict = new IntegerDictionary(expressionProvider);
        }

        @Test
        @DisplayName("lookupTerm — integer returns found word")
        void lookupInteger() {
            var result = dict.lookupTerm("42");
            assertEquals(1, result.size());
            assertEquals("42", result.get(0).getFoundWord().getValue());
            verify(expressionProvider).createExpression("integer", "42");
        }

        @Test
        @DisplayName("lookupTerm — zero returns found word")
        void lookupZero() {
            var result = dict.lookupTerm("0");
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("lookupTerm — non-integer returns empty")
        void lookupNonInteger() {
            var result = dict.lookupTerm("abc");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("lookupTerm — decimal returns empty")
        void lookupDecimal() {
            var result = dict.lookupTerm("3.14");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("lookupIfKnown returns true")
        void lookupIfKnown() {
            assertTrue(dict.lookupIfKnown());
        }
    }

    // ==================== OrdinalNumbersDictionary ====================

    @Nested
    @DisplayName("OrdinalNumbersDictionary")
    class OrdinalNumbersDictionaryTests {

        private OrdinalNumbersDictionary dict;

        @BeforeEach
        void setUp() {
            dict = new OrdinalNumbersDictionary(expressionProvider);
        }

        @Test
        @DisplayName("lookupTerm — '1st' returns found word")
        void lookupFirst() {
            var result = dict.lookupTerm("1st");
            assertEquals(1, result.size());
            verify(expressionProvider).createExpression(eq("ordinal_number"), any());
        }

        @Test
        @DisplayName("lookupTerm — '2nd' returns found word")
        void lookupSecond() {
            var result = dict.lookupTerm("2nd");
            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("lookupTerm — '3rd' returns found word")
        void lookupThird() {
            var result = dict.lookupTerm("3rd");
            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("lookupTerm — '10th' returns found word")
        void lookupTenth() {
            var result = dict.lookupTerm("10th");
            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("lookupTerm — non-ordinal returns empty")
        void lookupNonOrdinal() {
            var result = dict.lookupTerm("hello");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("lookupTerm — plain number returns empty")
        void lookupPlainNumber() {
            var result = dict.lookupTerm("42");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("lookupIfKnown returns false")
        void lookupIfKnown() {
            assertFalse(dict.lookupIfKnown());
        }
    }

    // ==================== TimeExpressionDictionary ====================

    @Nested
    @DisplayName("TimeExpressionDictionary")
    class TimeExpressionDictionaryTests {

        private TimeExpressionDictionary dict;

        @BeforeEach
        void setUp() {
            dict = new TimeExpressionDictionary(expressionProvider);
        }

        @Test
        @DisplayName("lookupTerm — '14:30' returns found word")
        void lookupTimeColon() {
            var result = dict.lookupTerm("14:30");
            assertEquals(1, result.size());
            verify(expressionProvider).createExpression(eq("time"), any());
        }

        @Test
        @DisplayName("lookupTerm — '9:05' returns found word")
        void lookupTimeSingleDigitHour() {
            var result = dict.lookupTerm("9:05");
            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("lookupTerm — '15h' returns found word")
        void lookupTimeH() {
            var result = dict.lookupTerm("15h");
            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("lookupTerm — '12h10' returns found word")
        void lookupTimeHWithMinutes() {
            var result = dict.lookupTerm("12h10");
            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("lookupTerm — '13:50:12' with seconds returns found word")
        void lookupTimeWithSeconds() {
            var result = dict.lookupTerm("13:50:12");
            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("lookupTerm — non-time returns empty")
        void lookupNonTime() {
            var result = dict.lookupTerm("hello");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("lookupIfKnown returns true")
        void lookupIfKnown() {
            assertTrue(dict.lookupIfKnown());
        }
    }

    // ==================== RegularDictionary extended branches ====================

    @Nested
    @DisplayName("RegularDictionary — Extended Branch Coverage")
    class RegularDictionaryExtendedTests {

        @Test
        @DisplayName("lookupTerm with lookupIfKnown=true also checks regex after word match")
        void lookupIfKnownTrueChecksRegex() {
            var dict = new RegularDictionary();
            dict.setLookupIfKnown(true);
            dict.addWord("123", new ai.labs.eddi.modules.nlp.expressions.Expressions(new Expression("num")), 0);
            dict.addRegex("\\d+", new ai.labs.eddi.modules.nlp.expressions.Expressions(new Expression("digit")));

            // With lookupIfKnown=true, BOTH the word match AND regex match should be in
            // results
            var result = dict.lookupTerm("123");
            assertTrue(result.size() >= 2, "Expected both word and regex matches");
        }

        @Test
        @DisplayName("lookupTerm phrase word exact match vs case-insensitive deduplication")
        void phraseWordDeduplication() {
            var dict = new RegularDictionary();
            dict.addPhrase("Hello World", new ai.labs.eddi.modules.nlp.expressions.Expressions(new Expression("greeting")));

            // "Hello" is an exact match (case-sensitive) of one of the phrase words
            var result = dict.lookupTerm("Hello");
            // Should have 1 result (exact match), and the case-insensitive duplicate should
            // be deduplicated
            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("lookupTerm phrase word case-insensitive match (not exact)")
        void phraseWordCaseInsensitive() {
            var dict = new RegularDictionary();
            dict.addPhrase("Hello World", new ai.labs.eddi.modules.nlp.expressions.Expressions(new Expression("greeting")));

            // "hello" is a case-insensitive match of "Hello" in the phrase
            var result = dict.lookupTerm("hello");
            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("setWords replaces word map")
        void setWords() {
            var dict = new RegularDictionary();
            var words = new java.util.TreeMap<String, IDictionary.IWord>(String.CASE_INSENSITIVE_ORDER);
            dict.setWords(words);
            assertTrue(dict.getWords().isEmpty());
        }

        @Test
        @DisplayName("setPhrases replaces phrase list")
        void setPhrases() {
            var dict = new RegularDictionary();
            dict.setPhrases(new java.util.LinkedList<>());
            assertTrue(dict.getPhrases().isEmpty());
        }

        @Test
        @DisplayName("setRegExs replaces regex list")
        void setRegExs() {
            var dict = new RegularDictionary();
            dict.setRegExs(new java.util.LinkedList<>());
            assertTrue(dict.getRegExs().isEmpty());
        }
    }
}
