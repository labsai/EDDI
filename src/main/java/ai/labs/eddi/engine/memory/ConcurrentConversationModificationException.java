/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore;

/**
 * A conversation document was modified by another writer between the load this
 * write started from and the write itself, so the write was refused instead of
 * applied.
 * <p>
 * This is the loud replacement for a silent lost update. Before the revision
 * guard existed, a turn that loaded the conversation, appended its step and
 * wrote the whole document back matched on {@code _id} alone: two turns whose
 * load/save windows overlapped both started from the same snapshot and the last
 * writer won, erasing the earlier turn's step while both stores reported
 * success. Measured on a live 6.4.0 instance, three back-to-back turns (each
 * awaiting its own HTTP 200) produced a conversation holding turns 1 and 3 with
 * turn 2 absent entirely.
 * <p>
 * A {@code ResourceStoreException} subtype rather than a new checked type:
 * every caller of {@code storeConversationMemorySnapshot} already handles that,
 * so existing paths keep failing safely (they no longer silently succeed) and
 * only the paths that want to react to a conflict specifically need to catch
 * this.
 */
public class ConcurrentConversationModificationException extends IResourceStore.ResourceStoreException {

    private final String conversationId;
    private final long expectedRevision;

    public ConcurrentConversationModificationException(String conversationId, long expectedRevision) {
        super("Conversation '" + conversationId + "' was modified concurrently — the write was refused and NOT applied. "
                + "This write started from revision " + expectedRevision
                + ", which is no longer the stored revision (another turn, resume, undo/redo or instance committed first).");
        this.conversationId = conversationId;
        this.expectedRevision = expectedRevision;
    }

    public String getConversationId() {
        return conversationId;
    }

    /**
     * The revision this write was derived from, i.e. the one that no longer
     * matched.
     */
    public long getExpectedRevision() {
        return expectedRevision;
    }
}
