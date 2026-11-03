/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import java.util.List;
import java.util.function.Predicate;

/**
 * The groups an agent-operations tool may name: the agent's own group (the one
 * its running discussion belongs to), the teams that group's
 * {@code teamBudget.oversight.teams} lets it read, and any groups the agent's
 * {@code agentOps.scorecard.groupIds} lists — each of them only while the
 * conversation's own user may view it.
 * <p>
 * The view check is the one the REST budget and workspace reads make
 * ({@code ResourceAccessGuard}, VIEW on the group), asked about the
 * conversation's user rather than the model. It runs at <em>call</em> time, not
 * assembly time, so a share withdrawn mid-conversation takes effect on the next
 * call.
 *
 * @param candidates
 *            own group first, then the others, no duplicates
 * @param mayView
 *            whether the conversation's user may view a group
 */
public record AgentOpsScope(List<String> candidates, Predicate<String> mayView) {

    public AgentOpsScope {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        mayView = mayView == null ? id -> false : mayView;
    }

    /** The candidates the user may view right now, in order. */
    public List<String> visible() {
        return candidates.stream().filter(this::mayViewSafely).toList();
    }

    /** Whether {@code groupId} is both a candidate and viewable. */
    public boolean contains(String groupId) {
        return groupId != null && candidates.contains(groupId) && mayViewSafely(groupId);
    }

    private boolean mayViewSafely(String groupId) {
        try {
            return mayView.test(groupId);
        } catch (RuntimeException e) {
            // Fail closed: a check that cannot answer is not a yes.
            return false;
        }
    }
}
