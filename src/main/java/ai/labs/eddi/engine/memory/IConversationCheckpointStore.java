/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.MemoryCheckpoint;

import java.util.List;

/**
 * Store for conversation memory checkpoints. Supports both MongoDB and
 * PostgreSQL backends via the DB-agnostic pattern in
 * {@code DataStoreProducers}.
 *
 * @since 6.0.0
 */
public interface IConversationCheckpointStore {

    /**
     * Create a new checkpoint.
     *
     * @param checkpoint
     *            the checkpoint to store
     */
    void create(MemoryCheckpoint checkpoint);

    /**
     * Find all checkpoints for a conversation, ordered by creation time (newest
     * first).
     *
     * @param conversationId
     *            the conversation ID
     * @param limit
     *            maximum number of checkpoints to return
     * @return list of checkpoints
     */
    List<MemoryCheckpoint> findByConversationId(String conversationId, int limit);

    /**
     * Find a specific checkpoint by ID.
     *
     * @param checkpointId
     *            the checkpoint ID
     * @return the checkpoint, or null if not found
     */
    MemoryCheckpoint findById(String checkpointId);

    /**
     * Delete a specific checkpoint.
     *
     * @param checkpointId
     *            the checkpoint ID
     */
    void deleteById(String checkpointId);

    /**
     * Prune oldest checkpoints for a conversation, keeping only the newest N.
     *
     * @param conversationId
     *            the conversation ID
     * @param keepCount
     *            number of newest checkpoints to keep
     * @return number of checkpoints deleted
     */
    int pruneOldest(String conversationId, int keepCount);

    /**
     * Delete all checkpoints for a conversation. Used during GDPR erasure.
     *
     * @param conversationId
     *            the conversation ID
     * @return number of checkpoints deleted
     */
    long deleteByConversationId(String conversationId);
}
