/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import ai.labs.eddi.configs.groups.model.HandoffPolicies.IntakeMode;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.SourceKind;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.Trigger;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffAdmission.Decision;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The team-budget gate on cross-team handoff: the RECEIVING team's budget
 * decides, with the start gate's own sentence.
 */
class TeamBudgetHandoffAdmissionTest {

    private static final String SOURCE = "5f1e2d3c4b5a69788796a5b4";
    private static final String TARGET = "6a7b8c9d0e1f2a3b4c5d6e7f";

    private final TeamBudgetService service = mock(TeamBudgetService.class);
    private final TeamBudgetHandoffAdmission admission = new TeamBudgetHandoffAdmission(service);

    private static HandoffRequest request(String target) {
        return new HandoffRequest(SourceKind.GROUP, SOURCE, null, "gc-1", "member-1", "user-1", target, "Follow up the lead", "details", 5,
                Trigger.TOOL, null, null, null, 0, List.of(), null, 0, null);
    }

    @Test
    @DisplayName("a target team that can pay admits the handoff, and nothing is counted")
    void admitsWhenTheTargetMayStartWork() {
        when(service.decide(TARGET)).thenReturn(new TeamBudgetService.Decision(true, null, null, null));

        Decision decision = admission.check(request(TARGET), "s", "d", IntakeMode.REVIEW);

        assertTrue(decision.admitted());
        verify(service, never()).countRefusal(any(), anyString());
    }

    @Test
    @DisplayName("an exhausted target refuses with the budget's sentence and counts surface=handoff")
    void refusesWhenTheTargetIsExhausted() {
        String message = "Team 'Account Desk' has spent $10.00 of its $10.00 budget for 2026-11; it resets 2026-12-01T00:00:00Z.";
        when(service.decide(TARGET)).thenReturn(new TeamBudgetService.Decision(false, "exhausted", message, null));

        Decision decision = admission.check(request(TARGET), "s", "d", IntakeMode.AUTO);

        assertFalse(decision.admitted());
        assertEquals(message, decision.reason());
        verify(service).countRefusal(TeamBudgetService.Surface.HANDOFF, "exhausted");
    }

    @Test
    @DisplayName("an unreadable target budget refuses (fail closed), as the start gate does")
    void refusesWhenTheTargetBudgetIsUnreadable() {
        when(service.decide(TARGET)).thenReturn(new TeamBudgetService.Decision(false, "unavailable", "could not be read", null));

        Decision decision = admission.check(request(TARGET), "s", "d", IntakeMode.REVIEW);

        assertFalse(decision.admitted());
        verify(service).countRefusal(TeamBudgetService.Surface.HANDOFF, "unavailable");
    }

    @Test
    @DisplayName("the SOURCE team's budget is never consulted — only the team that will do the work")
    void decidesOnTheTargetOnly() {
        when(service.decide(TARGET)).thenReturn(new TeamBudgetService.Decision(true, null, null, null));

        admission.check(request(TARGET), "s", "d", IntakeMode.REVIEW);

        verify(service).decide(TARGET);
        verify(service, never()).decide(SOURCE);
    }

    @Test
    @DisplayName("a request without a resolved target is left to the handoff service's own checks")
    void noTargetIsAdmitted() {
        assertTrue(admission.check(request(null), "s", "d", IntakeMode.REVIEW).admitted());
        assertTrue(admission.check(null, "s", "d", IntakeMode.REVIEW).admitted());
        verifyNoInteractions(service);
    }
}
