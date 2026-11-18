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
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.directory.DirectoryUser;
import ai.labs.eddi.engine.security.spaces.directory.IUserDirectoryStore;
import ai.labs.eddi.engine.security.spaces.directory.UserDirectory;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code eddi.workspaces.directory.stale-after} governs only what the user
 * directory suggests and shows (#1087). Whether a person's grants work is
 * decided from their live token on every request: a directory entry that has
 * gone stale must not take a single grant away, and a stale entry that still
 * lists a team must not give one back to somebody whose token no longer does.
 */
@DisplayName("directory staleness never touches grant validity")
class StaleDirectoryGrantIndependenceTest {

    private static final String RESOURCE_ID = "abcdef1234567890abcdef";
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
    private static final String ENGINEERING = Subjects.team("engineering");

    private WorkspaceSettings settings;
    private IUserDirectoryStore directoryStore;
    private IDocumentDescriptorStore descriptorStore;

    /** Carol, last seen a year ago: well outside a 90-day window. */
    private final DirectoryUser staleCarol = new DirectoryUser("carol", "sub-carol", "carol", "carol@example.com", true, "Carol",
            List.of(ENGINEERING), Instant.EPOCH, NOW.minus(Duration.ofDays(365)));

    @BeforeEach
    void setUp() {
        settings = new WorkspaceSettings(true, true, "groups", WorkspaceSettings.LEGACY_SHARED, Optional.empty());
        settings.validate();
        directoryStore = mock(IUserDirectoryStore.class);
        lenient().when(directoryStore.find("carol")).thenReturn(Optional.of(staleCarol));
        lenient().when(directoryStore.findAll(any())).thenReturn(Map.of("carol", staleCarol));
        descriptorStore = mock(IDocumentDescriptorStore.class);
    }

    /** An OIDC caller whose live token lists {@code groups}. */
    private static SecurityIdentity caller(String principal, String... groups) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        lenient().when(identity.isAnonymous()).thenReturn(false);
        JsonWebToken jwt = mock(JsonWebToken.class);
        lenient().when(jwt.getName()).thenReturn(principal);
        lenient().when(jwt.<Object>getClaim("groups")).thenReturn(List.of(groups));
        lenient().when(identity.getPrincipal()).thenReturn(jwt);
        return identity;
    }

    private ResourceAccessGuard guardFor(SecurityIdentity identity) {
        var spaceContext = new SpaceContext(identity, settings);
        var directory = new UserDirectory(directoryStore, identity, spaceContext, settings, mock(ManagedExecutor.class), true, true,
                Duration.ofDays(90), Clock.fixed(NOW, ZoneOffset.UTC));
        return new ResourceAccessGuard(identity, new OwnershipValidator(true), spaceContext, settings, descriptorStore, directory);
    }

    private void privateResourceGrantedTo(String subject, AccessLevel level) throws Exception {
        DocumentDescriptor d = new DocumentDescriptor();
        d.setOwnerId("alice");
        d.setSpaceId(Subjects.personalSpace("alice"));
        d.setVisibility(ResourceVisibility.privateAccess.wireName());
        d.setGrants(List.of(new ResourceGrant(subject, level.name(), "alice", new Date(0))));
        when(descriptorStore.readCurrentDescriptor(RESOURCE_ID)).thenReturn(DescriptorAccess.rebuildIndex(d));
    }

    @Test
    @DisplayName("a team grant still admits a member whose directory entry is stale")
    void staleEntryKeepsTeamGrant() throws Exception {
        privateResourceGrantedTo(ENGINEERING, AccessLevel.VIEW);
        var carolGuard = guardFor(caller("carol", "/engineering"));

        assertDoesNotThrow(() -> carolGuard.requireAccess(RESOURCE_ID, AccessLevel.VIEW, "agent"));
        assertTrue(carolGuard.hasAccess(RESOURCE_ID, AccessLevel.VIEW));
        verify(directoryStore, never()).searchWithinTeams(anyString(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("a personal grant still admits a person whose directory entry is stale")
    void staleEntryKeepsPersonalGrant() throws Exception {
        privateResourceGrantedTo(Subjects.user("carol"), AccessLevel.USE);
        var carolGuard = guardFor(caller("carol"));

        assertDoesNotThrow(() -> carolGuard.requireAgentUseAccess(RESOURCE_ID));
    }

    @Test
    @DisplayName("the live token decides: a stale entry still listing the team does not admit somebody whose token does not")
    void tokenDecidesNotDirectory() throws Exception {
        privateResourceGrantedTo(ENGINEERING, AccessLevel.VIEW);
        var carolGuard = guardFor(caller("carol", "/finance"));

        assertThrows(ForbiddenException.class, () -> carolGuard.requireAccess(RESOURCE_ID, AccessLevel.VIEW, "agent"));
    }

    @Test
    @DisplayName("…while the directory, for the same entry, no longer counts her as a teammate")
    void directoryMeanwhileHidesHer() {
        var alice = CallerSpaces.of("alice", Set.of("/engineering"));
        var aliceDirectory = new UserDirectory(directoryStore, caller("alice", "/engineering"), mock(SpaceContext.class), settings,
                mock(ManagedExecutor.class), true, true, Duration.ofDays(90), Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals("carol", aliceDirectory.detailFor(staleCarol, alice, false), "her address is no longer shown to a former teammate");
    }
}
