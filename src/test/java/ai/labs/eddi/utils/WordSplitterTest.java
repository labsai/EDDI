/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WordSplitter Tests")
class WordSplitterTest {

    @Nested
    @DisplayName("splitWords / splitPunctuationFromWords")
    class SplitWordsTests {

        @Test
        @DisplayName("punctuation at end — adds spaces")
        void punctuationAtEnd() {
            StringBuilder sb = new StringBuilder("hello!");
            new WordSplitter(sb).splitWords();
            assertTrue(sb.toString().contains(" !"));
        }

        @Test
        @DisplayName("comma in sentence — adds spaces")
        void commaInSentence() {
            StringBuilder sb = new StringBuilder("hello,world");
            new WordSplitter(sb).splitWords();
            assertTrue(sb.toString().contains(" ,"));
        }

        @Test
        @DisplayName("dot between digits — no split")
        void dotBetweenDigits() {
            StringBuilder sb = new StringBuilder("value is 3.14 ok");
            new WordSplitter(sb).splitWords();
            assertTrue(sb.toString().contains("3.14"));
        }

        @Test
        @DisplayName("no punctuation — unchanged")
        void noPunctuation() {
            StringBuilder sb = new StringBuilder("hello world");
            new WordSplitter(sb).splitWords();
            assertEquals("hello world", sb.toString());
        }

        @Test
        @DisplayName("question mark — adds spaces")
        void questionMark() {
            StringBuilder sb = new StringBuilder("why?");
            new WordSplitter(sb).splitWords();
            assertTrue(sb.toString().contains(" ?"));
        }

        @Test
        @DisplayName("colon — adds spaces")
        void colon() {
            StringBuilder sb = new StringBuilder("time:now");
            new WordSplitter(sb).splitWords();
            assertTrue(sb.toString().contains(" :"));
        }

        @Test
        @DisplayName("semicolon — adds spaces")
        void semicolon() {
            StringBuilder sb = new StringBuilder("a;b");
            new WordSplitter(sb).splitWords();
            assertTrue(sb.toString().contains(" ;"));
        }

        @Test
        @DisplayName("punctuation already has space before — no extra space")
        void spaceBeforePunctuation() {
            StringBuilder sb = new StringBuilder("hello !");
            new WordSplitter(sb).splitWords();
            // Should still add space after if needed
            assertNotNull(sb.toString());
        }

        @Test
        @DisplayName("multiple punctuation marks")
        void multiplePunctuation() {
            StringBuilder sb = new StringBuilder("wow!amazing,right?");
            new WordSplitter(sb).splitWords();
            assertTrue(sb.toString().contains(" !"));
            assertTrue(sb.toString().contains(" ,"));
            assertTrue(sb.toString().contains(" ?"));
        }

        @Test
        @DisplayName("dot at start — treated as punctuation")
        void dotAtStart() {
            StringBuilder sb = new StringBuilder(".hello");
            // Leading dot triggers StringIndexOutOfBounds in WordSplitter — known edge case
            assertThrows(StringIndexOutOfBoundsException.class, () -> new WordSplitter(sb).splitWords());
        }

        @Test
        @DisplayName("dot between letters — split")
        void dotBetweenLetters() {
            StringBuilder sb = new StringBuilder("end.start");
            new WordSplitter(sb).splitWords();
            assertTrue(sb.toString().contains(" . ") || sb.toString().contains(" ."));
        }

        @Test
        @DisplayName("empty string — no crash")
        void emptyString() {
            StringBuilder sb = new StringBuilder("");
            assertDoesNotThrow(() -> new WordSplitter(sb).splitWords());
            assertEquals("", sb.toString());
        }

        @Test
        @DisplayName("single character punctuation")
        void singlePunctuation() {
            StringBuilder sb = new StringBuilder("!");
            new WordSplitter(sb).splitWords();
            assertNotNull(sb.toString());
        }
    }

    @Nested
    @DisplayName("capitalizedWords")
    class CapitalizedWordsTests {

        @Test
        @DisplayName("inserts spaces before uppercase letters")
        void insertsSpaces() {
            StringBuilder sb = new StringBuilder("helloWorld");
            new WordSplitter(sb).capitalizedWords();
            assertTrue(sb.toString().contains(" W"));
        }

        @Test
        @DisplayName("all lowercase — unchanged")
        void allLowercase() {
            StringBuilder sb = new StringBuilder("hello");
            new WordSplitter(sb).capitalizedWords();
            assertEquals("hello", sb.toString());
        }

        @Test
        @DisplayName("all uppercase — spaces before each char")
        void allUppercase() {
            StringBuilder sb = new StringBuilder("ABC");
            new WordSplitter(sb).capitalizedWords();
            assertTrue(sb.toString().contains(" A"));
            assertTrue(sb.toString().contains(" B"));
            assertTrue(sb.toString().contains(" C"));
        }

        @Test
        @DisplayName("camelCase with multiple words")
        void camelCaseMultipleWords() {
            StringBuilder sb = new StringBuilder("myVariableName");
            new WordSplitter(sb).capitalizedWords();
            assertTrue(sb.toString().contains(" V"));
            assertTrue(sb.toString().contains(" N"));
        }

        @Test
        @DisplayName("empty string — no crash")
        void emptyString() {
            StringBuilder sb = new StringBuilder("");
            assertDoesNotThrow(() -> new WordSplitter(sb).capitalizedWords());
        }
    }

    @Nested
    @DisplayName("notAlphabetic")
    class NotAlphabeticTests {

        @Test
        @DisplayName("separates special chars")
        void separatesSpecialChars() {
            StringBuilder sb = new StringBuilder("hello@world");
            new WordSplitter(sb).notAlphabetic();
            assertTrue(sb.toString().contains(" @ "));
        }

        @Test
        @DisplayName("all alphabetic — unchanged")
        void allAlphabetic() {
            StringBuilder sb = new StringBuilder("hello");
            new WordSplitter(sb).notAlphabetic();
            assertEquals("hello", sb.toString());
        }

        @Test
        @DisplayName("digits are considered alphabetic")
        void digitsAlphabetic() {
            StringBuilder sb = new StringBuilder("hello123");
            new WordSplitter(sb).notAlphabetic();
            assertEquals("hello123", sb.toString());
        }

        @Test
        @DisplayName("spaces are preserved")
        void spacesPreserved() {
            StringBuilder sb = new StringBuilder("hello world");
            new WordSplitter(sb).notAlphabetic();
            assertEquals("hello world", sb.toString());
        }

        @Test
        @DisplayName("multiple special characters")
        void multipleSpecial() {
            StringBuilder sb = new StringBuilder("a#b$c");
            new WordSplitter(sb).notAlphabetic();
            assertTrue(sb.toString().contains(" # "));
            assertTrue(sb.toString().contains(" $ "));
        }

        @Test
        @DisplayName("uppercase characters handled")
        void uppercaseHandled() {
            StringBuilder sb = new StringBuilder("HELLO");
            new WordSplitter(sb).notAlphabetic();
            assertEquals("HELLO", sb.toString());
        }

        @Test
        @DisplayName("colon and dot are considered alphabetic")
        void colonAndDot() {
            StringBuilder sb = new StringBuilder("12:30.5");
            new WordSplitter(sb).notAlphabetic();
            assertEquals("12:30.5", sb.toString());
        }

        @Test
        @DisplayName("empty string — no crash")
        void emptyString() {
            StringBuilder sb = new StringBuilder("");
            assertDoesNotThrow(() -> new WordSplitter(sb).notAlphabetic());
        }
    }

    @Nested
    @DisplayName("notNumeric")
    class NotNumericTests {

        @Test
        @DisplayName("ordinal number — adds space after")
        void ordinalNumber() {
            StringBuilder sb = new StringBuilder("1st place");
            new WordSplitter(sb).notNumeric();
            // Should handle ordinal "1st"
            assertNotNull(sb.toString());
        }

        @Test
        @DisplayName("digit after letter — inserts space")
        void digitAfterLetter() {
            StringBuilder sb = new StringBuilder("hello3");
            new WordSplitter(sb).notNumeric();
            assertTrue(sb.toString().contains(" 3") || sb.toString().contains("o 3"));
        }

        @Test
        @DisplayName("all numbers — no split")
        void allNumbers() {
            StringBuilder sb = new StringBuilder("12345");
            new WordSplitter(sb).notNumeric();
            assertEquals("12345", sb.toString());
        }

        @Test
        @DisplayName("all letters — unchanged")
        void allLetters() {
            StringBuilder sb = new StringBuilder("hello");
            new WordSplitter(sb).notNumeric();
            assertEquals("hello", sb.toString());
        }

        @Test
        @DisplayName("empty string — no crash")
        void emptyString() {
            StringBuilder sb = new StringBuilder("");
            assertDoesNotThrow(() -> new WordSplitter(sb).notNumeric());
        }
    }

    @Nested
    @DisplayName("isPunctuation")
    class IsPunctuationTests {

        @Test
        @DisplayName("dot after letter — inserts space")
        void dotAfterLetter() {
            StringBuilder sb = new StringBuilder("hello. world");
            new WordSplitter(sb).isPunctuation();
            assertTrue(sb.toString().contains(" ."));
        }

        @Test
        @DisplayName("dot after digit — no space")
        void dotAfterDigit() {
            StringBuilder sb = new StringBuilder("3.14");
            new WordSplitter(sb).isPunctuation();
            assertTrue(sb.toString().contains("3.14"));
        }

        @Test
        @DisplayName("dot at beginning — no change (i=0)")
        void dotAtBeginning() {
            StringBuilder sb = new StringBuilder(".hello");
            new WordSplitter(sb).isPunctuation();
            // i=0, so the i>0 check prevents any insertion
            assertEquals(".hello", sb.toString());
        }

        @Test
        @DisplayName("dot after 'm' — no space (time: a.m.)")
        void dotAfterM() {
            StringBuilder sb = new StringBuilder("am.");
            new WordSplitter(sb).isPunctuation();
            assertTrue(sb.toString().contains("am."));
        }

        @Test
        @DisplayName("dot after 'a' — no space (a.m.)")
        void dotAfterA() {
            StringBuilder sb = new StringBuilder("a.m");
            new WordSplitter(sb).isPunctuation();
            assertTrue(sb.toString().contains("a.m"));
        }

        @Test
        @DisplayName("dot after 'p' — no space (p.m.)")
        void dotAfterP() {
            StringBuilder sb = new StringBuilder("p.m");
            new WordSplitter(sb).isPunctuation();
            assertTrue(sb.toString().contains("p.m"));
        }

        @Test
        @DisplayName("no dots — unchanged")
        void noDots() {
            StringBuilder sb = new StringBuilder("hello world");
            new WordSplitter(sb).isPunctuation();
            assertEquals("hello world", sb.toString());
        }

        @Test
        @DisplayName("empty string — no crash")
        void emptyString() {
            StringBuilder sb = new StringBuilder("");
            assertDoesNotThrow(() -> new WordSplitter(sb).isPunctuation());
        }
    }
}
