/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.configs.descriptors.ConfigResourceTypes;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.descriptors.model.ResourceGrant;
import ai.labs.eddi.configs.descriptors.model.ResourceVisibility;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.directory.DirectoryUser;
import ai.labs.eddi.engine.security.spaces.directory.UserDirectory;
import ai.labs.eddi.engine.security.spaces.notifications.WorkspaceNotifications;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What {@link ResourceSharingService#describe} tells a caller about a
 * resource's sharing, and how a share is announced to its recipient.
 */
class ResourceSharingServiceDescribeTest {

    private static final String AGENT = "agent0000000000000000";
    private static final URI AGENT_URI = URI.create("eddi://ai.labs.agent/agentstore/agents/0123456789abcdef01234567?version=1");

    private ResourceAccessGuard accessGuard;
    private UserDirectory directory;
    private WorkspaceNotifications notifications;
    private ResourceSharingService service;
    private DocumentDescriptor descriptor;

    @BeforeEach
    void setUp() throws Exception {
        descriptor = new DocumentDescriptor();
        descriptor.setResource(AGENT_URI);
        descriptor.setName("Support Agent");
        descriptor.setOwnerId("alice");
        descriptor.setSpaceId(Subjects.personalSpace("alice"));
        descriptor.setVisibility(ResourceVisibility.space.wireName());
        descriptor.setGrants(new ArrayList<>(List.of(new ResourceGrant(Subjects.user("bob"), "VIEW", "alice", new Date(1)),
                new ResourceGrant(Subjects.team("/support/l2"), "EDIT", "alice", new Date(2)),
                new ResourceGrant(Subjects.user("ghost"), "VIEW", "alice", new Date(3)))));

        Map<String, DocumentDescriptor> descriptors = new HashMap<>();
        descriptors.put(AGENT, descriptor);
        IDocumentDescriptorStore store = mock(IDocumentDescriptorStore.class);
        when(store.readDescriptor(anyString(), anyInt())).thenAnswer(i -> descriptors.get(i.<String>getArgument(0)));
        when(store.getCurrentResourceId(anyString())).thenAnswer(i -> {
            if (!descriptors.containsKey(i.<String>getArgument(0))) {
                throw new IResourceStore.ResourceNotFoundException("none");
            }
            return new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return i.getArgument(0);
                }

                @Override
                public Integer getVersion() {
                    return 1;
                }
            };
        });

        accessGuard = mock(ResourceAccessGuard.class);
        when(accessGuard.currentPrincipal()).thenReturn("alice");
        when(accessGuard.canAccess(any(), eq(AccessLevel.OWN))).thenReturn(true);
        directory = mock(UserDirectory.class);
        when(directory.lookup(any())).thenReturn(Map.of(
                "alice", new DirectoryUser("alice", Subjects.user("alice"), "alice", "alice@example.com", true, "Alice Admin", List.of(), null, null),
                "bob", new DirectoryUser("bob", Subjects.user("bob"), "bob", "bob@example.com", true, "Bob B", List.of(), null, null)));
        when(directory.detailFor(any(DirectoryUser.class), any(), eq(false))).thenAnswer(i -> i.<DirectoryUser>getArgument(0).email());
        notifications = mock(WorkspaceNotifications.class);

        @SuppressWarnings("unchecked")
        Event<SharingChangedEvent> event = mock(Event.class);
        service = new ResourceSharingService(store, accessGuard, mock(ConfigGraphResolver.class), directory, notifications, event,
                ConfigResourceTypes.of("agent", "ai.labs.agent", "ai.labs.workflow"));
    }

    @Test
    void theOwnerSeesEveryGrantWithLabels() {
        when(accessGuard.effectiveLevel(any())).thenReturn(AccessLevel.OWN);
        when(directory.isActive()).thenReturn(true);

        var info = service.describe(AGENT);
        assertEquals(AGENT, info.resourceId());
        assertEquals("alice", info.ownerId());
        assertEquals("Alice Admin", info.ownerLabel());
        assertEquals(Subjects.personalSpace("alice"), info.spaceId());
        assertEquals("space", info.visibility());
        assertEquals("OWN", info.callerLevel());
        assertEquals(3, info.grants().size());

        var bob = info.grants().get(0);
        assertEquals("user", bob.kind());
        assertEquals("Bob B", bob.label());
        assertEquals("bob@example.com", bob.detail());
        assertTrue(bob.known());

        var team = info.grants().get(1);
        assertEquals("team", team.kind());
        assertEquals("support/l2", team.label(), "the decoded, normalised group");
        assertNull(team.detail());
        assertEquals("EDIT", team.level());

        var ghost = info.grants().get(2);
        assertEquals("ghost", ghost.label(), "an unknown principal is shown as itself");
        assertFalse(ghost.known(), "the running directory does not know this principal");
    }

    @Test
    void withoutARunningDirectoryNobodyIsFlaggedUnknown() {
        when(accessGuard.effectiveLevel(any())).thenReturn(AccessLevel.OWN);
        when(directory.isActive()).thenReturn(false);
        assertTrue(service.describe(AGENT).grants().get(2).known());
    }

    @Test
    void aReaderBelowOwnSeesNoGrants() {
        when(accessGuard.effectiveLevel(any())).thenReturn(AccessLevel.VIEW);
        var info = service.describe(AGENT);
        assertTrue(info.grants().isEmpty(), "grant subjects are only for the owner");
        assertEquals("VIEW", info.callerLevel());
        assertEquals("Alice Admin", info.ownerLabel());
    }

    @Test
    void legacyDataWithoutOwnerVisibilityOrGrantsIsDescribed() {
        descriptor.setOwnerId(null);
        descriptor.setVisibility(null);
        descriptor.setGrants(null);
        when(accessGuard.effectiveLevel(any())).thenReturn(null);
        var info = service.describe(AGENT);
        assertNull(info.ownerId());
        assertNull(info.ownerLabel());
        assertEquals(ResourceVisibility.space.wireName(), info.visibility());
        assertNull(info.callerLevel());
        assertTrue(info.grants().isEmpty());
    }

    @Test
    void anOwnerTheDirectoryDoesNotKnowIsLabelledByPrincipal() {
        descriptor.setOwnerId("carol");
        descriptor.setGrants(null);
        when(accessGuard.effectiveLevel(any())).thenReturn(AccessLevel.OWN);
        assertEquals("carol", service.describe(AGENT).ownerLabel());
    }

    @Test
    void aGrantWithoutASubjectIsSkipped() {
        var blank = new ResourceGrant();
        var list = new ArrayList<ResourceGrant>();
        list.add(blank);
        list.add(new ResourceGrant("legacy-principal", "VIEW", "alice", null));
        descriptor.setGrants(list);
        when(accessGuard.effectiveLevel(any())).thenReturn(AccessLevel.OWN);
        var grants = service.describe(AGENT).grants();
        assertEquals(1, grants.size());
        assertEquals("legacy-principal", grants.getFirst().label(), "a bare principal from before subject prefixes");
    }

    @Test
    void aShareIsAnnouncedToTheRecipientWithTheResourceName() {
        service.share(AGENT, Subjects.user("bob"), AccessLevel.EDIT, false);
        verify(notifications).onShared(AGENT, AGENT_URI.toString(), "Support Agent", Subjects.user("bob"), AccessLevel.EDIT);
    }

    @Test
    void aDryRunIsNotAnnounced() {
        service.share(AGENT, Subjects.user("bob"), AccessLevel.EDIT, false, true);
        verify(notifications, never()).onShared(any(), any(), any(), any(), any());
    }

    @Test
    void aFailingInboxDoesNotFailTheShare() {
        doThrow(new IllegalStateException("inbox down")).when(notifications).onShared(any(), any(), any(), any(), any());
        var result = service.share(AGENT, Subjects.user("bob"), AccessLevel.VIEW, false);
        assertEquals(List.of(AGENT), result.updatedIds());
        verify(notifications).onShared(AGENT, AGENT_URI.toString(), "Support Agent", Subjects.user("bob"), AccessLevel.VIEW);
    }

    @Test
    void canonicalSpaceIds() {
        assertTrue(ResourceSharingService.isCanonicalSpace(Subjects.personalSpace("alice")));
        assertTrue(ResourceSharingService.isCanonicalSpace(Subjects.teamSpace("/support")));
        assertFalse(ResourceSharingService.isCanonicalSpace("user:"));
        assertFalse(ResourceSharingService.isCanonicalSpace("team:"));
        assertFalse(ResourceSharingService.isCanonicalSpace("space:alice"));
    }
}
