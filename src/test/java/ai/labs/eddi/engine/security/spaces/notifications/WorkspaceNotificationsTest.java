/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.notifications;

import ai.labs.eddi.configs.descriptors.ConfigResourceTypes;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.engine.security.spaces.Subjects;
import ai.labs.eddi.engine.security.spaces.directory.UserDirectory;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Notifications tell people about shares and carry access requests. The rules
 * worth pinning: an access request never reveals anything about a resource the
 * requester cannot see, it is bounded, and nobody is told about their own
 * share.
 */
class WorkspaceNotificationsTest {

    private static final String RESOURCE = "6aba70011a25f8be7920e055";

    private IWorkspaceNotificationStore store;
    private IDocumentDescriptorStore descriptors;
    private ResourceAccessGuard guard;
    private SpaceContext spaceContext;
    private UserDirectory directory;
    private WorkspaceNotifications sut;

    @BeforeEach
    void setUp() {
        store = mock(IWorkspaceNotificationStore.class);
        descriptors = mock(IDocumentDescriptorStore.class);
        guard = mock(ResourceAccessGuard.class);
        spaceContext = mock(SpaceContext.class);
        directory = mock(UserDirectory.class);
        ManagedExecutor executor = mock(ManagedExecutor.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any());
        when(spaceContext.currentPrincipal()).thenReturn("bob");
        when(directory.labelFor(anyString())).thenAnswer(i -> "Name " + i.getArgument(0));
        sut = new WorkspaceNotifications(store, descriptors, guard, spaceContext, directory, executor, ConfigResourceTypes.of("ai.labs.agent"));
    }

    private DocumentDescriptor ownedBy(String owner) throws Exception {
        var descriptor = new DocumentDescriptor();
        descriptor.setOwnerId(owner);
        descriptor.setName("Support Agent");
        descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + RESOURCE + "?version=1"));
        when(descriptors.readCurrentDescriptor(RESOURCE)).thenReturn(descriptor);
        return descriptor;
    }

    @Nested
    @DisplayName("access requests")
    class Requests {

        @Test
        @DisplayName("are delivered to the owner, with the requester's name and note")
        void deliveredToOwner() throws Exception {
            ownedBy("alice");

            assertEquals(WorkspaceNotifications.RequestOutcome.SENT, sut.requestAccess(RESOURCE, AccessLevel.VIEW, "  need it\n for the demo "));

            var captor = ArgumentCaptor.forClass(WorkspaceNotification.class);
            verify(store).add(captor.capture());
            WorkspaceNotification delivered = captor.getValue();
            assertEquals("alice", delivered.recipient());
            assertEquals(WorkspaceNotification.Type.ACCESS_REQUESTED, delivered.type());
            assertEquals("bob", delivered.actor());
            assertEquals("VIEW", delivered.level());
            assertEquals("need it for the demo", delivered.message(), "a note is shown verbatim, so it is flattened to one line");
        }

        @Test
        @DisplayName("for an id that matches nothing are answered exactly like a delivered one")
        void unknownResourceLooksDelivered() throws Exception {
            when(descriptors.readCurrentDescriptor(anyString())).thenThrow(new IResourceStore.ResourceNotFoundException("none"));

            assertEquals(WorkspaceNotifications.RequestOutcome.SENT, sut.requestAccess(RESOURCE, AccessLevel.USE, null),
                    "a different answer would make this an oracle for which ids exist");
            verify(store, never()).add(any());
        }

        @Test
        @DisplayName("for a non-configuration id (a conversation) are answered like a missing one, after counting the attempt")
        void nonConfigurationResourceLooksDelivered() throws Exception {
            var conversation = new DocumentDescriptor();
            conversation.setOwnerId("alice");
            conversation.setResource(URI.create("eddi://ai.labs.conversation/conversationstore/conversations/" + RESOURCE));
            when(descriptors.readCurrentDescriptor(RESOURCE)).thenReturn(conversation);

            assertEquals(WorkspaceNotifications.RequestOutcome.SENT, sut.requestAccess(RESOURCE, AccessLevel.USE, null));
            verify(store, never()).add(any());
            verify(store).countByActorSince(eq("bob"), eq(WorkspaceNotification.Type.ACCESS_REQUESTED), any());
        }

        @Test
        @DisplayName("say so when the requester already holds the level — they can see it anyway")
        void alreadyHasAccess() throws Exception {
            var descriptor = ownedBy("alice");
            when(guard.effectiveLevel(descriptor)).thenReturn(AccessLevel.EDIT);

            assertEquals(WorkspaceNotifications.RequestOutcome.ALREADY_HAS_ACCESS, sut.requestAccess(RESOURCE, AccessLevel.VIEW, null));
            verify(store, never()).add(any());
        }

        @Test
        @DisplayName("are not delivered twice while the first is unanswered — and say so no differently")
        void deduplicated() throws Exception {
            ownedBy("alice");
            when(store.hasUnread("alice", "bob", RESOURCE, WorkspaceNotification.Type.ACCESS_REQUESTED)).thenReturn(true);

            // A distinct answer for a repeat would tell the caller the id names an
            // owned resource — which a request for a missing id answers as SENT.
            assertEquals(WorkspaceNotifications.RequestOutcome.SENT, sut.requestAccess(RESOURCE, AccessLevel.USE, null));
            verify(store, never()).add(any());
        }

        @Test
        @DisplayName("count every attempt, so probing ids that match nothing reaches the limit too")
        void probingIsLimited() throws Exception {
            when(descriptors.readCurrentDescriptor(anyString())).thenThrow(new ResourceNotFoundException("none"));

            for (int i = 0; i < WorkspaceNotifications.MAX_REQUESTS_PER_DAY; i++) {
                assertEquals(WorkspaceNotifications.RequestOutcome.SENT, sut.requestAccess("probe-" + i, AccessLevel.USE, null));
            }
            assertEquals(WorkspaceNotifications.RequestOutcome.RATE_LIMITED, sut.requestAccess("probe-x", AccessLevel.USE, null));
        }

        @Test
        @DisplayName("are capped per requester per day")
        void rateLimited() throws Exception {
            ownedBy("alice");
            when(store.countByActorSince(eq("bob"), eq(WorkspaceNotification.Type.ACCESS_REQUESTED), any()))
                    .thenReturn((long) WorkspaceNotifications.MAX_REQUESTS_PER_DAY);

            assertEquals(WorkspaceNotifications.RequestOutcome.RATE_LIMITED, sut.requestAccess(RESOURCE, AccessLevel.USE, null));
            verify(store, never()).add(any());
        }

        @Test
        @DisplayName("to an unowned resource go nowhere, and still read as sent")
        void unownedGoesNowhere() throws Exception {
            ownedBy(null);

            assertEquals(WorkspaceNotifications.RequestOutcome.SENT, sut.requestAccess(RESOURCE, AccessLevel.USE, null));
            verify(store, never()).add(any());
        }

        @Test
        @DisplayName("notes are plain, single-spaced and bounded")
        void notesAreCleaned() {
            assertNull(WorkspaceNotifications.cleanMessage("   "));
            // Built rather than escaped: the formatter decodes backslash-u escapes into
            // raw, invisible characters in the source file.
            char nul = 0;
            assertEquals("a b c", WorkspaceNotifications.cleanMessage("a" + nul + " b\t\tc"));
            assertEquals(WorkspaceNotifications.MAX_MESSAGE_LENGTH, WorkspaceNotifications.cleanMessage("x".repeat(2000)).length());
        }
    }

    @Nested
    @DisplayName("share notifications")
    class Shares {

        @Test
        @DisplayName("reach the person shared with")
        void reachPerson() {
            sut.onShared(RESOURCE, "eddi://x", "Support Agent", Subjects.user("carol"), AccessLevel.USE);

            var captor = ArgumentCaptor.forClass(WorkspaceNotification.class);
            verify(store).add(captor.capture());
            assertEquals("carol", captor.getValue().recipient());
            assertEquals("Name bob", captor.getValue().actorLabel());
            verify(store).prune(eq("carol"), eq(WorkspaceNotifications.INBOX_SIZE), any());
        }

        @Test
        @DisplayName("reach every recorded team member except the sharer")
        void reachTeam() {
            when(directory.teamMembers(eq(Subjects.team("engineering")), anyInt())).thenReturn(List.of("bob", "carol", "dave"));

            sut.onShared(RESOURCE, "eddi://x", "Support Agent", Subjects.team("engineering"), AccessLevel.USE);

            var captor = ArgumentCaptor.forClass(WorkspaceNotification.class);
            verify(store, times(2)).add(captor.capture());
            assertEquals(List.of("carol", "dave"), captor.getAllValues().stream().map(WorkspaceNotification::recipient).toList(),
                    "nobody is told about a share they made themselves");
        }

        @Test
        @DisplayName("a failing inbox never fails the share")
        void failingStoreSwallowed() {
            doAnswer(i -> {
                throw new IllegalStateException("down");
            }).when(store).add(any());

            sut.onShared(RESOURCE, "eddi://x", "Support Agent", Subjects.user("carol"), AccessLevel.USE);
            assertTrue(true, "reaching here is the assertion");
        }
    }

    @Test
    @DisplayName("the inbox is always the caller's own")
    void inboxIsCallers() {
        sut.inbox(true, 5000);
        sut.markRead(List.of("someone-elses-id"));

        verify(store).list("bob", true, WorkspaceNotifications.INBOX_SIZE);
        verify(store).markRead(eq("bob"), eq(List.of("someone-elses-id")), any());
    }

    @Nested
    @DisplayName("update notices")
    class UpdateNotices {

        @Test
        @DisplayName("go to the administrators' shared inbox, naming the version and the release page")
        void addressedToAdministrators() {
            sut.onUpdateAvailable("9.9.9", "https://github.com/labsai/EDDI/releases/tag/9.9.9");

            ArgumentCaptor<WorkspaceNotification> saved = ArgumentCaptor.forClass(WorkspaceNotification.class);
            verify(store).add(saved.capture());
            WorkspaceNotification n = saved.getValue();
            assertEquals(WorkspaceNotifications.SYSTEM_ADMINS, n.recipient());
            assertEquals(WorkspaceNotification.Type.UPDATE_AVAILABLE, n.type());
            assertEquals("9.9.9", n.resourceName());
            assertEquals("https://github.com/labsai/EDDI/releases/tag/9.9.9", n.resourceUri());
            assertTrue(n.unread());
        }

        @Test
        @DisplayName("report false when the notice could not be stored")
        void reportsStoreFailure() {
            doThrow(new IllegalStateException("down")).when(store).add(any());

            assertFalse(sut.onUpdateAvailable("9.9.9", null));
        }

        @Test
        @DisplayName("report true for a notice that is already waiting")
        void alreadyWaitingCounts() {
            when(store.hasUnread(WorkspaceNotifications.SYSTEM_ADMINS, "system", "9.9.9", WorkspaceNotification.Type.UPDATE_AVAILABLE))
                    .thenReturn(true);

            assertTrue(sut.onUpdateAvailable("9.9.9", null));
        }

        @Test
        @DisplayName("cannot be read through a principal that is named like the shared inbox")
        void reservedPrincipal() {
            when(spaceContext.currentPrincipal()).thenReturn(WorkspaceNotifications.SYSTEM_ADMINS);
            when(guard.isAdmin()).thenReturn(false);

            sut.inbox(false, 50);
            sut.unreadCount();
            sut.markRead(null);

            verify(store, never()).list(eq(WorkspaceNotifications.SYSTEM_ADMINS), anyBoolean(), anyInt());
            verify(store, never()).countUnread(WorkspaceNotifications.SYSTEM_ADMINS);
            verify(store, never()).markRead(eq(WorkspaceNotifications.SYSTEM_ADMINS), any(), any());
        }

        @Test
        @DisplayName("are not raised twice while the first is unread")
        void notTwice() {
            when(store.hasUnread(WorkspaceNotifications.SYSTEM_ADMINS, "system", "9.9.9", WorkspaceNotification.Type.UPDATE_AVAILABLE))
                    .thenReturn(true);

            sut.onUpdateAvailable("9.9.9", null);

            verify(store, never()).add(any());
        }

        @Test
        @DisplayName("are read by an administrator in addition to their own inbox, newest first")
        void administratorReadsBoth() {
            when(guard.isAdmin()).thenReturn(true);
            var own = new WorkspaceNotification("a", "bob", WorkspaceNotification.Type.SHARED_WITH_YOU, "r", null, "R", "al", "Al", "VIEW", null,
                    Instant.parse("2026-01-01T00:00:00Z"), null);
            var update = new WorkspaceNotification("b", WorkspaceNotifications.SYSTEM_ADMINS, WorkspaceNotification.Type.UPDATE_AVAILABLE, "9.9.9",
                    null, "9.9.9", "system", "EDDI", null, null, Instant.parse("2026-02-01T00:00:00Z"), null);
            when(store.list("bob", false, 50)).thenReturn(List.of(own));
            when(store.list(WorkspaceNotifications.SYSTEM_ADMINS, false, 50)).thenReturn(List.of(update));
            when(store.countUnread("bob")).thenReturn(1L);
            when(store.countUnread(WorkspaceNotifications.SYSTEM_ADMINS)).thenReturn(1L);

            assertEquals(List.of("b", "a"), sut.inbox(false, 50).stream().map(WorkspaceNotification::id).toList());
            assertEquals(2L, sut.unreadCount());
        }

        @Test
        @DisplayName("are invisible to a caller who is not an administrator")
        void hiddenFromOthers() {
            when(guard.isAdmin()).thenReturn(false);

            sut.inbox(false, 50);
            sut.unreadCount();
            sut.markRead(null);

            verify(store, never()).list(eq(WorkspaceNotifications.SYSTEM_ADMINS), anyBoolean(), anyInt());
            verify(store, never()).countUnread(WorkspaceNotifications.SYSTEM_ADMINS);
            verify(store, never()).markRead(eq(WorkspaceNotifications.SYSTEM_ADMINS), any(), any());
        }

        @Test
        @DisplayName("reach an administrator when authorization is off and there is no principal")
        void anonymousAdministrator() {
            when(spaceContext.currentPrincipal()).thenReturn(null);
            when(guard.isAdmin()).thenReturn(true);

            sut.markRead(null);

            verify(store).markRead(eq(WorkspaceNotifications.SYSTEM_ADMINS), any(), any());
            verify(store, never()).markRead(eq("bob"), any(), any());
        }
    }
}
