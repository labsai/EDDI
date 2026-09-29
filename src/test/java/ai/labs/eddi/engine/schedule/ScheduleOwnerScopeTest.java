/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule;

import org.junit.jupiter.api.Test;

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
}
