/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StringUtilitiesTest {

    // --- searchText ---

    @Test
    void searchText_plainText_isTakenLiterally() {
        assertEquals("a+b*c", StringUtilities.searchText("a+b*c"));
    }

    @Test
    void searchText_quotedText_dropsTheQuotes() {
        assertEquals("hello world", StringUtilities.searchText("\"hello world\""));
    }

    @Test
    void searchText_emptyOrLoneQuote_isAnEmptySearch() {
        assertEquals("", StringUtilities.searchText("\"\""));
        assertEquals("", StringUtilities.searchText("\""));
    }

    @Test
    void searchText_quoteOnOneSideOnly_isKept() {
        assertEquals("\"hello", StringUtilities.searchText("\"hello"));
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
