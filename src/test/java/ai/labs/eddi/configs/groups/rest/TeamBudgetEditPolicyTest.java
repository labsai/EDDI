/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig;
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig.Oversight;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamBudgetEditPolicyTest {

    private ResourceAccessGuard guard;

    @BeforeEach
    void setUp() {
        guard = mock(ResourceAccessGuard.class);
    }

    private static TeamBudgetConfig cap(double usd) {
        return new TeamBudgetConfig(usd, null, null);
    }

    @Test
    void anEditorMayNotRaiseRemoveOrAddACapWhileAdminOnly() {
        assertThrows(ForbiddenException.class, () -> TeamBudgetEditPolicy.check(cap(10), cap(50), true, guard));
        assertThrows(ForbiddenException.class, () -> TeamBudgetEditPolicy.check(cap(10), null, true, guard));
        assertThrows(ForbiddenException.class, () -> TeamBudgetEditPolicy.check(null, cap(10), true, guard));
    }

    @Test
    void anAdminMay() {
        when(guard.isAdmin()).thenReturn(true);
        assertDoesNotThrow(() -> TeamBudgetEditPolicy.check(cap(10), cap(50), true, guard));
    }

    @Test
    void anUnchangedOrAbsentBudgetNeverNeedsAdmin() {
        assertDoesNotThrow(() -> TeamBudgetEditPolicy.check(cap(10), cap(10), true, guard));
        assertDoesNotThrow(() -> TeamBudgetEditPolicy.check(null, null, true, guard));
    }

    @Test
    void anUnreadableStoredVersionCountsAsAChange() {
        // null-to-null would compare equal; with the stored block unknown it must not
        assertThrows(ForbiddenException.class, () -> TeamBudgetEditPolicy.checkAgainstUnreadable(null, true, guard));
        assertThrows(ForbiddenException.class, () -> TeamBudgetEditPolicy.checkAgainstUnreadable(cap(10), true, guard));
        when(guard.isAdmin()).thenReturn(true);
        assertDoesNotThrow(() -> TeamBudgetEditPolicy.checkAgainstUnreadable(null, true, guard));
    }

    @Test
    void editorsMayEditWhenAdminOnlyIsSwitchedOff() {
        assertDoesNotThrow(() -> TeamBudgetEditPolicy.check(cap(10), cap(50), false, guard));
    }

    @Test
    void anOversightListNeedsViewOnEveryTeamInIt() {
        when(guard.isAdmin()).thenReturn(true);
        when(guard.hasAccess("a", AccessLevel.VIEW)).thenReturn(true);
        when(guard.hasAccess("b", AccessLevel.VIEW)).thenReturn(false);
        var board = new TeamBudgetConfig(10.0, null, new Oversight(List.of("a", "b")));
        assertThrows(ForbiddenException.class, () -> TeamBudgetEditPolicy.check(null, board, true, guard));
        when(guard.hasAccess("b", AccessLevel.VIEW)).thenReturn(true);
        assertDoesNotThrow(() -> TeamBudgetEditPolicy.check(null, board, true, guard));
    }
}
