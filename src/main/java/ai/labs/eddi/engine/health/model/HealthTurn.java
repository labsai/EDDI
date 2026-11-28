/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One turn execution, as Agent Health records it — the fact row of
 * {@code healthturns} / {@code health_turns}.
 * <p>
 * Keyed by ({@code conversationId}, {@code stepIndex}, {@code executionId}) and
 * written by <b>replace</b>, never by increment: a HITL resume settles the same
 * step again and replaces its own row, a retried or late write replaces it with
 * the same content, and a say after an undo runs at the same step index under a
 * new execution id. The numbers are the step's totals as the turn left them —
 * the same {@code audit:*} keys the audit ledger records — so health and the
 * ledger always agree. Not billing-grade: quotas and cost ceilings stay the
 * authoritative counters.
 * <p>
 * Holds no conversation content: no input or output text, no property values.
 * {@code userId} is kept for GDPR erasure and export, never shown in
 * aggregates.
 *
 * @param stepIndex
 *            0-based position of the step in the conversation
 * @param executionId
 *            the step's {@code health:execution_id}; {@code legacy-<index>} for
 *            a step that finished an execution before Agent Health existed; a
 *            fresh random id for a turn the watchdog abandoned before its step
 *            was stamped
 * @param previousAgentVersion
 *            the version the conversation moved from on this step
 *            ({@code agent:switch}), else {@code null}
 * @param origin
 *            where the turn came from — {@code HealthOrigins}
 * @param day
 *            the UTC day the execution was first recorded; kept by every later
 *            replace, so a turn paused on one day and resumed on the next stays
 *            on the first
 * @param timestamp
 *            when the execution was first recorded (kept on replace, like
 *            {@code day})
 * @param updatedAt
 *            when this version of the row was captured — the replace guard: a
 *            write older than the stored row's {@code updatedAt} is ignored
 * @param costUsd
 *            the step's {@code audit:cost}, {@code null} when nothing priced
 *            ran
 * @param discardedCostUsd
 *            the spend of undone steps carried onto this one
 *            ({@code audit:discarded_cost}); summing it over the non-discarded
 *            rows equals summing {@code costUsd} over the discarded ones
 * @param durationMs
 *            how long the execution took as the hook measured it; for a resumed
 *            turn, the resumed part only
 * @param errorKind
 *            the turn's {@code ErrorKind} name, {@code null} for a clean turn
 * @param paused
 *            the turn ended {@code AWAITING_HUMAN}
 * @param failures
 *            every failed LLM attempt of the step, recovered ones included
 * @param fallbackServed
 *            an LLM task served its configured fallback
 * @param toolCalls
 *            tool calls that ran (mocked ones included) — the
 *            {@code tool_result} trace entries not marked {@code notExecuted}
 * @param toolFailures
 *            tool calls that ran and failed (an exception, an HTTP error, a
 *            timeout, an unknown tool) — a subset of {@code toolCalls}
 * @param toolRefusals
 *            tool calls that never ran: refused by a quota, a budget, the turn
 *            deadline, an approval cap or a credential-reference check
 * @param guardrailTrips
 *            times a guardrail fired
 * @param userInput
 *            the turn carried input from a person (not the start turn, not a
 *            self-continuation)
 * @param agentOutput
 *            the turn rendered output text
 * @param lastOutputWasQuestion
 *            the output text, trimmed, ends with a question mark — a cheap
 *            heuristic for "the agent asked and is waiting"
 * @param actions
 *            the turn's actions, capped
 * @param discarded
 *            an undo removed this execution and a later turn replaced it
 */
public record HealthTurn(String conversationId, int stepIndex, String executionId, String agentId, Integer agentVersion,
        Integer previousAgentVersion, String userId, String origin, LocalDate day, Instant timestamp, Instant updatedAt, Double costUsd,
        Double discardedCostUsd,
        Long inputTokens, Long outputTokens, Long totalTokens, Long durationMs, String errorKind, boolean paused, List<HealthFailure> failures,
        boolean fallbackServed, int toolCalls, int toolFailures, int toolRefusals, int guardrailTrips, boolean userInput, boolean agentOutput,
        boolean lastOutputWasQuestion, List<String> actions, boolean discarded) {

    public HealthTurn {
        failures = failures == null ? List.of() : List.copyOf(failures);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /**
     * Whether this is a real row — a discard marker written before its execution's
     * row arrived has no {@code timestamp}.
     */
    public boolean recorded() {
        return timestamp != null;
    }

    /** The prefix of the execution id of a step stored before Agent Health. */
    public static final String LEGACY_EXECUTION_PREFIX = "legacy-";

    /** Whether the turn failed. */
    public boolean failed() {
        return errorKind != null;
    }

    /** This row with {@code discarded} set. */
    public HealthTurn asDiscarded() {
        return new HealthTurn(conversationId, stepIndex, executionId, agentId, agentVersion, previousAgentVersion, userId, origin, day, timestamp,
                updatedAt, costUsd, discardedCostUsd, inputTokens, outputTokens, totalTokens, durationMs, errorKind, paused, failures, fallbackServed,
                toolCalls, toolFailures, toolRefusals, guardrailTrips, userInput, agentOutput, lastOutputWasQuestion, actions, true);
    }
}
