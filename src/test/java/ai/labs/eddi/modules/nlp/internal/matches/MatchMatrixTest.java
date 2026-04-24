/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal.matches;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MatchMatrix Tests")
class MatchMatrixTest {

    @Nested
    @DisplayName("addMatchingResult / getMatchingResults")
    class AddAndGetTests {

        @Test
        @DisplayName("add and retrieve single result")
        void addAndRetrieve() {
            var matrix = new MatchMatrix();
            var result = new MatchingResult();

            matrix.addMatchingResult(0, "hello", result);

            List<MatchingResult> results = matrix.getMatchingResults(0);
            assertNotNull(results);
            assertEquals(1, results.size());
            assertSame(result, results.get(0));
        }

        @Test
        @DisplayName("multiple results for same index/term")
        void multipleResultsSameKey() {
            var matrix = new MatchMatrix();
            var r1 = new MatchingResult();
            var r2 = new MatchingResult();

            matrix.addMatchingResult(0, "hello", r1);
            matrix.addMatchingResult(0, "hello", r2);

            List<MatchingResult> results = matrix.getMatchingResults(0);
            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("different terms create separate entries")
        void differentTerms() {
            var matrix = new MatchMatrix();
            matrix.addMatchingResult(0, "hello", new MatchingResult());
            matrix.addMatchingResult(1, "world", new MatchingResult());

            assertNotNull(matrix.getMatchingResults(0));
            assertNotNull(matrix.getMatchingResults(1));
        }

        @Test
        @DisplayName("getMatchingResults returns null for out-of-bounds index")
        void outOfBoundsReturnsNull() {
            var matrix = new MatchMatrix();
            assertNull(matrix.getMatchingResults(0));
            assertNull(matrix.getMatchingResults(5));
        }
    }

    @Nested
    @DisplayName("Iterator / SolutionIterator")
    class IteratorTests {

        @Test
        @DisplayName("empty matrix — iterator has no elements")
        void emptyMatrix() {
            var matrix = new MatchMatrix();
            var it = matrix.iterator();
            assertFalse(it.hasNext());
        }

        @Test
        @DisplayName("single entry — iterator yields one suggestion")
        void singleEntry() {
            var matrix = new MatchMatrix();
            matrix.addMatchingResult(0, "hello", new MatchingResult());

            var suggestions = new ArrayList<Suggestion>();
            matrix.iterator().forEachRemaining(suggestions::add);

            assertEquals(1, suggestions.size());
        }

        @Test
        @DisplayName("two entries — yields combinatorial suggestions")
        void twoEntries() {
            var matrix = new MatchMatrix();
            matrix.addMatchingResult(0, "hello", new MatchingResult());
            matrix.addMatchingResult(1, "world", new MatchingResult());

            var suggestions = new ArrayList<Suggestion>();
            matrix.iterator().forEachRemaining(suggestions::add);

            assertTrue(suggestions.size() >= 1);
        }

        @Test
        @DisplayName("next() throws NoSuchElementException when exhausted")
        void nextThrowsWhenExhausted() {
            var matrix = new MatchMatrix();
            var it = matrix.iterator();
            assertThrows(NoSuchElementException.class, it::next);
        }

        @Test
        @DisplayName("for-each loop works")
        void forEachLoop() {
            var matrix = new MatchMatrix();
            matrix.addMatchingResult(0, "test", new MatchingResult());

            int count = 0;
            for (Suggestion s : matrix) {
                assertNotNull(s);
                count++;
            }
            assertTrue(count > 0);
        }
    }

    @Nested
    @DisplayName("MatchingResult")
    class MatchingResultTests {

        @Test
        @DisplayName("default is not corrected")
        void defaultNotCorrected() {
            var result = new MatchingResult();
            assertFalse(result.isCorrected());
        }

        @Test
        @DisplayName("result list starts empty")
        void emptyResults() {
            var result = new MatchingResult();
            assertNotNull(result.getResult());
            assertTrue(result.getResult().isEmpty());
        }
    }
}
