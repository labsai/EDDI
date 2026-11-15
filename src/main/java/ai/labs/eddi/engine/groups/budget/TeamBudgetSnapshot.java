/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import java.time.Instant;

/**
 * A team's budget at a moment (F6). Numbers are USD, the window is the UTC
 * calendar {@code month}.
 *
 * @param baseUsd
 *            the configured monthly cap, or null when the group has no budget
 * @param adjustmentUsd
 *            this month's delta over the base (transfers, a raised cap)
 * @param capUsd
 *            {@code base + adjustment}, or null without a budget
 * @param remainingUsd
 *            {@code max(0, cap - spent)}, or null without a budget
 * @param exhausted
 *            {@code spent >= cap}; equality refuses, as the tenant does
 * @param tenantRemainingUsd
 *            what is left of the tenant's monthly budget, or null when none is
 *            in force
 * @param limitedBy
 *            {@code TEAM}, {@code TENANT} or {@code NONE}: which limit binds
 *            first
 * @param resetsAt
 *            the start of the next UTC month
 */
public record TeamBudgetSnapshot(String groupId, String groupName, String month, Double baseUsd, double adjustmentUsd, Double capUsd,
        double spentUsd, Double remainingUsd, boolean exhausted, Instant resetsAt, Double tenantRemainingUsd, String limitedBy) {

    public static final String LIMIT_TEAM = "TEAM";
    public static final String LIMIT_TENANT = "TENANT";
    public static final String LIMIT_NONE = "NONE";

    /** The most the team can still spend this month under both limits. */
    public Double headroomUsd() {
        if (remainingUsd == null) {
            return tenantRemainingUsd;
        }
        return tenantRemainingUsd == null ? remainingUsd : Math.min(remainingUsd, tenantRemainingUsd);
    }
}
