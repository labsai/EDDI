/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Adds turn rows up into {@link HealthTurnAggregate}s, one per
 * ({@code agentId}, {@code agentVersion}, {@code origin}, {@code day}).
 * <p>
 * Store-agnostic on purpose: the H2b rollup streams one agent-day through
 * {@code IHealthTurnStore#forEachByAgentAndDay} into {@link #accept} and stores
 * {@link #result()}, so MongoDB and PostgreSQL produce bit-identical aggregates
 * from the same rows, and a re-roll of a day is the same computation as the
 * first roll. Not thread-safe — one instance per rollup.
 * <p>
 * Durations go into fixed buckets ({@link #DURATION_BOUNDS_MS}), from which a
 * percentile can be read approximately, and into the two Apdex counts for the
 * threshold T the aggregator was built with.
 */
public final class HealthTurnAggregator implements Consumer<HealthTurn> {

    /**
     * Upper bounds (inclusive, ms) of the duration buckets; one more bucket holds
     * everything above the last bound. Never reorder or remove a bound: stored
     * daily rows are read by position. Appending changes the bucket count, which
     * the reader tolerates (a shorter stored list is padded with zeros).
     */
    public static final long[] DURATION_BOUNDS_MS = {100, 250, 500, 1_000, 2_000, 3_000, 4_000, 5_000, 7_500, 10_000, 15_000, 20_000, 30_000,
            60_000, 120_000, 300_000};

    /** The default Apdex threshold T. */
    public static final long DEFAULT_APDEX_T_MS = 4_000;

    private record Key(String agentId, Integer agentVersion, String origin, LocalDate day) {
    }

    private static final class Sums {
        long turns;
        final Set<String> conversations = new HashSet<>();
        long userInputTurns;
        long failedTurns;
        final Map<String, Long> errorKinds = new TreeMap<>();
        long pausedTurns;
        long fallbackTurns;
        final Map<String, Long> failuresByClass = new TreeMap<>();
        long recoveredFailures;
        double costUsd;
        long totalTokens;
        long durationMsTotal;
        long durationCount;
        long toolCalls;
        long toolFailures;
        long toolRefusals;
        long guardrailTrips;
        long agentFailedTurns;
        long platformFailedTurns;
        long platformAffectedTurns;
        long tokenTurns;
        final long[] durationBuckets = new long[DURATION_BOUNDS_MS.length + 1];
        long apdexSatisfied;
        long apdexTolerating;
    }

    private final Map<Key, Sums> sums = new LinkedHashMap<>();
    private final long apdexTMs;

    /** With the default Apdex threshold. */
    public HealthTurnAggregator() {
        this(DEFAULT_APDEX_T_MS);
    }

    /**
     * @param apdexTMs
     *            the Apdex threshold T in ms; a turn of at most T satisfies, one of
     *            at most 4T is tolerated
     */
    public HealthTurnAggregator(long apdexTMs) {
        this.apdexTMs = apdexTMs > 0 ? apdexTMs : DEFAULT_APDEX_T_MS;
    }

    /** The bucket a duration falls into. */
    public static int bucketOf(long durationMs) {
        for (int i = 0; i < DURATION_BOUNDS_MS.length; i++) {
            if (durationMs <= DURATION_BOUNDS_MS[i]) {
                return i;
            }
        }
        return DURATION_BOUNDS_MS.length;
    }

    @Override
    public void accept(HealthTurn turn) {
        if (turn == null || !turn.recorded()) {
            // A discard marker whose execution's row never arrived holds no turn.
            return;
        }
        Sums s = sums.computeIfAbsent(new Key(turn.agentId(), turn.agentVersion(), turn.origin(), turn.day()), k -> new Sums());
        // Spend counts on every row: an undone execution really ran. Its cost was
        // carried onto the replacing step as discardedCostUsd, which is therefore NOT
        // added here — that would count it twice.
        s.costUsd += positive(turn.costUsd());
        if (turn.discarded()) {
            return;
        }
        s.turns++;
        s.conversations.add(turn.conversationId());
        if (turn.userInput()) {
            s.userInputTurns++;
        }
        if (turn.errorKind() != null) {
            s.failedTurns++;
            s.errorKinds.merge(turn.errorKind(), 1L, Long::sum);
            if (HealthAttribution.attribute(turn) == HealthAttribution.Party.PLATFORM) {
                s.platformFailedTurns++;
            } else {
                s.agentFailedTurns++;
            }
        }
        if (HealthAttribution.platformAffected(turn)) {
            s.platformAffectedTurns++;
        }
        if (turn.paused()) {
            s.pausedTurns++;
        }
        if (turn.fallbackServed()) {
            s.fallbackTurns++;
        }
        for (HealthFailure failure : turn.failures()) {
            s.failuresByClass.merge(failure.failureClass(), 1L, Long::sum);
            if (failure.recovered()) {
                s.recoveredFailures++;
            }
        }
        if (turn.totalTokens() != null && turn.totalTokens() > 0) {
            s.totalTokens += turn.totalTokens();
            s.tokenTurns++;
        }
        // A paused turn's duration is its run up to the pause; its resume replaces
        // the row, so the paused version is not a latency sample.
        if (turn.durationMs() != null && turn.durationMs() >= 0 && !turn.paused()) {
            long duration = turn.durationMs();
            s.durationMsTotal += duration;
            s.durationCount++;
            s.durationBuckets[bucketOf(duration)]++;
            if (duration <= apdexTMs) {
                s.apdexSatisfied++;
            } else if (duration <= 4 * apdexTMs) {
                s.apdexTolerating++;
            }
        }
        s.toolCalls += turn.toolCalls();
        s.toolFailures += turn.toolFailures();
        s.toolRefusals += turn.toolRefusals();
        s.guardrailTrips += turn.guardrailTrips();
    }

    /** One aggregate per key seen, ordered by day, version and origin. */
    public List<HealthTurnAggregate> result() {
        List<HealthTurnAggregate> out = new ArrayList<>(sums.size());
        sums.forEach((k, s) -> out.add(new HealthTurnAggregate(k.agentId(), k.agentVersion(), k.origin(), k.day(), s.turns,
                s.conversations.size(), s.userInputTurns, s.failedTurns, s.errorKinds, s.pausedTurns, s.fallbackTurns, s.failuresByClass,
                s.recoveredFailures, s.costUsd, s.totalTokens, s.durationMsTotal, s.durationCount, s.toolCalls, s.toolFailures, s.toolRefusals,
                s.guardrailTrips, s.agentFailedTurns, s.platformFailedTurns, s.platformAffectedTurns, s.tokenTurns,
                Arrays.stream(s.durationBuckets).boxed().toList(), apdexTMs, s.apdexSatisfied, s.apdexTolerating)));
        out.sort(Comparator.comparing(HealthTurnAggregate::day, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(HealthTurnAggregate::agentVersion, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(a -> Objects.toString(a.origin(), "")));
        return out;
    }

    private static double positive(Double value) {
        return value != null && Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }
}
