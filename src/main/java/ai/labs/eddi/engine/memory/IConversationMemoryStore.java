/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;

import java.util.List;

/**
 * @author ginccc
 */
public interface IConversationMemoryStore {
    String storeConversationMemorySnapshot(ConversationMemorySnapshot snapshot) throws IResourceStore.ResourceStoreException;

    ConversationMemorySnapshot loadConversationMemorySnapshot(String conversationId)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    List<ConversationMemorySnapshot> loadActiveConversationMemorySnapshot(String agentId, Integer agentVersion)
            throws IResourceStore.ResourceStoreException;

    void setConversationState(String conversationId, ConversationState conversationState);

    void deleteConversationMemorySnapshot(String conversationId)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    ConversationState getConversationState(String conversationId);

    Long getActiveConversationCount(String agentId, Integer agentVersion);

    List<String> getEndedConversationIds();

    // === HITL ===

    /**
     * Atomically transition conversation state from {@code expected} to
     * {@code target}. Returns true if the transition was performed, false if the
     * current state did not match {@code expected}.
     *
     * @param conversationId
     *            the conversation identifier
     * @param expected
     *            the state the conversation must currently be in
     * @param target
     *            the new state to set
     * @return true if the state was changed
     * @throws IResourceStore.ResourceStoreException
     *             on persistence failures
     */
    boolean compareAndSetState(String conversationId, ConversationState expected, ConversationState target)
            throws IResourceStore.ResourceStoreException;

    /**
     * Find all conversation IDs currently in the given state.
     *
     * @param state
     *            the state to filter by
     * @return list of matching conversation IDs (never null)
     * @throws IResourceStore.ResourceStoreException
     *             on persistence failures
     */
    List<String> findConversationIdsByState(ConversationState state)
            throws IResourceStore.ResourceStoreException;

    // === GDPR ===

    /**
     * Find all conversation IDs belonging to a specific user.
     *
     * @param userId
     *            the user identifier
     * @return list of conversation IDs
     */
    List<String> getConversationIdsByUserId(String userId);

    /**
     * Delete all conversations belonging to a specific user (GDPR Art. 17).
     *
     * @param userId
     *            the user identifier
     * @return number of conversations deleted
     */
    long deleteConversationsByUserId(String userId);
}
