/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal.matches;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link MatchMatrix#getMatchingResults(int)} sits in the parser's hot
 * enumeration loop — it must not copy the whole match collection on every call.
 * <p>
 * Note that asserting on the value returned by {@code getMatchingResults}
 * cannot detect an un-memoized implementation:
 * {@code new ArrayList<>(values).get(i)} copies only the outer collection and
 * hands back the very same inner list, so {@code assertSame} on the result
 * holds either way. The memoization is therefore pinned by counting the
 * rebuilds and by the identity of the <em>outer</em> positional view.
 */
@DisplayName("MatchMatrix — positional lookup caching")
class MatchMatrixCachingTest {

    @Test
    @DisplayName("many lookups rebuild the positional view exactly once")
    void positionalViewIsBuiltOnce() {
        var matrix = new CountingMatchMatrix();
        matrix.addMatchingResult(0, "hello", new MatchingResult());
        matrix.addMatchingResult(1, "world", new MatchingResult());

        matrix.getMatchingResults(0);
        matrix.getMatchingResults(1);
        matrix.getMatchingResults(1);
        matrix.getMatchingResults(0);

        assertEquals(1, matrix.builds, "getMatchingResults must not rebuild the positional view on every call");
    }

    @Test
    @DisplayName("the positional view instance survives repeated lookups")
    void positionalViewInstanceIsReused() {
        var matrix = new MatchMatrix();
        matrix.addMatchingResult(0, "hello", new MatchingResult());
        matrix.addMatchingResult(1, "world", new MatchingResult());

        var viewBeforeLookups = matrix.matchingResultsByIndex();
        matrix.getMatchingResults(0);
        matrix.getMatchingResults(1);

        assertSame(viewBeforeLookups, matrix.matchingResultsByIndex());
    }

    @Test
    @DisplayName("a new input term causes exactly one further rebuild")
    void invalidationRebuildsOnlyOnce() {
        var matrix = new CountingMatchMatrix();
        matrix.addMatchingResult(0, "hello", new MatchingResult());
        matrix.getMatchingResults(0);

        matrix.addMatchingResult(1, "world", new MatchingResult());
        matrix.getMatchingResults(0);
        matrix.getMatchingResults(1);

        assertEquals(2, matrix.builds, "the view is rebuilt when invalidated — but only once, not per lookup");
    }

    @Test
    @DisplayName("repeated lookups return the same list")
    void repeatedLookupsReturnTheSameList() {
        var matrix = new MatchMatrix();
        matrix.addMatchingResult(0, "hello", new MatchingResult());
        matrix.addMatchingResult(1, "world", new MatchingResult());

        assertSame(matrix.getMatchingResults(1), matrix.getMatchingResults(1));
    }

    @Test
    @DisplayName("adding a new input term rebuilds the positional view")
    void addingNewTermInvalidatesCache() {
        var matrix = new MatchMatrix();
        matrix.addMatchingResult(0, "hello", new MatchingResult());
        assertNull(matrix.getMatchingResults(1));

        var staleView = matrix.matchingResultsByIndex();
        matrix.addMatchingResult(1, "world", new MatchingResult());

        assertNotSame(staleView, matrix.matchingResultsByIndex(), "a new input term must invalidate the cached positional view");
        assertEquals(1, matrix.getMatchingResults(1).size());
    }

    @Test
    @DisplayName("adding another result for a known term is visible without invalidation")
    void addingResultForKnownTermIsVisible() {
        var matrix = new MatchMatrix();
        matrix.addMatchingResult(0, "hello", new MatchingResult());
        assertEquals(1, matrix.getMatchingResults(0).size());

        var view = matrix.matchingResultsByIndex();
        matrix.addMatchingResult(0, "hello", new MatchingResult());

        assertSame(view, matrix.matchingResultsByIndex(), "appending to a known term shares the inner list — no rebuild needed");
        assertEquals(2, matrix.getMatchingResults(0).size());
    }

    /**
     * Counts how often the cache actually misses. The value handed back by
     * {@code getMatchingResults} is identical with and without the cache, so this
     * counter is the only thing that distinguishes them.
     */
    private static final class CountingMatchMatrix extends MatchMatrix {
        private int builds;

        @Override
        List<List<MatchingResult>> buildPositionalView() {
            builds++;
            return super.buildPositionalView();
        }
    }
}
