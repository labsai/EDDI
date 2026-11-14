/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;

import java.util.List;
import java.util.Objects;

/**
 * Who may change a group's {@code teamBudget} (F6).
 * <p>
 * Otherwise an editor could raise their own team's cap by saving the group,
 * which would make the budget advisory. With admin-only edits on (the default)
 * any change to the block, including removing it, needs the admin role; a group
 * that has no budget before and after stays editable by editors. Independently
 * of the admin switch, whoever changes the block must hold VIEW on every group
 * in its {@code oversight} list, so a board can never be configured to read a
 * team its author cannot read. A save that leaves the block unchanged is not
 * checked at all.
 */
final class TeamBudgetEditPolicy {

    private TeamBudgetEditPolicy() {
    }

    static void check(TeamBudgetConfig before, TeamBudgetConfig after, boolean adminOnly, ResourceAccessGuard guard) {
        if (Objects.equals(before, after)) {
            return;
        }
        checkChange(after, adminOnly, guard);
    }

    /**
     * For an update whose stored version could not be read: nothing shows that the
     * block is unchanged, so it is treated as changed (fail closed). Otherwise an
     * editor could send {@code teamBudget: null} while the read fails and remove
     * the cap, since null-to-null compares equal.
     */
    static void checkAgainstUnreadable(TeamBudgetConfig after, boolean adminOnly, ResourceAccessGuard guard) {
        checkChange(after, adminOnly, guard);
    }

    private static void checkChange(TeamBudgetConfig after, boolean adminOnly, ResourceAccessGuard guard) {
        if (adminOnly && !guard.isAdmin()) {
            throw new ForbiddenException("Changing a group's teamBudget requires the eddi-admin role "
                    + "(eddi.groups.team-budget.admin-only-edits=true)");
        }
        List<String> teams = after != null && after.oversight() != null ? after.oversight().teams() : null;
        if (teams != null) {
            for (String team : teams) {
                if (team != null && !guard.hasAccess(team, AccessLevel.VIEW)) {
                    throw new ForbiddenException("teamBudget.oversight lists a team you may not view");
                }
            }
        }
    }
}
