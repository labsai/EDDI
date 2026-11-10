/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Save-time checks for {@code teamBudget} (F6). A new field, so a bad value is
 * a hard 400 rather than coalesced: there is no legacy data to keep loading.
 */
public final class TeamBudgetConfigValidator {

    private TeamBudgetConfigValidator() {
    }

    /**
     * @throws IllegalArgumentException
     *             (mapped to 400) naming every problem
     */
    public static void validate(String groupId, AgentGroupConfiguration config) {
        List<String> problems = problems(groupId, config == null ? null : config.getTeamBudget());
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid teamBudget: " + String.join("; ", problems));
        }
    }

    static List<String> problems(String groupId, TeamBudgetConfig budget) {
        List<String> problems = new ArrayList<>();
        if (budget == null) {
            return problems;
        }
        Double cap = budget.monthlyBudgetUsd();
        if (cap == null || !Double.isFinite(cap) || !(cap > 0.0)) {
            problems.add("'monthlyBudgetUsd' must be a positive, finite number");
        }
        TeamBudgetConfig.Oversight oversight = budget.oversight();
        if (oversight != null && oversight.teams() != null) {
            List<String> teams = oversight.teams();
            if (teams.size() > TeamBudgetConfig.MAX_OVERSIGHT_TEAMS) {
                problems.add("'oversight.teams' may list at most " + TeamBudgetConfig.MAX_OVERSIGHT_TEAMS + " groups");
            }
            Set<String> seen = new HashSet<>();
            for (String team : teams) {
                if (team == null || team.isBlank()) {
                    problems.add("'oversight.teams' must not contain blank ids");
                } else if (!seen.add(team)) {
                    problems.add("'oversight.teams' lists '" + team + "' more than once");
                } else if (team.equals(groupId)) {
                    problems.add("'oversight.teams' must not list the group itself");
                }
            }
        }
        return problems;
    }
}
