/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.rest.RestWorkspaces;
import ai.labs.eddi.engine.security.spaces.rest.model.SpaceInfo;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.DisplayName;
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
 * The contract a client draws its workspace UI from.
 * <p>
 * The load-bearing assertion here is that space ids come back <em>encoded</em>
 * and labels come back <em>decoded</em>. A client that receives a decoded id
 * and sends it as {@code ?space=} selects nothing, and selecting nothing
 * renders as "you have no agents" rather than as an error — so the two are
 * pinned separately, with a principal that actually needs escaping.
 */
class RestWorkspacesTest {

    private static WorkspaceSettings settings(boolean enabled) {
        var s = new WorkspaceSettings(enabled, true, "groups", WorkspaceSettings.LEGACY_SHARED, Optional.empty());
        s.validate();
        return s;
    }

    private static SecurityIdentity identity(String principal, Set<String> groups, String... roles) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        if (principal == null) {
            lenient().when(identity.isAnonymous()).thenReturn(true);
            return identity;
        }
        lenient().when(identity.isAnonymous()).thenReturn(false);
        Principal p = mock(Principal.class);
        lenient().when(p.getName()).thenReturn(principal);
        lenient().when(identity.getPrincipal()).thenReturn(p);
        lenient().when(identity.<Object>getAttribute("groups")).thenReturn(groups);
        for (String role : roles) {
            lenient().when(identity.hasRole(role)).thenReturn(true);
        }
        return identity;
    }

    private static RestWorkspaces sut(SecurityIdentity identity, WorkspaceSettings settings) {
        var spaceContext = new SpaceContext(identity, settings);
        var guard = new ResourceAccessGuard(identity, new OwnershipValidator(settings.isStampingOwnership()), spaceContext,
                settings, mock(IDocumentDescriptorStore.class));
        return new RestWorkspaces(spaceContext, settings, guard);
    }

    @Test
    @DisplayName("reports the caller's personal space first, then teams")
    void listsSpacesPersonalFirst() {
        var info = sut(identity("alice", Set.of("/engineering")), settings(true)).readWorkspaceInfo();

        assertTrue(info.enabled());
        assertEquals("alice", info.principal());
        assertEquals(List.of("personal", "team"), info.spaces().stream().map(SpaceInfo::kind).toList());
        assertEquals(Subjects.personalSpace("alice"), info.spaces().getFirst().id());
        assertEquals(Subjects.teamSpace("/engineering"), info.spaces().get(1).id());
    }

    @Test
    @DisplayName("ids stay encoded while labels come back readable")
    void encodesIdsButNotLabels() {
        // A principal carrying the index delimiter. If the id were served decoded,
        // a client would send back a space that matches nothing.
        var info = sut(identity("a|b", Set.of()), settings(true)).readWorkspaceInfo();

        SpaceInfo personal = info.spaces().getFirst();
        assertEquals("user:a%7Cb", personal.id());
        assertEquals("a|b", personal.label());
        assertEquals("a|b", info.principal(), "the principal is the ownerId a client compares against, so it is not encoded");
    }

    @Test
    @DisplayName("says enforcement is off, so a client can hide sharing rather than offer a no-op")
    void reportsDisabled() {
        var info = sut(identity("alice", Set.of("/engineering")), settings(false)).readWorkspaceInfo();

        assertFalse(info.enabled());
        assertTrue(info.seesEverything(), "nobody is restricted while the feature is off");
        // Still described: the client can say who you are either way. Only `enabled`
        // decides whether the sharing controls are worth drawing.
        assertEquals(2, info.spaces().size());
    }

    @Test
    @DisplayName("an administrator is told they see everything")
    void reportsAdminReach() {
        var info = sut(identity("root", Set.of(), "eddi-admin"), settings(true)).readWorkspaceInfo();

        assertTrue(info.enabled());
        assertTrue(info.seesEverything());
    }

    @Test
    @DisplayName("a non-admin under enforcement is not told they see everything")
    void reportsRestrictedReach() {
        var info = sut(identity("alice", Set.of()), settings(true)).readWorkspaceInfo();

        assertFalse(info.seesEverything());
    }

    @Test
    @DisplayName("an anonymous caller gets no spaces at all")
    void anonymousHasNoSpaces() {
        // Not "every space" and not "a space named null" — the backend resolves
        // anonymous to nothing, and a client must not offer a filter the server
        // will not honour.
        var info = sut(identity(null, Set.of()), settings(true)).readWorkspaceInfo();

        assertNull(info.principal());
        assertNull(info.defaultSpace());
        assertTrue(info.spaces().isEmpty());
    }

    @Test
    @DisplayName("the default write space is the one new resources land in")
    void reportsDefaultWriteSpace() {
        var info = sut(identity("alice", Set.of("/engineering")), settings(true)).readWorkspaceInfo();

        assertEquals(Subjects.personalSpace("alice"), info.defaultSpace());
    }

    @Test
    @DisplayName("a configured default team overrides the personal space")
    void honoursConfiguredDefaultTeam() {
        var settings = new WorkspaceSettings(true, true, "groups", WorkspaceSettings.LEGACY_SHARED, Optional.of("engineering"));
        settings.validate();

        var info = sut(identity("alice", Set.of("/engineering")), settings).readWorkspaceInfo();

        assertEquals(Subjects.teamSpace("engineering"), info.defaultSpace());
    }
}
