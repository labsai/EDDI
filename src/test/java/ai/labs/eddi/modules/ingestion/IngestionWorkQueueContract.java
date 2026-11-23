/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.modules.ingestion.IIngestionStateStore.DocumentState;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore.IngestionRun;
import ai.labs.eddi.modules.ingestion.IngestionWorkQueue.EnqueueOutcome;
import ai.labs.eddi.modules.ingestion.IngestionWorkQueue.FailOutcome;
import ai.labs.eddi.modules.ingestion.IngestionWorkQueue.WorkClaim;
import ai.labs.eddi.modules.ingestion.IngestionWorkQueue.WorkCounts;
import ai.labs.eddi.modules.ingestion.IngestionWorkQueue.WorkRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour every backend of the work table ({@link IngestionWorkQueue})
 * must share, run against MongoDB, PostgreSQL and the in-memory double —
 * through the persistence boundary, not around it.
 */
public interface IngestionWorkQueueContract {

    /** A store with no state for the ids this test uses. */
    IIngestionStateStore store();

    String WORK_SOURCE = "work-src-1";
    String WORK_OTHER_SOURCE = "work-src-2";

    /** Truncated, as both databases store time to the millisecond or coarser. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private String openRun(String sourceId) {
        return store().startRun(sourceId).orElseThrow(() -> new AssertionError("expected to claim a run"));
    }

    private void closeRun(String runId, String sourceId) {
        store().finishRun(new IngestionRun(runId, sourceId, IngestionRun.Status.COMPLETED, null, Instant.now(),
                0, 0, 0, 0, 0, 0, 0.0, null));
    }

    private static WorkRequest request(String documentId, String token) {
        return new WorkRequest(documentId, token, "{\"p\":\"implicit\",\"d\":\"" + documentId + "\"}");
    }

    private List<WorkClaim> claimAll(String sourceId, String runId, Instant at) {
        return store().claimWork(sourceId, runId, 1_000, at, at.plus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("a queued document the source never held is a row with no vectors, stamped by the listing run")
    default void queueingANewDocumentWritesARowWithoutVectors() {
        String run = openRun(WORK_SOURCE);
        assertEquals(EnqueueOutcome.QUEUED, store().enqueueWork(WORK_SOURCE, run, request("doc-a", "v1"), now()));

        DocumentState state = store().lookup(WORK_SOURCE, "doc-a").orElseThrow();
        assertTrue(state.tombstoned(), "a queued document has no vectors yet");
        assertNull(state.contentHash(), "and no content hash: it was never ingested");
        assertNull(state.lastIngestedAt());
        assertEquals(run, state.lastRunId(), "the listing saw it, so it must not count as missed");
        assertTrue(state.hasChanged("v1"), "it must read as needing an embedding");
        assertTrue(store().bumpAndFindMissing(WORK_SOURCE, run, 1).isEmpty(),
                "a row without vectors is never reported gone: there is nothing to delete");
    }

    @Test
    @DisplayName("queueing never touches what a document was ingested as")
    default void queueingAnIngestedDocumentKeepsItsState() {
        String first = openRun(WORK_SOURCE);
        store().recordIngested(WORK_SOURCE, "doc-a", "hash-1", "etag", "lm", first, "cite");
        closeRun(first, WORK_SOURCE);

        String second = openRun(WORK_SOURCE);
        assertEquals(EnqueueOutcome.QUEUED, store().enqueueWork(WORK_SOURCE, second, request("doc-a", "v2"), now()));
        DocumentState state = store().lookup(WORK_SOURCE, "doc-a").orElseThrow();
        assertEquals("hash-1", state.contentHash(), "the old hash stays until the new vectors are stored");
        assertFalse(state.tombstoned(), "its old vectors stay live meanwhile");
        assertEquals("etag", state.etag());
        assertEquals(second, state.lastRunId(), "but it counts as seen by the listing");
    }

    @Test
    @DisplayName("queueing equal work again keeps its deferral and its attempts")
    default void enqueueingEqualWorkIsANoOp() {
        String run = openRun(WORK_SOURCE);
        Instant start = now();
        store().enqueueWork(WORK_SOURCE, run, request("doc-a", "v1"), start);
        WorkClaim claim = claimAll(WORK_SOURCE, run, start).get(0);
        assertEquals(FailOutcome.RETRY, store().failWork(WORK_SOURCE, "doc-a", run, claim.workVersion(), "boom",
                start.plus(Duration.ofHours(1)), 5));

        assertEquals(EnqueueOutcome.ALREADY_QUEUED,
                store().enqueueWork(WORK_SOURCE, run, request("doc-a", "v1"), start.plusSeconds(1)));

        assertTrue(claimAll(WORK_SOURCE, run, start.plusSeconds(2)).isEmpty(),
                "re-listing must not reset the backoff, or a throttle's deferral is undone by every cron");
        WorkClaim later = claimAll(WORK_SOURCE, run, start.plus(Duration.ofHours(2))).get(0);
        assertEquals(1, later.attempts(), "nor the attempt count");
        assertEquals(claim.workVersion(), later.workVersion());
    }

    @Test
    @DisplayName("a new version re-arms the row and bumps its work version")
    default void aNewVersionRearms() {
        String run = openRun(WORK_SOURCE);
        Instant start = now();
        store().enqueueWork(WORK_SOURCE, run, request("doc-a", "v1"), start);
        WorkClaim first = claimAll(WORK_SOURCE, run, start).get(0);
        store().failWork(WORK_SOURCE, "doc-a", run, first.workVersion(), "boom", start.plus(Duration.ofHours(1)), 5);

        assertEquals(EnqueueOutcome.QUEUED, store().enqueueWork(WORK_SOURCE, run, request("doc-a", "v2"), start));
        WorkClaim second = claimAll(WORK_SOURCE, run, start).get(0);
        assertEquals("v2", second.versionToken());
        assertEquals(0, second.attempts(), "a new version starts with no attempts");
        assertTrue(second.workVersion() > first.workVersion());
    }

    @Test
    @DisplayName("a completion for a superseded version leaves the newer work pending")
    default void completionIsConditionalOnTheClaimedVersion() {
        String run = openRun(WORK_SOURCE);
        Instant start = now();
        store().enqueueWork(WORK_SOURCE, run, request("doc-a", "v1"), start);
        WorkClaim held = claimAll(WORK_SOURCE, run, start).get(0);

        // Discovery queues version 2 while a worker still holds version 1.
        store().enqueueWork(WORK_SOURCE, run, request("doc-a", "v2"), start);
        assertFalse(store().completeWork(WORK_SOURCE, "doc-a", run, held.workVersion()),
                "the old version's completion must match nothing");
        List<WorkClaim> again = claimAll(WORK_SOURCE, run, start);
        assertEquals(1, again.size(), "the newer work is not lost");
        assertEquals("v2", again.get(0).versionToken());
    }

    @Test
    @DisplayName("a completion from a claimer that does not hold the row matches nothing")
    default void completionIsConditionalOnTheClaimer() {
        String run = openRun(WORK_SOURCE);
        Instant start = now();
        store().enqueueWork(WORK_SOURCE, run, request("doc-a", "v1"), start);
        WorkClaim held = claimAll(WORK_SOURCE, run, start).get(0);
        assertFalse(store().completeWork(WORK_SOURCE, "doc-a", "someone-else", held.workVersion()));
        assertEquals(1, store().workCounts(WORK_SOURCE, start).inFlight());
        assertTrue(store().completeWork(WORK_SOURCE, "doc-a", run, held.workVersion()));
        assertEquals(1, store().workCounts(WORK_SOURCE, start).done());
    }

    @Test
    @DisplayName("concurrent claimers never share a row")
    default void concurrentClaimersNeverShareARow() throws Exception {
        String run = openRun(WORK_SOURCE);
        Instant start = now();
        int documents = 200;
        for (int i = 0; i < documents; i++) {
            store().enqueueWork(WORK_SOURCE, run, request("doc-" + i, "v1"), start);
        }
        int claimers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(claimers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<String>>> futures = new ArrayList<>();
        for (int c = 0; c < claimers; c++) {
            futures.add(pool.submit(() -> {
                go.await();
                List<String> mine = new ArrayList<>();
                List<WorkClaim> batch;
                while (!(batch = store().claimWork(WORK_SOURCE, run, 7, start, start.plus(Duration.ofMinutes(30))))
                        .isEmpty()) {
                    batch.forEach(claim -> mine.add(claim.documentId()));
                }
                return mine;
            }));
        }
        go.countDown();
        List<String> all = Collections.synchronizedList(new ArrayList<>());
        for (Future<List<String>> future : futures) {
            all.addAll(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        Set<String> distinct = new HashSet<>(all);
        assertEquals(all.size(), distinct.size(), "a row was handed to two claimers");
        assertEquals(documents, distinct.size(), "every row was claimed exactly once");
    }

    @Test
    @DisplayName("a claim whose lease has passed can be claimed again, so a crash loses no document")
    default void anExpiredClaimIsReclaimable() {
        String run = openRun(WORK_SOURCE);
        Instant start = now();
        store().enqueueWork(WORK_SOURCE, run, request("doc-a", "v1"), start);
        Instant lease = start.plus(Duration.ofMinutes(10));
        assertEquals(1, store().claimWork(WORK_SOURCE, run, 10, start, lease).size());

        assertTrue(store().claimWork(WORK_SOURCE, run, 10, start.plusSeconds(60), lease).isEmpty(),
                "a live claim is not handed out again");
        List<WorkClaim> reclaimed = store().claimWork(WORK_SOURCE, run, 10, lease.plusSeconds(1),
                lease.plus(Duration.ofMinutes(10)));
        assertEquals(1, reclaimed.size(), "the crashed worker's row comes back once its lease passes");
    }

    @Test
    @DisplayName("reaping a dead run returns its claims at once")
    default void reapingReleasesTheDeadRunsClaims() {
        String dead = store().startRun(WORK_SOURCE, Instant.now().minusSeconds(1)).orElseThrow();
        Instant start = now();
        store().enqueueWork(WORK_SOURCE, dead, request("doc-a", "v1"), start);
        assertEquals(1, store().claimWork(WORK_SOURCE, dead, 10, start, start.plus(Duration.ofHours(1))).size());

        assertEquals(1, store().reapStaleRuns(WORK_SOURCE, Instant.now()));
        String next = openRun(WORK_SOURCE);
        assertEquals(1, claimAll(WORK_SOURCE, next, now()).size(),
                "the next run takes over the dead run's document without waiting out its lease");
    }

    @Test
    @DisplayName("a run that no longer owns the source claims nothing")
    default void claimsAreFenced() {
        String run = openRun(WORK_SOURCE);
        Instant start = now();
        store().enqueueWork(WORK_SOURCE, run, request("doc-a", "v1"), start);
        assertTrue(store().claimWork(WORK_SOURCE, "not-the-owner", 10, start, start.plusSeconds(600)).isEmpty());
        assertEquals(EnqueueOutcome.FENCED,
                store().enqueueWork(WORK_SOURCE, "not-the-owner", request("doc-a", "v2"), start));
    }

    @Test
    @DisplayName("notBefore is honoured, and the earliest due row is claimed first")
    default void notBeforeIsHonoured() {
        String run = openRun(WORK_SOURCE);
        Instant start = now();
        store().enqueueWork(WORK_SOURCE, run, request("doc-late", "v1"), start);
        store().enqueueWork(WORK_SOURCE, run, request("doc-early", "v1"), start);
        List<WorkClaim> both = claimAll(WORK_SOURCE, run, start);
        assertEquals(2, both.size());
        WorkClaim late = both.stream().filter(c -> c.documentId().equals("doc-late")).findFirst().orElseThrow();
        WorkClaim early = both.stream().filter(c -> c.documentId().equals("doc-early")).findFirst().orElseThrow();
        assertTrue(store().deferWork(WORK_SOURCE, "doc-late", run, late.workVersion(), start.plus(Duration.ofMinutes(20))));
        assertTrue(store().deferWork(WORK_SOURCE, "doc-early", run, early.workVersion(), start.plus(Duration.ofMinutes(5))));

        assertTrue(claimAll(WORK_SOURCE, run, start.plus(Duration.ofMinutes(1))).isEmpty(), "neither is due yet");
        WorkCounts counts = store().workCounts(WORK_SOURCE, start.plus(Duration.ofMinutes(1)));
        assertEquals(2, counts.waiting());
        assertEquals(start.plus(Duration.ofMinutes(5)), counts.nextAttemptAt());

        List<WorkClaim> due = store().claimWork(WORK_SOURCE, run, 1, start.plus(Duration.ofMinutes(30)),
                start.plus(Duration.ofHours(1)));
        assertEquals("doc-early", due.get(0).documentId(), "the oldest due row goes first");
    }

    @Test
    @DisplayName("deferring keeps the attempts; failing counts one and poisons at the cap")
    default void deferIsNotFailAndTheCapPoisons() {
        String run = openRun(WORK_SOURCE);
        Instant at = now();
        store().enqueueWork(WORK_SOURCE, run, request("doc-a", "v1"), at);
        WorkClaim claim = claimAll(WORK_SOURCE, run, at).get(0);
        store().deferWork(WORK_SOURCE, "doc-a", run, claim.workVersion(), at);
        claim = claimAll(WORK_SOURCE, run, at).get(0);
        assertEquals(0, claim.attempts(), "a deferral is not an attempt");

        for (int attempt = 1; attempt < 3; attempt++) {
            assertEquals(FailOutcome.RETRY, store().failWork(WORK_SOURCE, "doc-a", run, claim.workVersion(), "boom",
                    at, 3));
            claim = claimAll(WORK_SOURCE, run, at).get(0);
            assertEquals(attempt, claim.attempts());
        }
        assertEquals(FailOutcome.POISONED, store().failWork(WORK_SOURCE, "doc-a", run, claim.workVersion(),
                "boom again", at, 3));
        assertTrue(claimAll(WORK_SOURCE, run, at.plus(Duration.ofDays(1))).isEmpty(), "a poisoned row is never claimed");
        assertEquals(1, store().workCounts(WORK_SOURCE, at).failed());
        assertEquals(EnqueueOutcome.ALREADY_QUEUED, store().enqueueWork(WORK_SOURCE, run, request("doc-a", "v1"), at),
                "re-listing the same version does not re-arm it");

        assertEquals(1, store().retryFailedWork(WORK_SOURCE, at));
        WorkClaim rearmed = claimAll(WORK_SOURCE, run, at).get(0);
        assertEquals(0, rearmed.attempts(), "retry-failed starts it over");
    }

    @Test
    @DisplayName("releasing a run's claims returns them unchanged; counts are by state")
    default void releaseAndCounts() {
        String run = openRun(WORK_SOURCE);
        Instant at = now();
        for (String id : List.of("a", "b", "c", "d")) {
            store().enqueueWork(WORK_SOURCE, run, request(id, "v1"), at);
        }
        List<WorkClaim> claims = claimAll(WORK_SOURCE, run, at);
        store().completeWork(WORK_SOURCE, "a", run, version(claims, "a"));
        store().deferWork(WORK_SOURCE, "b", run, version(claims, "b"), at.plus(Duration.ofHours(1)));
        store().failWork(WORK_SOURCE, "c", run, version(claims, "c"), "boom", at.plus(Duration.ofHours(1)), 1);

        WorkCounts counts = store().workCounts(WORK_SOURCE, at);
        assertEquals(1, counts.done());
        assertEquals(1, counts.waiting());
        assertEquals(1, counts.failed());
        assertEquals(1, counts.inFlight());
        assertEquals(0, counts.queued());

        assertEquals(1, store().releaseClaims(WORK_SOURCE, run));
        counts = store().workCounts(WORK_SOURCE, at);
        assertEquals(1, counts.queued(), "the released row is due again");
        assertEquals(0, counts.inFlight());
        assertEquals(WorkCounts.NONE, store().workCounts(WORK_OTHER_SOURCE, at), "counts are per source");
    }

    @Test
    @DisplayName("a complete listing withdraws the work of documents it did not see, and only those")
    default void cancelUnlistedWorkWithdrawsOnlyUnseenDocuments() {
        String first = openRun(WORK_SOURCE);
        Instant at = now();
        store().enqueueWork(WORK_SOURCE, first, request("kept", "v1"), at);
        store().enqueueWork(WORK_SOURCE, first, request("gone", "v1"), at);
        closeRun(first, WORK_SOURCE);

        String second = openRun(WORK_SOURCE);
        store().enqueueWork(WORK_SOURCE, second, request("kept", "v1"), at);
        assertEquals(1, store().cancelUnlistedWork(WORK_SOURCE, second));

        List<WorkClaim> left = claimAll(WORK_SOURCE, second, at);
        assertEquals(List.of("kept"), left.stream().map(WorkClaim::documentId).toList());
    }

    @Test
    @DisplayName("work survives a new run taking over the source, and goes with a purge")
    default void workSurvivesRunsAndGoesWithAPurge() {
        String first = openRun(WORK_SOURCE);
        Instant at = now();
        store().enqueueWork(WORK_SOURCE, first, request("doc-a", "v1"), at);
        closeRun(first, WORK_SOURCE);

        String second = openRun(WORK_SOURCE);
        WorkClaim claim = claimAll(WORK_SOURCE, second, at).get(0);
        assertEquals("v1", claim.versionToken());
        assertEquals(request("doc-a", "v1").payload(), claim.payload(), "the payload round-trips unchanged");
        store().recordIngested(WORK_SOURCE, "doc-a", "v1", null, null, second, null);
        assertTrue(store().completeWork(WORK_SOURCE, "doc-a", second, claim.workVersion()));
        DocumentState state = store().lookup(WORK_SOURCE, "doc-a").orElseThrow();
        assertFalse(state.tombstoned(), "recording it lifts the no-vectors mark");
        assertEquals("v1", state.contentHash());

        store().enqueueWork(WORK_SOURCE, second, request("doc-b", "v1"), at);
        closeRun(second, WORK_SOURCE);
        store().purgeSource(WORK_SOURCE);
        assertEquals(WorkCounts.NONE, store().workCounts(WORK_SOURCE, at));
    }

    private static long version(List<WorkClaim> claims, String documentId) {
        return claims.stream().filter(claim -> claim.documentId().equals(documentId)).findFirst().orElseThrow()
                .workVersion();
    }
}
