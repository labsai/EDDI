/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.descriptors.model.ResourceGrant;
import ai.labs.eddi.configs.descriptors.model.ResourceVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The policy is written twice — once as a decision about one descriptor, once
 * as a materialised token index a query filters on. The whole class of bug this
 * design can produce is the two disagreeing, so most of what follows checks
 * that they agree.
 */
class DescriptorAccessTest {

    private static final CallerSpaces ALICE = CallerSpaces.of("alice", Set.of("/engineering"));
    private static final CallerSpaces BOB = CallerSpaces.of("bob", Set.of());
    private static final CallerSpaces CAROL_IN_TEAM = CallerSpaces.of("carol", Set.of("/engineering"));

    private static DocumentDescriptor descriptor(String owner, String space, ResourceVisibility visibility) {
        DocumentDescriptor d = new DocumentDescriptor();
        d.setOwnerId(owner);
        d.setSpaceId(space);
        d.setVisibility(visibility == null ? null : visibility.wireName());
        return DescriptorAccess.rebuildIndex(d);
    }

    private static void grant(DocumentDescriptor d, String subject, AccessLevel level) {
        List<ResourceGrant> grants = d.getGrants() == null ? new ArrayList<>() : new ArrayList<>(d.getGrants());
        grants.add(new ResourceGrant(subject, level.name(), "alice", new Date(0)));
        d.setGrants(grants);
        DescriptorAccess.rebuildIndex(d);
    }

    /** Mirrors what the query layer does: OR the caller's tokens over the index. */
    private static boolean listedFor(DocumentDescriptor d, CallerSpaces caller, boolean admitLegacy) {
        for (String token : DescriptorAccess.admittingTokens(caller, admitLegacy)) {
            if (Pattern.compile(Subjects.tokenPattern(token)).matcher(d.getAccessIndex()).find()) {
                return true;
            }
        }
        return false;
    }

    @Nested
    @DisplayName("effective level")
    class EffectiveLevel {

        @Test
        @DisplayName("the owner owns it")
        void ownerOwns() {
            var d = descriptor("alice", Subjects.personalSpace("alice"), ResourceVisibility.space);
            assertEquals(AccessLevel.OWN, DescriptorAccess.effectiveLevel(d, ALICE, true));
        }

        @Test
        @DisplayName("a stranger gets nothing from a personal space")
        void strangerGetsNothing() {
            var d = descriptor("alice", Subjects.personalSpace("alice"), ResourceVisibility.space);
            assertNull(DescriptorAccess.effectiveLevel(d, BOB, true));
        }

        @Test
        @DisplayName("a teammate in a team space may edit but not own")
        void teammateEditsButDoesNotOwn() {
            var d = descriptor("alice", Subjects.teamSpace("engineering"), ResourceVisibility.space);
            AccessLevel level = DescriptorAccess.effectiveLevel(d, CAROL_IN_TEAM, true);
            assertEquals(AccessLevel.EDIT, level, "sharing a space must not confer the right to delete or re-share");
            assertFalse(level.includes(AccessLevel.OWN));
        }

        @Test
        @DisplayName("published grants VIEW to anyone, including an anonymous caller")
        void publishedGrantsView() {
            var d = descriptor("alice", Subjects.personalSpace("alice"), ResourceVisibility.published);
            assertEquals(AccessLevel.VIEW, DescriptorAccess.effectiveLevel(d, BOB, true));
            assertEquals(AccessLevel.VIEW, DescriptorAccess.effectiveLevel(d, CallerSpaces.ANONYMOUS, true));
        }

        @Test
        @DisplayName("private hides from a teammate who has no explicit grant")
        void privateHidesFromTeam() {
            var d = descriptor("alice", Subjects.teamSpace("engineering"), ResourceVisibility.privateAccess);
            assertNull(DescriptorAccess.effectiveLevel(d, CAROL_IN_TEAM, true));
            assertEquals(AccessLevel.OWN, DescriptorAccess.effectiveLevel(d, ALICE, true), "the owner still owns a private resource");
        }

        @Test
        @DisplayName("an explicit grant reaches a stranger, at exactly the granted level")
        void explicitGrant() {
            var d = descriptor("alice", Subjects.personalSpace("alice"), ResourceVisibility.privateAccess);
            grant(d, Subjects.user("bob"), AccessLevel.USE);
            AccessLevel level = DescriptorAccess.effectiveLevel(d, BOB, true);
            assertEquals(AccessLevel.USE, level);
            assertFalse(level.includes(AccessLevel.VIEW), "USE must not leak the configuration");
        }

        @Test
        @DisplayName("a team grant reaches every member of that team")
        void teamGrant() {
            var d = descriptor("alice", Subjects.personalSpace("alice"), ResourceVisibility.privateAccess);
            grant(d, Subjects.team("engineering"), AccessLevel.VIEW);
            assertEquals(AccessLevel.VIEW, DescriptorAccess.effectiveLevel(d, CAROL_IN_TEAM, true));
            assertNull(DescriptorAccess.effectiveLevel(d, BOB, true));
        }

        @Test
        @DisplayName("the most permissive applicable grant wins")
        void highestWins() {
            var d = descriptor("alice", Subjects.teamSpace("engineering"), ResourceVisibility.space);
            grant(d, Subjects.user("carol"), AccessLevel.USE);
            assertEquals(AccessLevel.EDIT, DescriptorAccess.effectiveLevel(d, CAROL_IN_TEAM, true),
                    "a narrower explicit grant must not reduce what the space already allows");
        }

        @Test
        @DisplayName("a grant whose level is unreadable grants nothing")
        void unparseableLevelGrantsNothing() {
            var d = descriptor("alice", Subjects.personalSpace("alice"), ResourceVisibility.privateAccess);
            List<ResourceGrant> grants = new ArrayList<>();
            grants.add(new ResourceGrant(Subjects.user("bob"), "SUPERUSER", "alice", new Date(0)));
            d.setGrants(grants);
            DescriptorAccess.rebuildIndex(d);
            assertNull(DescriptorAccess.effectiveLevel(d, BOB, true),
                    "a level written by a newer version must not be read as OWN by accident");
        }

        @Test
        @DisplayName("a missing visibility defaults to space, never to published")
        void missingVisibilityDefaultsToSpace() {
            var d = descriptor("alice", Subjects.personalSpace("alice"), null);
            assertNull(DescriptorAccess.effectiveLevel(d, BOB, true),
                    "an absent visibility field must not silently widen access");
        }

        @Test
        @DisplayName("legacy data follows the operator's policy")
        void legacyFollowsPolicy() {
            var d = descriptor(null, null, null);
            assertEquals(AccessLevel.OWN, DescriptorAccess.effectiveLevel(d, BOB, true));
            assertNull(DescriptorAccess.effectiveLevel(d, BOB, false));
        }
    }

    @Nested
    @DisplayName("the index and the decision agree")
    class IndexMirrorsDecision {

        private void assertAgrees(DocumentDescriptor d, CallerSpaces caller, boolean admitLegacy) {
            boolean readable = DescriptorAccess.effectiveLevel(d, caller, admitLegacy) != null;
            boolean listed = listedFor(d, caller, admitLegacy);
            assertEquals(readable, listed,
                    "listing and reading disagreed for index=" + d.getAccessIndex() + " caller=" + caller.selfPrincipals());
        }

        /**
         * The full cross-product, including the owner-less rows.
         * <p>
         * The earlier version of this test always set an owner, which is exactly the
         * axis on which the two halves diverged: a descriptor with no owner, a real
         * space and {@code private} visibility produced an empty token set, fell back
         * to the {@code legacy} token — admitted to everyone — while
         * {@link DescriptorAccess#effectiveLevel} granted nobody anything. Listed to
         * everyone, readable by no one, leaking its name, description and ACL. A
         * crafted import can produce that shape, so it is not hypothetical.
         */
        @Test
        @DisplayName("across the whole owner / space / visibility / grant / caller / policy cross-product")
        void matrix() {
            List<String> owners = new ArrayList<>();
            owners.add("alice");
            owners.add(null);

            List<String> spaces = new ArrayList<>();
            spaces.add(Subjects.personalSpace("alice"));
            spaces.add(Subjects.teamSpace("engineering"));
            spaces.add(Subjects.LEGACY);
            spaces.add(null);

            int checked = 0;
            for (String owner : owners) {
                for (String space : spaces) {
                    for (ResourceVisibility visibility : ResourceVisibility.values()) {
                        for (int grantShape = 0; grantShape < 5; grantShape++) {
                            var d = descriptor(owner, space, visibility);
                            applyGrantShape(d, grantShape);
                            for (CallerSpaces caller : List.of(ALICE, BOB, CAROL_IN_TEAM, CallerSpaces.ANONYMOUS)) {
                                for (boolean admitLegacy : List.of(true, false)) {
                                    assertAgrees(d, caller, admitLegacy);
                                    checked++;
                                }
                            }
                        }
                    }
                }
            }
            assertTrue(checked >= 900, "the sweep must actually be a sweep; checked " + checked);
        }

        /** The grant shapes that have ever produced a surprising level. */
        private void applyGrantShape(DocumentDescriptor d, int shape) {
            switch (shape) {
                case 1 -> grant(d, Subjects.user("bob"), AccessLevel.USE);
                case 2 -> grant(d, Subjects.team("engineering"), AccessLevel.VIEW);
                case 3 -> {
                    // A grant with no subject at all.
                    List<ResourceGrant> grants = new ArrayList<>();
                    grants.add(new ResourceGrant(null, AccessLevel.OWN.name(), "alice", new Date(0)));
                    d.setGrants(grants);
                    DescriptorAccess.rebuildIndex(d);
                }
                case 4 -> {
                    // A level written by a newer version of EDDI.
                    List<ResourceGrant> grants = new ArrayList<>();
                    grants.add(new ResourceGrant(Subjects.user("bob"), "SUPERUSER", "alice", new Date(0)));
                    d.setGrants(grants);
                    DescriptorAccess.rebuildIndex(d);
                }
                default -> {
                    // no grants
                }
            }
        }

        @Test
        @DisplayName("with explicit user and team grants")
        void withGrants() {
            var d = descriptor("alice", Subjects.personalSpace("alice"), ResourceVisibility.privateAccess);
            grant(d, Subjects.user("bob"), AccessLevel.USE);
            grant(d, Subjects.team("engineering"), AccessLevel.VIEW);
            for (CallerSpaces caller : List.of(ALICE, BOB, CAROL_IN_TEAM, CallerSpaces.ANONYMOUS)) {
                assertAgrees(d, caller, true);
            }
        }

        @Test
        @DisplayName("for legacy data under both policies")
        void legacy() {
            var d = descriptor(null, null, null);
            assertAgrees(d, BOB, true);
            assertAgrees(d, BOB, false);
        }

        @Test
        @DisplayName("an index is never empty, so no resource becomes unlistable")
        void indexNeverEmpty() {
            var d = descriptor(null, Subjects.teamSpace("engineering"), ResourceVisibility.privateAccess);
            assertTrue(d.getAccessIndex() != null && d.getAccessIndex().length() > 1,
                    "an empty index would match nothing at all — worse than being admin-only");
        }
    }

    @Nested
    @DisplayName("ownership does not travel between deployments")
    class StripOwnership {

        @Test
        @DisplayName("everything about who owns it and who it is shared with is removed")
        void stripsAllOwnership() {
            var d = descriptor("alice", Subjects.teamSpace("engineering"), ResourceVisibility.published);
            grant(d, Subjects.user("bob"), AccessLevel.OWN);
            d.setName("My agent");
            d.setDescription("does things");

            DescriptorAccess.stripOwnership(d);

            assertNull(d.getOwnerId());
            assertNull(d.getSpaceId());
            assertNull(d.getVisibility());
            assertNull(d.getGrants());
            assertNull(d.getAccessIndex());
        }

        @Test
        @DisplayName("what the resource IS survives — only who it belongs to is dropped")
        void keepsIdentityOfTheResource() {
            var d = descriptor("alice", Subjects.personalSpace("alice"), ResourceVisibility.space);
            d.setName("My agent");
            d.setDescription("does things");
            d.setOriginId("origin-1");

            DescriptorAccess.stripOwnership(d);

            assertEquals("My agent", d.getName());
            assertEquals("does things", d.getDescription());
            assertEquals("origin-1", d.getOriginId());
        }

        @Test
        @DisplayName("a stripped descriptor reads as legacy, not as published")
        void strippedIsLegacyNotPublished() {
            var d = descriptor("alice", Subjects.personalSpace("alice"), ResourceVisibility.published);
            DescriptorAccess.stripOwnership(d);

            // The point: an archive that arrived claiming `published` must not still be
            // published after stripping. It becomes unowned, and the operator's
            // legacy-visibility policy decides — until the importer re-stamps it.
            assertTrue(DescriptorAccess.isUnowned(d));
            assertNull(DescriptorAccess.effectiveLevel(d, BOB, false));
        }
    }
}
