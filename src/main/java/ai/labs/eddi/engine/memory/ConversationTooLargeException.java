/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore;

/**
 * A conversation write was refused before it reached the database because the
 * stored document would exceed {@code eddi.conversations.max-document-bytes}.
 * <p>
 * MongoDB refuses a document over 16 MB, and a conversation document grows with
 * every turn, so a long-lived conversation reaches that limit eventually. The
 * database's own refusal arrives as a driver exception after the turn has run;
 * this check runs first, on both backends, and gives the same refusal a stable
 * type. Nothing is written: the document keeps its previous turns, and the turn
 * that would have crossed the limit is reported to its caller as not stored.
 * <p>
 * A {@code ResourceStoreException} subtype, like
 * {@link ConcurrentConversationModificationException}, so every caller of the
 * conversation stores already handles it as a failed write.
 */
public class ConversationTooLargeException extends IResourceStore.ResourceStoreException {

    private final long estimatedBytes;
    private final long limitBytes;

    public ConversationTooLargeException(String conversationId, long estimatedBytes, long limitBytes) {
        super("Conversation '" + conversationId + "' was not written: the stored document would be about " + estimatedBytes
                + " bytes, above eddi.conversations.max-document-bytes=" + limitBytes);
        this.estimatedBytes = estimatedBytes;
        this.limitBytes = limitBytes;
    }

    /** The estimated size of the document the write would have produced. */
    public long getEstimatedBytes() {
        return estimatedBytes;
    }

    /** The configured limit it exceeded. */
    public long getLimitBytes() {
        return limitBytes;
    }
}
