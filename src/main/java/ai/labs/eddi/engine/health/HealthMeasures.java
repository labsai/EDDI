/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthDefaults.Definition;
import ai.labs.eddi.engine.health.HealthDefaults.Direction;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import ai.labs.eddi.engine.health.model.HealthUsage;
import ai.labs.eddi.engine.health.report.HealthReports.BreakdownEntry;
import ai.labs.eddi.engine.health.report.HealthReports.CompareDelta;
import ai.labs.eddi.engine.health.report.HealthReports.EffectivenessSection;
import ai.labs.eddi.engine.health.report.HealthReports.Interval;
import ai.labs.eddi.engine.health.report.HealthReports.Measure;
import ai.labs.eddi.engine.health.report.HealthReports.Section;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Computes Agent Health's measures from what a period holds — pure functions
 * over {@link PeriodData}, no store access, so every number can be checked by a
 * unit test.
 * <p>
 * Each measure has a value with its sample size {@code n}, an interval where
 * one is defined, the same measure over the baseline period, the change,
 * whether the change is significant ({@link HealthStatistics}), and a band from
 * the default thresholds ({@link HealthDefaults}). No data gives {@code value:
 * null}, band {@code GREY} and a hint; too little data gives the value, band
 * {@code GREY} and a hint. Measures that need data not collected yet (outcomes,
 * H3) carry {@code value: null} and say so.
 */
public final class HealthMeasures {

    static final String HINT_NEEDS_OUTCOMES = "Available once conversation outcomes are recorded";
    static final String HINT_NO_DATA = "No data in this period";
    static final String HINT_RATINGS_UNAVAILABLE = "Ratings could not be read";
    static final String HINT_MIXED_APDEX = "Days of this period were rolled up with different Apdex thresholds (eddi.health.apdex-t)";
    static final String HINT_USAGE_UNAVAILABLE = "Older than the turn-row retention: people and sessions are not counted";

    /** Rating counts of a period (conversation ratings and reply thumbs). */
    public record RatingStats(long conversationRatings, Double conversationAverage, long thumbsUp, long thumbsDown) {
        public static final RatingStats EMPTY = new RatingStats(0, null, 0, 0);
    }

    /**
     * Daily rows of a period added up.
     *
     * @param apdexTimed
     *            turns with a duration — the Apdex and percentile denominator
     */
    public record Totals(long turns, long agentFailedTurns, long platformAffectedTurns, long fallbackTurns, Map<String, Long> failuresByClass,
            Map<String, Long> errorKinds, double costUsd, long totalTokens, long guardrailTrips, long apdexTimed, long apdexSatisfied,
            long apdexTolerating, List<Long> durationBuckets, Set<Long> apdexTs) {

        public Totals {
            apdexTs = apdexTs == null ? Set.of() : Set.copyOf(apdexTs);
        }

        public static final Totals EMPTY = of(List.of());

        public static Totals of(Collection<HealthTurnAggregate> rows) {
            long turns = 0, agentFailed = 0, platformAffected = 0, fallback = 0, tokens = 0, guardrails = 0, timed = 0, satisfied = 0,
                    tolerating = 0;
            double cost = 0;
            Map<String, Long> classes = new TreeMap<>();
            Map<String, Long> kinds = new TreeMap<>();
            long[] buckets = new long[HealthTurnAggregator.DURATION_BOUNDS_MS.length + 1];
            Set<Long> ts = new TreeSet<>();
            for (HealthTurnAggregate row : rows) {
                if (row.durationCount() > 0) {
                    ts.add(row.apdexTMs());
                }
                turns += row.turns();
                agentFailed += row.agentFailedTurns();
                platformAffected += row.platformAffectedTurns();
                fallback += row.fallbackTurns();
                tokens += row.totalTokens();
                guardrails += row.guardrailTrips();
                cost += row.costUsd();
                timed += row.durationCount();
                satisfied += row.apdexSatisfied();
                tolerating += row.apdexTolerating();
                row.failuresByClass().forEach((k, v) -> classes.merge(k, v, Long::sum));
                row.errorKinds().forEach((k, v) -> kinds.merge(k, v, Long::sum));
                List<Long> b = row.durationBuckets();
                for (int i = 0; i < b.size() && i < buckets.length; i++) {
                    buckets[i] += b.get(i);
                }
            }
            List<Long> bucketList = new ArrayList<>(buckets.length);
            for (long b : buckets) {
                bucketList.add(b);
            }
            return new Totals(turns, agentFailed, platformAffected, fallback, classes, kinds, cost, tokens, guardrails, timed, satisfied,
                    tolerating, bucketList, ts);
        }
    }

    /**
     * What one period holds.
     *
     * @param usage
     *            people and sessions, or {@code null} when they cannot be counted
     *            (the period is older than the turn-row retention)
     * @param ratings
     *            ratings, or {@code null} when they could not be read
     */
    public record PeriodData(Totals totals, HealthUsage usage, RatingStats ratings, double exposureDays) {
        public PeriodData {
            totals = totals == null ? Totals.EMPTY : totals;
        }

        /** Two such periods compare at equal exposure. */
        public PeriodData(Totals totals, HealthUsage usage, RatingStats ratings) {
            this(totals, usage, ratings, 1.0);
        }
    }

    /** How a measure's change is tested. */
    sealed interface Test permits Proportion, Mean, Count, NoTest {
    }

    record Proportion(double x, long n) implements Test {
    }

    record Mean(double mean, double variance, long n) implements Test {
    }

    record Count(long count) implements Test {
    }

    record NoTest() implements Test {
    }

    /** One measure's raw result over one period. */
    record Stat(Double value, long n, Interval interval, Test test, List<BreakdownEntry> breakdown, String hint) {
        static Stat none(String hint) {
            return new Stat(null, 0, null, new NoTest(), null, hint);
        }
    }

    private HealthMeasures() {
    }

    // ------------------------------------------------------------------ sections

    /**
     * Every section with its measures.
     *
     * @param baseline
     *            the previous period, or {@code null} for no comparison
     * @param qualityRestricted
     *            the caller may not read ratings: the quality section is
     *            {@code restricted} and empty
     */
    public static Map<String, Object> sections(PeriodData current, PeriodData baseline, boolean qualityRestricted) {
        Map<String, List<Measure>> bySection = new LinkedHashMap<>();
        for (String section : HealthDefaults.SECTIONS) {
            bySection.put(section, new ArrayList<>());
        }
        for (Definition definition : HealthDefaults.MEASURES.values()) {
            if (qualityRestricted && HealthDefaults.QUALITY.equals(definition.section())) {
                continue;
            }
            bySection.get(definition.section()).add(measure(definition.id(), current, baseline));
        }
        Map<String, Object> sections = new LinkedHashMap<>();
        for (Map.Entry<String, List<Measure>> entry : bySection.entrySet()) {
            String section = entry.getKey();
            if (HealthDefaults.EFFECTIVENESS.equals(section)) {
                sections.put(section, new EffectivenessSection(false, null, entry.getValue()));
            } else if (HealthDefaults.QUALITY.equals(section) && qualityRestricted) {
                sections.put(section, new Section(true, List.of()));
            } else {
                sections.put(section, new Section(false, entry.getValue()));
            }
        }
        return sections;
    }

    /** One measure; {@code baseline} may be null. Unknown ids answer null. */
    public static Measure measure(String id, PeriodData current, PeriodData baseline) {
        Definition d = HealthDefaults.of(id);
        if (d == null) {
            return null;
        }
        Stat now = stat(id, current);
        Stat before = baseline == null ? null : stat(id, baseline);
        Double baselineValue = before == null ? null : before.value();
        double ratio = baseline == null ? 1.0 : exposureRatio(current, baseline);
        if (before != null && before.test() instanceof Count && baselineValue != null) {
            // A count is compared at equal exposure: a window that ends this morning
            // is held against the same fraction of the previous period.
            baselineValue = baselineValue * ratio;
        }
        Double delta = now.value() != null && baselineValue != null ? now.value() - baselineValue : null;
        boolean significant = before != null && significant(d, now, before, ratio);
        String band = "GREY";
        String hint = now.hint();
        if (now.value() != null) {
            if (now.n() < d.minSample()) {
                hint = hint != null ? hint : "Fewer than " + d.minSample() + " " + d.sampleNoun() + " in this period";
            } else if (d.thresholds() != null) {
                band = HealthDefaults.band(now.value(), d.thresholds());
            }
        }
        return new Measure(d.id(), label(d, current), d.kind(), d.critical(), d.unit(), now.value(), now.n(), now.interval(), baselineValue, delta,
                significant, band, HealthDefaults.SCORED.equals(d.kind()) ? d.thresholds() : null, now.breakdown(), hint);
    }

    /**
     * The value and sample size of one measure over a period — for series points.
     */
    public static Measure pointOf(String id, PeriodData data) {
        return measure(id, data, null);
    }

    /**
     * The per-measure changes from one version's sections to another's — the
     * compare view. Only measures with a value on both sides.
     */
    public static List<CompareDelta> deltas(PeriodData from, PeriodData to, boolean qualityRestricted) {
        List<CompareDelta> deltas = new ArrayList<>();
        for (Definition d : HealthDefaults.MEASURES.values()) {
            if (qualityRestricted && HealthDefaults.QUALITY.equals(d.section())) {
                continue;
            }
            Stat a = stat(d.id(), from);
            Stat b = stat(d.id(), to);
            if (a.value() == null || b.value() == null) {
                continue;
            }
            double delta = b.value() - a.value();
            Boolean better = switch (d.direction()) {
                case HIGHER -> delta > 0 ? Boolean.TRUE : delta < 0 ? Boolean.FALSE : null;
                case LOWER -> delta < 0 ? Boolean.TRUE : delta > 0 ? Boolean.FALSE : null;
                case NONE -> null;
            };
            deltas.add(new CompareDelta(d.id(), a.value(), b.value(), delta, significant(d, b, a, exposureRatio(to, from)), better));
        }
        return deltas;
    }

    /**
     * How much worse the measure got, normalised to roughly 0..1 so that measures
     * of different units can be ranked: the absolute change for rates and scores,
     * the relative change (capped at 1) otherwise. 0 when it did not get
     * significantly worse.
     */
    public static double worsening(Measure m) {
        Definition d = HealthDefaults.of(m.id());
        if (d == null || !m.significant() || m.delta() == null || m.baseline() == null || d.direction() == Direction.NONE) {
            return 0.0;
        }
        double signed = d.direction() == Direction.HIGHER ? -m.delta() : m.delta();
        if (signed <= 0) {
            return 0.0;
        }
        if ("ratio".equals(d.unit()) || "score".equals(d.unit())) {
            return signed;
        }
        return m.baseline() == 0 ? 1.0 : Math.min(1.0, signed / Math.abs(m.baseline()));
    }

    // ------------------------------------------------------------------ statistics

    /**
     * The current period's exposure over the baseline's (1 when either is unknown).
     */
    static double exposureRatio(PeriodData current, PeriodData baseline) {
        return current.exposureDays() > 0 && baseline.exposureDays() > 0 ? current.exposureDays() / baseline.exposureDays() : 1.0;
    }

    /** The Apdex label names its T; other labels are the definition's. */
    private static String label(Definition d, PeriodData data) {
        if ("apdex".equals(d.id()) && data.totals().apdexTs().size() == 1) {
            return d.label().replace(")", ", T = " + seconds(data.totals().apdexTs().iterator().next()) + ")");
        }
        return d.label();
    }

    private static String seconds(long ms) {
        return ms % 1000 == 0 ? (ms / 1000) + " s" : ms + " ms";
    }

    private static boolean significant(Definition d, Stat now, Stat before, double exposureRatio) {
        if (now.value() == null || before.value() == null || now.n() < d.minSample() || before.n() < d.minSample()) {
            return false;
        }
        Test a = now.test();
        Test b = before.test();
        if (a instanceof Proportion p1 && b instanceof Proportion p2) {
            return HealthStatistics.significantProportions(p1.x(), p1.n(), p2.x(), p2.n());
        }
        if (a instanceof Mean m1 && b instanceof Mean m2) {
            return HealthStatistics.significantMeans(m1.mean(), m1.variance(), m1.n(), m2.mean(), m2.variance(), m2.n());
        }
        if (a instanceof Count c1 && b instanceof Count c2) {
            return HealthStatistics.significantCounts(c1.count(), c2.count(), exposureRatio);
        }
        return false;
    }

    private static Stat proportion(double x, long n, List<BreakdownEntry> breakdown) {
        if (n <= 0) {
            return Stat.none(HINT_NO_DATA);
        }
        double bounded = Math.max(0.0, Math.min(n, x));
        return new Stat(bounded / n, n, HealthStatistics.wilson(bounded, n), new Proportion(bounded, n), breakdown, null);
    }

    /**
     * The same value without a significance test: returning users depend on how
     * much history lies before a window, which differs between a period and its
     * baseline even with a fixed look-back once the retention clips it.
     */
    private static Stat untested(Stat stat) {
        return new Stat(stat.value(), stat.n(), stat.interval(), new NoTest(), stat.breakdown(), stat.hint());
    }

    private static Stat ratio(double numerator, long denominator) {
        if (denominator <= 0) {
            return Stat.none(HINT_NO_DATA);
        }
        return new Stat(numerator / denominator, denominator, null, new NoTest(), null, null);
    }

    private static Stat count(long count) {
        return new Stat((double) count, count, null, new Count(count), null, null);
    }

    static Stat stat(String id, PeriodData data) {
        Totals t = data.totals();
        HealthUsage u = data.usage();
        RatingStats r = data.ratings();
        return switch (id) {
            case "agentFailureRate" -> proportion(t.agentFailedTurns(), t.turns(), classBreakdown(t, false));
            case "platformFailureRate" -> proportion(t.platformAffectedTurns(), t.turns(), classBreakdown(t, true));
            case "apdex" -> apdex(t);
            case "turnDurationP95" -> t.apdexTimed() <= 0
                    ? Stat.none(HINT_NO_DATA)
                    : new Stat(HealthStatistics.percentile(t.durationBuckets(), HealthTurnAggregator.DURATION_BOUNDS_MS, 0.95), t.apdexTimed(),
                            null, new NoTest(), null, null);
            case "fallbackRate" -> proportion(t.fallbackTurns(), t.turns(), null);
            case "resolutionRate" -> new Stat(null, 0, null, new NoTest(), resolutionBreakdown(), HINT_NEEDS_OUTCOMES);
            case "escalationRate", "abandonmentRate", "deflectionRate", "outcomeCoverage", "costPerResolvedSession" ->
                Stat.none(HINT_NEEDS_OUTCOMES);
            case "ratingScore" -> r == null
                    ? Stat.none(HINT_RATINGS_UNAVAILABLE)
                    : ratingScore(r);
            case "ratingResponseRate" -> r == null
                    ? Stat.none(HINT_RATINGS_UNAVAILABLE)
                    : u == null ? Stat.none(HINT_USAGE_UNAVAILABLE) : proportion(r.conversationRatings(), u.sessions(), null);
            case "replyThumbsUp" -> r == null
                    ? Stat.none(HINT_RATINGS_UNAVAILABLE)
                    : proportion(r.thumbsUp(), r.thumbsUp() + r.thumbsDown(), null);
            case "guardrailTripRate" -> ratio(t.guardrailTrips(), t.turns());
            case "costPerSession" -> u == null ? Stat.none(HINT_USAGE_UNAVAILABLE) : ratio(t.costUsd(), u.sessions());
            case "tokensPerTurn" -> ratio(t.totalTokens(), t.turns());
            case "sessions" -> u == null ? Stat.none(HINT_USAGE_UNAVAILABLE) : count(u.sessions());
            case "engagedSessions" -> u == null ? Stat.none(HINT_USAGE_UNAVAILABLE) : count(u.engagedSessions());
            case "distinctUsers" -> u == null ? Stat.none(HINT_USAGE_UNAVAILABLE) : count(u.distinctUsers());
            case "returningUserShare" ->
                u == null ? Stat.none(HINT_USAGE_UNAVAILABLE) : untested(proportion(u.returningUsers(), u.distinctUsers(), null));
            case "turnsPerSession" -> u == null ? Stat.none(HINT_USAGE_UNAVAILABLE) : ratio(u.turns(), u.sessions());
            default -> Stat.none(HINT_NO_DATA);
        };
    }

    /**
     * Apdex = (satisfied + tolerating / 2) / timed turns. Each turn scores 1, ½ or
     * 0, so the variance is known exactly: E[x²] − E[x]².
     */
    private static Stat apdex(Totals t) {
        long n = t.apdexTimed();
        if (n <= 0) {
            return Stat.none(HINT_NO_DATA);
        }
        if (t.apdexTs().size() > 1) {
            // Days rolled up under different eddi.health.apdex-t values do not add up.
            return Stat.none(HINT_MIXED_APDEX);
        }
        double mean = (t.apdexSatisfied() + t.apdexTolerating() / 2.0) / n;
        double meanOfSquares = (t.apdexSatisfied() + t.apdexTolerating() / 4.0) / n;
        double variance = Math.max(0.0, meanOfSquares - mean * mean);
        Interval interval = HealthStatistics.normal(mean, variance, n);
        Interval bounded = interval == null ? null : new Interval(Math.max(0.0, interval.low()), Math.min(1.0, interval.high()));
        return new Stat(mean, n, bounded, new Mean(mean, variance, n), null, null);
    }

    /**
     * The Wilson lower bound of the average normalised score: the score the ratings
     * support with 95 % confidence, so three five-star ratings do not outrank three
     * hundred four-star ones.
     */
    private static Stat ratingScore(RatingStats r) {
        long n = r.conversationRatings();
        if (n <= 0 || r.conversationAverage() == null) {
            return Stat.none(HINT_NO_DATA);
        }
        double x = Math.max(0.0, Math.min(1.0, r.conversationAverage())) * n;
        Interval interval = HealthStatistics.wilson(x, n);
        return new Stat(interval.low(), n, interval, new Proportion(x, n),
                List.of(new BreakdownEntry("average", "Average normalised score", r.conversationAverage(), n)), null);
    }

    private static List<BreakdownEntry> classBreakdown(Totals t, boolean platform) {
        List<BreakdownEntry> entries = new ArrayList<>();
        t.failuresByClass().forEach((cls, count) -> {
            if (HealthAttribution.isPlatformClass(cls) == platform && count > 0) {
                entries.add(new BreakdownEntry(cls, label(cls) + " (failed provider attempts)", (double) count, t.turns()));
            }
        });
        if (!platform) {
            Long ceiling = t.errorKinds().get("COST_CEILING");
            if (ceiling != null && ceiling > 0) {
                entries.add(new BreakdownEntry("COST_CEILING", "Cost ceiling reached", (double) ceiling, t.turns()));
            }
        }
        return entries.isEmpty() ? null : entries;
    }

    private static List<BreakdownEntry> resolutionBreakdown() {
        return List.of(new BreakdownEntry("confirmed", "Confirmed", null, 0), new BreakdownEntry("selfReported", "Self-reported", null, 0),
                new BreakdownEntry("agentClosed", "Agent closed", null, 0), new BreakdownEntry("silence", "Silence", null, 0));
    }

    private static String label(String failureClass) {
        String lower = failureClass.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
