/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.spend;

import ai.labs.eddi.engine.documents.spend.IDocumentSpendStore.DaySpend;
import ai.labs.eddi.engine.documents.spend.IDocumentSpendStore.SubjectKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link IDocumentSpendStore} contract, run against a real database by each
 * implementation's {@code *IT} (and against the in-memory double by
 * {@code InMemoryDocumentSpendStoreTest}). Every test uses subjects of its own,
 * so the tests do not need an empty store.
 */
public interface DocumentSpendStoreContract {

    /**
     * A UTC epoch day (2100-01-01) far enough ahead that no bucket a test writes,
     * even {@code TODAY - 100} with its retention, has an {@code expiresAt} in the
     * past: MongoDB's TTL monitor would otherwise delete rows in the middle of a
     * test.
     */
    long TODAY = 47_482L;

    IDocumentSpendStore store();

    static String fresh(String prefix) {
        return prefix + UUID.randomUUID();
    }

    @Test
    @DisplayName("charges add up per (kind, subject, day) and each counts one call")
    default void chargesAddUp() {
        String user = fresh("user-");
        store().add(SubjectKind.USER, user, TODAY, 0.25, TODAY - 34);
        DaySpend after = store().add(SubjectKind.USER, user, TODAY, 0.5, TODAY - 34);
        assertEquals(0.75, after.spentUsd(), 1e-12);
        assertEquals(2L, after.calls());
        assertEquals(TODAY, after.epochDay());
        assertEquals(0.75, store().spent(SubjectKind.USER, user, TODAY), 1e-12);
        assertEquals(0.0, store().spent(SubjectKind.USER, user, TODAY - 1), "another day is another bucket");
        assertEquals(0.0, store().spent(SubjectKind.TEAM, user, TODAY), "another kind is another bucket");
        assertEquals(0.0, store().spent(SubjectKind.USER, fresh("user-"), TODAY), "no bucket reads as 0");
    }

    @Test
    @DisplayName("an unpriced or invalid amount adds no dollars but still counts the call")
    default void unpricedCountsTheCall() {
        String user = fresh("user-");
        store().add(SubjectKind.USER, user, TODAY, 0.0, TODAY - 34);
        store().add(SubjectKind.USER, user, TODAY, Double.NaN, TODAY - 34);
        DaySpend after = store().add(SubjectKind.USER, user, TODAY, -5.0, TODAY - 34);
        assertEquals(0.0, after.spentUsd(), 1e-12);
        assertEquals(3L, after.calls());
    }

    @Test
    @DisplayName("concurrent charges for one bucket add up exactly (atomic increments)")
    default void concurrentChargesAddUpExactly() throws Exception {
        String user = fresh("user-");
        String team = fresh("team:");
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // 0.25 is exact in binary, so the sum must be exact too
                        store().add(SubjectKind.USER, user, TODAY, 0.25, TODAY - 34);
                        store().add(SubjectKind.TEAM, team, TODAY, 0.25, TODAY - 34);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        double expected = threads * perThread * 0.25;
        assertEquals(expected, store().spent(SubjectKind.USER, user, TODAY), 0.0);
        assertEquals(expected, store().spent(SubjectKind.TEAM, team, TODAY), 0.0);
        DaySpend bucket = store().findBySubject(SubjectKind.USER, user, 10).getFirst();
        assertEquals((long) threads * perThread, bucket.calls(), "no lost increment");
    }

    @Test
    @DisplayName("a write deletes the same subject's buckets before the oldest retained day")
    default void retentionOnWrite() {
        String user = fresh("user-");
        String other = fresh("user-");
        store().add(SubjectKind.USER, user, TODAY - 40, 1.0, TODAY - 40 - 34);
        store().add(SubjectKind.USER, user, TODAY - 34, 1.0, TODAY - 34 - 34);
        store().add(SubjectKind.USER, other, TODAY - 40, 1.0, TODAY - 40 - 34);

        store().add(SubjectKind.USER, user, TODAY, 1.0, TODAY - 34);

        assertEquals(0.0, store().spent(SubjectKind.USER, user, TODAY - 40), "older than the retention: gone");
        assertEquals(1.0, store().spent(SubjectKind.USER, user, TODAY - 34), 1e-12, "the oldest retained day stays");
        assertEquals(1.0, store().spent(SubjectKind.USER, user, TODAY), 1e-12);
        assertEquals(1.0, store().spent(SubjectKind.USER, other, TODAY - 40), 1e-12,
                "another subject's old bucket is left to the sweep, not deleted by this write");
    }

    @Test
    @DisplayName("deleteBefore removes every subject's expired buckets and keeps the rest")
    default void deleteBeforeSweepsAllSubjects() {
        String a = fresh("user-");
        String b = fresh("team:");
        store().add(SubjectKind.USER, a, TODAY - 100, 1.0, TODAY - 134);
        store().add(SubjectKind.TEAM, b, TODAY - 100, 1.0, TODAY - 134);
        store().add(SubjectKind.TEAM, b, TODAY, 1.0, TODAY - 134);

        assertTrue(store().deleteBefore(TODAY - 34) >= 2);

        assertEquals(0.0, store().spent(SubjectKind.USER, a, TODAY - 100));
        assertEquals(0.0, store().spent(SubjectKind.TEAM, b, TODAY - 100));
        assertEquals(1.0, store().spent(SubjectKind.TEAM, b, TODAY), 1e-12);
    }

    @Test
    @DisplayName("findBySubject returns the subject's buckets newest first, at most max")
    default void findBySubjectNewestFirst() {
        String user = fresh("user-");
        store().add(SubjectKind.USER, user, TODAY - 2, 1.0, TODAY - 36);
        store().add(SubjectKind.USER, user, TODAY, 3.0, TODAY - 34);
        store().add(SubjectKind.USER, user, TODAY - 1, 2.0, TODAY - 35);
        store().add(SubjectKind.TEAM, user, TODAY, 9.0, TODAY - 34);

        List<DaySpend> all = store().findBySubject(SubjectKind.USER, user, 10);
        assertEquals(List.of(TODAY, TODAY - 1, TODAY - 2), all.stream().map(DaySpend::epochDay).toList());
        assertEquals(3.0, all.getFirst().spentUsd(), 1e-12);
        assertEquals(SubjectKind.USER, all.getFirst().kind());
        assertEquals(user, all.getFirst().subject());
        assertEquals(2, store().findBySubject(SubjectKind.USER, user, 2).size());
    }

    @Test
    @DisplayName("erasing a user removes every bucket of that user and nothing else; team buckets stay")
    default void erasureRemovesTheUsersBuckets() throws Exception {
        String user = fresh("user-");
        String other = fresh("user-");
        String team = fresh("team:");
        store().add(SubjectKind.USER, user, TODAY - 1, 1.0, TODAY - 35);
        store().add(SubjectKind.USER, user, TODAY, 2.0, TODAY - 34);
        store().add(SubjectKind.USER, other, TODAY, 4.0, TODAY - 34);
        store().add(SubjectKind.TEAM, team, TODAY, 3.0, TODAY - 34);
        // a team whose space id happens to equal the user id is still a team bucket
        store().add(SubjectKind.TEAM, user, TODAY, 5.0, TODAY - 34);

        var participant = new DocumentSpendGdprParticipant(store());
        @SuppressWarnings("unchecked")
        Map<String, Object> exported = (Map<String, Object>) participant.export(user);
        assertEquals(2, ((List<?>) exported.get("days")).size());

        assertEquals(2L, participant.erase(user));

        assertTrue(store().findBySubject(SubjectKind.USER, user, 10).isEmpty(), "zero rows for the user");
        assertNull(participant.export(user));
        assertEquals(4.0, store().spent(SubjectKind.USER, other, TODAY), 1e-12);
        assertEquals(3.0, store().spent(SubjectKind.TEAM, team, TODAY), 1e-12);
        assertEquals(5.0, store().spent(SubjectKind.TEAM, user, TODAY), 1e-12);
        assertEquals(0L, participant.erase(user), "a second erasure finds nothing");
    }
}
