/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StringUtilitiesTest {

    // --- convertToSearchString ---

    @Test
    void convertToSearchString_plainText_wrapsWithWildcards() {
        String result = StringUtilities.convertToSearchString("hello");
        assertEquals(".*hello.*", result);
    }

    @Test
    void convertToSearchString_quotedText_exactMatch() {
        String result = StringUtilities.convertToSearchString("\"hello\"");
        assertEquals("hello", result);
    }

    @Test
    void convertToSearchString_emptyQuotes_returnsEmpty() {
        String result = StringUtilities.convertToSearchString("\"\"");
        assertEquals("", result);
    }

    @Test
    void convertToSearchString_withRegexMetaChars_escapedProperly() {
        String result = StringUtilities.convertToSearchString("a+b*c");
        // Should escape + and * as regex meta-characters
        assertTrue(result.contains("\\+"));
        assertTrue(result.contains("\\*"));
    }

    @Test
    void convertToSearchString_quotedWithRegexMetaChars_escapedProperly() {
        String result = StringUtilities.convertToSearchString("\"a(b)c\"");
        assertTrue(result.contains("\\("));
        assertTrue(result.contains("\\)"));
    }

    // --- escapeRegexChars ---

    @Test
    void escapeRegexChars_noSpecialChars_returnsUnchanged() {
        assertEquals("hello", StringUtilities.escapeRegexChars("hello"));
    }

    @Test
    void escapeRegexChars_allSpecialChars_allEscaped() {
        String input = ".*+?()[]{}|^$\\";
        String result = StringUtilities.escapeRegexChars(input);
        // Each special char should be preceded by backslash
        for (char c : input.toCharArray()) {
            assertTrue(result.contains("\\" + c));
        }
    }

    // --- joinStrings ---

    @Test
    void joinStrings_withDelimiter_joinsCorrectly() {
        assertEquals("a,b,c", StringUtilities.joinStrings(",", "a", "b", "c"));
    }

    @Test
    void joinStrings_withNullValues_skipsNulls() {
        assertEquals("a,c", StringUtilities.joinStrings(",", "a", null, "c"));
    }

    @Test
    void joinStrings_withCollection_joinsCorrectly() {
        assertEquals("a-b-c", StringUtilities.joinStrings("-", List.of("a", "b", "c")));
    }

    @Test
    void joinStrings_withEmptyArray_returnsEmpty() {
        assertEquals("", StringUtilities.joinStrings(","));
    }

    // --- parseCommaSeparatedString ---

    @Test
    void parseCommaSeparatedString_standard_parsesCorrectly() {
        List<String> result = StringUtilities.parseCommaSeparatedString("a, b, c");
        assertEquals(List.of("a", "b", "c"), result);
    }

    @Test
    void parseCommaSeparatedString_noSpaces_parsesCorrectly() {
        List<String> result = StringUtilities.parseCommaSeparatedString("x,y,z");
        assertEquals(List.of("x", "y", "z"), result);
    }

    @Test
    void parseCommaSeparatedString_singleItem_returnsSingleElement() {
        List<String> result = StringUtilities.parseCommaSeparatedString("only");
        assertEquals(List.of("only"), result);
    }
}
