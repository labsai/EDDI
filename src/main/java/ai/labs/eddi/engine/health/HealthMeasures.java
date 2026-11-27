/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthDefaults.Definition;
import ai.labs.eddi.engine.health.HealthDefaults.Direction;
import ai.labs.eddi.engine.health.model.HealthOutcomeCount;
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
 * {@code GREY} and a hint. The effectiveness rates are withheld
 * ({@code value: null} with a hint) while the outcome coverage is below
 * {@code eddi.health.min-outcome-coverage}.
 */
public final class HealthMeasures {

    static final String HINT_OUTCOMES_UNAVAILABLE = "Older than the turn-row retention, or the session outcomes could not be read";
    static final String HINT_NO_CLOSED_SESSIONS = "No engaged session closed in this period";
    static final String HINT_LOW_COVERAGE = "Outcome coverage %d %% is below the %d %% this rate needs: map the actions that end sessions "
            + "(healthConfig.outcomes, see outcome-suggestions)";
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
     * The sessions of a period that ended in it, from the outcome rows.
     *
     * @param sessions
     *            every session, open or closed, judged or not
     * @param engagedSessions
     *            those engaged
     * @param turns
     *            their turns
     * @param closed
     *            the effectiveness denominator: engaged, closed sessions of a
     *            judged (interactive) origin
     * @param resolvedConfirmed
     *            of those, resolved on confirmed evidence (an external event, a
     *            mapped agent action)
     * @param resolvedSelfReported
     *            resolved on the model's own word
     * @param resolvedAgentClosed
     *            assumed resolved: the agent ended the conversation
     * @param resolvedSilence
     *            assumed resolved: the person went quiet after a successful turn
     * @param resolvedOther
     *            resolved on any other evidence (later phases)
     * @param minCoverage
     *            the coverage below which the rates are withheld
     */
    public record OutcomeStats(long sessions, long engagedSessions, long turns, long closed, long resolvedConfirmed, long resolvedSelfReported,
            long resolvedAgentClosed, long resolvedSilence, long resolvedOther, long escalated, long abandoned, long deflected, long failed,
            long endedQuietly, double minCoverage, String pending) {

        /**
         * The same counts, with the hint that the evaluation has not reached the end of
         * the period yet ({@code null}: it has).
         */
        public OutcomeStats withPending(String hint) {
            return new OutcomeStats(sessions, engagedSessions, turns, closed, resolvedConfirmed, resolvedSelfReported, resolvedAgentClosed,
                    resolvedSilence, resolvedOther, escalated, abandoned, deflected, failed, endedQuietly, minCoverage, hint);
        }

        public static final OutcomeStats EMPTY = of(List.of(), 0.5);

        /** Adds up the database's counts. */
        public static OutcomeStats of(Collection<HealthOutcomeCount> counts, double minCoverage) {
            long sessions = 0, engaged = 0, turns = 0, closed = 0, confirmed = 0, self = 0, agentClosed = 0, silence = 0, other = 0, escalated = 0,
                    abandoned = 0, deflected = 0, failed = 0, quiet = 0;
            for (HealthOutcomeCount c : counts) {
                sessions += c.sessions();
                turns += c.turns();
                if (!c.engaged()) {
                    continue;
                }
                engaged += c.sessions();
                if (c.provisional() || c.outcome() == null || HealthOutcomes.OPEN.equals(c.outcome())) {
                    continue;
                }
                closed += c.sessions();
                switch (c.outcome()) {
                    case HealthOutcomes.RESOLVED, HealthOutcomes.ASSUMED_RESOLVED -> {
                        String evidence = c.evidence() == null ? "" : c.evidence();
                        if (HealthOutcomes.CONFIRMED_EVIDENCE.contains(evidence)) {
                            confirmed += c.sessions();
                        } else if (HealthOutcomes.EVIDENCE_SELF_REPORTED.equals(evidence)) {
                            self += c.sessions();
                        } else if (HealthOutcomes.EVIDENCE_AGENT_CLOSED.equals(evidence)) {
                            agentClosed += c.sessions();
                        } else if (HealthOutcomes.EVIDENCE_SILENCE.equals(evidence)) {
                            silence += c.sessions();
                        } else {
                            other += c.sessions();
                        }
                    }
                    case HealthOutcomes.ESCALATED -> escalated += c.sessions();
                    case HealthOutcomes.ABANDONED -> abandoned += c.sessions();
                    case HealthOutcomes.DEFLECTED -> deflected += c.sessions();
                    case HealthOutcomes.FAILED -> failed += c.sessions();
                    case HealthOutcomes.ENDED_QUIETLY -> quiet += c.sessions();
                    default -> {
                        // an outcome this version does not know: in the denominator only
                    }
                }
            }
            return new OutcomeStats(sessions, engaged, turns, closed, confirmed, self, agentClosed, silence, other, escalated, abandoned, deflected,
                    failed, quiet, minCoverage, null);
        }

        /** Resolved sessions, confirmed or assumed. */
        public long resolved() {
            return resolvedConfirmed + resolvedSelfReported + resolvedAgentClosed + resolvedSilence + resolvedOther;
        }

        /** Closed sessions with an outcome other than {@code ENDED_QUIETLY}. */
        public long covered() {
            return closed - endedQuietly;
        }

        /** The share of closed sessions with an outcome, or 0 without any. */
        public double coverage() {
            return closed <= 0 ? 0.0 : (double) covered() / closed;
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
     * @param outcomes
     *            the period's sessions, or {@code null} when they cannot be counted
     *            (older than the retention, or not readable); sessions, engaged
     *            sessions and turns per session come from here when present
     */
    public record PeriodData(Totals totals, HealthUsage usage, RatingStats ratings, double exposureDays, OutcomeStats outcomes) {
        public PeriodData {
            totals = totals == null ? Totals.EMPTY : totals;
        }

        /** Without outcomes. */
        public PeriodData(Totals totals, HealthUsage usage, RatingStats ratings, double exposureDays) {
            this(totals, usage, ratings, exposureDays, null);
        }

        /** Two such periods compare at equal exposure. */
        public PeriodData(Totals totals, HealthUsage usage, RatingStats ratings) {
            this(totals, usage, ratings, 1.0, null);
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
                Double coverage = null;
                for (Measure m : entry.getValue()) {
                    if (m != null && "outcomeCoverage".equals(m.id())) {
                        coverage = m.value();
                    }
                }
                sections.put(section, new EffectivenessSection(false, coverage, entry.getValue()));
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

    /** Measures read, in part or whole, from the session outcome rows. */
    static final Set<String> OUTCOME_MEASURES = Set.of("resolutionRate", "escalationRate", "abandonmentRate", "deflectionRate", "outcomeCoverage",
            "costPerResolvedSession", "sessions", "engagedSessions", "turnsPerSession", "costPerSession", "ratingResponseRate");

    static Stat stat(String id, PeriodData data) {
        Stat stat = stat0(id, data);
        OutcomeStats o = data.outcomes();
        if (o != null && o.pending() != null && OUTCOME_MEASURES.contains(id)) {
            // The value stands for what is evaluated so far; say so — after the reason a
            // value is withheld, when there is one.
            String hint = stat.hint() == null ? o.pending() : stat.hint() + "; " + o.pending();
            return new Stat(stat.value(), stat.n(), stat.interval(), stat.test(), stat.breakdown(), hint);
        }
        return stat;
    }

    private static Stat stat0(String id, PeriodData data) {
        Totals t = data.totals();
        HealthUsage u = data.usage();
        RatingStats r = data.ratings();
        OutcomeStats o = data.outcomes();
        // Sessions are real sessions once outcomes are counted; before (a period
        // past the retention has neither) the conversations of the turn rows.
        Long sessions = o != null ? Long.valueOf(o.sessions()) : u != null ? Long.valueOf(u.sessions()) : null;
        return switch (id) {
            case "agentFailureRate" -> proportion(t.agentFailedTurns(), t.turns(), classBreakdown(t, false));
            case "platformFailureRate" -> proportion(t.platformAffectedTurns(), t.turns(), classBreakdown(t, true));
            case "apdex" -> apdex(t);
            case "turnDurationP95" -> t.apdexTimed() <= 0
                    ? Stat.none(HINT_NO_DATA)
                    : new Stat(HealthStatistics.percentile(t.durationBuckets(), HealthTurnAggregator.DURATION_BOUNDS_MS, 0.95), t.apdexTimed(),
                            null, new NoTest(), null, null);
            case "fallbackRate" -> proportion(t.fallbackTurns(), t.turns(), null);
            case "resolutionRate" -> resolution(o);
            case "escalationRate" -> outcomeRate(o, o == null ? 0 : o.escalated());
            case "abandonmentRate" -> outcomeRate(o, o == null ? 0 : o.abandoned());
            case "deflectionRate" -> outcomeRate(o, o == null ? 0 : o.deflected());
            case "outcomeCoverage" -> o == null
                    ? Stat.none(HINT_OUTCOMES_UNAVAILABLE)
                    : o.closed() <= 0 ? Stat.none(HINT_NO_CLOSED_SESSIONS) : untested(proportion(o.covered(), o.closed(), null));
            case "costPerResolvedSession" -> costPerResolved(t, o);
            case "ratingScore" -> r == null
                    ? Stat.none(HINT_RATINGS_UNAVAILABLE)
                    : ratingScore(r);
            case "ratingResponseRate" -> r == null
                    ? Stat.none(HINT_RATINGS_UNAVAILABLE)
                    : sessions == null ? Stat.none(HINT_USAGE_UNAVAILABLE) : proportion(r.conversationRatings(), sessions, null);
            case "replyThumbsUp" -> r == null
                    ? Stat.none(HINT_RATINGS_UNAVAILABLE)
                    : proportion(r.thumbsUp(), r.thumbsUp() + r.thumbsDown(), null);
            case "guardrailTripRate" -> ratio(t.guardrailTrips(), t.turns());
            case "costPerSession" -> sessions == null ? Stat.none(HINT_USAGE_UNAVAILABLE) : ratio(t.costUsd(), sessions);
            case "tokensPerTurn" -> ratio(t.totalTokens(), t.turns());
            case "sessions" -> sessions == null ? Stat.none(HINT_USAGE_UNAVAILABLE) : count(sessions);
            case "engagedSessions" -> o != null
                    ? count(o.engagedSessions())
                    : u == null ? Stat.none(HINT_USAGE_UNAVAILABLE) : count(u.engagedSessions());
            case "distinctUsers" -> u == null ? Stat.none(HINT_USAGE_UNAVAILABLE) : count(u.distinctUsers());
            case "returningUserShare" ->
                u == null ? Stat.none(HINT_USAGE_UNAVAILABLE) : untested(proportion(u.returningUsers(), u.distinctUsers(), null));
            case "turnsPerSession" -> o != null
                    ? ratio(o.turns(), o.sessions())
                    : u == null ? Stat.none(HINT_USAGE_UNAVAILABLE) : ratio(u.turns(), u.sessions());
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

    /**
     * The four parts of the resolution rate (plan decision 3), each as its share of
     * the closed engaged sessions, so they add up to the rate (less any resolution
     * on evidence of a later phase). {@code value} is null when the rate is
     * withheld; {@code n} is always the part's session count.
     */
    static List<BreakdownEntry> resolutionBreakdown(OutcomeStats o, boolean withValues) {
        long n = o == null ? 0 : o.closed();
        long[] parts = o == null
                ? new long[4]
                : new long[]{o.resolvedConfirmed(), o.resolvedSelfReported(), o.resolvedAgentClosed(), o.resolvedSilence()};
        String[] keys = {"confirmed", "selfReported", "agentClosed", "silence"};
        String[] labels = {"Confirmed", "Self-reported", "Agent closed", "Silence"};
        List<BreakdownEntry> entries = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            Double value = withValues && n > 0 ? (double) parts[i] / n : null;
            entries.add(new BreakdownEntry(keys[i], labels[i], value, parts[i]));
        }
        return entries;
    }

    /**
     * Whether the outcomes cover enough of the closed sessions for a rate to mean
     * anything; otherwise the hint that says so.
     */
    private static String coverageHint(OutcomeStats o) {
        if (o.coverage() + 1e-9 >= o.minCoverage()) {
            return null;
        }
        return String.format(Locale.ROOT, HINT_LOW_COVERAGE, Math.round(o.coverage() * 100), Math.round(o.minCoverage() * 100));
    }

    private static Stat resolution(OutcomeStats o) {
        if (o == null) {
            return new Stat(null, 0, null, new NoTest(), resolutionBreakdown(null, false), HINT_OUTCOMES_UNAVAILABLE);
        }
        if (o.closed() <= 0) {
            return new Stat(null, 0, null, new NoTest(), resolutionBreakdown(o, false), HINT_NO_CLOSED_SESSIONS);
        }
        String low = coverageHint(o);
        if (low != null) {
            return new Stat(null, o.closed(), null, new NoTest(), resolutionBreakdown(o, false), low);
        }
        return proportion(o.resolved(), o.closed(), resolutionBreakdown(o, true));
    }

    /**
     * The period's spend over its resolved sessions — withheld with the rates when
     * the coverage is too low to say what "resolved" counts.
     */
    private static Stat costPerResolved(Totals t, OutcomeStats o) {
        if (o == null) {
            return Stat.none(HINT_OUTCOMES_UNAVAILABLE);
        }
        String low = o.closed() > 0 ? coverageHint(o) : null;
        if (low != null) {
            return new Stat(null, o.resolved(), null, new NoTest(), null, low);
        }
        return ratio(t.costUsd(), o.resolved());
    }

    /**
     * Escalation, abandonment, deflection: a share of the closed engaged sessions.
     */
    private static Stat outcomeRate(OutcomeStats o, long count) {
        if (o == null) {
            return Stat.none(HINT_OUTCOMES_UNAVAILABLE);
        }
        if (o.closed() <= 0) {
            return Stat.none(HINT_NO_CLOSED_SESSIONS);
        }
        String low = coverageHint(o);
        if (low != null) {
            return new Stat(null, o.closed(), null, new NoTest(), null, low);
        }
        return proportion(count, o.closed(), null);
    }

    private static String label(String failureClass) {
        String lower = failureClass.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
