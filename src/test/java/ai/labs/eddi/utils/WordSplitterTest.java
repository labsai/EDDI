/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WordSplitterTest {

    @Test
    void splitWords_punctuationAtEnd_addSpaces() {
        StringBuilder sb = new StringBuilder("hello!");
        new WordSplitter(sb).splitWords();
        assertTrue(sb.toString().contains(" !"));
    }

    @Test
    void splitWords_commaInSentence_addSpaces() {
        StringBuilder sb = new StringBuilder("hello,world");
        new WordSplitter(sb).splitWords();
        assertTrue(sb.toString().contains(" ,"));
    }

    @Test
    void splitWords_dotBetweenDigits_noSplit() {
        StringBuilder sb = new StringBuilder("value is 3.14 ok");
        new WordSplitter(sb).splitWords();
        assertTrue(sb.toString().contains("3.14"));
    }

    @Test
    void splitWords_noPunctuation_unchanged() {
        StringBuilder sb = new StringBuilder("hello world");
        new WordSplitter(sb).splitWords();
        assertEquals("hello world", sb.toString());
    }

    @Test
    void capitalizedWords_insertsSpaces() {
        StringBuilder sb = new StringBuilder("helloWorld");
        new WordSplitter(sb).capitalizedWords();
        assertTrue(sb.toString().contains(" W"));
    }

    @Test
    void notAlphabetic_separatesSpecialChars() {
        StringBuilder sb = new StringBuilder("hello@world");
        new WordSplitter(sb).notAlphabetic();
        assertTrue(sb.toString().contains(" @ "));
    }
}
