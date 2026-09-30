/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.notifications;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Persistence for {@link WorkspaceNotification}s.
 * <p>
 * Every read is scoped to one recipient: there is deliberately no way to list
 * somebody else's notifications, so an endpoint built on this store cannot be
 * turned into one that does.
 */
public interface IWorkspaceNotificationStore {

    void add(WorkspaceNotification notification);

    /** The recipient's notifications, newest first. */
    List<WorkspaceNotification> list(String recipient, boolean unreadOnly, int limit);

    long countUnread(String recipient);

    /**
     * Marks notifications read.
     *
     * @param ids
     *            the notifications to mark, or null for every one of the
     *            recipient's
     * @return how many changed
     */
    long markRead(String recipient, Collection<String> ids, Instant readAt);

    /**
     * Whether {@code actor} already has an unread notification of {@code type}
     * about {@code resourceId} waiting for {@code recipient} — so asking twice does
     * not ask twice.
     */
    boolean hasUnread(String recipient, String actor, String resourceId, WorkspaceNotification.Type type);

    /**
     * How many notifications of {@code type} {@code actor} caused since
     * {@code since} — for rate limiting.
     */
    long countByActorSince(String actor, WorkspaceNotification.Type type, Instant since);

    /**
     * Keeps the recipient's newest {@code keep} notifications and deletes the rest,
     * together with anything older than {@code olderThan}.
     */
    void prune(String recipient, int keep, Instant olderThan);

    /**
     * Deletes every notification for or caused by {@code principal} — GDPR erasure.
     * A notification in somebody else's inbox still names the person who shared, so
     * it is personal data of theirs too.
     *
     * @return how many were deleted
     */
    long deleteInvolving(String principal);

    /** Notifications for or caused by {@code principal} — GDPR export. */
    List<WorkspaceNotification> listInvolving(String principal, int limit);
}
