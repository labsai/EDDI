/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.datastore.IResourceFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The space narrowing exists so the Manager's space switcher can page. Its one
 * dangerous failure mode is becoming a widening, which is what these pin down.
 */
class AccessScopeTest {

    private static final CallerSpaces ALICE = CallerSpaces.of("alice", Set.of("/engineering"));

    @Test
    @DisplayName("the access tokens stay an OR group among themselves")
    void accessTokensAreOred() {
        var filters = AccessScope.forCaller(ALICE, true).toQueryFilters();

        assertNotNull(filters);
        assertEquals(IResourceFilter.QueryFilters.ConnectingType.OR, filters.getConnectingType(),
                "ANDing the caller's own tokens would admit nothing at all");
        assertTrue(filters.getQueryFilters().size() > 1);
    }

    @Test
    @DisplayName("the space narrowing is a separate group, so it ANDs rather than ORs")
    void spaceIsItsOwnGroup() {
        var scope = AccessScope.forCaller(ALICE, true).withinSpace(Subjects.teamSpace("engineering"));

        var access = scope.toQueryFilters();
        var space = scope.toSpaceFilter();

        assertNotNull(space, "the narrowing must reach the query");
        assertEquals(IResourceFilter.QueryFilters.ConnectingType.OR, access.getConnectingType());
        assertEquals(1, space.getQueryFilters().size());
        assertEquals(AccessScope.FIELD_SPACE_ID, space.getQueryFilters().get(0).getField());
        // Folding the space into the access group would OR it — turning "only this
        // space" into "everything I can reach, PLUS this space".
        assertFalse(access.getQueryFilters().stream()
                .anyMatch(f -> AccessScope.FIELD_SPACE_ID.equals(f.getField())));
    }

    @Test
    @DisplayName("narrowing an unrestricted scope still narrows")
    void narrowsAnAdminScope() {
        var scope = AccessScope.unrestricted().withinSpace(Subjects.teamSpace("engineering"));

        assertTrue(scope.isUnrestricted(), "an admin's reach is unchanged by a view preference");
        assertNotNull(scope.toSpaceFilter(), "but the view preference is still applied");
        assertNull(scope.toQueryFilters(), "and it does not invent an access restriction");
    }

    @Test
    @DisplayName("a blank space is no narrowing at all")
    void blankSpaceIsNoOp() {
        assertNull(AccessScope.forCaller(ALICE, true).withinSpace("").toSpaceFilter());
        assertNull(AccessScope.forCaller(ALICE, true).withinSpace(null).toSpaceFilter());
        assertNull(AccessScope.forCaller(ALICE, true).withinSpace("   ").toSpaceFilter());
    }

    @Test
    @DisplayName("the space predicate is anchored, so one space id cannot match another")
    void spacePredicateIsAnchored() {
        var space = AccessScope.unrestricted().withinSpace(Subjects.teamSpace("eng")).toSpaceFilter();
        String pattern = space.getQueryFilters().get(0).getFilter().toString();

        assertTrue(Pattern.compile(pattern).matcher("team:eng").find());
        assertFalse(Pattern.compile(pattern).matcher("team:engineering").find(),
                "an unanchored space predicate would leak a longer team name into the narrower one");
    }

    @Test
    @DisplayName("asking for a space you cannot reach returns nothing rather than granting it")
    void narrowingCannotWiden() {
        var scope = AccessScope.forCaller(ALICE, true).withinSpace(Subjects.teamSpace("finance"));

        // The access group still only names Alice's own tokens; the space group ANDs on
        // top. No row can satisfy both unless Alice could already see it.
        assertFalse(scope.admittingTokens().contains(Subjects.teamSpace("finance")));
        assertNotNull(scope.toSpaceFilter());
    }
}
