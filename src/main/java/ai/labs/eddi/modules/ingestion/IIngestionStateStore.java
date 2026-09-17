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
 * <h2>Two rules that are easy to get wrong</h2>
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
     */
    void recordSeen(String sourceId, String documentId, String runId);

    /**
     * Increments the miss counter for every live document this run did not see, and
     * tombstones those that have now been missed {@code missedRunsThreshold} times
     * in a row.
     *
     * <p>
     * Only call this for a run that completed — a failed or aborted run saw an
     * arbitrary subset of the source and would tombstone the remainder.
     *
     * @return the documents tombstoned by this call, whose vectors the caller is
     *         then responsible for removing
     */
    List<DocumentState> tombstoneMissing(String sourceId, String runId, int missedRunsThreshold);

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
     */
    Optional<String> startRun(String sourceId);

    /** Closes a run with its outcome and counters. */
    void finishRun(IngestionRun run);

    /** The run currently in flight for a source, if any. */
    Optional<IngestionRun> activeRun(String sourceId);

    /** Most recent runs first. */
    List<IngestionRun> listRuns(String sourceId, int limit);

    /**
     * Fails any run left {@code RUNNING} by a process that died, so a crashed
     * instance does not block the source forever. Called at startup and before
     * claiming a new run.
     *
     * @return how many runs were reaped
     */
    int reapStaleRuns(Instant startedBefore);

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

        /** Whether freshly converted content differs from what was last stored. */
        public boolean hasChanged(String candidateHash) {
            return contentHash == null || !contentHash.equals(candidateHash);
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
            RUNNING, COMPLETED, FAILED, CANCELLED
        }

        /**
         * A run that reached its own limits rather than covering the source. Used to
         * decide whether tombstoning is safe.
         */
        public boolean completedCleanly() {
            return status == Status.COMPLETED && documentsFailed == 0;
        }
    }
}
