/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag.evaluation;

import ai.labs.eddi.modules.rag.evaluation.RetrievalMetrics.Aggregate;
import ai.labs.eddi.modules.rag.evaluation.RetrievalMetrics.Comparison;
import ai.labs.eddi.modules.rag.evaluation.RetrievalMetrics.Observation;
import ai.labs.eddi.modules.rag.evaluation.RetrievalMetrics.QueryScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalMetricsTest {

    private static final double DELTA = 1e-12;

    private static List<List<String>> ranked(String... firstIds) {
        return Arrays.stream(firstIds).map(id -> id == null ? List.<String>of() : List.of(id)).toList();
    }

    @Nested
    @DisplayName("scoring one query")
    class Scoring {

        @Test
        @DisplayName("the first hit at rank 3 gives reciprocal rank 1/3")
        void firstHitRank() {
            QueryScore score = RetrievalMetrics.score(ranked("a", "b", "doc", "c"), List.of("doc"), 5);

            assertEquals(3, score.firstHitRank());
            assertTrue(score.hit());
            assertEquals(1.0 / 3, score.reciprocalRank(), DELTA);
            assertEquals(1.0, score.recall(), DELTA);
            assertEquals(4, score.resultCount());
        }

        @Test
        @DisplayName("a hit below the cut-off is no hit")
        void hitBeyondK() {
            QueryScore score = RetrievalMetrics.score(ranked("a", "b", "doc"), List.of("doc"), 2);

            assertNull(score.firstHitRank());
            assertFalse(score.hit());
            assertEquals(0.0, score.reciprocalRank(), DELTA);
            assertEquals(0.0, score.recall(), DELTA);
            assertEquals(2, score.resultCount());
            assertEquals(List.of("doc"), score.missing());
        }

        @Test
        @DisplayName("recall is the share of distinct expected documents found")
        void partialRecall() {
            QueryScore score = RetrievalMetrics.score(ranked("x", "b", "y"), List.of("a", "b", "c", "d"), 3);

            assertEquals(2, score.firstHitRank());
            assertEquals(0.25, score.recall(), DELTA);
            assertEquals(List.of("b"), score.found());
            assertEquals(List.of("a", "c", "d"), score.missing());
        }

        @Test
        @DisplayName("several chunks of one document count once towards recall")
        void duplicateHits() {
            QueryScore score = RetrievalMetrics.score(ranked("a", "a", "a"), List.of("a", "b"), 3);

            assertEquals(1, score.firstHitRank());
            assertEquals(0.5, score.recall(), DELTA);
            assertEquals(List.of("a"), score.found());
        }

        @Test
        @DisplayName("an expected document listed twice is expected once")
        void duplicateExpected() {
            QueryScore score = RetrievalMetrics.score(ranked("a"), List.of("a", "a", " a "), 3);

            assertEquals(1, score.expectedCount());
            assertEquals(1.0, score.recall(), DELTA);
        }

        @Test
        @DisplayName("k larger than the result count scores what came back")
        void kLargerThanResults() {
            QueryScore score = RetrievalMetrics.score(ranked("x", "a"), List.of("a"), 50);

            assertEquals(2, score.resultCount());
            assertEquals(2, score.firstHitRank());
            assertEquals(50, score.k());
        }

        @Test
        @DisplayName("no results is a miss with zero recall, not an error")
        void noResults() {
            QueryScore score = RetrievalMetrics.score(List.of(), List.of("a"), 5);

            assertFalse(score.hit());
            assertEquals(0, score.resultCount());
            assertEquals(0.0, score.recall(), DELTA);
            assertEquals(0.0, score.reciprocalRank(), DELTA);

            assertEquals(0, RetrievalMetrics.score(null, List.of("a"), 5).resultCount());
        }

        @Test
        @DisplayName("a query that expects nothing cannot be scored")
        void noExpected() {
            QueryScore score = RetrievalMetrics.score(ranked("a"), List.of(), 5);

            assertFalse(score.scorable());
            assertNull(score.recall());
            assertFalse(score.hit());

            assertFalse(RetrievalMetrics.score(ranked("a"), null, 5).scorable());
            assertFalse(RetrievalMetrics.score(ranked("a"), Arrays.asList(" ", null), 5).scorable());
        }

        @Test
        @DisplayName("matching is exact and case-sensitive")
        void caseSensitive() {
            assertFalse(RetrievalMetrics.score(ranked("Guide.md"), List.of("guide.md"), 5).hit());
            assertFalse(RetrievalMetrics.score(ranked("guide.md#1"), List.of("guide.md"), 5).hit());
        }

        @Test
        @DisplayName("any identifier of a chunk can be the hit")
        void anyIdentifier() {
            List<List<String>> chunks = List.of(List.of("doc-1", "https://example.com/a"));

            assertTrue(RetrievalMetrics.score(chunks, List.of("https://example.com/a"), 1).hit());
        }

        @Test
        @DisplayName("one chunk may satisfy two expected documents")
        void oneChunkTwoDocuments() {
            List<List<String>> chunks = List.of(List.of("a", "b"));

            assertEquals(1.0, RetrievalMetrics.score(chunks, List.of("a", "b"), 1).recall(), DELTA);
        }

        @Test
        @DisplayName("null chunks and null identifiers are skipped")
        void nullEntries() {
            List<List<String>> chunks = Arrays.asList(null, Arrays.asList((String) null), List.of("a"));

            assertEquals(3, RetrievalMetrics.score(chunks, List.of("a"), 3).firstHitRank());
        }

        @Test
        @DisplayName("k below 1 is refused")
        void kBelowOne() {
            assertThrows(IllegalArgumentException.class, () -> RetrievalMetrics.score(ranked("a"), List.of("a"), 0));
        }
    }

    @Nested
    @DisplayName("aggregating")
    class Aggregating {

        @Test
        @DisplayName("hit rate, recall and MRR are means over the scorable queries")
        void means() {
            QueryScore first = RetrievalMetrics.score(ranked("a"), List.of("a"), 5); // rr 1, recall 1
            QueryScore second = RetrievalMetrics.score(ranked("x", "b"), List.of("b", "c"), 5); // rr .5, recall .5
            QueryScore third = RetrievalMetrics.score(ranked("x"), List.of("z"), 5); // rr 0, recall 0
            QueryScore unscorable = RetrievalMetrics.score(ranked("a"), List.of(), 5);

            Aggregate aggregate = RetrievalMetrics.aggregate(List.of(first, second, third, unscorable));

            assertEquals(4, aggregate.queries());
            assertEquals(3, aggregate.scoredQueries());
            assertEquals(2.0 / 3, aggregate.hitRate(), DELTA);
            assertEquals(0.5, aggregate.recall(), DELTA);
            assertEquals(0.5, aggregate.mrr(), DELTA);
        }

        @Test
        @DisplayName("nothing scorable gives null averages, not zero")
        void nothingScorable() {
            Aggregate aggregate = RetrievalMetrics.aggregate(List.of(RetrievalMetrics.score(ranked("a"), List.of(), 5)));

            assertEquals(1, aggregate.queries());
            assertEquals(0, aggregate.scoredQueries());
            assertNull(aggregate.hitRate());
            assertNull(aggregate.recall());
            assertNull(aggregate.mrr());

            assertNull(RetrievalMetrics.aggregate(List.of()).mrr());
        }
    }

    @Nested
    @DisplayName("comparing with a baseline")
    class Comparing {

        private Observation obs(String key, Double rr, Double recall) {
            return new Observation(key, 5, rr == null ? null : rr > 0, rr, recall);
        }

        @Test
        @DisplayName("a query whose reciprocal rank fell is a regression")
        void regression() {
            Comparison comparison = RetrievalMetrics.compare(List.of(obs("q", 0.5, 1.0)), List.of(obs("q", 1.0, 1.0)));

            assertEquals(1, comparison.regressions());
            assertTrue(comparison.regressed());
            assertTrue(comparison.queries().get(0).regressed());
            assertEquals(-0.5, comparison.queries().get(0).reciprocalRankDelta(), DELTA);
            assertEquals(-0.5, comparison.mrrDelta(), DELTA);
            assertEquals(0.0, comparison.recallDelta(), DELTA);
        }

        @Test
        @DisplayName("a better first hit with a lost document is still a regression")
        void mixedIsRegression() {
            Comparison comparison = RetrievalMetrics.compare(List.of(obs("q", 1.0, 0.5)), List.of(obs("q", 0.5, 1.0)));

            assertTrue(comparison.queries().get(0).regressed());
            assertFalse(comparison.queries().get(0).improved());
            assertEquals(0, comparison.improvements());
        }

        @Test
        @DisplayName("an improvement is not flagged")
        void improvement() {
            Comparison comparison = RetrievalMetrics.compare(List.of(obs("q", 1.0, 1.0)), List.of(obs("q", 0.0, 0.0)));

            assertFalse(comparison.regressed());
            assertEquals(1, comparison.improvements());
            assertEquals(1.0, comparison.hitRateDelta(), DELTA);
        }

        @Test
        @DisplayName("unchanged scores are neither")
        void unchanged() {
            Comparison comparison = RetrievalMetrics.compare(List.of(obs("q", 1.0 / 3, 1.0)),
                    List.of(obs("q", 1.0 / 3, 1.0)));

            assertFalse(comparison.regressed());
            assertEquals(0, comparison.improvements());
        }

        @Test
        @DisplayName("queries only on one side are listed and kept out of the aggregate deltas")
        void mismatchedQueries() {
            Comparison comparison = RetrievalMetrics.compare(
                    List.of(obs("shared", 1.0, 1.0), obs("new", 0.0, 0.0)),
                    List.of(obs("shared", 1.0, 1.0), obs("dropped", 1.0, 1.0)));

            assertEquals(1, comparison.comparedQueries());
            assertEquals(List.of("new"), comparison.onlyInCurrent());
            assertEquals(List.of("dropped"), comparison.onlyInBaseline());
            assertEquals(0.0, comparison.mrrDelta(), DELTA);
            assertFalse(comparison.regressed());
        }

        @Test
        @DisplayName("no query in common gives null aggregate deltas")
        void nothingInCommon() {
            Comparison comparison = RetrievalMetrics.compare(List.of(obs("a", 1.0, 1.0)), List.of(obs("b", 1.0, 1.0)));

            assertEquals(0, comparison.comparedQueries());
            assertNull(comparison.mrrDelta());
            assertFalse(comparison.regressed());

            assertEquals(List.of("a"), RetrievalMetrics.compare(List.of(obs("a", 1.0, 1.0)), null).onlyInCurrent());
        }

        @Test
        @DisplayName("a baseline value left out is not compared")
        void partialBaseline() {
            Comparison comparison = RetrievalMetrics.compare(List.of(obs("q", 0.5, 0.5)),
                    List.of(new Observation("q", null, null, null, 1.0)));

            assertNull(comparison.queries().get(0).reciprocalRankDelta());
            assertNull(comparison.mrrDelta());
            assertEquals(-0.5, comparison.recallDelta(), DELTA);
            assertTrue(comparison.regressed());
        }

        @Test
        @DisplayName("a different cut-off is flagged")
        void kChanged() {
            Comparison comparison = RetrievalMetrics.compare(List.of(new Observation("q", 10, true, 1.0, 1.0)),
                    List.of(new Observation("q", 5, true, 1.0, 1.0)));

            assertTrue(comparison.queries().get(0).kChanged());
        }

        @Test
        @DisplayName("a key twice on either side is refused")
        void duplicateKeys() {
            assertThrows(IllegalArgumentException.class, () -> RetrievalMetrics.compare(
                    List.of(obs("q", 1.0, 1.0)), List.of(obs("q", 1.0, 1.0), obs("q", 0.0, 0.0))));
            assertThrows(IllegalArgumentException.class, () -> RetrievalMetrics.compare(
                    List.of(obs("q", 1.0, 1.0), obs("q", 0.0, 0.0)), List.of()));
        }
    }
}
