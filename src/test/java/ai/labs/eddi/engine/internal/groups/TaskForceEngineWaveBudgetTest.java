/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy;
import ai.labs.eddi.engine.internal.GroupConversationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The EXECUTE wave's wall-clock budget.
 * <p>
 * The wave used to wait {@code agentTimeoutSeconds × maxTasksPerAgent}, which
 * is shorter than what a single member turn may legitimately consume. Two ways
 * it lost the race, both asserted here:
 * <ul>
 * <li>under {@code onAgentFailure=RETRY} a member gets
 * {@code timeout × (maxRetries + 1)}, so a wave sized for one attempt aborted
 * mid-flight the first time anything retried;</li>
 * <li>with no retries at all it still carried no setup grace, so the
 * orchestrator's deadline — armed at dispatch — could expire while the member
 * was still inside its own budget, because a member turn reaches its response
 * wait only after agent lookup, conversation start and attachment grants.</li>
 * </ul>
 * The invariant is simply that the wave never gives up before the turns it is
 * waiting for could have.
 *
 * @author ginccc
 */
@DisplayName("TaskForceEngine — EXECUTE wave budget")
class TaskForceEngineWaveBudgetTest {

    private static ProtocolConfig protocol(int timeoutSeconds, MemberFailurePolicy onFailure, int maxRetries) {
        return new ProtocolConfig(timeoutSeconds, onFailure, maxRetries, MemberUnavailablePolicy.SKIP);
    }

    /** What one member turn may legitimately consume, per the batch helper. */
    private static long perTurn(ProtocolConfig protocol) {
        return GroupConversationService.parallelBatchBudgetSeconds(protocol);
    }

    @Test
    @DisplayName("never shorter than one member turn's own budget")
    void neverShorterThanOneMemberTurn() {
        var retrying = protocol(30, MemberFailurePolicy.RETRY, 2);

        assertTrue(TaskForceEngine.waveBudgetSeconds(retrying, 1) >= perTurn(retrying),
                "a one-task wave must outlast the single turn it waits for");
    }

    @Test
    @DisplayName("RETRY budget covers every attempt, not just the first")
    void retryBudgetCoversEveryAttempt() {
        var retrying = protocol(30, MemberFailurePolicy.RETRY, 2);

        long budget = TaskForceEngine.waveBudgetSeconds(retrying, 1);

        // 3 attempts x 30s = 90s of legitimate member time. The old formula produced
        // exactly 30 and aborted the wave on the first retry.
        assertTrue(budget >= 90, "expected at least 3 attempts' worth of budget, got " + budget);
        assertTrue(budget > 30L, "the old agentTimeoutSeconds x maxTasksPerAgent formula would have produced 30");
    }

    @Test
    @DisplayName("SKIP and ABORT policies get one attempt's budget plus grace")
    void nonRetryingPoliciesGetGrace() {
        for (var policy : new MemberFailurePolicy[]{MemberFailurePolicy.SKIP, MemberFailurePolicy.ABORT}) {
            var config = protocol(30, policy, 2);
            long budget = TaskForceEngine.waveBudgetSeconds(config, 1);

            assertTrue(budget > 30L,
                    policy + ": the deadline must sit strictly behind the member's own, so its timeout handling stays reachable");
            assertEquals(perTurn(config), budget, policy + ": one task means exactly one member turn's budget");
        }
    }

    @Test
    @DisplayName("scales with the longest task chain a single agent holds")
    void scalesWithTheLongestChain() {
        var config = protocol(30, MemberFailurePolicy.SKIP, 2);

        long one = TaskForceEngine.waveBudgetSeconds(config, 1);
        long four = TaskForceEngine.waveBudgetSeconds(config, 4);

        assertEquals(one * 4, four, "an agent runs its own tasks sequentially");
    }

    @Test
    @DisplayName("a non-positive task count is treated as one task")
    void nonPositiveTaskCountFloorsAtOne() {
        var config = protocol(30, MemberFailurePolicy.SKIP, 2);
        long one = TaskForceEngine.waveBudgetSeconds(config, 1);

        assertEquals(one, TaskForceEngine.waveBudgetSeconds(config, 0));
        assertEquals(one, TaskForceEngine.waveBudgetSeconds(config, -5));
    }

    @Test
    @DisplayName("an unset timeout falls back to the engine default, not to zero")
    void unsetTimeoutUsesTheDefault() {
        var unset = protocol(0, MemberFailurePolicy.SKIP, 0);

        assertTrue(TaskForceEngine.waveBudgetSeconds(unset, 1) >= ProtocolConfig.DEFAULT_AGENT_TIMEOUT_SECONDS,
                "a protocol with no timeout must not produce a zero-second wave deadline");
    }

    @Test
    @DisplayName("an absurd protocol cannot overflow the deadline into the past")
    void absurdProtocolStaysPositive() {
        var absurd = protocol(Integer.MAX_VALUE, MemberFailurePolicy.RETRY, Integer.MAX_VALUE);

        long budget = TaskForceEngine.waveBudgetSeconds(absurd, Integer.MAX_VALUE);

        assertTrue(budget > 0, "expected a positive budget, got " + budget);
    }
}
