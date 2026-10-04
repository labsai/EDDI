/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore;

/**
 * A turn's write was refused because it carried a cluster fencing token older
 * than one the conversation document has already seen: this node lost the
 * conversation's lease while the turn was running (it hung, was partitioned
 * from NATS, or its lease expired), and a turn on another node has committed
 * since. Applying the write would overwrite that turn.
 * <p>
 * Never retried — unlike a plain revision conflict, re-applying it is exactly
 * the zombie write fencing exists to stop. The turn is dead-lettered with its
 * input so an operator can replay it.
 */
public class ConversationFencedException extends IResourceStore.ResourceStoreException {

    private final String conversationId;
    private final long token;
    private final long storedFence;

    public ConversationFencedException(String conversationId, long token, long storedFence) {
        super("Conversation '" + conversationId + "' was taken over by another node — this turn's write (fencing token " + token
                + ") was refused because the conversation already carries token " + storedFence
                + "; the message was not stored");
        this.conversationId = conversationId;
        this.token = token;
        this.storedFence = storedFence;
    }

    public String getConversationId() {
        return conversationId;
    }

    public long getToken() {
        return token;
    }

    public long getStoredFence() {
        return storedFence;
    }
}
