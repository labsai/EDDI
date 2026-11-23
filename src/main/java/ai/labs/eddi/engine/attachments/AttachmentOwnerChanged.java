/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

/**
 * CDI event: a root — and with it its lineage — now belongs to another owner (a
 * file promoted to a library, or moved between libraries). Everything keyed by
 * the old owner key (caches, jobs, index chunks) must be re-keyed or rebuilt.
 * <p>
 * Fired by the attachment stores once per root, after the change committed: by
 * {@code IAttachmentStore.promote} (conversation to library) and
 * {@code moveOwner} (library to library). Observers must be quick and must not
 * throw; a failing one is logged and does not undo the change.
 *
 * @param rootRef
 *            the root whose owner changed
 * @param oldOwnerKey
 *            the owner key it had
 * @param newOwnerKey
 *            the owner key it has now
 * @param newScope
 *            the scope it has now
 * @since 6.8.0
 */
public record AttachmentOwnerChanged(String rootRef, String oldOwnerKey, String newOwnerKey, OwnerScope newScope) {
}
