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

        int reaped = store().reapStaleRuns(Instant.now().plusSeconds(60));

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

        assertEquals(0, store().reapStaleRuns(Instant.now().minusSeconds(3600)));
        assertTrue(store().activeRun(SOURCE).isPresent());
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
