/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One session of a conversation and how it ended — the row of
 * {@code healthoutcomes} / {@code health_outcomes}, keyed by
 * ({@code conversationId}, {@code sessionNo}).
 * <p>
 * Derived from the turn rows (and the conversation's ratings) by the outcome
 * evaluation, and written by replace: evaluating the same rows again writes the
 * same row. Holds no conversation content — ids, counts, an outcome, the
 * evidence for it, and the action names of the session's last turn.
 *
 * @param sessionNo
 *            the step index the session starts at: {@code 0} for a
 *            conversation's first session, the index of its first turn for a
 *            later one. Not a count — a step index stays put when older turns
 *            leave the retention
 * @param agentVersion
 *            the version of the session's last turn
 * @param userId
 *            for GDPR erasure and export only
 * @param origin
 *            the conversation's origin: its first turn row that is not
 *            {@code api}
 * @param engaged
 *            a person's turn came after the agent's first output
 * @param endedAt
 *            when the session's last turn ended (so far)
 * @param turns
 *            the session's turns (not discarded)
 * @param costUsd
 *            their spend, undone executions included
 * @param failures
 *            failed turns
 * @param outcome
 *            {@code HealthOutcomes}; {@code null} for a session of an origin
 *            that is not evaluated (schedules, webhooks, A2A, groups,
 *            delegation, agent tests)
 * @param evidence
 *            what the outcome rests on; {@code null} while {@code OPEN} and for
 *            an origin that is not evaluated
 * @param provisional
 *            the session is still inside its inactivity window, so a later turn
 *            can change the outcome; only closed sessions enter the rates
 * @param day
 *            the UTC day of {@code endedAt} — the day the session counts on
 * @param dueAt
 *            when the evaluation must look at the session again (its window
 *            closes, or a rating arrived); {@code null} when nothing is pending
 * @param firstOutputStep
 *            the step index of the conversation's first agent output, as far as
 *            the evaluation saw — what engagement is judged against
 * @param lastActions
 *            the actions of the session's last turn — for mapping suggestions
 * @param evaluatedAt
 *            when this version of the row was computed
 */
public record HealthOutcome(String conversationId, int sessionNo, String agentId, Integer agentVersion, String userId, String origin,
        boolean engaged, Instant startedAt, Instant endedAt, int turns, double costUsd, int failures, String outcome, String evidence,
        boolean provisional, LocalDate day, Instant dueAt, Integer firstOutputStep, List<String> lastActions, Instant evaluatedAt) {

    public HealthOutcome {
        lastActions = lastActions == null ? List.of() : List.copyOf(lastActions);
    }

    /**
     * Whether the session is closed and has an outcome — it counts in the rates.
     */
    public boolean closed() {
        return !provisional && outcome != null;
    }
}
