/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import java.util.List;

/**
 * What {@link AttachmentGrantBackfill} needs from an attachment store to copy
 * the grants kept in the legacy shape (MongoDB {@code metadata.grants},
 * PostgreSQL {@code attachments.grants TEXT[]}) into {@code attachment_grants}.
 * Kept off {@link IAttachmentStore} on purpose: it exists for one release, and
 * nothing but the backfill may call it.
 * <p>
 * Both stores implement it; {@code DataStoreProducers} exposes the active one.
 *
 * @since 6.8.0
 */
public interface IAttachmentGrantMigration {

    /**
     * The conversations a blob's legacy shape grants read access to.
     *
     * @param storageRef
     *            the blob's canonical reference — the one grant rows are keyed by
     * @param conversationIds
     *            the grantees, as stored
     */
    record LegacyGrants(String storageRef, List<String> conversationIds) {
    }

    /**
     * One page of blobs that carry a non-empty legacy grant list.
     *
     * @param blobs
     *            the page, in cursor order
     * @param nextCursor
     *            the cursor to pass for the next page, or {@code null} when this
     *            was the last one
     */
    record Page(List<LegacyGrants> blobs, String nextCursor) {
    }

    /**
     * Blobs with a non-empty legacy grant list, ordered by an internal key, the
     * first {@code limit} after {@code afterCursor}.
     *
     * @param afterCursor
     *            {@code null} for the first page, else a {@link Page#nextCursor()}
     */
    Page readLegacyGrants(String afterCursor, int limit);

    /**
     * Writes one grant in the new shape only. Idempotent.
     *
     * @return {@code true} if a row was added, {@code false} if it existed
     */
    boolean copyGrant(String storageRef, String conversationId);
}
