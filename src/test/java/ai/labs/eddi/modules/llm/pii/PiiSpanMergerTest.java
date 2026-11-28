/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PiiSpanMergerTest {

    private static final String TEXT = "Musterkanzlei GmbH, Max Muster, DE89 3704 0044 0532 0130 00";

    private static PiiSpan span(int start, int end, String type, double score, String detector) {
        return new PiiSpan(start, end, type, score, detector);
    }

    @Test
    @DisplayName("the longer span wins an overlap; the result is sorted by start")
    void longerWins() {
        var shortSpan = span(32, 46, "PHONE", 1.0, "builtin");
        var longSpan = span(32, 59, "IBAN", 1.0, "builtin");
        var name = span(20, 30, "PERSON", 0.7, "presidio");
        assertEquals(List.of(name, longSpan), PiiSpanMerger.merge(TEXT, List.of(shortSpan, longSpan, name), PiiSpanMerger.Rules.NONE));
    }

    @Test
    @DisplayName("among equal lengths the higher score wins, then the earlier detector")
    void scoreThenOrder() {
        var low = span(20, 30, "LOCATION", 0.5, "presidio");
        var high = span(20, 30, "PERSON", 0.9, "model");
        assertEquals(List.of(high), PiiSpanMerger.merge(TEXT, List.of(low, high), PiiSpanMerger.Rules.NONE));
        var first = span(20, 30, "PERSON", 0.9, "presidio");
        var second = span(20, 30, "ORGANIZATION", 0.9, "model");
        assertEquals(List.of(first), PiiSpanMerger.merge(TEXT, List.of(first, second), PiiSpanMerger.Rules.NONE));
    }

    @Test
    @DisplayName("an always-replace term wins over a longer span")
    void termsPinned() {
        var term = span(24, 30, "TERM", 0.1, PiiSpanMerger.TERMS_DETECTOR_ID);
        var longer = span(20, 30, "PERSON", 1.0, "presidio");
        assertEquals(List.of(term), PiiSpanMerger.merge(TEXT, List.of(longer, term), PiiSpanMerger.Rules.NONE));
    }

    @Test
    @DisplayName("a span inside a never-term occurrence is dropped, case-insensitively")
    void neverTerms() {
        var firm = span(0, 13, "ORGANIZATION", 0.9, "presidio");
        var person = span(20, 30, "PERSON", 0.9, "presidio");
        var rules = new PiiSpanMerger.Rules(List.of("musterkanzlei gmbh"), List.of());
        assertEquals(List.of(person), PiiSpanMerger.merge(TEXT, List.of(firm, person), rules));
    }

    @Test
    @DisplayName("a span overlapping a skip range (a placeholder EDDI wrote) is dropped")
    void skipRanges() {
        var person = span(20, 30, "PERSON", 0.9, "presidio");
        var iban = span(32, 59, "IBAN", 1.0, "builtin");
        var rules = new PiiSpanMerger.Rules(List.of(), List.of(new TextRange(25, 26)));
        assertEquals(List.of(iban), PiiSpanMerger.merge(TEXT, List.of(person, iban), rules));
    }

    @Test
    @DisplayName("a span past the end of the text is dropped rather than failing the replace")
    void outOfBounds() {
        assertEquals(List.of(), PiiSpanMerger.merge("short", List.of(span(2, 10, "X", 1.0, "model")), PiiSpanMerger.Rules.NONE));
    }

    @Test
    void invalidSpan() {
        assertThrows(IllegalArgumentException.class, () -> span(5, 5, "X", 1.0, "builtin"));
        assertThrows(IllegalArgumentException.class, () -> span(-1, 3, "X", 1.0, "builtin"));
    }

    @Test
    @DisplayName("term occurrences are whole-word: 'Mai' is not found in 'Maine'")
    void termMatcherWholeWord() {
        assertEquals(List.of(new TextRange(0, 3), new TextRange(18, 21)), TermMatcher.occurrences("Mai visits Maine; MAI", List.of("mai")));
        assertEquals(List.of(), TermMatcher.occurrences("anything", List.of(" ", "")));
    }
}
