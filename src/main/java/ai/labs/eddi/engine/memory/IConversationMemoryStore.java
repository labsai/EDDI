/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.model.ConversationActivitySummary;
import ai.labs.eddi.engine.memory.model.ConversationGenerationCandidate;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import ai.labs.eddi.engine.memory.model.GenerationUpgradeOutcome;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.PendingApprovalSummary;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * The conversation's current state without its history — see
     * {@link ConversationMemoryTail} for exactly what it holds and what it may
     * omit. For reads that show only the current step (a chat reload, the
     * approval-status card); never for anything that persists, resumes or walks the
     * history.
     * <p>
     * The default loads the conversation in full and is only there so a test fake
     * keeps working; both stores override it with a projected read that does not
     * transfer the step history.
     *
     * @return the tail, or {@code null} when the conversation does not exist
     */
    default ConversationMemoryTail loadConversationMemoryTail(String conversationId)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        var snapshot = loadConversationMemorySnapshot(conversationId);
        return snapshot == null ? null : ConversationMemoryTail.ofFullSnapshot(snapshot);
    }

    /**
     * The full conversation for the approver view
     * ({@code approval-status?detail=full}), which serves it through
     * {@link ConversationMemoryUtilities#sanitizePendingToolCallsForApprover}. A
     * store may leave out the pending batch's resume machinery that projection
     * nulls anyway — {@code chatTranscriptJson},
     * {@code gatingAssistantMessageJson}, {@code traceSoFar}, {@code fingerprint},
     * each call's {@code argumentsRaw} — so a paused conversation's frozen LLM
     * transcript is not transferred only to be discarded. Everything else is the
     * full snapshot. Never persist or resume from it.
     * <p>
     * The default is the full load.
     *
     * @return the snapshot, or {@code null} when the conversation does not exist
     */
    default ConversationMemorySnapshot loadConversationMemorySnapshotForApprover(String conversationId)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return loadConversationMemorySnapshot(conversationId);
    }

    /**
     * The listing fields of several conversations in one read — the conversation
     * listing asks for a descriptor page's worth at a time instead of loading each
     * conversation in full.
     * <p>
     * The default loads each conversation in full and is only there so a test fake
     * keeps working; both stores override it with a single projected query.
     *
     * @param conversationIds
     *            the conversations to summarise
     * @return a summary per conversation that exists, keyed by its id; an id the
     *         store does not hold — or cannot hold, such as a malformed one — has
     *         no entry
     */
    default Map<String, ConversationListingSummary> loadListingSummaries(Collection<String> conversationIds)
            throws IResourceStore.ResourceStoreException {
        Map<String, ConversationListingSummary> summaries = new HashMap<>();
        for (String conversationId : conversationIds) {
            try {
                var snapshot = loadConversationMemorySnapshot(conversationId);
                if (snapshot != null) {
                    summaries.put(conversationId, ConversationListingSummary.of(snapshot, conversationId));
                }
            } catch (IResourceStore.ResourceNotFoundException e) {
                // not stored: no entry
            }
        }
        return summaries;
    }

    /**
     * Loads every open conversation of an agent <strong>in full</strong>. Memory
     * grows with the number of open conversations times their size (a production
     * 5.x deployment held tens of thousands of conversations of several hundred KB
     * each), so a sweep or listing over open conversations must use
     * {@link #loadOpenConversationActivity} instead.
     */
    List<ConversationMemorySnapshot> loadActiveConversationMemorySnapshot(String agentId, Integer agentVersion)
            throws IResourceStore.ResourceStoreException;

    /**
     * One batch of the open (not {@code ENDED}) conversations of an agent, as a
     * projection: id, state, agent, version and last-interaction time, never the
     * steps. Batches are keyset-paged in ascending id order, so a sweep that ends
     * conversations while it pages neither skips nor repeats one.
     *
     * @param agentId
     *            the agent
     * @param agentVersion
     *            one version, or {@code null} for every version
     * @param afterConversationId
     *            the last id of the previous batch, or {@code null} for the first
     *            batch
     * @param limit
     *            the largest batch to return
     * @return at most {@code limit} summaries; an empty list ends the paging
     */
    List<ConversationActivitySummary> loadOpenConversationActivity(String agentId, Integer agentVersion, String afterConversationId, int limit)
            throws IResourceStore.ResourceStoreException;

    /**
     * The activity projection of one conversation — the re-check a sweep makes
     * right before it ends a conversation, without loading it in full.
     *
     * @return the summary, or {@code null} when the conversation does not exist
     */
    ConversationActivitySummary loadConversationActivity(String conversationId) throws IResourceStore.ResourceStoreException;

    /**
     * One keyset-paged batch of the conversations of an agent whose compatibility
     * generation is absent or lower than {@code belowGeneration} — the candidates
     * of a generation upgrade — as a projection (id, state, version, generation),
     * never the steps. <strong>Ended conversations are excluded</strong>: they are
     * never written, so they would stay candidates forever and crowd out the ones
     * that can move; count them with
     * {@link #countEndedGenerationUpgradeCandidates}. The generation filter runs in
     * the database, so only candidates are read. Ascending id order, so a sweep
     * neither skips nor repeats a conversation while it pages.
     *
     * @param agentId
     *            the agent
     * @param agentVersions
     *            restrict to these stored versions, or {@code null}/empty for every
     *            version
     * @param belowGeneration
     *            only conversations with no generation, or one lower than this
     * @param afterConversationId
     *            the last id of the previous batch, or {@code null} for the first
     * @param limit
     *            the largest batch to return
     * @return at most {@code limit} candidates; an empty list ends the paging
     */
    List<ConversationGenerationCandidate> loadGenerationUpgradeCandidates(String agentId, Collection<Integer> agentVersions, int belowGeneration,
                                                                          String afterConversationId, int limit)
            throws IResourceStore.ResourceStoreException;

    /**
     * Whether {@code id} can name a conversation in this store at all (an ObjectId
     * for MongoDB, a UUID for PostgreSQL). Lets a caller refuse a user-supplied id,
     * such as a paging cursor, before it reaches a query.
     */
    boolean isValidConversationId(String id);

    /**
     * How many ended conversations of the agent would be candidates but for being
     * ended — same filters as {@link #loadGenerationUpgradeCandidates}, grouped by
     * stored agent version, in one count query.
     *
     * @return count per stored version; versions with none are absent
     */
    Map<Integer, Long> countEndedGenerationUpgradeCandidates(String agentId, Collection<Integer> agentVersions, int belowGeneration)
            throws IResourceStore.ResourceStoreException;

    /**
     * Moves one conversation onto {@code targetGeneration} — the <em>only</em>
     * field this writes, besides the revision markers below. Nothing is loaded and
     * no step is touched.
     * <p>
     * <strong>One atomic conditional write.</strong> It matches only a conversation
     * of {@code agentId}, in one of
     * {@link GenerationUpgradeOutcome#UPGRADABLE_STATES}, whose generation is
     * absent or lower than the target — so a generation is never lowered, an ended
     * or paused conversation is never touched, and a conversation that changed
     * since it was listed is reported instead of overwritten.
     * <p>
     * <strong>It moves the revision and the history revision.</strong> That is what
     * keeps a turn that loaded the conversation <em>before</em> this write from
     * putting the old generation back: such a turn's full-document write is refused
     * on the revision guard, its append is refused because the history revision
     * moved past the one it loaded, and a turn still waiting in the coordinator
     * sees the newer revision when it starts and is rebuilt over the current
     * document (see {@code ConversationStepRunner.reloadIfSuperseded}). Every one
     * of them is a detectable outcome and none is a silent revert. Callers still
     * serialise this write with the conversation's turns through the coordinator,
     * so in practice no turn is in flight to be refused.
     *
     * @param conversationId
     *            the conversation
     * @param agentId
     *            the agent it must belong to
     * @param targetGeneration
     *            the generation to write
     * @return {@link GenerationUpgradeOutcome#UPGRADED}, or why nothing was written
     */
    GenerationUpgradeOutcome upgradeCompatibilityGeneration(String conversationId, String agentId, int targetGeneration)
            throws IResourceStore.ResourceStoreException;

    /**
     * Ends idle conversations in the database, in one pass: every {@code READY}
     * conversation whose last interaction is older than {@code idleSince} (and that
     * belongs to {@code agentId}, when given) gets state {@code ENDED} and the
     * {@code endReason}. Nothing is loaded into the application.
     * <p>
     * Only {@code READY} is touched: a paused ({@code AWAITING_HUMAN}) conversation
     * is a live pending approval that needs the HITL-aware end, and an
     * {@code IN_PROGRESS} one is running. A conversation that carries no timestamp
     * at all cannot be proven idle and is left alone. Like
     * {@link #setConversationState}, the write is narrow: it does not move the
     * document revision, and it does not touch the conversation descriptor.
     *
     * @param idleSince
     *            conversations last active strictly before this instant qualify
     * @param agentId
     *            restrict to one agent, or {@code null} for every agent
     * @param endReason
     *            the reason to record, or {@code null} for none
     * @param dryRun
     *            count what would be ended without ending anything
     * @return how many conversations were ended (or would be, for a dry run)
     */
    long endIdleConversations(Instant idleSince, String agentId, String endReason, boolean dryRun) throws IResourceStore.ResourceStoreException;

    void setConversationState(String conversationId, ConversationState conversationState);

    /**
     * Record why a conversation ended, as a narrow field update that — like
     * {@link #setConversationState} — does not bump the document revision.
     * Best-effort by contract: a caller ends the conversation first and a failure
     * here must not undo that.
     */
    void setConversationEndReason(String conversationId, String endReason);

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

    /**
     * The persisted state and revision of a conversation, in ONE projection read.
     *
     * @param state
     *            the stored state, or {@code null} when the conversation does not
     *            exist
     * @param revision
     *            as {@link #getRevision}: {@code null} means "unknown", never
     *            "changed"
     */
    record StateAndRevision(ConversationState state, Long revision) {
    }

    /**
     * {@link #getConversationState} and {@link #getRevision} together, as one round
     * trip instead of two. A queued turn needs both when it starts (the queued-say
     * guard and the stale-snapshot guard); asking twice cost a second wait for a
     * database connection on every turn.
     *
     * @return both values; {@code null} when this store cannot answer them in one
     *         read, in which case the caller asks the two methods separately — so a
     *         store (or a test double) that predates this method behaves exactly as
     *         before
     */
    default StateAndRevision getStateAndRevision(String conversationId) {
        return null;
    }

    Long getActiveConversationCount(String agentId, Integer agentVersion);

    /**
     * The agent's open conversations grouped by their own compatibility generation,
     * across every agent version — counts only, no content.
     * <p>
     * Counted by what a conversation <em>is</em>, not by the version it last ran
     * on: a conversation follows the newest ready version of its own generation, so
     * after a few compatible saves many idle conversations are stored on versions
     * that are no longer deployed and would be missed by a count per deployed
     * version. A conversation without a generation (it predates generations) is
     * counted under the {@code null} key. Conversations that are ENDED or
     * AWAITING_HUMAN are left out, exactly as in
     * {@link #getActiveConversationCount}.
     *
     * @param agentId
     *            the agent
     * @return open conversations per generation; empty when there are none
     */
    Map<Integer, Long> getActiveConversationCountsByGeneration(String agentId);

    /**
     * How much an agent is used, across all its versions — counts only, no content.
     * What an agent's maintainers may see without being able to read anybody's
     * conversation.
     *
     * @param total
     *            conversations ever started
     * @param active
     *            conversations not yet ended
     * @param distinctUsers
     *            how many different users started them
     */
    record ConversationUsage(long total, long active, long distinctUsers) {
    }

    /** Usage counts for one agent — see {@link ConversationUsage}. */
    ConversationUsage getConversationUsage(String agentId);

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
     * Cluster mode: record that the lease holder carrying {@code fence} has started
     * its turn, by raising the document's {@code _fence} to at least that value
     * (never lowering it, never touching the revision).
     * <p>
     * Fenced writes refuse a token older than the stored one, but the stored one
     * only moves when a write lands. A holder whose lease expired (a stalled JVM, a
     * hung pipeline thread the watchdog abandoned) could therefore still write in
     * the gap between its successor acquiring the lease and the successor's first
     * write, interleaving a turn that never saw the successor's. Raising the fence
     * when the successor starts closes that gap: from then on the predecessor's
     * write is refused. No-op for stores without fencing and for a missing
     * document.
     *
     * @param conversationId
     *            the conversation
     * @param fence
     *            the lease's fencing token
     * @throws IResourceStore.ResourceStoreException
     *             on persistence failures
     */
    default void raiseFence(String conversationId, long fence) throws IResourceStore.ResourceStoreException {
        // single-node stores have nothing to fence
    }

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
