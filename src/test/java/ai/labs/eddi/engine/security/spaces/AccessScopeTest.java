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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        var space = scope.toNarrowingFilter();

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
        assertNotNull(scope.toNarrowingFilter(), "but the view preference is still applied");
        assertNull(scope.toQueryFilters(), "and it does not invent an access restriction");
    }

    @Test
    @DisplayName("a blank space is no narrowing at all")
    void blankSpaceIsNoOp() {
        assertNull(AccessScope.forCaller(ALICE, true).withinSpace("").toNarrowingFilter());
        assertNull(AccessScope.forCaller(ALICE, true).withinSpace(null).toNarrowingFilter());
        assertNull(AccessScope.forCaller(ALICE, true).withinSpace("   ").toNarrowingFilter());
    }

    @Test
    @DisplayName("the space predicate is anchored, so one space id cannot match another")
    void spacePredicateIsAnchored() {
        var space = AccessScope.unrestricted().withinSpace(Subjects.teamSpace("eng")).toNarrowingFilter();
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
        assertNotNull(scope.toNarrowingFilter());
    }

    @Test
    @DisplayName("'mine' matches the caller's owner token and nobody else's")
    void mineMatchesOwnerToken() {
        var narrowing = AccessScope.forCaller(ALICE, true).withOwnership(Ownership.MINE, "alice").toNarrowingFilter();

        assertNotNull(narrowing);
        var filter = narrowing.getQueryFilters().get(0);
        assertEquals("accessIndex", filter.getField());
        Pattern pattern = Pattern.compile(filter.getFilter().toString());
        assertTrue(pattern.matcher("|owner:alice|space:user:alice|").find());
        assertFalse(pattern.matcher("|owner:malice|").find(), "an unescaped, unanchored owner token would match a longer name");
        assertFalse(pattern.matcher("|owner:bob|user:alice|").find(), "a grant to alice is not ownership");
    }

    @Test
    @DisplayName("'shared' is a negated owner match, so unowned and other people's rows pass")
    void sharedNegatesOwnerToken() {
        var narrowing = AccessScope.forCaller(ALICE, true).withOwnership(Ownership.SHARED, "alice").toNarrowingFilter();

        assertNotNull(narrowing);
        var filter = narrowing.getQueryFilters().get(0);
        var notMatching = assertInstanceOf(IResourceFilter.NotMatching.class, filter.getFilter(),
                "a plain String would be a positive regex — 'shared' would list exactly what it must exclude");
        Pattern owner = Pattern.compile(notMatching.pattern());
        assertTrue(owner.matcher("|owner:alice|").find(), "the negated pattern must match alice's own rows so they are excluded");
        assertFalse(owner.matcher("|owner:bob|user:alice|").find());
    }

    @Test
    @DisplayName("space and ownership narrow together, in one AND group")
    void spaceAndOwnershipCombine() {
        var narrowing = AccessScope.forCaller(ALICE, true).withinSpace(Subjects.teamSpace("engineering"))
                .withOwnership(Ownership.SHARED, "alice").toNarrowingFilter();

        assertNotNull(narrowing);
        assertEquals(IResourceFilter.QueryFilters.ConnectingType.AND, narrowing.getConnectingType(),
                "ORing the two narrowings would widen the listing to either one");
        assertEquals(2, narrowing.getQueryFilters().size());
    }

    @Test
    @DisplayName("'mine' without a principal matches nothing instead of dropping the filter")
    void mineWithoutPrincipalMatchesNothing() {
        var narrowing = AccessScope.forCaller(ALICE, true).withOwnership(Ownership.MINE, null).toNarrowingFilter();

        assertNotNull(narrowing, "dropping the filter would turn 'mine' into 'everything'");
        assertTrue(narrowing.getQueryFilters().get(0).getFilter().toString().contains(Subjects.TOKEN_NONE));
    }

    @Test
    @DisplayName("ownership survives a later space narrowing, and ANY clears it")
    void ownershipComposes() {
        var scope = AccessScope.forCaller(ALICE, true).withOwnership(Ownership.MINE, "alice").withinSpace(Subjects.teamSpace("engineering"));
        assertEquals(Ownership.MINE, scope.ownership());
        assertEquals(2, scope.toNarrowingFilter().getQueryFilters().size());

        assertNull(AccessScope.forCaller(ALICE, true).withOwnership(Ownership.ANY, "alice").toNarrowingFilter());
    }

    @Test
    @DisplayName("ownership parses the query-parameter spellings and refuses anything else")
    void parsesOwnership() {
        assertEquals(Ownership.ANY, Ownership.parseOrNull(null));
        assertEquals(Ownership.ANY, Ownership.parseOrNull(" "));
        assertEquals(Ownership.MINE, Ownership.parseOrNull("Mine"));
        assertEquals(Ownership.SHARED, Ownership.parseOrNull("shared"));
        assertNull(Ownership.parseOrNull("shraed"), "a typo must not silently list everything");
    }
}
