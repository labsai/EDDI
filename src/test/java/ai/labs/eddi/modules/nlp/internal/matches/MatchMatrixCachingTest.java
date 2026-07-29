/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal.matches;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link MatchMatrix#getMatchingResults(int)} sits in the parser's hot
 * enumeration loop — it must not copy the whole match collection on every call.
 */
@DisplayName("MatchMatrix — positional lookup caching")
class MatchMatrixCachingTest {

    @Test
    @DisplayName("repeated lookups reuse the same positional view")
    void repeatedLookupsAreCached() {
        var matrix = new MatchMatrix();
        matrix.addMatchingResult(0, "hello", new MatchingResult());
        matrix.addMatchingResult(1, "world", new MatchingResult());

        List<MatchingResult> first = matrix.getMatchingResults(1);
        List<MatchingResult> second = matrix.getMatchingResults(1);

        assertSame(first, second, "getMatchingResults must not rebuild the positional view on every call");
    }

    @Test
    @DisplayName("adding a new input term invalidates the cached view")
    void addingNewTermInvalidatesCache() {
        var matrix = new MatchMatrix();
        matrix.addMatchingResult(0, "hello", new MatchingResult());
        assertNull(matrix.getMatchingResults(1));

        matrix.addMatchingResult(1, "world", new MatchingResult());

        assertEquals(1, matrix.getMatchingResults(1).size());
    }

    @Test
    @DisplayName("adding another result for a known term is visible without invalidation")
    void addingResultForKnownTermIsVisible() {
        var matrix = new MatchMatrix();
        matrix.addMatchingResult(0, "hello", new MatchingResult());
        assertEquals(1, matrix.getMatchingResults(0).size());

        matrix.addMatchingResult(0, "hello", new MatchingResult());

        assertEquals(2, matrix.getMatchingResults(0).size());
    }
}
