/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The record of one run of an agent test suite — evidence that a given agent
 * version behaved (or did not behave) as its suite says.
 * <p>
 * Not configuration: runs are written by the runner, never edited, and kept for
 * a bounded number per agent ({@code eddi.agent-tests.max-runs-per-agent}).
 *
 * @param id
 *            run id, assigned on creation
 * @param suiteId
 *            the suite that ran
 * @param suiteVersion
 *            its version
 * @param suiteName
 *            its name at the time, for display
 * @param agentId
 *            the agent under test
 * @param agentVersion
 *            the version that was deployed to the {@code test} environment and
 *            ran
 * @param status
 *            {@link Status}
 * @param startedBy
 *            principal that started the run, or {@code system:*}
 * @param startedAt
 *            epoch milliseconds; also the listing sort key
 * @param finishedAt
 *            epoch milliseconds, absent while running
 * @param durationMs
 *            wall time of the run
 * @param totalCostUsd
 *            summed {@code audit:cost} of every turn, including each
 *            conversation's start turn
 * @param maxCostPerRun
 *            the ceiling that applied
 * @param costCeilingReached
 *            the run stopped early because of it
 * @param models
 *            distinct model names the turns reported ({@code audit:model_name})
 * @param casesTotal
 *            number of cases
 * @param casesPassed
 *            cases that met their pass rate
 * @param casesFailed
 *            cases that did not
 * @param cases
 *            per-case results
 * @param keepConversations
 *            whether the test conversations were kept after the run
 * @param error
 *            why the run could not complete, for {@link Status#ERROR},
 *            {@link Status#ABORTED} and {@link Status#CANCELLED}
 * @param ownerNode
 *            the node executing the run, as {@code <nodeId>/<bootId>}; absent
 *            on runs recorded before 6.6
 * @param heartbeatAt
 *            epoch milliseconds of the owner's last sign of life while the run
 *            is {@code RUNNING}; a run whose heartbeat has gone stale is an
 *            orphan any node may close
 * @since 6.5.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentTestRun(String id, String suiteId, Integer suiteVersion, String suiteName, String agentId, Integer agentVersion,
        Status status, String startedBy, Long startedAt, Long finishedAt, Long durationMs, Double totalCostUsd, Double maxCostPerRun,
        Boolean costCeilingReached, List<String> models, Integer casesTotal, Integer casesPassed, Integer casesFailed,
        List<CaseResult> cases, Boolean keepConversations, String error, String ownerNode, Long heartbeatAt) {

    public enum Status {
        /** Accepted and executing. */
        RUNNING,
        /** Every case met its pass rate. */
        PASSED,
        /** At least one case did not, or the cost ceiling stopped the run. */
        FAILED,
        /** The run could not be executed (agent not deployable, runner failure). */
        ERROR,
        /**
         * The node executing the run stopped before it finished (restart, crash); found
         * {@code RUNNING} at the next startup. Its results are incomplete.
         */
        ABORTED,
        /**
         * Stopped on request ({@code POST /agenttests/runs/{runId}/cancel}). The cases
         * that finished keep their results; the rest did not run to the end.
         */
        CANCELLED
    }

    /**
     * This run, finished as {@link Status#ABORTED} with {@code reason}. The
     * heartbeat is cleared, so the owner's next compare-and-swap on it fails and
     * the owner learns its run was closed.
     */
    public AgentTestRun aborted(String reason, long now) {
        return new AgentTestRun(id, suiteId, suiteVersion, suiteName, agentId, agentVersion, Status.ABORTED, startedBy, startedAt, now,
                startedAt != null ? now - startedAt : null, totalCostUsd, maxCostPerRun, costCeilingReached, models, casesTotal, casesPassed,
                casesFailed, cases, keepConversations, reason, ownerNode, null);
    }

    public AgentTestRun withId(String newId) {
        return new AgentTestRun(newId, suiteId, suiteVersion, suiteName, agentId, agentVersion, status, startedBy, startedAt, finishedAt,
                durationMs, totalCostUsd, maxCostPerRun, costCeilingReached, models, casesTotal, casesPassed, casesFailed, cases,
                keepConversations, error, ownerNode, heartbeatAt);
    }

    /** This run with a fresh heartbeat. */
    public AgentTestRun withHeartbeat(long now) {
        return new AgentTestRun(id, suiteId, suiteVersion, suiteName, agentId, agentVersion, status, startedBy, startedAt, finishedAt,
                durationMs, totalCostUsd, maxCostPerRun, costCeilingReached, models, casesTotal, casesPassed, casesFailed, cases,
                keepConversations, error, ownerNode, now);
    }

    /**
     * @param name
     *            the case name
     * @param repetitions
     *            how often it ran
     * @param passRate
     *            the fraction that had to pass
     * @param passedRepetitions
     *            how many did
     * @param passed
     *            {@code passedRepetitions / repetitions >= passRate}
     * @param attempts
     *            one entry per repetition
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaseResult(String name, int repetitions, double passRate, int passedRepetitions, boolean passed,
            List<AttemptResult> attempts) {
    }

    /**
     * One repetition of a case — one conversation.
     *
     * @param repetition
     *            1-based
     * @param conversationId
     *            the test conversation; only kept when the conversations were
     * @param passed
     *            every turn passed and nothing unmocked was called
     * @param costUsd
     *            this conversation's cost
     * @param durationMs
     *            wall time
     * @param finalState
     *            the conversation state after the last executed turn
     * @param unmockedToolCalls
     *            tools called without a mock while unmocked calls fail — each one
     *            fails the attempt, and none of them was executed
     * @param turns
     *            per-turn results
     * @param error
     *            why the attempt could not run to the end
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AttemptResult(int repetition, String conversationId, boolean passed, double costUsd, long durationMs, String finalState,
            List<String> unmockedToolCalls, List<TurnResult> turns, String error) {
    }

    /**
     * @param index
     *            1-based turn number within the case
     * @param input
     *            what was sent
     * @param passed
     *            every expectation held
     * @param costUsd
     *            the turn's {@code audit:cost}
     * @param durationMs
     *            wall time
     * @param actions
     *            the actions that fired
     * @param output
     *            the output text, truncated
     * @param toolCalls
     *            the tools called, in order
     * @param conversationState
     *            the state after the turn
     * @param failures
     *            the expectations that did not hold
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TurnResult(int index, String input, boolean passed, double costUsd, long durationMs, List<String> actions, String output,
            List<String> toolCalls, String conversationState, List<ExpectationFailure> failures) {
    }

    /**
     * One expectation that did not hold.
     *
     * @param expectation
     *            which one, e.g. {@code actionsContain}, {@code properties.city}
     * @param expected
     *            what the suite asked for
     * @param actual
     *            what the turn produced
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExpectationFailure(String expectation, Object expected, Object actual) {
    }
}
