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
 */
public final class HealthTurnAggregator implements Consumer<HealthTurn> {

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
    }

    private final Map<Key, Sums> sums = new LinkedHashMap<>();

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
        }
        if (turn.durationMs() != null && turn.durationMs() >= 0) {
            s.durationMsTotal += turn.durationMs();
            s.durationCount++;
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
                s.guardrailTrips)));
        out.sort(Comparator.comparing(HealthTurnAggregate::day, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(HealthTurnAggregate::agentVersion, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(a -> Objects.toString(a.origin(), "")));
        return out;
    }

    private static double positive(Double value) {
        return value != null && Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }
}
