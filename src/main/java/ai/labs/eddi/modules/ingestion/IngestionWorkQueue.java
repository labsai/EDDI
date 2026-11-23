/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import java.time.Instant;
import java.util.List;

/**
 * The durable per-document work table of a source: what discovery listed and
 * the drain has not processed yet.
 *
 * <p>
 * It lives on the document-state rows themselves
 * ({@link IIngestionStateStore}), keyed {@code (source, document)} like them,
 * so one row says both what a document was last ingested as and what is still
 * to be done with it. A run lists the source and <em>enqueues</em> every
 * document that needs fetching; it then <em>claims</em> due rows in batches and
 * processes them one by one. Whatever a run does not finish stays in the table:
 * a crash, a deploy or the end of the time budget loses at most the rows a run
 * had claimed, and those become claimable again once their claim expires.
 *
 * <h2>The rules</h2>
 *
 * <ul>
 * <li><b>Enqueueing is idempotent.</b> A document already queued (pending,
 * claimed or failed for good) under the same version token keeps its row as it
 * is — its {@code notBefore} and attempt count included. Re-listing a source
 * must not undo a throttle's deferral, nor re-arm a poisoned document. A new
 * version token re-arms the row and bumps its {@code workVersion}.</li>
 * <li><b>Enqueueing counts as seeing.</b> It stamps the row with the listing
 * run's id, as {@link IIngestionStateStore#recordUnreachable} does, so a
 * document the listing found but the drain has not reached is never counted as
 * missed. It never touches the content hash, the validators, the miss counter
 * or the tombstone: a queued document is not an ingested one. A document the
 * source has never held gets a row that is {@code tombstoned} with no content
 * hash — it has no vectors, and every reader already treats such a row as
 * "embed me".</li>
 * <li><b>Claims are exclusive.</b> A claim moves a row to {@code CLAIMED} in
 * one atomic statement per row, so two claimers — two threads, two nodes —
 * never hold the same row. A claim whose lease ({@code claimUntil}) has passed
 * can be claimed again: that is how a crashed run's rows come back. Claims are
 * fenced like document writes: only the run that owns the source's rows can
 * claim them.</li>
 * <li><b>Completion is conditional</b> on the claimer and the
 * {@code workVersion} it claimed. When discovery queued a newer version while a
 * worker held the old one, the worker's completion matches nothing and the
 * newer work stays pending.</li>
 * <li><b>Deferring is not failing.</b> {@link #deferWork} moves a row's
 * {@code notBefore} and leaves its attempts alone — for a throttle, a budget
 * stop or lost ownership. Only {@link #failWork} counts an attempt, and a row
 * that reaches the cap is poisoned until its version changes or
 * {@link #retryFailedWork} re-arms it.</li>
 * </ul>
 */
public interface IngestionWorkQueue {

    /** Where a row's work stands. Null on a row that never had any. */
    enum WorkState {
        /** Waiting for a claim once {@code notBefore} has passed. */
        PENDING,
        /** Held by a claimer until {@code claimUntil}. */
        CLAIMED,
        /** The last work queued for the row was processed. */
        DONE,
        /** Failed {@code maxAttempts} times; re-armed only by a new version. */
        POISON;

        /** Reads a stored state, tolerating one this build does not know. */
        public static WorkState parse(String stored) {
            if (stored == null) {
                return null;
            }
            try {
                return valueOf(stored);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * One document to queue.
     *
     * @param versionToken
     *            the version discovery saw (a Drive checksum, an upload's hash);
     *            null when the source has none. Two nulls are equal: the drain
     *            fetches the current content whatever was listed, so re-queueing
     *            would only reset the row's deferral
     * @param payload
     *            what the drain needs to rebuild the work item — the connector's
     *            hints and the citation — as opaque text, at most
     *            {@link #MAX_PAYLOAD_LENGTH} characters
     */
    record WorkRequest(String documentId, String versionToken, String payload) {
    }

    /** The longest payload a row carries. */
    int MAX_PAYLOAD_LENGTH = 16_384;

    /** What {@link #enqueueWork} did. */
    enum EnqueueOutcome {
        /** The row now has pending work at this version. */
        QUEUED,
        /**
         * Equal work was already queued (or poisoned); the row only got the run stamp.
         */
        ALREADY_QUEUED,
        /** The row belongs to another run; nothing was written. */
        FENCED
    }

    /** A claimed row, handed to the drain. */
    record WorkClaim(String documentId, long workVersion, String versionToken, String payload, int attempts) {
    }

    /** What {@link #failWork} did. */
    enum FailOutcome {
        /** The row waits for another attempt after its backoff. */
        RETRY,
        /** The row reached the attempt cap and is poisoned. */
        POISONED,
        /** The claim was no longer this claimer's, or the version moved on. */
        NOT_CLAIMED
    }

    /**
     * Counts by work state, for progress.
     *
     * @param queued
     *            pending rows that may be claimed now
     * @param waiting
     *            pending rows whose {@code notBefore} is still ahead — deferred by
     *            a throttle, a budget or a backoff
     * @param inFlight
     *            claimed rows
     * @param done
     *            rows whose last work was processed
     * @param failed
     *            poisoned rows
     * @param nextAttemptAt
     *            the earliest {@code notBefore} of a waiting row; null when none
     *            waits
     */
    record WorkCounts(long queued, long waiting, long inFlight, long done, long failed, Instant nextAttemptAt) {

        public static final WorkCounts NONE = new WorkCounts(0, 0, 0, 0, 0, null);

        /** Rows that still need a run: queued, waiting or in flight. */
        public long outstanding() {
            return queued + waiting + inFlight;
        }
    }

    /**
     * Queues one document for the drain — see the interface for when this is a
     * no-op. Fenced on {@code runId} like every document write.
     */
    EnqueueOutcome enqueueWork(String sourceId, String runId, WorkRequest request, Instant now);

    /**
     * Claims up to {@code limit} due rows for {@code claimer}, oldest
     * {@code notBefore} first: pending rows due by {@code now}, and claimed rows
     * whose lease passed. Only rows {@code claimer} owns are claimed.
     */
    List<WorkClaim> claimWork(String sourceId, String claimer, int limit, Instant now, Instant claimUntil);

    /** Marks a claimed row done. Conditional on the claimer and the version. */
    boolean completeWork(String sourceId, String documentId, String claimer, long workVersion);

    /**
     * Returns a claimed row to pending, due at {@code notBefore}, with its attempts
     * unchanged. Conditional on the claimer and the version.
     */
    boolean deferWork(String sourceId, String documentId, String claimer, long workVersion, Instant notBefore);

    /**
     * Counts a failed attempt: the row waits until {@code notBefore}, or is
     * poisoned once it has failed {@code maxAttempts} times. Conditional on the
     * claimer and the version.
     *
     * @param error
     *            why, kept on the row; truncated
     */
    FailOutcome failWork(String sourceId, String documentId, String claimer, long workVersion, String error,
                         Instant notBefore, int maxAttempts);

    /**
     * Returns every row {@code claimer} still holds to pending, due when it was due
     * before. Called when a run ends, so its unprocessed claims need not wait for
     * their lease.
     *
     * @return how many rows were released
     */
    int releaseClaims(String sourceId, String claimer);

    /**
     * Withdraws the pending and poisoned work of every document this run's complete
     * listing did not see: they are not in the source, or not in its scope, any
     * more. Call it only for a listing that covered the whole source — a partial
     * one saw an arbitrary subset. Fenced on {@code runId}.
     *
     * @return how many rows lost their work
     */
    int cancelUnlistedWork(String sourceId, String runId);

    /** Counts by work state; {@link WorkCounts#NONE} for a source without work. */
    WorkCounts workCounts(String sourceId, Instant now);

    /** Re-arms every poisoned row of a source, due at once with no attempts. */
    int retryFailedWork(String sourceId, Instant now);
}
