/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.time.LocalDate;
import java.util.Map;

/**
 * The turn facts of one ({@code agentId}, {@code agentVersion}, {@code origin},
 * {@code day}) added up — the shape the H2b daily rollup stores. Built by
 * {@code HealthTurnAggregator} from rows, so it can always be recomputed.
 * <p>
 * Discarded rows (undone executions) count only towards {@link #costUsd}: they
 * really ran and really spent, but a later execution replaced them, so they are
 * not turns of the conversation any more.
 *
 * @param turns
 *            non-discarded turn executions
 * @param conversations
 *            distinct conversations among them
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
 */
public record HealthTurnAggregate(String agentId, Integer agentVersion, String origin, LocalDate day, long turns, long conversations,
        long userInputTurns, long failedTurns, Map<String, Long> errorKinds, long pausedTurns, long fallbackTurns,
        Map<String, Long> failuresByClass, long recoveredFailures, double costUsd, long totalTokens, long durationMsTotal, long durationCount,
        long toolCalls, long toolFailures, long toolRefusals, long guardrailTrips) {

    public HealthTurnAggregate {
        errorKinds = errorKinds == null ? Map.of() : Map.copyOf(errorKinds);
        failuresByClass = failuresByClass == null ? Map.of() : Map.copyOf(failuresByClass);
    }
}
