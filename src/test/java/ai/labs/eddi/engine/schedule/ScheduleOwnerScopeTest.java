/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleOwnerScopeTest {

    @Test
    void restrictedScope_admitsOwnAndSharedOnly() {
        var scope = ScheduleOwnerScope.visibleTo("editor-1");

        assertTrue(scope.admits("editor-1"));
        assertTrue(scope.admits(null));
        assertTrue(scope.admits(""));
        assertTrue(scope.admits("  "));
        assertTrue(scope.admits(ScheduleOwnerScope.SHARED_OWNER));
        assertFalse(scope.admits("victim-42"));
    }

    @Test
    void callerWithoutId_admitsSharedOnly() {
        for (String none : new String[]{null, "", "  "}) {
            var scope = ScheduleOwnerScope.visibleTo(none);

            assertNull(scope.callerId());
            assertTrue(scope.admits(null));
            assertTrue(scope.admits(ScheduleOwnerScope.SHARED_OWNER));
            assertFalse(scope.admits("victim-42"));
        }
    }

    @Test
    void unrestrictedScope_admitsEveryOwner() {
        assertTrue(ScheduleOwnerScope.ALL.admits("victim-42"));
        assertEquals(ScheduleOwnerScope.ALL, new ScheduleOwnerScope(true, "ignored"));
    }

    @Test
    void sharedOnlyIfCreatedByCaller_admitsOnlyTheCallersSharedSchedules() {
        var scope = ScheduleOwnerScope.visibleTo("editor-1").sharedOnlyIfCreatedByCaller();

        assertTrue(scope.admits("editor-1", "someone", null), "own schedules stay visible");
        assertTrue(scope.admits(ScheduleOwnerScope.SHARED_OWNER, "editor-1", null));
        assertTrue(scope.admits(null, "editor-1", null));
        assertFalse(scope.admits(ScheduleOwnerScope.SHARED_OWNER, "colleague", null), "another team's system schedule");
        assertFalse(scope.admits(null, null, null), "a shared schedule nobody is recorded as creating");
        assertFalse(ScheduleOwnerScope.visibleTo(null).sharedOnlyIfCreatedByCaller().admits(null, null, null));
    }

    @Test
    void withTeamCadences_admitsEveryCadenceButNoOtherPersonalSchedule() {
        var cadence = Map.<String, Object>of("teamCadenceType", "team_cadence", "groupId", "g1", "cadenceId", "c1");
        var scope = ScheduleOwnerScope.visibleTo("editor-1").withTeamCadences();

        assertTrue(scope.admits("creator", "creator", cadence));
        assertFalse(scope.admits("victim-42", "victim-42", Map.of("dreamType", "dream_consolidation")));
        assertFalse(ScheduleOwnerScope.visibleTo("editor-1").admits("creator", "creator", cadence),
                "without the refinement a cadence is its creator's");
    }

    @Test
    void unrestrictedScope_dropsTheRefinements() {
        var scope = new ScheduleOwnerScope(true, "ignored", true, true);

        assertEquals(ScheduleOwnerScope.ALL, scope);
        assertTrue(scope.admits(ScheduleOwnerScope.SHARED_OWNER, "colleague", null));
    }
}
