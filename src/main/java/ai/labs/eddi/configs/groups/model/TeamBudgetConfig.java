/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;

import java.util.List;

/**
 * A group's monthly dollar budget (F6). {@code null} on the group means no team
 * cap and no team metering: the tenant budget still applies, but nothing is
 * charged to the team. A cap added mid-month therefore counts only the spend
 * made after it was added; earlier spend is not back-filled.
 * <p>
 * The window is the UTC calendar month, like the tenant's. The base cap lives
 * here (versioned and exported with the group); a mid-month raise or a transfer
 * from another team is a separate runtime adjustment that never mints a config
 * version.
 *
 * @param monthlyBudgetUsd
 *            the base cap per month; must be positive and finite
 * @param onExhausted
 *            what a running discussion does when the team cap binds;
 *            {@code null} means the group's {@code protocol.onCostExceeded}
 * @param oversight
 *            other teams this group's members may read the budget of (template
 *            value {@code context.teamBudgets}); {@code null} reads only its
 *            own
 */
public record TeamBudgetConfig(Double monthlyBudgetUsd, CostPolicy onExhausted, Oversight oversight) {

    /** Upper bound on {@link Oversight#teams}. */
    public static final int MAX_OVERSIGHT_TEAMS = 50;

    /**
     * @param teams
     *            group ids whose budget this team may read
     */
    public record Oversight(List<String> teams) {
    }
}
