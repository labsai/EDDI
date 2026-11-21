/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.attachments.IAttachmentStore.OwnerRef;

import java.time.Duration;
import java.time.Instant;

/**
 * Quota held for a write that is still in flight — a streaming upload, a job
 * producing a file — so that the write does not have to hold the quota lock
 * while it runs. Taken with {@link IAttachmentStore#reserve}, it counts as
 * usage of its buckets until it is {@linkplain IAttachmentStore#convert
 * converted} into the blob it was taken for,
 * {@linkplain IAttachmentStore#release released}, or it expires.
 *
 * @param id
 *            the reservation's id, an unguessable random UUID
 * @param ownerKey
 *            the owner key of the bucket it is held in (the conversation id for
 *            {@link OwnerScope#CONVERSATION})
 * @param bytes
 *            the bytes held
 * @param count
 *            the files held
 * @param expiresAt
 *            when it stops counting, by the database's clock
 * @since 6.8.0
 */
public record QuotaReservation(String id, String ownerKey, long bytes, int count, Instant expiresAt) {

    /**
     * The longest a reservation may be held. A write that needs longer takes a new
     * one: a reservation outliving a crashed writer keeps the quota blocked until
     * it expires.
     */
    public static final Duration MAX_TTL = Duration.ofHours(24);

    /**
     * Checks the arguments of a reservation request, the same way on every store.
     *
     * @throws IllegalArgumentException
     *             for an owner other than a conversation (the library buckets do
     *             not exist yet), a conversation that is not the owner's, a
     *             negative amount, a reservation of nothing, or a lifetime shorter
     *             than 1 ms or longer than {@link #MAX_TTL}
     */
    public static void validateRequest(OwnerRef owner, String conversationId, long bytes, int count, Duration ttl) {
        if (owner == null || owner.scope() != OwnerScope.CONVERSATION) {
            throw new IllegalArgumentException("Only a conversation's quota can be reserved in this release");
        }
        if (conversationId == null || !conversationId.equals(owner.ownerKey())) {
            throw new IllegalArgumentException("A conversation-owned reservation is held in the owner's own conversation");
        }
        if (bytes < 0 || count < 0) {
            throw new IllegalArgumentException("A quota reservation cannot hold a negative amount");
        }
        if (bytes == 0 && count == 0) {
            throw new IllegalArgumentException("A quota reservation must hold bytes or files");
        }
        if (ttl == null || ttl.toMillis() < 1 || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("A quota reservation needs a lifetime between 1 ms and " + MAX_TTL);
        }
    }
}
