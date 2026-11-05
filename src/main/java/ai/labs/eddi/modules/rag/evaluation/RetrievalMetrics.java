/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag.evaluation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The arithmetic of a retrieval benchmark: how well one ranked result list
 * answers a golden query, the averages over a set of them, and the difference
 * from an earlier run. Pure — no store, no model, no I/O — so every number the
 * evaluate endpoint reports can be checked by hand.
 *
 * <h2>What counts as a hit</h2> A golden query names the documents it expects
 * by identifier. A retrieved chunk carries a set of identifiers (the caller
 * reads them from the chunk's metadata), and it is a hit for an expected
 * document when one of its identifiers <em>equals</em> that document's
 * identifier — exactly, case-sensitively, after trimming the expected value.
 * Several chunks of one document count once: a document is found or not.
 *
 * <h2>The numbers</h2>
 * <ul>
 * <li><b>firstHitRank</b> — the 1-based rank of the first chunk that hits any
 * expected document within the top {@code k}, or null when none does.</li>
 * <li><b>hit</b> — hit@k: whether {@code firstHitRank} exists.</li>
 * <li><b>reciprocalRank</b> — {@code 1 / firstHitRank}, 0 without a hit (so the
 * mean is MRR@k).</li>
 * <li><b>recall</b> — recall@k: the fraction of the distinct expected documents
 * found among the top {@code k}. Differs from hit@k whenever a query expects
 * more than one document.</li>
 * </ul>
 * A query that expects nothing cannot be scored: its {@code recall} is null and
 * it is left out of every average, rather than counted as a perfect or a failed
 * answer.
 */
public final class RetrievalMetrics {

    /**
     * Differences smaller than this are rounding, not a change — the scores are
     * ratios of small integers, so any real change is far larger.
     */
    static final double EPSILON = 1e-9;

    private RetrievalMetrics() {
    }

    /**
     * How one ranked result list scores against one golden query.
     *
     * @param k
     *            the cut-off the query was scored at
     * @param resultCount
     *            how many results there were within the cut-off
     * @param expectedCount
     *            how many distinct documents the query expected
     * @param found
     *            the expected documents found within the cut-off, in the order they
     *            were first retrieved
     * @param missing
     *            the expected documents not found, in the order they were given
     */
    public record QueryScore(int k, int resultCount, int expectedCount, Integer firstHitRank, boolean hit,
            double reciprocalRank, Double recall, List<String> found, List<String> missing) {

        /** Whether the query expected anything, and so enters the averages. */
        public boolean scorable() {
            return expectedCount > 0;
        }
    }

    /**
     * Scores {@code ranked} — each element the identifiers of one retrieved chunk,
     * best first — against the documents {@code expected}, looking at the first
     * {@code k} results only.
     *
     * @throws IllegalArgumentException
     *             when {@code k} is less than 1
     */
    public static QueryScore score(List<? extends Collection<String>> ranked, Collection<String> expected, int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be at least 1, was " + k);
        }
        Set<String> wanted = normalize(expected);
        List<? extends Collection<String>> top = ranked == null ? List.of() : ranked.subList(0, Math.min(k, ranked.size()));

        Integer firstHitRank = null;
        Set<String> found = new LinkedHashSet<>();
        for (int i = 0; i < top.size(); i++) {
            Collection<String> identifiers = top.get(i);
            if (identifiers == null) {
                continue;
            }
            boolean hitHere = false;
            for (String identifier : identifiers) {
                if (identifier != null && wanted.contains(identifier)) {
                    found.add(identifier);
                    hitHere = true;
                }
            }
            if (hitHere && firstHitRank == null) {
                firstHitRank = i + 1;
            }
        }

        List<String> missing = new ArrayList<>();
        for (String document : wanted) {
            if (!found.contains(document)) {
                missing.add(document);
            }
        }
        Double recall = wanted.isEmpty()
                ? null
                : Math.min(1.0, (double) top.stream().filter(c -> c != null && c.stream().anyMatch(wanted::contains)).count() / wanted.size());
        double reciprocalRank = firstHitRank == null ? 0.0 : 1.0 / firstHitRank;
        return new QueryScore(k, top.size(), wanted.size(), firstHitRank, firstHitRank != null, reciprocalRank, recall,
                List.copyOf(found), List.copyOf(missing));
    }

    /**
     * The averages over a set of queries.
     *
     * @param queries
     *            every query evaluated
     * @param scoredQueries
     *            those that expected something, and so are averaged
     * @param hitRate
     *            mean hit@k (the share of queries with any hit); null when no query
     *            could be scored
     * @param recall
     *            mean recall@k; null when no query could be scored
     * @param mrr
     *            mean reciprocal rank (MRR@k); null when no query could be scored
     */
    public record Aggregate(int queries, int scoredQueries, Double hitRate, Double recall, Double mrr) {
    }

    public static Aggregate aggregate(Collection<QueryScore> scores) {
        List<Observation> observations = new ArrayList<>();
        int index = 0;
        for (QueryScore score : scores) {
            observations.add(Observation.of(String.valueOf(index++), score));
        }
        return aggregateObservations(observations, observations.size());
    }

    /**
     * One query's outcome, as a run reports it and as a baseline gives it back. Any
     * value may be null in a baseline written by hand; a null value is not
     * compared.
     *
     * @param key
     *            what pairs the query with its counterpart in the other run — the
     *            query's id, or its text when it has none
     */
    public record Observation(String key, Integer k, Boolean hit, Double reciprocalRank, Double recall) {

        public static Observation of(String key, QueryScore score) {
            return new Observation(key, score.k(), score.scorable() ? score.hit() : null,
                    score.scorable() ? score.reciprocalRank() : null, score.recall());
        }
    }

    /**
     * How one query moved against the baseline. A delta is null when either side
     * lacks the value.
     *
     * @param regressed
     *            the reciprocal rank or the recall went down (either one is enough:
     *            a query that ranks its first hit higher but loses a second
     *            expected document has still lost something)
     * @param improved
     *            something went up and nothing went down
     * @param kChanged
     *            the two runs scored the query at different cut-offs, so the deltas
     *            compare different things
     */
    public record QueryDelta(String key, Boolean hitBefore, Boolean hitNow, Double reciprocalRankDelta,
            Double recallDelta, boolean regressed, boolean improved, boolean kChanged) {
    }

    /**
     * The whole difference from a baseline. The aggregate deltas are taken over the
     * queries both runs contain, so adding or dropping a query never moves them;
     * those queries are listed instead.
     *
     * @param regressed
     *            any compared query regressed, or any aggregate went down
     */
    public record Comparison(int comparedQueries, Double hitRateDelta, Double recallDelta, Double mrrDelta,
            int regressions, int improvements, boolean regressed, List<QueryDelta> queries, List<String> onlyInCurrent,
            List<String> onlyInBaseline) {
    }

    /**
     * Compares the current run with a baseline, pairing queries by key.
     *
     * @throws IllegalArgumentException
     *             when either side holds two queries with one key — the pairing
     *             would be a guess
     */
    public static Comparison compare(List<Observation> current, List<Observation> baseline) {
        Map<String, Observation> now = byKey(current, "current run");
        Map<String, Observation> before = byKey(baseline, "baseline");

        List<QueryDelta> deltas = new ArrayList<>();
        List<Observation> pairedNow = new ArrayList<>();
        List<Observation> pairedBefore = new ArrayList<>();
        List<String> onlyInCurrent = new ArrayList<>();
        int regressions = 0;
        int improvements = 0;
        for (Observation observation : now.values()) {
            Observation earlier = before.get(observation.key());
            if (earlier == null) {
                onlyInCurrent.add(observation.key());
                continue;
            }
            pairedNow.add(observation);
            pairedBefore.add(earlier);
            Double rrDelta = delta(observation.reciprocalRank(), earlier.reciprocalRank());
            Double recallDelta = delta(observation.recall(), earlier.recall());
            boolean down = isDown(rrDelta) || isDown(recallDelta);
            boolean up = isUp(rrDelta) || isUp(recallDelta);
            boolean regressed = down;
            boolean improved = up && !down;
            if (regressed) {
                regressions++;
            }
            if (improved) {
                improvements++;
            }
            boolean kChanged = observation.k() != null && earlier.k() != null && !observation.k().equals(earlier.k());
            deltas.add(new QueryDelta(observation.key(), earlier.hit(), observation.hit(), rrDelta, recallDelta,
                    regressed, improved, kChanged));
        }
        List<String> onlyInBaseline = new ArrayList<>();
        for (String key : before.keySet()) {
            if (!now.containsKey(key)) {
                onlyInBaseline.add(key);
            }
        }

        Aggregate aggregateNow = aggregateObservations(pairedNow, pairedNow.size());
        Aggregate aggregateBefore = aggregateObservations(pairedBefore, pairedBefore.size());
        Double hitRateDelta = delta(aggregateNow.hitRate(), aggregateBefore.hitRate());
        Double recallDelta = delta(aggregateNow.recall(), aggregateBefore.recall());
        Double mrrDelta = delta(aggregateNow.mrr(), aggregateBefore.mrr());
        boolean regressed = regressions > 0 || isDown(hitRateDelta) || isDown(recallDelta) || isDown(mrrDelta);
        return new Comparison(deltas.size(), hitRateDelta, recallDelta, mrrDelta, regressions, improvements, regressed,
                List.copyOf(deltas), List.copyOf(onlyInCurrent), List.copyOf(onlyInBaseline));
    }

    /**
     * Each average is over the observations that carry that value, so a baseline
     * written by hand with only {@code recall} still yields a recall average.
     */
    private static Aggregate aggregateObservations(List<Observation> observations, int queries) {
        int scored = 0;
        double hits = 0;
        int hitCount = 0;
        double recall = 0;
        int recallCount = 0;
        double rr = 0;
        int rrCount = 0;
        for (Observation observation : observations) {
            boolean any = false;
            if (observation.hit() != null) {
                hits += observation.hit() ? 1 : 0;
                hitCount++;
                any = true;
            }
            if (observation.recall() != null) {
                recall += observation.recall();
                recallCount++;
                any = true;
            }
            if (observation.reciprocalRank() != null) {
                rr += observation.reciprocalRank();
                rrCount++;
                any = true;
            }
            if (any) {
                scored++;
            }
        }
        return new Aggregate(queries, scored, hitCount == 0 ? null : hits / hitCount,
                recallCount == 0 ? null : recall / recallCount, rrCount == 0 ? null : rr / rrCount);
    }

    private static Map<String, Observation> byKey(List<Observation> observations, String side) {
        Map<String, Observation> keyed = new LinkedHashMap<>();
        if (observations == null) {
            return keyed;
        }
        for (Observation observation : observations) {
            Objects.requireNonNull(observation.key(), "every query needs a key");
            if (keyed.putIfAbsent(observation.key(), observation) != null) {
                throw new IllegalArgumentException(
                        "The " + side + " holds the query '" + observation.key() + "' twice; give each query a unique id");
            }
        }
        return keyed;
    }

    private static Set<String> normalize(Collection<String> expected) {
        Set<String> wanted = new LinkedHashSet<>();
        if (expected == null) {
            return wanted;
        }
        for (String document : expected) {
            if (document != null && !document.isBlank()) {
                wanted.add(document.trim());
            }
        }
        return wanted;
    }

    private static Double delta(Double now, Double before) {
        return now == null || before == null ? null : now - before;
    }

    private static boolean isDown(Double delta) {
        return delta != null && delta < -EPSILON;
    }

    private static boolean isUp(Double delta) {
        return delta != null && delta > EPSILON;
    }
}
