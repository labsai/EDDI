/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.notifications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every {@link IWorkspaceNotificationStore} must do, run against real
 * MongoDB and PostgreSQL. The property that matters most: nothing reads or
 * changes another recipient's inbox.
 */
interface WorkspaceNotificationStoreContract {

    IWorkspaceNotificationStore store();

    private static WorkspaceNotification note(String recipient, String actor, WorkspaceNotification.Type type, String resourceId, long at) {
        return new WorkspaceNotification(UUID.randomUUID().toString(), recipient, type, resourceId, "eddi://ai.labs.agent/agentstore/agents/"
                + resourceId + "?version=1", "Agent " + resourceId, actor, "Name " + actor, "USE", null, Instant.ofEpochSecond(at), null);
    }

    @Test
    @DisplayName("lists one recipient's inbox, newest first, and nobody else's")
    default void listsOwnInbox() {
        store().add(note("alice", "bob", WorkspaceNotification.Type.SHARED_WITH_YOU, "a1", 100));
        store().add(note("alice", "bob", WorkspaceNotification.Type.SHARED_WITH_YOU, "a2", 200));
        store().add(note("carol", "bob", WorkspaceNotification.Type.SHARED_WITH_YOU, "c1", 300));

        List<WorkspaceNotification> inbox = store().list("alice", false, 10);
        assertEquals(List.of("a2", "a1"), inbox.stream().map(WorkspaceNotification::resourceId).toList());
        assertEquals(2, store().countUnread("alice"));
    }

    @Test
    @DisplayName("marking read touches only the recipient's own notifications")
    default void markReadIsScoped() {
        var mine = note("alice", "bob", WorkspaceNotification.Type.SHARED_WITH_YOU, "a1", 100);
        var theirs = note("carol", "bob", WorkspaceNotification.Type.SHARED_WITH_YOU, "c1", 100);
        store().add(mine);
        store().add(theirs);

        // Alice names carol's notification id: it must not change.
        assertEquals(0, store().markRead("alice", List.of(theirs.id()), Instant.ofEpochSecond(500)));
        assertEquals(1, store().countUnread("carol"));

        assertEquals(1, store().markRead("alice", null, Instant.ofEpochSecond(500)));
        assertEquals(0, store().countUnread("alice"));
        assertTrue(store().list("alice", true, 10).isEmpty());
        assertEquals(1, store().list("alice", false, 10).size(), "read is dismissed, not deleted");
    }

    @Test
    @DisplayName("an unread request from the same person for the same resource is recognised")
    default void detectsUnreadDuplicates() {
        store().add(note("alice", "bob", WorkspaceNotification.Type.ACCESS_REQUESTED, "a1", 100));

        assertTrue(store().hasUnread("alice", "bob", "a1", WorkspaceNotification.Type.ACCESS_REQUESTED));
        assertFalse(store().hasUnread("alice", "bob", "a2", WorkspaceNotification.Type.ACCESS_REQUESTED));
        assertFalse(store().hasUnread("alice", "bob", "a1", WorkspaceNotification.Type.SHARED_WITH_YOU));

        store().markRead("alice", null, Instant.ofEpochSecond(500));
        assertFalse(store().hasUnread("alice", "bob", "a1", WorkspaceNotification.Type.ACCESS_REQUESTED));
    }

    @Test
    @DisplayName("counts what one actor caused since a moment, for rate limiting")
    default void countsByActor() {
        store().add(note("alice", "bob", WorkspaceNotification.Type.ACCESS_REQUESTED, "a1", 100));
        store().add(note("carol", "bob", WorkspaceNotification.Type.ACCESS_REQUESTED, "c1", 300));
        store().add(note("carol", "bob", WorkspaceNotification.Type.SHARED_WITH_YOU, "c2", 300));

        assertEquals(1, store().countByActorSince("bob", WorkspaceNotification.Type.ACCESS_REQUESTED, Instant.ofEpochSecond(200)));
        assertEquals(2, store().countByActorSince("bob", WorkspaceNotification.Type.ACCESS_REQUESTED, Instant.ofEpochSecond(0)));
    }

    @Test
    @DisplayName("pruning keeps the newest and drops the expired")
    default void prunes() {
        for (int i = 0; i < 5; i++) {
            store().add(note("alice", "bob", WorkspaceNotification.Type.SHARED_WITH_YOU, "a" + i, 100 + i));
        }
        store().add(note("carol", "bob", WorkspaceNotification.Type.SHARED_WITH_YOU, "c1", 1));

        store().prune("alice", 3, Instant.ofEpochSecond(101));

        assertEquals(List.of("a4", "a3", "a2"), store().list("alice", false, 10).stream().map(WorkspaceNotification::resourceId).toList());
        assertEquals(1, store().list("carol", false, 10).size(), "pruning one inbox must not touch another");
    }

    @Test
    @DisplayName("erasure removes what a person received and what they caused")
    default void deletesInvolving() {
        store().add(note("alice", "bob", WorkspaceNotification.Type.SHARED_WITH_YOU, "a1", 100));
        store().add(note("bob", "carol", WorkspaceNotification.Type.SHARED_WITH_YOU, "b1", 100));
        store().add(note("carol", "dave", WorkspaceNotification.Type.SHARED_WITH_YOU, "c1", 100));

        assertEquals(2, store().listInvolving("bob", 10).size());
        assertEquals(2, store().deleteInvolving("bob"));
        assertTrue(store().list("alice", false, 10).isEmpty(), "bob's share in alice's inbox names bob");
        assertEquals(1, store().list("carol", false, 10).size());
    }
}
