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
 * Declared here so that listeners can be written against it; no code path
 * changes an owner yet, so nothing fires it in this release.
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
