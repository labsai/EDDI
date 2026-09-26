/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Per-source record of what has been ingested, and the history of the runs that
 * did it.
 *
 * <p>
 * Ingestion is a reconciliation, not an append: every run compares what a
 * source offers now against what the knowledge base already holds, and has to
 * decide per document whether to skip it, re-embed it, or conclude it is gone.
 * That decision needs durable state, and getting its shape wrong corrupts a
 * knowledge base quietly — the failure mode is an answer citing text that was
 * deleted from the source months ago, with nothing in any log.
 *
 * <h2>Three rules that are easy to get wrong</h2>
 *
 * <p>
 * <b>Record a document only after its vectors are safely stored.</b>
 * {@link #recordIngested} exists separately from {@link #lookup} for exactly
 * this reason. A store that writes the new hash while *deciding* whether to
 * ingest will mark a document done even when the embedding call afterwards
 * fails — and because the hash now matches, every later run reports it
 * "unchanged" and it is never embedded again. One provider timeout silently
 * costs you a page, permanently.
 *
 * <p>
 * <b>A document missing from one run is not a deleted document.</b> Sites go
 * down, crawls hit their page cap, networks blip. {@link #tombstoneMissing}
 * therefore counts consecutive misses and only tombstones after a threshold,
 * and callers must not call it at all for a run that failed. Tombstoning is
 * what drives vector deletion, so a hair trigger here empties a knowledge base
 * because a site was briefly unreachable.
 *
 * <p>
 * <b>A run that has been superseded must not write anything.</b> A worker can
 * stall past the stale threshold, have its run reaped by
 * {@link #reapStaleRuns}, and wake up afterwards — by which time a replacement
 * run owns the source. Its late {@code record*} calls would land on rows that
 * belong to the new run. The worst case is not a miscounted run: a document the
 * previous run tombstoned had its vectors deleted, so a stale
 * {@link #recordIngested} that clears the tombstone and restores the old hash
 * makes every later run report the page "unchanged" and it is never embedded
 * again — the page is gone from retrieval for good, with nothing in any log.
 * Implementations must therefore <b>fence</b> document writes, as described
 * below.
 *
 * <h2>The fence</h2>
 *
 * <p>
 * Every document row records which run <em>owns</em> it. Ownership changes in
 * exactly two places, and both are single statements the database applies
 * atomically:
 *
 * <ul>
 * <li>{@link #startRun} takes ownership of the source's document rows for the
 * run it has just claimed.</li>
 * <li>{@link #reapStaleRuns} releases ownership when it actually reaps
 * something, so a reaped worker is fenced from that moment rather than only
 * once a replacement claims the source.</li>
 * </ul>
 *
 * <p>
 * Every {@code record*} and {@link #tombstoneMissing} call then carries its
 * {@code runId} into the update's own filter, so a write from a run that no
 * longer owns the source matches nothing. The caller reads no extra state and
 * makes no extra round trip: the {@code runId} it already passes <em>is</em>
 * the fencing token.
 *
 * <p>
 * A fenced write is a no-op, not an exception. The reaper has already decided
 * this run is dead and {@link #finishRun} logs that its result was discarded;
 * failing every document of a doomed crawl would only add noise to a run whose
 * outcome is thrown away anyway.
 *
 * <p>
 * <b>What the fence does not cover.</b> A document the superseded run is the
 * first ever to see has no row to own, so its insert still lands. That is the
 * benign direction — it adds a document rather than deleting or hiding one, and
 * the owning run's {@link #tombstoneMissing} reconciles it away over the
 * following runs.
 */
public interface IIngestionStateStore {

    /**
     * What is known about one document of one source. Absent means never
     * successfully ingested.
     */
    Optional<DocumentState> lookup(String sourceId, String documentId);

    /**
     * Records that a document was fetched, embedded and stored successfully.
     *
     * <p>
     * Call this <em>after</em> the vectors are in the embedding store, never before
     * or while deciding — see the interface Javadoc. Also counts as seeing the
     * document: the miss counter resets and any tombstone is lifted.
     *
     * <p>
     * Fenced on {@code runId}: ignored when the run no longer owns the source. That
     * is what stops a reaped worker lifting a tombstone whose vectors are already
     * deleted.
     *
     * @param contentHash
     *            hash of the converted content, from {@link ContentHashes}
     * @param etag
     *            the source's ETag, for a conditional fetch next run; may be null
     * @param lastModified
     *            the source's Last-Modified, same purpose; may be null
     */
    void recordIngested(String sourceId, String documentId, String contentHash,
                        String etag, String lastModified, String runId);

    /**
     * Records that a document still exists and has not changed, so it keeps its
     * hash but is not treated as missing. Resets the miss counter and lifts any
     * tombstone.
     *
     * <p>
     * Fenced on {@code runId}, and ignored for a document with no row — both are
     * no-ops.
     */
    void recordSeen(String sourceId, String documentId, String runId);

    /**
     * {@link #recordSeen}, for a document whose body was downloaded again and
     * turned out unchanged — and so also replaces its stored validators with the
     * ones this response carried.
     *
     * <p>
     * Without it the ETag and Last-Modified of the first ingest were kept for ever.
     * A server that rotates its ETag without changing the text (a build id in the
     * validator, markup outside the main content) answered every later conditional
     * request with a full 200, because the validator sent back was one it no longer
     * recognised — so an unchanged page never cost a 304 again. A response without
     * validators clears them: sending back what the server stopped issuing buys
     * nothing.
     *
     * <p>
     * Fenced on {@code runId} and ignored for a document with no row, exactly like
     * {@link #recordSeen}.
     */
    void recordSeen(String sourceId, String documentId, String runId, String etag, String lastModified);

    /**
     * Records that this run could not find out whether a document still exists —
     * the server refused, failed, or asked us to come back later.
     *
     * <p>
     * Neither a sighting nor a miss. The document keeps its miss counter and its
     * hash, but this run no longer counts against it, so a page behind a 503, a 429
     * or a WAF is not deleted for being unreachable. Without this, the same tail
     * pages of a rate-limited site are tombstoned after
     * {@code tombstoneAfterMissedRuns} runs while every run reports success.
     *
     * <p>
     * Fenced on {@code runId}. Stamping the run marker is exactly how a document
     * escapes the owning run's miss count, so a stale worker allowed to stamp it
     * would hand a page the owning run had just seen back to
     * {@link #tombstoneMissing}.
     */
    void recordUnreachable(String sourceId, String documentId, String runId);

    /**
     * Increments the miss counter for every live document this run did not see, and
     * tombstones those that have now been missed {@code missedRunsThreshold} times
     * in a row.
     *
     * <p>
     * Only call this for a run that completed — a failed or aborted run saw an
     * arbitrary subset of the source and would tombstone the remainder.
     *
     * <p>
     * Fenced on {@code runId}: a superseded run tombstones nothing and is handed
     * back an empty list, so it deletes no vectors.
     *
     * @return the documents tombstoned by this call, whose vectors the caller is
     *         then responsible for removing
     */
    default List<DocumentState> tombstoneMissing(String sourceId, String runId, int missedRunsThreshold) {
        List<DocumentState> missing = bumpAndFindMissing(sourceId, runId, missedRunsThreshold);
        markTombstoned(sourceId, missing.stream().map(DocumentState::documentId).toList());
        // Restamped, because bumpAndFindMissing reports its candidates BEFORE they
        // are marked and DocumentState is immutable -- so the list still says
        // tombstoned=false although marking has just succeeded. A caller that
        // believed it would re-report the same documents on the next run.
        return missing.stream().map(DocumentState::asTombstoned).toList();
    }

    /**
     * Counts this run's misses and returns the documents that have now been missed
     * often enough to be considered gone — <em>without</em> tombstoning them.
     *
     * <p>
     * Split from the marking so a caller can remove the vectors first. Marking
     * first is durable in the wrong order: a crash, or a store that refuses the
     * delete, leaves a document flagged as gone while its chunks stay retrievable,
     * and a tombstoned document is never reported again — so nothing would ever
     * remove them.
     */
    List<DocumentState> bumpAndFindMissing(String sourceId, String runId, int missedRunsThreshold);

    /**
     * Marks documents gone, after their vectors have actually been removed. Safe to
     * call with an empty list, and safe to repeat.
     */
    void markTombstoned(String sourceId, List<String> documentIds);

    /** Every document known for a source, tombstoned ones included. */
    List<DocumentState> listDocuments(String sourceId, int limit);

    /**
     * Forgets a source entirely. Used when the source is deleted or a full
     * re-ingest is requested; the caller purges the vectors separately.
     */
    void purgeSource(String sourceId);

    /**
     * Opens a run and returns its id. A source may have only one run in flight:
     * this returns empty when one is already active, which is what stops an
     * impatient operator clicking "run now" five times from starting five
     * concurrent crawls into one knowledge base.
     *
     * <p>
     * A successful claim also takes ownership of the source's existing document
     * rows — the fence described in the interface Javadoc. That costs one bulk
     * update per run, proportional to the documents the source already holds; it
     * buys a fence the caller can enforce with the {@code runId} it already has,
     * which is the only shape both backends can apply in the same statement as the
     * document write.
     */
    Optional<String> startRun(String sourceId);

    /** Closes a run with its outcome and counters. */
    void finishRun(IngestionRun run);

    /** The run currently in flight for a source, if any. */
    Optional<IngestionRun> activeRun(String sourceId);

    /**
     * Most recent runs first — the runs, not the maintenance claims.
     *
     * <p>
     * A claim closed as {@link IngestionRun.Status#MAINTENANCE} (deleting a file
     * takes the run slot so no run can race it) is left out. It used to be closed
     * as a {@code COMPLETED} run with zeroes in every counter, so after deleting
     * one file the source's "last run" read as a successful run that saw nothing —
     * and the real last run, with its errors, was pushed down the list.
     */
    List<IngestionRun> listRuns(String sourceId, int limit);

    /**
     * Fails a run of <em>this source</em> left {@code RUNNING} by a process that
     * died, so a crash does not block the source forever. Called before claiming a
     * new run.
     *
     * <p>
     * Scoped to the source on purpose. The staleness threshold is derived from the
     * source's own time budget, so a store-wide sweep let a source with the default
     * 10-minute budget reap the live run of a source configured for hours — and a
     * reaped run is one whose source immediately accepts a second, concurrent
     * crawl.
     *
     * <p>
     * When it reaps anything it also releases ownership of the source's document
     * rows, so the worker it just declared dead is fenced immediately rather than
     * only once a replacement run claims the source.
     *
     * @return how many runs were reaped
     */
    int reapStaleRuns(String sourceId, Instant startedBefore);

    /**
     * What a previous run knows about a document.
     *
     * @param contentHash
     *            hash of the content as last successfully ingested
     * @param etag
     *            last seen ETag, for a conditional fetch; may be null
     * @param lastModified
     *            last seen Last-Modified header; may be null
     * @param missedRuns
     *            consecutive completed runs that did not see this document
     * @param tombstoned
     *            whether the document is considered gone from the source
     */
    record DocumentState(
            String sourceId,
            String documentId,
            String contentHash,
            String etag,
            String lastModified,
            Instant firstIngestedAt,
            Instant lastIngestedAt,
            String lastRunId,
            int missedRuns,
            boolean tombstoned) {

        /**
         * Whether this document needs embedding again.
         *
         * <p>
         * True for a tombstoned document even when its content is byte-identical:
         * tombstoning deleted its vectors, so the store no longer holds what the hash
         * says it holds. Comparing hashes alone means a page that 404s for two runs and
         * then comes back unchanged is reported "unchanged" forever and is never
         * retrievable again — silent, permanent loss with nothing in any log.
         */
        public boolean hasChanged(String candidateHash) {
            return tombstoned || contentHash == null || !contentHash.equals(candidateHash);
        }

        /** The same document, reported as gone. */
        public DocumentState asTombstoned() {
            return tombstoned
                    ? this
                    : new DocumentState(sourceId, documentId, contentHash, etag, lastModified,
                            firstIngestedAt, lastIngestedAt, lastRunId, missedRuns, true);
        }
    }

    /** One execution of a source's ingestion. */
    record IngestionRun(
            String runId,
            String sourceId,
            Status status,
            Instant startedAt,
            Instant finishedAt,
            int documentsSeen,
            int documentsIngested,
            int documentsUnchanged,
            int documentsFailed,
            int documentsTombstoned,
            int segmentsStored,
            double costUsd,
            String error) {

        public enum Status {
            RUNNING, COMPLETED, FAILED, CANCELLED,
            /**
             * The run slot was held for something other than a run — a file delete — and
             * has been released. Kept as a row, never shown: the row carries the claim's
             * generation, which the next claim must count past, or the document rows
             * stamped by this claim would outrank the run that follows it and fence it out
             * of every write. {@link #listRuns} skips it.
             */
            MAINTENANCE;

            /**
             * Reads a stored status, tolerating one this build does not know. A row written
             * by a newer build — as {@code MAINTENANCE} was new to the build before it —
             * reads as {@code FAILED} instead of failing the whole run history with an
             * exception during a rolling upgrade or after a rollback.
             */
            public static Status parse(String stored) {
                if (stored == null) {
                    return FAILED;
                }
                try {
                    return valueOf(stored);
                } catch (IllegalArgumentException e) {
                    return FAILED;
                }
            }
        }

        /**
         * A run that completed with no failed documents, as opposed to one that failed,
         * was cancelled or lost pages. Used to decide whether tombstoning is safe.
         */
        public boolean completedCleanly() {
            return status == Status.COMPLETED && documentsFailed == 0;
        }
    }
}
