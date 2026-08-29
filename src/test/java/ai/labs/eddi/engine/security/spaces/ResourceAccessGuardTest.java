/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.descriptors.model.ResourceGrant;
import ai.labs.eddi.configs.descriptors.model.ResourceVisibility;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Enforcement behaviour, especially the answers that must be conservative: an
 * unverifiable owner is not an absent owner, and a disabled feature must behave
 * exactly like the release before it.
 */
class ResourceAccessGuardTest {

    private static final String RESOURCE_ID = "abcdef1234567890abcdef";

    private static WorkspaceSettings settings(boolean enabled, boolean authEnabled, String legacyVisibility) {
        var s = new WorkspaceSettings(enabled, authEnabled, "groups", legacyVisibility, Optional.empty());
        s.validate();
        return s;
    }

    private static SecurityIdentity identity(String principal, String... roles) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        if (principal == null) {
            lenient().when(identity.isAnonymous()).thenReturn(true);
            return identity;
        }
        lenient().when(identity.isAnonymous()).thenReturn(false);
        Principal p = mock(Principal.class);
        lenient().when(p.getName()).thenReturn(principal);
        lenient().when(identity.getPrincipal()).thenReturn(p);
        for (String role : roles) {
            lenient().when(identity.hasRole(role)).thenReturn(true);
        }
        return identity;
    }

    private static DocumentDescriptor ownedBy(String owner) {
        DocumentDescriptor d = new DocumentDescriptor();
        d.setOwnerId(owner);
        d.setSpaceId(Subjects.personalSpace(owner));
        d.setVisibility(ResourceVisibility.space.wireName());
        return DescriptorAccess.rebuildIndex(d);
    }

    private static ResourceAccessGuard guard(SecurityIdentity identity, WorkspaceSettings settings, IDocumentDescriptorStore store) {
        var ownershipValidator = new OwnershipValidator(settings.isStampingOwnership());
        var spaceContext = new SpaceContext(identity, settings);
        return new ResourceAccessGuard(identity, ownershipValidator, spaceContext, settings, store);
    }

    @Nested
    @DisplayName("when workspaces are not enforced")
    class Disabled {

        @Test
        @DisplayName("everyone sees everything, exactly as before the feature existed")
        void behavesLikeBefore() throws Exception {
            var settings = settings(false, true, WorkspaceSettings.LEGACY_SHARED);
            var store = mock(IDocumentDescriptorStore.class);
            var g = guard(identity("bob"), settings, store);

            assertTrue(g.seesEverything());
            assertTrue(g.listingScope().isUnrestricted());
            assertDoesNotThrow(() -> g.requireAccess(RESOURCE_ID, AccessLevel.OWN, "agent"));
            assertEquals(AccessLevel.OWN, g.effectiveLevel(ownedBy("alice")));
        }

        @Test
        @DisplayName("the flag alone does nothing while authentication is off")
        void needsAuthentication() {
            var settings = settings(true, false, WorkspaceSettings.LEGACY_SHARED);
            assertFalse(settings.isEnforcing(),
                    "with no authenticated principal there is nothing to scope to; enforcing would deny everyone everything");
        }
    }

    @Nested
    @DisplayName("when workspaces are enforced")
    class Enforced {

        private final WorkspaceSettings settings = settings(true, true, WorkspaceSettings.LEGACY_SHARED);

        @Test
        @DisplayName("the owner is admitted and a stranger is refused")
        void ownerVersusStranger() throws Exception {
            var store = mock(IDocumentDescriptorStore.class);
            when(store.readCurrentDescriptor(RESOURCE_ID)).thenReturn(ownedBy("alice"));

            assertDoesNotThrow(() -> guard(identity("alice"), settings, store).requireAccess(RESOURCE_ID, AccessLevel.OWN, "agent"));
            assertThrows(ForbiddenException.class,
                    () -> guard(identity("bob"), settings, store).requireAccess(RESOURCE_ID, AccessLevel.VIEW, "agent"));
        }

        @Test
        @DisplayName("an admin is admitted without the descriptor being read at all")
        void adminBypasses() throws Exception {
            var store = mock(IDocumentDescriptorStore.class);
            when(store.readCurrentDescriptor(anyString())).thenThrow(new IResourceStore.ResourceStoreException("must not be called", null));

            var g = guard(identity("root", "eddi-admin"), settings, store);
            assertTrue(g.seesEverything());
            assertDoesNotThrow(() -> g.requireAccess(RESOURCE_ID, AccessLevel.OWN, "agent"));
            assertTrue(g.listingScope().isUnrestricted());
        }

        @Test
        @DisplayName("a store failure denies rather than admits")
        void failsClosedOnStoreError() throws Exception {
            var store = mock(IDocumentDescriptorStore.class);
            when(store.readCurrentDescriptor(RESOURCE_ID)).thenThrow(new IResourceStore.ResourceStoreException("db down", null));

            // An owner that cannot be verified is not an absent owner. This mirrors
            // ConversationAccessGuard, which refuses for the same reason.
            assertThrows(ForbiddenException.class,
                    () -> guard(identity("bob"), settings, store).requireAccess(RESOURCE_ID, AccessLevel.VIEW, "agent"));
        }

        @Test
        @DisplayName("a missing descriptor admits reading, and never more than reading")
        void missingDescriptorIsReadOnly() throws Exception {
            var store = mock(IDocumentDescriptorStore.class);
            when(store.readCurrentDescriptor(RESOURCE_ID)).thenThrow(new IResourceStore.ResourceNotFoundException("none"));
            var g = guard(identity("bob"), settings, store);

            // Some creation paths produce no descriptor at all — the setup API reaches the
            // stores over an unauthenticated loopback call, for one. Treating that as OWN
            // would hand every editor delete, undeploy and re-share on those resources.
            assertDoesNotThrow(() -> g.requireAccess(RESOURCE_ID, AccessLevel.VIEW, "agent"));
            assertDoesNotThrow(() -> g.requireAgentUseAccess(RESOURCE_ID));

            assertThrows(ForbiddenException.class, () -> g.requireAccess(RESOURCE_ID, AccessLevel.EDIT, "agent"),
                    "an absent record must not grant the authority to change the resource");
            assertThrows(ForbiddenException.class, () -> g.requireAccess(RESOURCE_ID, AccessLevel.OWN, "agent"),
                    "an absent record must not grant the authority to delete or re-share");
        }

        @Test
        @DisplayName("admin-only legacy visibility refuses a missing descriptor outright")
        void missingDescriptorUnderStrictPolicy() throws Exception {
            var store = mock(IDocumentDescriptorStore.class);
            when(store.readCurrentDescriptor(RESOURCE_ID)).thenThrow(new IResourceStore.ResourceNotFoundException("none"));

            var strict = settings(true, true, WorkspaceSettings.LEGACY_ADMIN_ONLY);
            assertThrows(ForbiddenException.class,
                    () -> guard(identity("bob"), strict, store).requireAccess(RESOURCE_ID, AccessLevel.VIEW, "agent"));
        }

        @Test
        @DisplayName("USE admits a conversation but not a read of the configuration")
        void useIsNarrowerThanView() throws Exception {
            var d = ownedBy("alice");
            d.setVisibility(ResourceVisibility.privateAccess.wireName());
            d.setGrants(List.of(new ResourceGrant(Subjects.user("bob"), AccessLevel.USE.name(), "alice", new Date(0))));
            DescriptorAccess.rebuildIndex(d);

            var store = mock(IDocumentDescriptorStore.class);
            when(store.readCurrentDescriptor(RESOURCE_ID)).thenReturn(d);
            var g = guard(identity("bob"), settings, store);

            assertDoesNotThrow(() -> g.requireAgentUseAccess(RESOURCE_ID));
            assertThrows(ForbiddenException.class, () -> g.requireAccess(RESOURCE_ID, AccessLevel.VIEW, "agent"));
        }

        @Test
        @DisplayName("an anonymous caller reaches a published agent and nothing else")
        void anonymousReachesPublishedOnly() throws Exception {
            var published = ownedBy("alice");
            published.setVisibility(ResourceVisibility.published.wireName());
            DescriptorAccess.rebuildIndex(published);

            var store = mock(IDocumentDescriptorStore.class);
            when(store.readCurrentDescriptor(RESOURCE_ID)).thenReturn(published);
            assertDoesNotThrow(() -> guard(identity(null), settings, store).requireAgentUseAccess(RESOURCE_ID));

            var store2 = mock(IDocumentDescriptorStore.class);
            when(store2.readCurrentDescriptor(RESOURCE_ID)).thenReturn(ownedBy("alice"));
            assertThrows(ForbiddenException.class, () -> guard(identity(null), settings, store2).requireAgentUseAccess(RESOURCE_ID));
        }
    }

    @Nested
    @DisplayName("redaction")
    class Redaction {

        private final WorkspaceSettings settings = settings(true, true, WorkspaceSettings.LEGACY_SHARED);

        private DocumentDescriptor publishedWithGrants() {
            var d = ownedBy("alice");
            d.setVisibility(ResourceVisibility.published.wireName());
            d.setGrants(List.of(new ResourceGrant(Subjects.user("bob"), AccessLevel.USE.name(), "alice", new Date(0))));
            return DescriptorAccess.rebuildIndex(d);
        }

        @Test
        @DisplayName("a non-owner listing a published resource does not receive its ACL")
        void nonOwnerLosesGrants() {
            var g = guard(identity("carol"), settings, mock(IDocumentDescriptorStore.class));

            var redacted = g.redactForCaller(publishedWithGrants());

            // Published grants VIEW to everyone, so without this every listing would
            // serialise the full grant audience — real principal and team names — making
            // describe()'s owner-only disclosure theatre.
            assertNull(redacted.getGrants());
            assertNull(redacted.getAccessIndex());
            assertEquals("alice", redacted.getOwnerId(), "owner stays: the Manager's owner column needs it");
            assertEquals(ResourceVisibility.published.wireName(), redacted.getVisibility());
        }

        @Test
        @DisplayName("the owner keeps the full descriptor")
        void ownerKeepsGrants() {
            var g = guard(identity("alice"), settings, mock(IDocumentDescriptorStore.class));

            var d = g.redactForCaller(publishedWithGrants());

            assertEquals(1, d.getGrants().size());
        }

        @Test
        @DisplayName("an admin keeps the full descriptor")
        void adminKeepsGrants() {
            var g = guard(identity("root", "eddi-admin"), settings, mock(IDocumentDescriptorStore.class));

            assertEquals(1, g.redactForCaller(publishedWithGrants()).getGrants().size());
        }
    }

    @Nested
    @DisplayName("stamping")
    class Stamping {

        @Test
        @DisplayName("a new descriptor gets its creator, their space, and an index")
        void stampsCreator() {
            var settings = settings(false, true, WorkspaceSettings.LEGACY_SHARED);
            var g = guard(identity("alice"), settings, mock(IDocumentDescriptorStore.class));

            var stamped = g.stampNewDescriptor(new DocumentDescriptor());
            assertEquals("alice", stamped.getOwnerId());
            assertEquals(Subjects.personalSpace("alice"), stamped.getSpaceId());
            assertEquals(ResourceVisibility.space.wireName(), stamped.getVisibility());
            assertTrue(stamped.getAccessIndex().contains(Subjects.OWNER_TOKEN_PREFIX + "alice"));
        }

        @Test
        @DisplayName("a principal with surrounding whitespace is stored trimmed")
        void trimsThePrincipal() {
            var settings = settings(false, true, WorkspaceSettings.LEGACY_SHARED);
            var g = guard(identity("  alice  "), settings, mock(IDocumentDescriptorStore.class));

            var stamped = g.stampNewDescriptor(new DocumentDescriptor());
            assertEquals("alice", stamped.getOwnerId(),
                    "CallerSpaces trims, so an untrimmed owner would never match its own principal again");
        }

        @Test
        @DisplayName("ownership is recorded even while enforcement is off")
        void stampsWithoutEnforcement() {
            var settings = settings(false, true, WorkspaceSettings.LEGACY_SHARED);
            assertFalse(settings.isEnforcing());
            assertTrue(settings.isStampingOwnership(),
                    "an operator must be able to accumulate attribution before switching enforcement on");
        }

        @Test
        @DisplayName("with no authenticated caller nothing is attributed, and the index still exists")
        void stampsNothingWhenAnonymous() {
            var settings = settings(false, false, WorkspaceSettings.LEGACY_SHARED);
            var g = guard(identity(null), settings, mock(IDocumentDescriptorStore.class));

            var stamped = g.stampNewDescriptor(new DocumentDescriptor());
            assertEquals(null, stamped.getOwnerId());
            assertTrue(stamped.getAccessIndex().contains(Subjects.LEGACY),
                    "an unowned resource must still carry a token, or it can never be listed");
        }
    }
}
