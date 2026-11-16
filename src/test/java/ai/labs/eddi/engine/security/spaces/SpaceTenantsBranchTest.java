/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tenant id derived from a space: a readable slug, always unique, and
 * recognisable as a space tenant.
 */
class SpaceTenantsBranchTest {

    @Test
    void userAndTeamSpacesGetTheirKindPrefixAndASlug() {
        String user = SpaceTenants.tenantFor(Subjects.personalSpace("alice.smith@corp.com"));
        assertTrue(user.startsWith("u.alice_smith_corp_com."), user);
        String team = SpaceTenants.tenantFor(Subjects.teamSpace("/Eng/Back-end_2"));
        assertTrue(team.startsWith("t.Eng_Back-end_2."), team);
        assertTrue(SpaceTenants.isSpaceTenant(user));
        assertTrue(SpaceTenants.isSpaceTenant(team));
    }

    @Test
    void principalsThatSlugAlikeStillGetDistinctTenants() {
        String a = SpaceTenants.tenantFor(Subjects.personalSpace("alice.smith@corp.com"));
        String b = SpaceTenants.tenantFor(Subjects.personalSpace("alice@smith.corp.com"));
        assertEquals(a.substring(0, a.lastIndexOf('.')), b.substring(0, b.lastIndexOf('.')), "the slugs collide");
        assertNotEquals(a, b, "the hash keeps them apart");
        assertEquals(a, SpaceTenants.tenantFor(Subjects.personalSpace("alice.smith@corp.com")), "and the id is stable");
    }

    @Test
    void theSlugIsCappedAndNeverEmpty() {
        String longName = SpaceTenants.tenantFor(Subjects.personalSpace("x".repeat(100)));
        assertEquals("u." + "x".repeat(48) + ".", longName.substring(0, 2 + 48 + 1));
        assertTrue(SpaceTenants.isSpaceTenant(longName));
        String symbols = SpaceTenants.tenantFor("user:");
        assertTrue(symbols.startsWith("u._."), symbols);
    }

    @Test
    void anythingButASpaceIdIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SpaceTenants.tenantFor(null));
        assertThrows(IllegalArgumentException.class, () -> SpaceTenants.tenantFor("group:eng"));
    }

    @Test
    void onlyIdsThisClassProducesAreSpaceTenants() {
        assertFalse(SpaceTenants.isSpaceTenant(null));
        assertFalse(SpaceTenants.isSpaceTenant("default"));
        assertFalse(SpaceTenants.isSpaceTenant("u.alice.0123"));
        assertFalse(SpaceTenants.isSpaceTenant("x.alice.0123456789abcdef"));
    }
}
