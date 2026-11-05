/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import ai.labs.eddi.configs.groups.model.HandoffPolicies.IntakeMode;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffAdmission;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Refuses a cross-team handoff while the <em>receiving</em> team cannot pay for
 * new work: its monthly budget is spent, the tenant budget is spent, or the
 * budget cannot be read (fail closed, as the discussion start gate does).
 * <p>
 * A handed-off task is worked by the receiving team's cadence, which is a root
 * discussion of that team and so is charged to it; filing it while that team is
 * out of money only fills a backlog nobody can work until the month resets, and
 * the sender would not learn why. The refusal carries the same sentence the
 * start gate gives ({@link TeamBudgetService#decide}), so the model and the
 * audit entry say what the cap is and when it resets.
 * <p>
 * A target without a {@code teamBudget} is admitted without a store read, and
 * with {@code eddi.groups.team-budget.metering-enabled=false} every handoff is
 * admitted — the same off-switch as every other team-budget gate.
 */
@ApplicationScoped
public class TeamBudgetHandoffAdmission implements HandoffAdmission {

    private final TeamBudgetService teamBudgetService;

    @Inject
    public TeamBudgetHandoffAdmission(TeamBudgetService teamBudgetService) {
        this.teamBudgetService = teamBudgetService;
    }

    @Override
    public Decision check(HandoffRequest request, String subject, String description, IntakeMode mode) {
        if (request == null || request.targetGroupId() == null) {
            return Decision.admit();
        }
        TeamBudgetService.Decision budget = teamBudgetService.decide(request.targetGroupId());
        if (budget.allowed()) {
            return Decision.admit();
        }
        teamBudgetService.countRefusal(TeamBudgetService.Surface.HANDOFF, budget.reason());
        return Decision.refuse(budget.message());
    }
}
