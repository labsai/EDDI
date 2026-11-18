/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What {@link AttachmentGrantBackfill} needs from an attachment store to copy
 * the grants kept in the legacy shape (MongoDB {@code metadata.grants},
 * PostgreSQL {@code attachments.grants TEXT[]}) into {@code attachment_grants}.
 * Kept off {@link IAttachmentStore} on purpose: it exists for the releases that
 * carry both shapes, and nothing but the backfill may call it.
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
     * Creates the grant indexes that are not built on the request path (the MongoDB
     * grant indexes, the PostgreSQL index on the legacy column, built without
     * blocking writes). Idempotent; called by the backfill before it writes.
     */
    void ensureGrantIndexes();

    /**
     * Blobs with a non-empty legacy grant list, ordered by an internal key, the
     * first {@code limit} after {@code afterCursor}.
     *
     * @param afterCursor
     *            {@code null} for the first page, else a {@link Page#nextCursor()}
     */
    Page readLegacyGrants(String afterCursor, int limit);

    /**
     * Writes one grant in the new shape only — and only while the blob exists.
     * Idempotent.
     *
     * @return {@code true} if a row was added, {@code false} if it existed or the
     *         blob is gone
     */
    boolean copyGrant(String storageRef, String conversationId);

    /**
     * The grant rows already present in the new shape on the given blobs, one query
     * for a whole page — so a catch-up pass that finds a grant already copied asks
     * nothing more about it.
     *
     * @return per canonical reference, the conversations holding a row; a blob with
     *         none is absent
     */
    Map<String, Set<String>> existingGrants(Collection<String> storageRefs);

    /** Removes one grant row in the new shape. */
    void removeGrant(String storageRef, String conversationId);

    /**
     * Removes grant rows whose blob no longer exists — left behind when a node of
     * the previous release deleted the blob.
     *
     * @return the number of rows removed
     */
    long pruneGrantsWithoutBlob();

    /**
     * Where the backfill stopped, kept across starts; {@code null} = from the
     * start.
     */
    String readBackfillCursor();

    /** Keeps the backfill's position; {@code null} clears it. */
    void writeBackfillCursor(String cursor);
}
