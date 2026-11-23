/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import java.time.Instant;

/**
 * A library document as the attachment store keeps it: an uploaded file (a
 * root) whose owner is a library ({@link OwnerScope#USER}, later
 * {@link OwnerScope#TEAM}) instead of a conversation.
 * <p>
 * <b>Store-level only.</b> It carries the storage reference and the owner key,
 * which are capabilities and identifiers: it is never returned by REST or by a
 * tool as it is. The surfaces build their own views from it.
 *
 * @param storageRef
 *            the root's storage reference
 * @param title
 *            the title it was saved with
 * @param slug
 *            the last part of its qualified name, unique per owner
 * @param scope
 *            the owner's scope
 * @param ownerKey
 *            the owner key everything of the document is stored under
 * @param agentScope
 *            the agent it is visible to, or {@code null} for all of the owner's
 *            agents
 * @param contributedBy
 *            the user who put it into the library; {@code null} when unknown
 * @param filename
 *            the uploaded file's name
 * @param mimeType
 *            its validated MIME type
 * @param sizeBytes
 *            its size (the root only, not its derived views)
 * @param pageCount
 *            its page count when known, else {@code null}
 * @param savedAt
 *            when it entered this owner's library
 * @param lastAccessedAt
 *            when it was last opened ({@code savedAt} until then); retention
 *            counts from it
 * @param removedAt
 *            when it was soft-removed, or {@code null}
 * @param retentionDays
 *            its own retention, {@code null} for the deployment default,
 *            {@code 0} for none
 * @since 6.8.0
 */
public record LibraryEntry(String storageRef, String title, String slug, OwnerScope scope, String ownerKey, String agentScope,
        String contributedBy, String filename, String mimeType, long sizeBytes, Integer pageCount, Instant savedAt,
        Instant lastAccessedAt, Instant removedAt, Integer retentionDays) {

    /** Whether it is soft-removed: hidden, and purged after a grace period. */
    public boolean removed() {
        return removedAt != null;
    }
}
