/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.json.Json;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Reading who the caller is, and which groups they are in.
 *
 * <h3>The failure mode this is defending against is silence</h3> A groups claim
 * arrives as a JSON array through one code path, a {@code List<String>} through
 * another, and a bare string when it is single-valued — depending on how the
 * token was parsed. If a shape is not handled, nothing throws: the caller
 * simply has no team spaces, and every resource shared with their team becomes
 * invisible to them. That reads as "nobody shared anything with me", which is
 * not a bug report anyone can act on. So each shape is pinned.
 */
class SpaceContextTest {

    private static WorkspaceSettings settings() {
        return settings("groups");
    }

    private static WorkspaceSettings settings(String groupsClaim) {
        var s = new WorkspaceSettings(true, true, groupsClaim, WorkspaceSettings.LEGACY_SHARED, Optional.empty());
        s.validate();
        return s;
    }

    /** An identity whose groups arrive as an identity attribute (non-OIDC path). */
    private static SecurityIdentity withAttribute(String principal, Object claim) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        lenient().when(identity.isAnonymous()).thenReturn(false);
        Principal p = mock(Principal.class);
        lenient().when(p.getName()).thenReturn(principal);
        lenient().when(identity.getPrincipal()).thenReturn(p);
        lenient().when(identity.<Object>getAttribute("groups")).thenReturn(claim);
        return identity;
    }

    /** An identity whose principal is a JWT, which is the OIDC path. */
    private static SecurityIdentity withJwtClaim(String principal, Object claim) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        lenient().when(identity.isAnonymous()).thenReturn(false);
        JsonWebToken jwt = mock(JsonWebToken.class);
        lenient().when(jwt.getName()).thenReturn(principal);
        lenient().when(jwt.<Object>getClaim("groups")).thenReturn(claim);
        lenient().when(identity.getPrincipal()).thenReturn(jwt);
        return identity;
    }

    private static SpaceContext contextFor(SecurityIdentity identity) {
        return new SpaceContext(identity, settings());
    }

    @Nested
    @DisplayName("the groups claim, in every shape it actually arrives in")
    class GroupsClaim {

        @Test
        @DisplayName("a list of strings")
        void listOfStrings() {
            var ctx = contextFor(withJwtClaim("alice", List.of("/engineering", "/design")));

            assertEquals(Set.of("/engineering", "/design"), ctx.currentGroupPaths());
        }

        @Test
        @DisplayName("a JSON array, which is what an OIDC token often deserialises to")
        void jsonArray() {
            var array = Json.createArrayBuilder().add("/engineering").add("/design").build();

            var ctx = contextFor(withJwtClaim("alice", array));

            // JsonString stringifies WITH surrounding quotes. Left unhandled the space
            // id becomes team:"engineering", which matches nothing and looks like an
            // empty workspace rather than a parsing bug.
            assertEquals(Set.of("/engineering", "/design"), ctx.currentGroupPaths());
        }

        @Test
        @DisplayName("a bare string, which is what a single-valued claim can arrive as")
        void bareString() {
            var ctx = contextFor(withJwtClaim("alice", "/engineering"));

            assertEquals(Set.of("/engineering"), ctx.currentGroupPaths());
        }

        @Test
        @DisplayName("an array of strings")
        void stringArray() {
            var ctx = contextFor(withJwtClaim("alice", new Object[]{"/engineering", "/design"}));

            assertEquals(Set.of("/engineering", "/design"), ctx.currentGroupPaths());
        }

        @Test
        @DisplayName("an identity attribute, for a non-OIDC augmentor")
        void identityAttribute() {
            // The principal is not a JsonWebToken here, so the JWT branch cannot fire.
            var ctx = contextFor(withAttribute("alice", List.of("/engineering")));

            assertEquals(Set.of("/engineering"), ctx.currentGroupPaths());
        }

        @Test
        @DisplayName("no claim at all is a realm without a group mapper, not a failure")
        void noClaim() {
            var ctx = contextFor(withJwtClaim("alice", null));

            assertTrue(ctx.currentGroupPaths().isEmpty());
            // And the caller still has their own space — losing that would hide their
            // own work from them.
            assertEquals(Set.of(Subjects.personalSpace("alice")), ctx.current().spaces());
        }

        @Test
        @DisplayName("blank entries are dropped rather than becoming a space nobody holds")
        void blankEntriesDropped() {
            var ctx = contextFor(withJwtClaim("alice", List.of("  ", "/engineering", "")));

            assertEquals(Set.of("/engineering"), ctx.currentGroupPaths());
        }

        @Test
        @DisplayName("a claim that throws is treated as absent rather than failing the request")
        void throwingClaimIsAbsent() {
            SecurityIdentity identity = mock(SecurityIdentity.class);
            lenient().when(identity.isAnonymous()).thenReturn(false);
            JsonWebToken jwt = mock(JsonWebToken.class);
            lenient().when(jwt.getName()).thenReturn("alice");
            lenient().when(jwt.getClaim("groups")).thenThrow(new IllegalStateException("malformed"));
            lenient().when(identity.getPrincipal()).thenReturn(jwt);

            var ctx = new SpaceContext(identity, settings());

            // Fail-closed on membership, not on the whole request: the caller keeps
            // their personal space and simply sees no team resources.
            assertTrue(ctx.currentGroupPaths().isEmpty());
            assertEquals("alice", ctx.currentPrincipal());
        }

        @Test
        @DisplayName("the claim name is configurable, and a different one is genuinely read")
        void honoursConfiguredClaimName() {
            SecurityIdentity identity = mock(SecurityIdentity.class);
            lenient().when(identity.isAnonymous()).thenReturn(false);
            JsonWebToken jwt = mock(JsonWebToken.class);
            lenient().when(jwt.getName()).thenReturn("alice");
            lenient().when(jwt.<Object>getClaim("roles_as_groups")).thenReturn(List.of("/engineering"));
            lenient().when(jwt.<Object>getClaim("groups")).thenReturn(List.of("/should-not-be-read"));
            lenient().when(identity.getPrincipal()).thenReturn(jwt);

            var ctx = new SpaceContext(identity, settings("roles_as_groups"));

            assertEquals(Set.of("/engineering"), ctx.currentGroupPaths());
        }
    }

    @Nested
    @DisplayName("the principal")
    class CurrentPrincipal {

        @Test
        @DisplayName("an anonymous caller resolves to nothing, not to a name")
        void anonymousIsNull() {
            SecurityIdentity identity = mock(SecurityIdentity.class);
            lenient().when(identity.isAnonymous()).thenReturn(true);

            var ctx = new SpaceContext(identity, settings());

            assertNull(ctx.currentPrincipal());
            assertNull(ctx.defaultWriteSpace());
            assertTrue(ctx.current().isAnonymous());
            assertTrue(ctx.current().spaces().isEmpty());
            assertTrue(ctx.currentGroupPaths().isEmpty());
        }

        @Test
        @DisplayName("a blank principal name is nobody")
        void blankNameIsNull() {
            var ctx = contextFor(withJwtClaim("   ", null));

            assertNull(ctx.currentPrincipal());
        }

        @Test
        @DisplayName("the personal space comes first, then teams")
        void personalSpaceFirst() {
            var ctx = contextFor(withJwtClaim("alice", List.of("/engineering")));

            var spaces = List.copyOf(ctx.current().spaces());
            assertEquals(Subjects.personalSpace("alice"), spaces.get(0));
            assertTrue(spaces.contains(Subjects.teamSpace("/engineering")));
        }

        @Test
        @DisplayName("membership in a child group is not membership in its parent")
        void nestedGroupsStayDistinct() {
            var ctx = contextFor(withJwtClaim("alice", List.of("/engineering/backend")));

            var spaces = ctx.current().spaces();
            assertTrue(spaces.contains(Subjects.teamSpace("/engineering/backend")));
            assertFalse(spaces.contains(Subjects.teamSpace("/engineering")),
                    "inventing the parent would hand out access nobody granted");
        }
    }

    @Nested
    @DisplayName("the default write space")
    class DefaultWriteSpace {

        @Test
        @DisplayName("new resources land in the creator's personal space by default")
        void defaultsToPersonal() {
            var ctx = contextFor(withJwtClaim("alice", List.of("/engineering")));

            assertEquals(Subjects.personalSpace("alice"), ctx.defaultWriteSpace());
        }

        @Test
        @DisplayName("a configured team overrides it, for a team-first deployment")
        void configuredTeamWins() {
            var s = new WorkspaceSettings(true, true, "groups", WorkspaceSettings.LEGACY_SHARED, Optional.of("engineering"));
            s.validate();

            var ctx = new SpaceContext(withJwtClaim("alice", List.of()), s);

            assertEquals(Subjects.teamSpace("engineering"), ctx.defaultWriteSpace());
        }

        @Test
        @DisplayName("a configured team applies even to someone who is not in it")
        void configuredTeamAppliesRegardlessOfMembership() {
            // Deliberate: this is the deployment saying "everything is filed here",
            // not a per-user preference. Whether the creator can then SEE it is the
            // access model's business, and it says no — which is why an operator
            // setting this must also add people to that group.
            var s = new WorkspaceSettings(true, true, "groups", WorkspaceSettings.LEGACY_SHARED, Optional.of("engineering"));
            s.validate();

            var ctx = new SpaceContext(withJwtClaim("outsider", List.of()), s);

            assertEquals(Subjects.teamSpace("engineering"), ctx.defaultWriteSpace());
            assertFalse(ctx.current().spaces().contains(Subjects.teamSpace("engineering")));
        }
    }
}
