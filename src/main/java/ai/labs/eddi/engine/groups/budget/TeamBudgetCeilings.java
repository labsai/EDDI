/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.TeamBudgetState;

/**
 * The ceiling a discussion actually runs under once its team's budget is in the
 * picture (F6): the tighter of the group's own {@code maxCostPerDiscussion} and
 * the team ceiling, expressed in the discussion's {@code totalCost} terms.
 * <p>
 * Every ceiling check in the engines funnels through
 * {@code GroupCostLedger.enforceCeiling} / {@code wouldExceedCeiling} and the
 * nested-remainder computation, which ask this class, so no phase or task-force
 * engine needs to know teams exist.
 */
public final class TeamBudgetCeilings {

    private TeamBudgetCeilings() {
    }

    /**
     * @param ceiling
     *            the binding ceiling, or null for unlimited
     * @param policy
     *            what to do when it is reached
     * @param teamBound
     *            true when the team (or tenant) ceiling is the one that binds; a
     *            tie goes to the group's own protocol
     * @param limitedBy
     *            {@code TEAM} or {@code TENANT} when {@code teamBound}, else null
     * @param month
     *            the budget month when {@code teamBound}
     */
    public record Effective(Double ceiling, CostPolicy policy, boolean teamBound, String limitedBy, String month) {
    }

    public static Effective effective(GroupConversation gc, ProtocolConfig protocol) {
        Double own = protocol == null ? null : protocol.maxCostPerDiscussion();
        CostPolicy ownPolicy = protocol == null ? CostPolicy.SYNTHESIZE_NOW : protocol.onCostExceeded();
        TeamBudgetState state = gc == null ? null : gc.getTeamBudgetState();
        Double team = state == null ? null : state.getCeilingUsd();
        if (team == null || team.isNaN()) {
            return new Effective(own, ownPolicy, false, null, null);
        }
        if (own == null || team < own) {
            CostPolicy policy = state.getPolicy() != null ? state.getPolicy() : ownPolicy;
            return new Effective(team, policy, true, state.getLimitedBy(), state.getMonth());
        }
        return new Effective(own, ownPolicy, false, null, null);
    }
}
