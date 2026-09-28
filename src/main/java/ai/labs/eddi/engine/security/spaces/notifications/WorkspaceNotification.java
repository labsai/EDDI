/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.notifications;

import java.time.Instant;

/**
 * Something about sharing that happened to somebody, waiting for them in the
 * Manager.
 *
 * @param id
 *            opaque identifier
 * @param recipient
 *            the principal it is for
 * @param type
 *            what happened
 * @param resourceId
 *            the resource it concerns
 * @param resourceUri
 *            that resource's {@code eddi://} URI, from which a client builds a
 *            link — or null when unknown
 * @param resourceName
 *            its name at the time, so the notification still reads sensibly
 *            after a rename or a revoke
 * @param actor
 *            the principal who caused it — who shared, or who asked
 * @param actorLabel
 *            that person's name at the time
 * @param level
 *            the access level shared or asked for
 * @param message
 *            a short note the actor wrote, for an access request; plain text,
 *            bounded
 * @param createdAt
 *            when
 * @param readAt
 *            when the recipient dismissed it, or null while unread
 */
public record WorkspaceNotification(String id, String recipient, Type type, String resourceId, String resourceUri, String resourceName,
        String actor, String actorLabel, String level, String message, Instant createdAt, Instant readAt) {

    /** What a notification reports. */
    public enum Type {
        /**
         * Somebody shared a resource with the recipient, or with a team they are in.
         */
        SHARED_WITH_YOU,
        /** Somebody asked the recipient, as owner, for access to a resource. */
        ACCESS_REQUESTED
    }

    /** Whether the recipient has not dismissed it yet. */
    public boolean unread() {
        return readAt == null;
    }
}
