/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.PendingApprovalSummary;

import java.util.List;
import java.util.Objects;

/**
 * @author ginccc
 */
public interface IConversationMemoryStore {
    /**
     * Persist the full snapshot under optimistic concurrency.
     * <p>
     * A full-document write whose snapshot is not itself {@code ENDED} is also
     * refused while the stored conversation is {@code ENDED}: ending a conversation
     * is a narrow state write that does not move the revision, so the revision
     * guard alone would let a turn that was already running (a rerun, or a memory
     * whose append baseline is unknown) replace the terminal state and resurrect
     * the conversation. The refusal is reported like any other conflict.
     * <p>
     * The write is guarded on {@link ConversationMemorySnapshot#getRevision()} —
     * the revision the snapshot was loaded at — and increments it. A snapshot whose
     * conversationId is {@code null} is inserted instead, at revision 1.
     * <p>
     * <strong>The guard is the point.</strong> Without it the write matched on the
     * conversation id alone, so two turns whose load/save windows overlapped both
     * started from the same snapshot and the last writer won: the earlier turn's
     * step vanished while both writes reported success and both callers had already
     * been handed their reply. Implementations MUST refuse such a write rather than
     * apply it, and MUST distinguish the two zero-match causes — a
     * {@link ConcurrentConversationModificationException} when the conversation is
     * still there at a different revision (a retry from a fresh load can still
     * land), and a plain {@link IResourceStore.ResourceStoreException} when it is
     * gone (deleted mid-turn — nothing to retry against).
     * <p>
     * On refusal, implementations MUST leave {@code snapshot.getRevision()} at the
     * value they were called with, so a caller that retries re-presents the
     * revision it actually loaded.
     *
     * @param snapshot
     *            the full conversation snapshot to persist
     * @return the conversation id (generated on insert)
     * @throws ConcurrentConversationModificationException
     *             another writer committed first; nothing was written
     * @throws IResourceStore.ResourceStoreException
     *             the conversation no longer exists, or the write failed
     */
    String storeConversationMemorySnapshot(ConversationMemorySnapshot snapshot) throws IResourceStore.ResourceStoreException;

    /**
     * Store the full snapshot ONLY IF the conversation is still in
     * {@code expectedState} <em>and</em> still holds the snapshot's
     * {@link ConversationMemorySnapshot#getRevision() revision} — an atomic
     * compare-and-store on both. The state half stops a concurrent terminal writer
     * from being overwritten; the revision half stops a concurrent NON-terminal
     * writer from being overwritten, which the state filter alone cannot detect
     * because both writers leave the same state behind. Returns true if the
     * snapshot was persisted, false if the current persisted state no longer
     * matched (a concurrent terminal writer won).
     * <p>
     * The resume path uses this to persist a resumed outcome (which flips the state
     * away from IN_PROGRESS) without clobbering an ENDED/EXECUTION_INTERRUPTED
     * state written concurrently by {@code end}/{@code cancel}. A plain
     * full-document store overwrites the whole row, so it would replace the
     * terminal state with a non-terminal one and resurrect a terminated
     * conversation.
     *
     * @param snapshot
     *            the full conversation snapshot to persist
     * @param expectedState
     *            the state the conversation must currently be in for the store to
     *            proceed
     * @return true if the snapshot was persisted; false if the CAS precondition
     *         failed
     * @throws IResourceStore.ResourceStoreException
     *             on persistence failures
     */
    boolean storeConversationMemorySnapshotIfState(ConversationMemorySnapshot snapshot, ConversationState expectedState)
            throws IResourceStore.ResourceStoreException;

    ConversationMemorySnapshot loadConversationMemorySnapshot(String conversationId)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    List<ConversationMemorySnapshot> loadActiveConversationMemorySnapshot(String agentId, Integer agentVersion)
            throws IResourceStore.ResourceStoreException;

    void setConversationState(String conversationId, ConversationState conversationState);

    void deleteConversationMemorySnapshot(String conversationId)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    ConversationState getConversationState(String conversationId);

    /**
     * The optimistic-concurrency revision the stored conversation currently holds —
     * a projection read, never the whole document.
     * <p>
     * A queued turn uses it to learn whether the memory it was loaded with has been
     * superseded while it waited behind an earlier turn of the same conversation: a
     * turn built on the older snapshot would evaluate its rules, its LLM history
     * and its property writes without the earlier turn, and its commit would
     * re-apply the stale properties over the ones that turn wrote.
     *
     * @param conversationId
     *            the conversation identifier
     * @return the stored revision
     *         ({@link ConversationMemorySnapshot#UNVERSIONED_REVISION} for a
     *         document written before revisions existed), or {@code null} when the
     *         conversation does not exist or the backend cannot answer — callers
     *         must treat {@code null} as "unknown", not as "changed"
     */
    Long getRevision(String conversationId);

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
    List<PendingApprovalSummary> findPendingApprovalSummaries(int limit)
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
    default List<PendingApprovalSummary> findPendingApprovalSummaries(
                                                                      String ownerUserId, int limit)
            throws IResourceStore.ResourceStoreException {
        return findPendingApprovalSummaries(limit).stream()
                .filter(summary -> Objects.equals(ownerUserId, summary.getUserId()))
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
