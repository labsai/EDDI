/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.modules.ingestion.IIngestionStateStore.DocumentState;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore.IngestionRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour every {@link IIngestionStateStore} backend must share.
 *
 * <p>
 * Run against both MongoDB and PostgreSQL. A shared contract rather than two
 * parallel suites because the implementations this replaces had quietly drifted
 * apart — one overwrote the first-ingested timestamp on every call while the
 * other preserved it — and nothing failed, because each was only ever tested
 * against itself. A knowledge base that behaves differently depending on which
 * database an operator chose is a support nightmare with no error message.
 */
public interface IngestionStateStoreContract {

    /** A store with no state for the ids this test uses. */
    IIngestionStateStore store();

    String SOURCE = "src-1";
    String OTHER_SOURCE = "src-2";
    String DOC = "https://example.com/docs/intro";

    private String openRun(String sourceId) {
        return store().startRun(sourceId).orElseThrow(() -> new AssertionError("expected to claim a run"));
    }

    private void closeRun(String runId, String sourceId, IngestionRun.Status status) {
        store().finishRun(new IngestionRun(runId, sourceId, status, null, Instant.now(),
                0, 0, 0, 0, 0, 0, 0.0, null));
    }

    // === document state ===

    @Test
    @DisplayName("a document reported as tombstoned says it is tombstoned")
    default void tombstonedDocumentsComeBackTombstoned() {
        String runOne = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash", null, null, runOne);
        closeRun(runOne, SOURCE, IngestionRun.Status.COMPLETED);

        String runTwo = openRun(SOURCE);
        List<DocumentState> gone = store().tombstoneMissing(SOURCE, runTwo, 1);
        closeRun(runTwo, SOURCE, IngestionRun.Status.COMPLETED);

        assertEquals(1, gone.size());
        // The state handed back describes the document after the transition, not
        // before it. One backend read its candidates and marked them in a second
        // call, so every state it returned still said false — and a caller that
        // believed it would have re-reported the same documents next time.
        assertTrue(gone.getFirst().tombstoned(),
                "the returned state must reflect the transition that just happened");
        assertTrue(store().lookup(SOURCE, DOC).orElseThrow().tombstoned());
    }

    @Test
    @DisplayName("a document is reported as newly tombstoned exactly once")
    default void aDocumentIsTombstonedOnlyOnce() {
        String runOne = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash", null, null, runOne);
        closeRun(runOne, SOURCE, IngestionRun.Status.COMPLETED);

        String runTwo = openRun(SOURCE);
        assertEquals(1, store().tombstoneMissing(SOURCE, runTwo, 1).size());
        // Whatever the caller does with the second answer, it must not be told to
        // delete the same document's vectors again — and on a store that reads
        // before it writes, two callers racing here both get the document.
        assertTrue(store().tombstoneMissing(SOURCE, runTwo, 1).isEmpty(),
                "an already-tombstoned document must not be reported again");
        closeRun(runTwo, SOURCE, IngestionRun.Status.COMPLETED);
    }

    @Test
    @DisplayName("an unknown document has no state")
    default void unknownDocumentIsAbsent() {
        assertTrue(store().lookup(SOURCE, DOC).isEmpty());
    }

    @Test
    @DisplayName("null ids are tolerated rather than thrown")
    default void nullIdsAreSafe() {
        assertTrue(store().lookup(null, DOC).isEmpty());
        assertTrue(store().lookup(SOURCE, null).isEmpty());
    }

    @Test
    @DisplayName("recording an ingested document stores its hash and conditional-fetch headers")
    default void recordIngestedStoresState() {
        String runId = openRun(SOURCE);

        store().recordIngested(SOURCE, DOC, "hash-1", "\"etag-1\"", "Wed, 21 Oct 2026 07:28:00 GMT", runId);

        DocumentState state = store().lookup(SOURCE, DOC).orElseThrow();
        assertEquals("hash-1", state.contentHash());
        assertEquals("\"etag-1\"", state.etag());
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", state.lastModified());
        assertEquals(runId, state.lastRunId());
        assertEquals(0, state.missedRuns());
        assertFalse(state.tombstoned());
        assertNotNull(state.firstIngestedAt());
        assertNotNull(state.lastIngestedAt());
    }

    @Test
    @DisplayName("hasChanged compares against the stored hash")
    default void hasChangedComparesHash() {
        String runId = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", null, null, runId);

        DocumentState state = store().lookup(SOURCE, DOC).orElseThrow();
        assertFalse(state.hasChanged("hash-1"));
        assertTrue(state.hasChanged("hash-2"));
    }

    @Test
    @DisplayName("re-ingesting keeps the date the document first entered the knowledge base")
    default void firstIngestedAtIsPreserved() throws Exception {
        String firstRun = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", null, null, firstRun);
        Instant originalFirstIngest = store().lookup(SOURCE, DOC).orElseThrow().firstIngestedAt();
        closeRun(firstRun, SOURCE, IngestionRun.Status.COMPLETED);

        Thread.sleep(10);
        String secondRun = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-2", null, null, secondRun);

        DocumentState state = store().lookup(SOURCE, DOC).orElseThrow();
        assertEquals(originalFirstIngest.truncatedTo(ChronoUnit.MILLIS),
                state.firstIngestedAt().truncatedTo(ChronoUnit.MILLIS),
                "firstIngestedAt must survive a re-ingest — it is the document's history, not the last touch");
        assertEquals("hash-2", state.contentHash());
    }

    @Test
    @DisplayName("state is scoped per source, so two sources can hold the same URL")
    default void stateIsScopedPerSource() {
        String runA = openRun(SOURCE);
        String runB = openRun(OTHER_SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-a", null, null, runA);
        store().recordIngested(OTHER_SOURCE, DOC, "hash-b", null, null, runB);

        assertEquals("hash-a", store().lookup(SOURCE, DOC).orElseThrow().contentHash());
        assertEquals("hash-b", store().lookup(OTHER_SOURCE, DOC).orElseThrow().contentHash());
    }

    // === tombstoning ===

    @Test
    @DisplayName("a document missed once is not tombstoned — a blip is not a deletion")
    default void singleMissDoesNotTombstone() {
        String firstRun = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", null, null, firstRun);
        closeRun(firstRun, SOURCE, IngestionRun.Status.COMPLETED);

        String secondRun = openRun(SOURCE);
        List<DocumentState> tombstoned = store().tombstoneMissing(SOURCE, secondRun, 2);

        assertTrue(tombstoned.isEmpty(), "one miss must not tombstone when the threshold is 2");
        DocumentState state = store().lookup(SOURCE, DOC).orElseThrow();
        assertEquals(1, state.missedRuns());
        assertFalse(state.tombstoned());
    }

    @Test
    @DisplayName("a document missed as often as the threshold is tombstoned and reported once")
    default void repeatedMissesTombstone() {
        String firstRun = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", null, null, firstRun);
        closeRun(firstRun, SOURCE, IngestionRun.Status.COMPLETED);

        String secondRun = openRun(SOURCE);
        store().tombstoneMissing(SOURCE, secondRun, 2);
        closeRun(secondRun, SOURCE, IngestionRun.Status.COMPLETED);

        String thirdRun = openRun(SOURCE);
        List<DocumentState> tombstoned = store().tombstoneMissing(SOURCE, thirdRun, 2);

        assertEquals(1, tombstoned.size(), "the document should be reported as newly tombstoned exactly once");
        assertEquals(DOC, tombstoned.get(0).documentId());
        assertTrue(store().lookup(SOURCE, DOC).orElseThrow().tombstoned());
    }

    @Test
    @DisplayName("an already tombstoned document is never reported again")
    default void tombstoneIsReportedOnlyOnce() {
        String firstRun = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", null, null, firstRun);
        closeRun(firstRun, SOURCE, IngestionRun.Status.COMPLETED);

        String secondRun = openRun(SOURCE);
        store().tombstoneMissing(SOURCE, secondRun, 1);
        closeRun(secondRun, SOURCE, IngestionRun.Status.COMPLETED);

        String thirdRun = openRun(SOURCE);
        // Vector deletion is driven by this list; reporting twice would mean
        // deleting vectors that were already gone on every subsequent run.
        assertTrue(store().tombstoneMissing(SOURCE, thirdRun, 1).isEmpty());
    }

    @Test
    @DisplayName("a document seen again resets its miss counter")
    default void seeingADocumentResetsMisses() {
        String firstRun = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", null, null, firstRun);
        closeRun(firstRun, SOURCE, IngestionRun.Status.COMPLETED);

        String secondRun = openRun(SOURCE);
        store().tombstoneMissing(SOURCE, secondRun, 3);
        closeRun(secondRun, SOURCE, IngestionRun.Status.COMPLETED);
        assertEquals(1, store().lookup(SOURCE, DOC).orElseThrow().missedRuns());

        String thirdRun = openRun(SOURCE);
        store().recordSeen(SOURCE, DOC, thirdRun);

        assertEquals(0, store().lookup(SOURCE, DOC).orElseThrow().missedRuns());
    }

    @Test
    @DisplayName("a tombstoned document needs re-embedding even when its content is identical")
    default void tombstonedDocumentAlwaysNeedsReIngest() {
        // Tombstoning deleted the document's vectors, so the stored hash no longer
        // describes what the vector store holds. Comparing hashes alone means a page
        // that 404s for two runs and then comes back byte-identical is reported
        // "unchanged" forever and is never retrievable again.
        String firstRun = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", null, null, firstRun);
        closeRun(firstRun, SOURCE, IngestionRun.Status.COMPLETED);

        String secondRun = openRun(SOURCE);
        store().tombstoneMissing(SOURCE, secondRun, 1);

        DocumentState tombstoned = store().lookup(SOURCE, DOC).orElseThrow();
        assertTrue(tombstoned.tombstoned());
        assertTrue(tombstoned.hasChanged("hash-1"),
                "identical content still has to be re-embedded once its vectors are gone");
    }

    @Test
    @DisplayName("a tombstoned document that reappears is revived")
    default void reappearingDocumentIsRevived() {
        String firstRun = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", null, null, firstRun);
        closeRun(firstRun, SOURCE, IngestionRun.Status.COMPLETED);

        String secondRun = openRun(SOURCE);
        store().tombstoneMissing(SOURCE, secondRun, 1);
        closeRun(secondRun, SOURCE, IngestionRun.Status.COMPLETED);
        assertTrue(store().lookup(SOURCE, DOC).orElseThrow().tombstoned());

        String thirdRun = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-2", null, null, thirdRun);

        DocumentState state = store().lookup(SOURCE, DOC).orElseThrow();
        assertFalse(state.tombstoned(), "a page that came back must be retrievable again");
        assertEquals(0, state.missedRuns());
    }

    @Test
    @DisplayName("tombstoning one source leaves another alone")
    default void tombstoningIsScopedPerSource() {
        String runA = openRun(SOURCE);
        String runB = openRun(OTHER_SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-a", null, null, runA);
        store().recordIngested(OTHER_SOURCE, DOC, "hash-b", null, null, runB);
        closeRun(runA, SOURCE, IngestionRun.Status.COMPLETED);

        String nextRunA = openRun(SOURCE);
        store().tombstoneMissing(SOURCE, nextRunA, 1);

        assertFalse(store().lookup(OTHER_SOURCE, DOC).orElseThrow().tombstoned());
    }

    @Test
    @DisplayName("a threshold below one is clamped rather than tombstoning everything instantly")
    default void thresholdIsClamped() {
        String firstRun = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", null, null, firstRun);

        // Same run: the document was seen, so even a zero threshold must not
        // tombstone it.
        assertTrue(store().tombstoneMissing(SOURCE, firstRun, 0).isEmpty());
        assertFalse(store().lookup(SOURCE, DOC).orElseThrow().tombstoned());
    }

    // === runs ===

    @Test
    @DisplayName("only one run may be in flight per source")
    default void onlyOneRunInFlight() {
        String runId = openRun(SOURCE);

        Optional<String> second = store().startRun(SOURCE);

        assertTrue(second.isEmpty(),
                "a second concurrent run would crawl the same source twice into one knowledge base");
        assertEquals(runId, store().activeRun(SOURCE).orElseThrow().runId());
    }

    @Test
    @DisplayName("a different source can run at the same time")
    default void differentSourcesRunConcurrently() {
        openRun(SOURCE);

        assertTrue(store().startRun(OTHER_SOURCE).isPresent());
    }

    @Test
    @DisplayName("finishing a run frees the source for the next one")
    default void finishingRunReleasesTheSource() {
        String runId = openRun(SOURCE);
        closeRun(runId, SOURCE, IngestionRun.Status.COMPLETED);

        assertTrue(store().activeRun(SOURCE).isEmpty());
        assertTrue(store().startRun(SOURCE).isPresent());
    }

    @Test
    @DisplayName("a finished run keeps its counters for the history view")
    default void finishedRunKeepsCounters() {
        String runId = openRun(SOURCE);

        store().finishRun(new IngestionRun(runId, SOURCE, IngestionRun.Status.COMPLETED, null, Instant.now(),
                10, 3, 6, 1, 2, 42, 0.125, null));

        IngestionRun run = store().listRuns(SOURCE, 10).get(0);
        assertEquals(IngestionRun.Status.COMPLETED, run.status());
        assertEquals(10, run.documentsSeen());
        assertEquals(3, run.documentsIngested());
        assertEquals(6, run.documentsUnchanged());
        assertEquals(1, run.documentsFailed());
        assertEquals(2, run.documentsTombstoned());
        assertEquals(42, run.segmentsStored());
        assertEquals(0.125, run.costUsd(), 0.0001);
        assertNotNull(run.finishedAt());
    }

    @Test
    @DisplayName("a failed run records why, so the Manager can show it")
    default void failedRunKeepsItsError() {
        String runId = openRun(SOURCE);

        store().finishRun(new IngestionRun(runId, SOURCE, IngestionRun.Status.FAILED, null, Instant.now(),
                2, 0, 0, 2, 0, 0, 0.0, "embedding provider returned 429"));

        IngestionRun run = store().listRuns(SOURCE, 10).get(0);
        assertEquals(IngestionRun.Status.FAILED, run.status());
        assertEquals("embedding provider returned 429", run.error());
        assertFalse(run.completedCleanly());
    }

    @Test
    @DisplayName("run history is newest first")
    default void runHistoryIsNewestFirst() throws Exception {
        String first = openRun(SOURCE);
        closeRun(first, SOURCE, IngestionRun.Status.COMPLETED);
        Thread.sleep(10);
        String second = openRun(SOURCE);
        closeRun(second, SOURCE, IngestionRun.Status.COMPLETED);

        List<IngestionRun> runs = store().listRuns(SOURCE, 10);
        assertEquals(2, runs.size());
        assertEquals(second, runs.get(0).runId());
        assertNotEquals(second, runs.get(1).runId());
    }

    @Test
    @DisplayName("a run abandoned by a dead instance is reaped so the source is not blocked forever")
    default void staleRunsAreReaped() {
        String runId = openRun(SOURCE);

        int reaped = store().reapStaleRuns(SOURCE, Instant.now().plusSeconds(60));

        assertEquals(1, reaped);
        assertTrue(store().activeRun(SOURCE).isEmpty(), "a reaped run must release the source");
        IngestionRun run = store().listRuns(SOURCE, 10).get(0);
        assertEquals(IngestionRun.Status.FAILED, run.status());
        assertNotNull(run.error());
        assertEquals(runId, run.runId());
    }

    @Test
    @DisplayName("a healthy run is not reaped")
    default void healthyRunSurvivesReaping() {
        openRun(SOURCE);

        assertEquals(0, store().reapStaleRuns(SOURCE, Instant.now().minusSeconds(3600)));
        assertTrue(store().activeRun(SOURCE).isPresent());
    }

    @Test
    @DisplayName("reaping one source never touches another source's run")
    default void reapingIsScopedToOneSource() {
        // The staleness threshold comes from the source's own time budget, so a
        // store-wide sweep let a source with the default 10-minute budget reap the
        // live run of a source configured for hours — and the reaped source then
        // accepts a second, concurrent crawl.
        String other = openRun(OTHER_SOURCE);

        int reaped = store().reapStaleRuns(SOURCE, Instant.now().plusSeconds(60));

        assertEquals(0, reaped, "no run of this source was in flight");
        assertTrue(store().activeRun(OTHER_SOURCE).isPresent(),
                "another source's run must survive: reaping it would let a second crawl start alongside it");
        assertEquals(other, store().activeRun(OTHER_SOURCE).orElseThrow().runId());
    }

    @Test
    @DisplayName("a reaped run's own result is discarded rather than resurrecting it")
    default void finishingAReapedRunDoesNotResurrectIt() {
        // The reaper has already declared the run dead and the source has been
        // released, so a second run may be in flight by now. Letting the first
        // worker's finishRun overwrite the record would report COMPLETED for a run
        // nobody was waiting on, and hide the reaping entirely.
        String runId = openRun(SOURCE);
        store().reapStaleRuns(SOURCE, Instant.now().plusSeconds(60));

        closeRun(runId, SOURCE, IngestionRun.Status.COMPLETED);

        IngestionRun run = store().listRuns(SOURCE, 10).get(0);
        assertEquals(IngestionRun.Status.FAILED, run.status(), "the reaped status must stand");
        assertNotNull(run.error());
    }

    // === fencing a superseded run ===

    /**
     * Puts a worker in the position the reaper leaves it in: its run has been
     * failed, a replacement has claimed the source, and it is about to wake up and
     * write with the run id it still holds.
     *
     * @return the id of the run that has just been superseded
     */
    private String supersede(String staleRunId) {
        int reaped = store().reapStaleRuns(SOURCE, Instant.now().plusSeconds(60));
        assertEquals(1, reaped, "the stalled run should have been reaped");
        return staleRunId;
    }

    @Test
    @DisplayName("a reaped run cannot overwrite a document the run that replaced it owns")
    default void staleRunCannotRecordAnIngestedDocument() {
        String stale = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", "\"etag-1\"", null, stale);
        supersede(stale);
        String replacement = openRun(SOURCE);

        // The stalled worker wakes up and finishes the document it was embedding
        // when it lost the source.
        store().recordIngested(SOURCE, DOC, "hash-stale", "\"etag-stale\"", null, stale);

        DocumentState state = store().lookup(SOURCE, DOC).orElseThrow();
        assertEquals("hash-1", state.contentHash(), "a superseded run must not rewrite the stored hash");
        assertEquals("\"etag-1\"", state.etag());

        // ...and the run that replaced it writes the same document normally.
        store().recordIngested(SOURCE, DOC, "hash-2", null, null, replacement);
        assertEquals("hash-2", store().lookup(SOURCE, DOC).orElseThrow().contentHash());
    }

    @Test
    @DisplayName("a reaped run cannot revive a tombstoned document whose vectors are gone")
    default void staleRunCannotReviveATombstonedDocument() {
        // The worst case of the lot, and the reason the fence exists. Tombstoning
        // deleted this document's vectors. A stale recordIngested clears the
        // tombstone and restores the hash the vectors used to match, so every later
        // run compares hashes, reports the page "unchanged" and never embeds it
        // again. The page is unreachable for good, and nothing is logged.
        String first = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", null, null, first);
        closeRun(first, SOURCE, IngestionRun.Status.COMPLETED);

        String stale = openRun(SOURCE);
        assertEquals(1, store().tombstoneMissing(SOURCE, stale, 1).size());
        supersede(stale);
        openRun(SOURCE);

        store().recordIngested(SOURCE, DOC, "hash-1", null, null, stale);

        DocumentState state = store().lookup(SOURCE, DOC).orElseThrow();
        assertTrue(state.tombstoned(), "a superseded run must not lift a tombstone");
        assertTrue(state.hasChanged("hash-1"),
                "the document must still be re-embedded — its vectors were deleted");
    }

    @Test
    @DisplayName("a reaped run cannot clear the miss counter the run that replaced it is keeping")
    default void staleRunCannotRecordADocumentAsSeen() {
        String first = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", null, null, first);
        closeRun(first, SOURCE, IngestionRun.Status.COMPLETED);

        String stale = openRun(SOURCE);
        store().tombstoneMissing(SOURCE, stale, 3);
        assertEquals(1, store().lookup(SOURCE, DOC).orElseThrow().missedRuns());
        supersede(stale);
        openRun(SOURCE);

        store().recordSeen(SOURCE, DOC, stale);

        assertEquals(1, store().lookup(SOURCE, DOC).orElseThrow().missedRuns(),
                "a superseded run's sighting must not forgive a miss the live run is counting");
    }

    @Test
    @DisplayName("a reaped run cannot make the live run lose a document it just saw")
    default void staleRunCannotStampTheRunMarkerOfADocumentTheLiveRunSaw() {
        // recordUnreachable writes only the run marker, which looks harmless until
        // you follow it: escaping the miss count is exactly what that marker does.
        // A stale worker overwriting it hands a page the live run has already seen
        // back to tombstoneMissing — and tombstoning is what deletes vectors.
        String first = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", null, null, first);
        supersede(first);
        String live = openRun(SOURCE);
        store().recordSeen(SOURCE, DOC, live);

        store().recordUnreachable(SOURCE, DOC, first);

        assertTrue(store().tombstoneMissing(SOURCE, live, 1).isEmpty(),
                "a document the live run saw must not be deleted because a zombie called it unreachable");
        assertFalse(store().lookup(SOURCE, DOC).orElseThrow().tombstoned());
    }

    @Test
    @DisplayName("a reaped run tombstones nothing, so it deletes no vectors")
    default void staleRunCannotTombstone() {
        String first = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", null, null, first);
        closeRun(first, SOURCE, IngestionRun.Status.COMPLETED);

        String stale = openRun(SOURCE);
        supersede(stale);
        openRun(SOURCE);

        assertTrue(store().tombstoneMissing(SOURCE, stale, 1).isEmpty(),
                "a superseded run's reconciliation saw an arbitrary subset of the source");
        DocumentState state = store().lookup(SOURCE, DOC).orElseThrow();
        assertFalse(state.tombstoned());
        assertEquals(0, state.missedRuns(), "a superseded run must not raise anyone's miss counter either");
    }

    @Test
    @DisplayName("the run that replaced a reaped one can still write every document")
    default void theReplacementRunOwnsEverySortOfDocument() {
        // The fence is worthless if it also blocks the live run. This covers the
        // three paths a write can take: a row inherited from an earlier run, a row
        // the live run created itself, and a document nobody has ever seen.
        String first = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", null, null, first);
        supersede(first);

        String live = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-2", null, null, live);
        store().recordIngested(SOURCE, DOC + "/new", "hash-new", null, null, live);
        store().recordSeen(SOURCE, DOC + "/new", live);
        store().recordUnreachable(SOURCE, DOC, live);

        assertEquals("hash-2", store().lookup(SOURCE, DOC).orElseThrow().contentHash());
        assertEquals("hash-new", store().lookup(SOURCE, DOC + "/new").orElseThrow().contentHash());
        assertEquals(live, store().lookup(SOURCE, DOC).orElseThrow().lastRunId());

        // A row the live run inserted belongs to it as much as one it inherited —
        // otherwise a document first seen mid-run would be the one hole in the
        // fence, and it is the hole a stalled worker is most likely to find.
        store().recordIngested(SOURCE, DOC + "/new", "hash-stale", null, null, first);
        assertEquals("hash-new", store().lookup(SOURCE, DOC + "/new").orElseThrow().contentHash(),
                "a document the live run created must be fenced against the run it replaced");
    }

    @Test
    @DisplayName("fencing one source does not fence another")
    default void fencingIsScopedPerSource() {
        String other = openRun(OTHER_SOURCE);
        store().recordIngested(OTHER_SOURCE, DOC, "hash-b", null, null, other);

        String stale = openRun(SOURCE);
        supersede(stale);
        openRun(SOURCE);

        // Reaping SOURCE released SOURCE's rows. It must not have touched the run
        // that is still crawling OTHER_SOURCE.
        store().recordIngested(OTHER_SOURCE, DOC, "hash-b2", null, null, other);
        assertEquals("hash-b2", store().lookup(OTHER_SOURCE, DOC).orElseThrow().contentHash());
    }

    // === unreachable documents ===

    @Test
    @DisplayName("a document the run could not reach is neither a sighting nor a miss")
    default void unreachableDocumentIsNotTombstoned() {
        // A 503, a 429 or a WAF 403 says nothing about whether the page still
        // exists. Counting it as a miss deletes the tail of a rate-limited site
        // after two runs, while every run reports success.
        String first = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", null, null, first);
        closeRun(first, SOURCE, IngestionRun.Status.COMPLETED);

        for (int i = 0; i < 3; i++) {
            String runId = openRun(SOURCE);
            store().recordUnreachable(SOURCE, DOC, runId);
            assertTrue(store().tombstoneMissing(SOURCE, runId, 1).isEmpty(),
                    "an unreachable document must never be tombstoned, however often it is unreachable");
            closeRun(runId, SOURCE, IngestionRun.Status.COMPLETED);
        }

        DocumentState state = store().lookup(SOURCE, DOC).orElseThrow();
        assertFalse(state.tombstoned());
        assertEquals("hash-1", state.contentHash(), "its content is unchanged, so its hash must be kept");
    }

    @Test
    @DisplayName("being unreachable does not absolve a document that was already missing")
    default void unreachableDoesNotResetTheMissCounter() {
        // recordSeen forgives past misses; this must not, or a page that is missing
        // and then unreachable never reaches the threshold.
        String first = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", null, null, first);
        closeRun(first, SOURCE, IngestionRun.Status.COMPLETED);

        String second = openRun(SOURCE);
        store().tombstoneMissing(SOURCE, second, 2);
        closeRun(second, SOURCE, IngestionRun.Status.COMPLETED);
        assertEquals(1, store().lookup(SOURCE, DOC).orElseThrow().missedRuns());

        String third = openRun(SOURCE);
        store().recordUnreachable(SOURCE, DOC, third);
        store().tombstoneMissing(SOURCE, third, 2);

        assertEquals(1, store().lookup(SOURCE, DOC).orElseThrow().missedRuns(),
                "the miss counter is neither raised nor cleared by a run that could not look");
    }

    @Test
    @DisplayName("an ETag longer than a column can hold does not break the store")
    default void longValidatorsAreStored() {
        // Recorded after the vectors are written, so a failure here re-embeds and
        // re-bills the page on every run. RFC 7232 puts no length limit on an ETag,
        // and a source id carries an operator-chosen name.
        String longSource = "kb-" + "x".repeat(400);
        String longEtag = "\"" + "e".repeat(600) + "\"";
        String runId = store().startRun(longSource).orElseThrow();

        store().recordIngested(longSource, DOC, "hash-1", longEtag, "Wed, 21 Oct 2026 07:28:00 GMT", runId);

        assertEquals(longEtag, store().lookup(longSource, DOC).orElseThrow().etag());
    }

    // === purge ===

    @Test
    @DisplayName("purging a source forgets its documents and its run history")
    default void purgeRemovesEverythingForOneSource() {
        String runA = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-a", null, null, runA);
        closeRun(runA, SOURCE, IngestionRun.Status.COMPLETED);
        String runB = openRun(OTHER_SOURCE);
        store().recordIngested(OTHER_SOURCE, DOC, "hash-b", null, null, runB);

        store().purgeSource(SOURCE);

        assertTrue(store().lookup(SOURCE, DOC).isEmpty());
        assertTrue(store().listRuns(SOURCE, 10).isEmpty());
        assertTrue(store().lookup(OTHER_SOURCE, DOC).isPresent(), "purge must not touch another source");
    }

    @Test
    @DisplayName("listing documents returns what the source holds, tombstones included")
    default void listDocumentsIncludesTombstones() {
        String runId = openRun(SOURCE);
        store().recordIngested(SOURCE, DOC, "hash-1", null, null, runId);
        store().recordIngested(SOURCE, DOC + "/other", "hash-2", null, null, runId);
        closeRun(runId, SOURCE, IngestionRun.Status.COMPLETED);

        List<DocumentState> documents = store().listDocuments(SOURCE, 100);

        assertEquals(2, documents.size());
    }
}
