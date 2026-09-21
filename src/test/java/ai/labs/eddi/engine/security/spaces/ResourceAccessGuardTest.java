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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
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

        @Test
        @DisplayName("a version read redacts on the level it was gated with, not on the version in hand")
        void versionedReadUsesTheGatedLevel() {
            // Sharing writes land on the CURRENT version, so an older version's
            // descriptor can still name a previous owner and carry that era's grants.
            // Deciding redaction against it would hand the grant list to somebody who no
            // longer owns the resource — two different answers to "does this caller own
            // it" inside one request.
            var g = guard(identity("carol"), settings, mock(IDocumentDescriptorStore.class));
            var staleVersionStillNamingCarol = ownedBy("carol");
            staleVersionStillNamingCarol.setVisibility(ResourceVisibility.published.wireName());
            staleVersionStillNamingCarol.setGrants(
                    List.of(new ResourceGrant(Subjects.user("bob"), AccessLevel.USE.name(), "carol", new Date(0))));
            DescriptorAccess.rebuildIndex(staleVersionStillNamingCarol);

            // What the CURRENT descriptor said: carol may read it, and no more.
            var redacted = g.redactUnlessOwner(staleVersionStillNamingCarol, AccessLevel.VIEW);

            assertNull(redacted.getGrants(), "the old version's owner field must not decide this");
            assertNull(redacted.getAccessIndex());
        }

        @Test
        @DisplayName("a caller gated at OWN keeps the grants")
        void gatedOwnerKeepsGrants() {
            var g = guard(identity("carol"), settings, mock(IDocumentDescriptorStore.class));

            assertEquals(1, g.redactUnlessOwner(publishedWithGrants(), AccessLevel.OWN).getGrants().size());
        }

        @Test
        @DisplayName("a null level redacts — an unanswerable question is not a yes")
        void unknownLevelRedacts() {
            var g = guard(identity("carol"), settings, mock(IDocumentDescriptorStore.class));

            assertNull(g.redactUnlessOwner(publishedWithGrants(), null).getGrants());
        }
    }

    @Nested
    @DisplayName("callerLevel on a descriptor going out")
    class CallerLevel {

        private final WorkspaceSettings enforcing = settings(true, true, WorkspaceSettings.LEGACY_SHARED);
        private final WorkspaceSettings notEnforcing = settings(false, true, WorkspaceSettings.LEGACY_SHARED);

        private DocumentDescriptor publishedWithGrantsOwnedBy(String owner) {
            var d = published(owner);
            d.setGrants(List.of(new ResourceGrant(Subjects.user("bob"), AccessLevel.USE.name(), owner, new Date(0))));
            return DescriptorAccess.rebuildIndex(d);
        }

        private DocumentDescriptor published(String owner) {
            var d = ownedBy(owner);
            d.setVisibility(ResourceVisibility.published.wireName());
            return DescriptorAccess.rebuildIndex(d);
        }

        @Test
        @DisplayName("an owner is told they own it")
        void ownerGetsOwn() {
            var g = guard(identity("alice"), enforcing, mock(IDocumentDescriptorStore.class));

            var d = g.redactForCaller(ownedBy("alice"));

            assertEquals(AccessLevel.OWN.name(), d.getCallerLevel());
        }

        @Test
        @DisplayName("a stranger reading a published resource is told VIEW, not OWN")
        void publishedStrangerGetsView() {
            // The whole point of the field: without it this row looks identical to
            // one the caller owns, so a client offers Delete and Share on somebody
            // else's agent and learns what it may do from a 403.
            var g = guard(identity("carol"), enforcing, mock(IDocumentDescriptorStore.class));

            var d = g.redactForCaller(published("alice"));

            assertEquals(AccessLevel.VIEW.name(), d.getCallerLevel());
            assertNull(d.getGrants(), "and still no grant list");
        }

        @Test
        @DisplayName("a caller who may do nothing is told nothing, not USE")
        void noAccessGetsNull() {
            var privateToAlice = ownedBy("alice");
            privateToAlice.setVisibility(ResourceVisibility.privateAccess.wireName());
            DescriptorAccess.rebuildIndex(privateToAlice);
            var g = guard(identity("bob"), enforcing, mock(IDocumentDescriptorStore.class));

            var d = g.redactForCaller(privateToAlice);

            assertNull(d.getCallerLevel());
        }

        @Test
        @DisplayName("an administrator is told OWN")
        void adminGetsOwn() {
            var g = guard(identity("root", "eddi-admin"), enforcing, mock(IDocumentDescriptorStore.class));

            assertEquals(AccessLevel.OWN.name(), g.redactForCaller(published("alice")).getCallerLevel());
        }

        @Test
        @DisplayName("nothing is stamped while enforcement is off")
        void absentWhenNotEnforcing() {
            // Everyone may do everything then, so a level would be true and useless —
            // and omitting it keeps the listing byte-identical to a deployment that
            // has never heard of workspaces.
            var g = guard(identity("carol"), notEnforcing, mock(IDocumentDescriptorStore.class));

            var d = g.redactForCaller(published("alice"));

            assertNull(d.getCallerLevel());
            assertNotNull(d.getGrants() == null ? d.getOwnerId() : d.getOwnerId(), "owner still travels");
        }

        @Test
        @DisplayName("a versioned read stamps the level it was gated with")
        void versionedReadStampsTheGatedLevel() {
            // Same reason the redaction uses it: the version in hand may name an
            // owner who no longer owns the resource.
            var g = guard(identity("carol"), enforcing, mock(IDocumentDescriptorStore.class));

            var d = g.redactUnlessOwner(ownedBy("carol"), AccessLevel.VIEW);

            assertEquals(AccessLevel.VIEW.name(), d.getCallerLevel());
            assertNull(d.getGrants());
        }

        @Test
        @DisplayName("a stamp never survives into the next caller's read")
        void previousStampIsOverwritten() {
            // Descriptors are deserialised fresh per read, so this cannot happen
            // today — but it is one cached instance away from happening, and the
            // failure would be a silent privilege report rather than an error.
            var alreadyStamped = published("alice");
            alreadyStamped.setCallerLevel(AccessLevel.OWN.name());
            var g = guard(identity("carol"), enforcing, mock(IDocumentDescriptorStore.class));

            assertEquals(AccessLevel.VIEW.name(), g.redactForCaller(alreadyStamped).getCallerLevel());
        }

        @Test
        @DisplayName("grants are NOT handed to a non-owner just because enforcement is off")
        void grantsStayHiddenWhenNotEnforcing() {
            // seesEverything() is true for everyone while enforcement is off, so
            // keying disclosure on the granted level alone gave every editor the
            // full grant audience of every resource. Ownership and grants ARE
            // recorded in that state — the documented rollout is to let attribution
            // accumulate before switching enforcement on — so this is a deployment
            // part-way through the recommended path, broadcasting the audience lists
            // it had just built up.
            var g = guard(identity("carol"), notEnforcing, mock(IDocumentDescriptorStore.class));

            var d = g.redactForCaller(publishedWithGrantsOwnedBy("alice"));

            assertNull(d.getGrants());
            assertNull(d.getAccessIndex());
            assertEquals("alice", d.getOwnerId(), "owner and visibility still travel");
        }

        @Test
        @DisplayName("an owner still sees their own grants while enforcement is off")
        void ownerKeepsGrantsWhenNotEnforcing() {
            var g = guard(identity("alice"), notEnforcing, mock(IDocumentDescriptorStore.class));

            assertEquals(1, g.redactForCaller(publishedWithGrantsOwnedBy("alice")).getGrants().size());
        }

        @Test
        @DisplayName("an administrator still sees them while enforcement is off")
        void adminKeepsGrantsWhenNotEnforcing() {
            var g = guard(identity("root", "eddi-admin"), notEnforcing, mock(IDocumentDescriptorStore.class));

            assertEquals(1, g.redactForCaller(publishedWithGrantsOwnedBy("alice")).getGrants().size());
        }

        @Test
        @DisplayName("a stamp is cleared rather than kept when enforcement is off")
        void staleStampClearedWhenNotEnforcing() {
            var alreadyStamped = published("alice");
            alreadyStamped.setCallerLevel(AccessLevel.OWN.name());
            var g = guard(identity("carol"), notEnforcing, mock(IDocumentDescriptorStore.class));

            assertNull(g.redactForCaller(alreadyStamped).getCallerLevel());
        }
    }

    @Nested
    @DisplayName("requireAccess reports the level it granted")
    class GrantedLevel {

        private final WorkspaceSettings settings = settings(true, true, WorkspaceSettings.LEGACY_SHARED);

        @Test
        @DisplayName("an owner asking for VIEW is told they hold OWN, not merely enough")
        void reportsOwn() throws Exception {
            // The return value is what a redaction decision downstream is made on, so
            // "sufficient for what you asked" is the wrong answer — it would strip an
            // owner's own grant list off a versioned read.
            var store = mock(IDocumentDescriptorStore.class);
            when(store.readCurrentDescriptor(RESOURCE_ID)).thenReturn(ownedBy("alice"));

            var granted = guard(identity("alice"), settings, store).requireAccess(RESOURCE_ID, AccessLevel.VIEW, "agent");

            assertEquals(AccessLevel.OWN, granted);
        }

        @Test
        @DisplayName("seeing everything reports OWN without reading the store at all")
        void adminReportsOwn() throws Exception {
            var store = mock(IDocumentDescriptorStore.class);

            var granted = guard(identity("root", "eddi-admin"), settings, store)
                    .requireAccess(RESOURCE_ID, AccessLevel.OWN, "agent");

            assertEquals(AccessLevel.OWN, granted);
            verify(store, never()).readCurrentDescriptor(anyString());
        }

        @Test
        @DisplayName("a resource with no descriptor reports only what was asked, never OWN")
        void legacyFallbackReportsTheRequestedLevel() throws Exception {
            // Reporting OWN here would let a caller redact-check their way into a grant
            // list on a resource whose owner could not be established at all.
            var store = mock(IDocumentDescriptorStore.class);
            when(store.readCurrentDescriptor(RESOURCE_ID)).thenThrow(new IResourceStore.ResourceNotFoundException("none"));

            var granted = guard(identity("alice"), settings, store).requireAccess(RESOURCE_ID, AccessLevel.VIEW, "agent");

            assertEquals(AccessLevel.VIEW, granted);
        }
    }

    @Nested
    @DisplayName("use access on a target that is not an agent")
    class NonAgentUse {

        private final WorkspaceSettings settings = settings(true, true, WorkspaceSettings.LEGACY_SHARED);

        private DocumentDescriptor privateTo(String owner) {
            var d = ownedBy(owner);
            d.setVisibility(ResourceVisibility.privateAccess.wireName());
            return DescriptorAccess.rebuildIndex(d);
        }

        @Test
        @DisplayName("refuses a group the caller cannot reach, and calls it a group")
        void refusesUnreachableGroup() throws Exception {
            // A channel target can be a GROUP, and a group is a guarded descriptor like
            // any other. Checking only AGENT targets left that whole branch open. The
            // label matters too: a refusal naming the wrong resource type sends the
            // caller to ask the wrong owner.
            var store = mock(IDocumentDescriptorStore.class);
            when(store.readCurrentDescriptor(RESOURCE_ID)).thenReturn(privateTo("alice"));

            var refusal = assertThrows(ForbiddenException.class,
                    () -> guard(identity("bob"), settings, store).requireUseAccess(RESOURCE_ID, "group"));

            assertTrue(refusal.getMessage().contains("group"), "the refusal must name what the caller cannot reach");
            assertFalse(refusal.getMessage().contains("agent"));
        }

        @Test
        @DisplayName("admits a member of the target's own space")
        void admitsSpaceMember() throws Exception {
            var store = mock(IDocumentDescriptorStore.class);
            when(store.readCurrentDescriptor(RESOURCE_ID)).thenReturn(ownedBy("alice"));

            assertDoesNotThrow(() -> guard(identity("alice"), settings, store).requireUseAccess(RESOURCE_ID, "group"));
        }

        @Test
        @DisplayName("an unaddressed target is not this check's business to refuse")
        void blankIdPasses() {
            // The operation addressing nothing fails on its own terms; refusing here
            // would turn a malformed request into a confusing authorisation error.
            var g = guard(identity("bob"), settings, mock(IDocumentDescriptorStore.class));

            assertDoesNotThrow(() -> g.requireUseAccess("  ", "group"));
            assertDoesNotThrow(() -> g.requireUseAccess(null, "group"));
        }

        @Test
        @DisplayName("requireAgentUseAccess still refuses as an agent")
        void agentLabelUnchanged() throws Exception {
            var store = mock(IDocumentDescriptorStore.class);
            when(store.readCurrentDescriptor(RESOURCE_ID)).thenReturn(privateTo("alice"));

            var refusal = assertThrows(ForbiddenException.class,
                    () -> guard(identity("bob"), settings, store).requireAgentUseAccess(RESOURCE_ID));

            assertTrue(refusal.getMessage().contains("agent"));
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
