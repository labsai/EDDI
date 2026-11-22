/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

/**
 * CDI event: an uploaded blob and its whole lineage are gone. Fired by the
 * attachment store once per root, after the delete succeeded — by
 * {@link IAttachmentStore#delete} and by
 * {@link IAttachmentStore#deleteByConversation}, so a conversation's erasure
 * announces every root it removed. Never fired for a derived blob.
 * <p>
 * What hangs off a root outside the store (a structure cache, document jobs,
 * memory cards, index chunks) listens for this and removes itself; the store
 * knows nothing about those systems. Observers run synchronously on the
 * deleting thread and must be quick; an observer that throws is logged and does
 * not undo the delete. Remove by {@code rootRef}, never by owner key alone.
 *
 * @param rootRef
 *            the deleted root's storage reference
 * @param scope
 *            whom it belonged to
 * @param ownerKey
 *            the owner key it was stored under (the conversation id for a
 *            conversation-owned blob)
 * @param ownerUserId
 *            the user who uploaded it, or {@code null} when unknown
 * @since 6.8.0
 */
public record AttachmentRootDeleted(String rootRef, OwnerScope scope, String ownerKey, String ownerUserId) {
}
