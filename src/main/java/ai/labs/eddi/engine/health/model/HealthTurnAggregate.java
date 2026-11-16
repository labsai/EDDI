/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The turn facts of one ({@code agentId}, {@code agentVersion}, {@code origin},
 * {@code day}) added up — the row the daily rollup stores in
 * {@code healthdaily} / {@code health_daily}. Built by
 * {@code HealthTurnAggregator} from rows, so it can always be recomputed.
 * <p>
 * Discarded rows (undone executions) count only towards {@link #costUsd}: they
 * really ran and really spent, but a later execution replaced them, so they are
 * not turns of the conversation any more.
 * <p>
 * Holds no personal data: no user id, no conversation id. People and sessions
 * are counted from the turn rows themselves (see
 * {@code IHealthTurnStore#usage}), because distinct counts cannot be added up
 * across days.
 *
 * @param turns
 *            non-discarded turn executions
 * @param conversations
 *            distinct conversations among them (on this day, version and origin
 *            only — not additive across days)
 * @param userInputTurns
 *            turns carrying input from a person
 * @param failedTurns
 *            turns with an {@code ErrorKind}
 * @param errorKinds
 *            failed turns per {@code ErrorKind}
 * @param failuresByClass
 *            failed LLM attempts per {@code FailureClass}, recovered or not
 * @param recoveredFailures
 *            failed LLM attempts the engine recovered from
 * @param costUsd
 *            the spend of every row, discarded ones included — equal to the
 *            conversations' {@code conversationCost}
 * @param durationMsTotal
 *            summed over the turns that have a duration ({@code durationCount})
 * @param agentFailedTurns
 *            failed turns the agent is accountable for
 *            ({@code HealthAttribution})
 * @param platformFailedTurns
 *            failed turns caused by the platform or a provider
 * @param platformAffectedTurns
 *            turns a platform event touched: a platform-caused failure, or at
 *            least one failed provider attempt of a platform class, recovered
 *            or not
 * @param tokenTurns
 *            turns that reported a token count
 * @param durationBuckets
 *            how many turns fell into each bucket of
 *            {@code HealthTurnAggregator.DURATION_BOUNDS_MS} (one more entry
 *            than bounds: the last one is everything above the last bound)
 * @param apdexTMs
 *            the Apdex threshold T the two Apdex counts were taken with
 * @param apdexSatisfied
 *            turns that took at most T
 * @param apdexTolerating
 *            turns that took more than T and at most 4T
 */
public record HealthTurnAggregate(String agentId, Integer agentVersion, String origin, LocalDate day, long turns, long conversations,
        long userInputTurns, long failedTurns, Map<String, Long> errorKinds, long pausedTurns, long fallbackTurns,
        Map<String, Long> failuresByClass, long recoveredFailures, double costUsd, long totalTokens, long durationMsTotal, long durationCount,
        long toolCalls, long toolFailures, long toolRefusals, long guardrailTrips, long agentFailedTurns, long platformFailedTurns,
        long platformAffectedTurns, long tokenTurns, List<Long> durationBuckets, long apdexTMs, long apdexSatisfied, long apdexTolerating) {

    public HealthTurnAggregate {
        errorKinds = errorKinds == null ? Map.of() : Map.copyOf(errorKinds);
        failuresByClass = failuresByClass == null ? Map.of() : Map.copyOf(failuresByClass);
        durationBuckets = durationBuckets == null ? List.of() : List.copyOf(durationBuckets);
    }
}
