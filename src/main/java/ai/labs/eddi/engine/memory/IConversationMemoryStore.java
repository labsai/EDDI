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

    /**
     * Bounded, projection-friendly listing of conversations awaiting human
     * approval. Implementations must NOT load the full conversation documents where
     * the backend supports field projection — this method backs a potentially
     * polled REST listing.
     *
     * @param limit
     *            maximum number of summaries to return
     * @return summaries of paused conversations (never null)
     * @throws IResourceStore.ResourceStoreException
     *             on persistence failures
     */
    List<ai.labs.eddi.engine.model.PendingApprovalSummary> findPendingApprovalSummaries(int limit)
            throws IResourceStore.ResourceStoreException;

    /**
     * Owner-filtered variant of {@link #findPendingApprovalSummaries(int)}. The
     * limit must apply AFTER restricting to the given owner, so a non-admin
     * caller's approval inbox cannot be starved by other users' backlog.
     * Implementations must push the owner filter into the query; this default
     * exists only as a bridge while the backends adopt it and inherits the
     * post-limit filtering weakness it is meant to remove.
     *
     * @param ownerUserId
     *            only summaries whose userId equals this value are returned
     * @param limit
     *            maximum number of summaries to return
     * @return summaries of paused conversations owned by the user (never null)
     * @throws IResourceStore.ResourceStoreException
     *             on persistence failures
     */
    default List<ai.labs.eddi.engine.model.PendingApprovalSummary> findPendingApprovalSummaries(
                                                                                                String ownerUserId, int limit)
            throws IResourceStore.ResourceStoreException {
        return findPendingApprovalSummaries(limit).stream()
                .filter(summary -> java.util.Objects.equals(ownerUserId, summary.getUserId()))
                .toList();
    }

    /**
     * Removes the persisted HITL pause bookmark fields from a conversation
     * document. Called when a pause is terminally resolved OUTSIDE resume (cancel,
     * end-while-paused) — a stale bookmark would otherwise round-trip through every
     * later snapshot store, mislead approval-status, and make crash recovery's
     * IN_PROGRESS classifier resurrect a pause nobody made.
     *
     * @param conversationId
     *            the conversation identifier
     * @throws IResourceStore.ResourceStoreException
     *             on persistence failures
     */
    void clearHitlBookmark(String conversationId) throws IResourceStore.ResourceStoreException;

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
