/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.outcomes;

import ai.labs.eddi.engine.groups.outcomes.IOutcomeEventStore.InsertOutcome;
import ai.labs.eddi.engine.groups.outcomes.OutcomeEvent.SubjectType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract of {@link IOutcomeEventStore}, run against a real database by
 * the MongoDB and PostgreSQL ITs and against the in-memory double by
 * {@link InMemoryOutcomeStoreContractTest}. Each implementing class hands out
 * an empty store per test.
 */
public interface OutcomeStoreContract {

    Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
    /** A per-subject cap no test outside the cap tests reaches. */
    int ROOMY = 100;

    IOutcomeEventStore outcomes();

    static OutcomeEvent event(String id, String groupId, String subjectId, Instant recordedAt, String by) {
        return new OutcomeEvent(id, groupId, SubjectType.TASK, subjectId, "reply_received", "replies", 3.0, "count", "note " + id, "crm", by,
                recordedAt.minusSeconds(60), recordedAt, null);
    }

    private void put(OutcomeEvent e) {
        assertEquals(InsertOutcome.INSERTED, outcomes().insert(e, ROOMY));
    }

    @Test
    default void insertIsIdempotentAndRoundTrips() {
        OutcomeEvent e = event("e1", "g1", "t1", T0, "alice");
        assertEquals(InsertOutcome.INSERTED, outcomes().insert(e, ROOMY));
        assertEquals(InsertOutcome.DUPLICATE, outcomes().insert(e, ROOMY), "the same id stores once");

        List<OutcomeEvent> read = outcomes().listBySubject("g1", "t1", 10);
        assertEquals(1, read.size());
        assertEquals(e, read.get(0));
    }

    @Test
    default void aFullSubjectRefusesMoreButStillRecognisesADuplicate() {
        put(event("a", "g1", "t1", T0, "alice"));
        put(event("b", "g1", "t1", T0.plusSeconds(1), "alice"));

        assertEquals(InsertOutcome.FULL, outcomes().insert(event("c", "g1", "t1", T0.plusSeconds(2), "alice"), 2));
        assertEquals(InsertOutcome.DUPLICATE, outcomes().insert(event("a", "g1", "t1", T0, "alice"), 2), "a retry is not a refusal");
        assertEquals(InsertOutcome.INSERTED, outcomes().insert(event("d", "g1", "t2", T0, "alice"), 2), "the cap is per subject");
        assertEquals(InsertOutcome.INSERTED, outcomes().insert(event("e", "g2", "t1", T0, "alice"), 2), "and per team");
        assertEquals(List.of("b", "a"), outcomes().listBySubject("g1", "t1", ROOMY).stream().map(OutcomeEvent::id).toList());
    }

    @Test
    default void roomFreedByADeleteCanBeUsedAgain() {
        put(event("old", "g1", "t1", T0, "a"));
        put(event("new", "g1", "t1", T0.plusSeconds(500), "a"));
        outcomes().deleteRecordedBefore("g1", T0.plusSeconds(100));

        assertEquals(InsertOutcome.INSERTED, outcomes().insert(event("next", "g1", "t1", T0.plusSeconds(600), "a"), 2));
        assertEquals(InsertOutcome.FULL, outcomes().insert(event("over", "g1", "t1", T0.plusSeconds(700), "a"), 2));
    }

    @Test
    default void concurrentInsertsIntoTheLastFreePlaceStoreExactlyOne() throws Exception {
        put(event("a", "g1", "t1", T0, "alice"));
        put(event("b", "g1", "t1", T0.plusSeconds(1), "alice"));
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<InsertOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            OutcomeEvent e = event("w" + i, "g1", "t1", T0.plusSeconds(10 + i), "alice");
            futures.add(pool.submit(() -> {
                start.await();
                return outcomes().insert(e, 3);
            }));
        }
        start.countDown();
        int inserted = 0;
        for (Future<InsertOutcome> f : futures) {
            InsertOutcome outcome = f.get();
            if (outcome == InsertOutcome.INSERTED) {
                inserted++;
            } else {
                assertEquals(InsertOutcome.FULL, outcome);
            }
        }
        pool.shutdown();
        assertEquals(1, inserted);
        assertEquals(3, outcomes().listBySubject("g1", "t1", ROOMY).size());
    }

    @Test
    default void readsAreScopedToTheTeam() {
        put(event("a", "g1", "t1", T0, "alice"));
        put(event("b", "g2", "t1", T0, "alice"));

        assertEquals(List.of("a"), outcomes().listBySubject("g1", "t1", 10).stream().map(OutcomeEvent::id).toList());
        assertEquals(1, outcomes().countByGroup("g1"));
        assertEquals(List.of("b"), outcomes().listBySubject("g2", "t1", 10).stream().map(OutcomeEvent::id).toList());
        assertTrue(outcomes().listBySubject("g1", "other", 10).isEmpty());
        assertEquals(List.of("b"), outcomes().listBySubjects("g2", List.of("t1", "t9"), 10).stream().map(OutcomeEvent::id).toList());
        assertTrue(outcomes().listBySubjects("g1", List.of(), 10).isEmpty());
    }

    @Test
    default void listsNewestFirstAndHonoursSinceAndLimit() {
        put(event("old", "g1", "t1", T0, "a"));
        put(event("mid", "g1", "t2", T0.plusSeconds(100), "a"));
        put(event("new", "g1", "t3", T0.plusSeconds(200), "a"));

        assertEquals(List.of("new", "mid", "old"), outcomes().listByGroup("g1", null, 10).stream().map(OutcomeEvent::id).toList());
        assertEquals(List.of("new", "mid"), outcomes().listByGroup("g1", T0.plusSeconds(100), 10).stream().map(OutcomeEvent::id).toList());
        assertEquals(List.of("new"), outcomes().listByGroup("g1", null, 1).stream().map(OutcomeEvent::id).toList());
    }

    @Test
    default void deleteOldestBeyondKeepsTheNewestAndOnlyOfThatTeam() {
        for (int i = 0; i < 5; i++) {
            put(event("g1-" + i, "g1", "t", T0.plusSeconds(i), "a"));
        }
        put(event("g2-0", "g2", "t", T0, "a"));

        assertEquals(3, outcomes().deleteOldestBeyond("g1", 2));
        assertEquals(List.of("g1-4", "g1-3"), outcomes().listByGroup("g1", null, 10).stream().map(OutcomeEvent::id).toList());
        assertEquals(1, outcomes().countByGroup("g2"));
        assertEquals(0, outcomes().deleteOldestBeyond("g1", 2));
    }

    @Test
    default void deleteRecordedBeforeAndByGroup() {
        put(event("old", "g1", "t", T0, "a"));
        put(event("new", "g1", "t", T0.plusSeconds(500), "a"));
        put(event("other", "g2", "t", T0, "a"));

        assertEquals(1, outcomes().deleteRecordedBefore("g1", T0.plusSeconds(100)));
        assertEquals(List.of("new"), outcomes().listByGroup("g1", null, 10).stream().map(OutcomeEvent::id).toList());
        assertEquals(1, outcomes().deleteByGroup("g1"));
        assertEquals(0, outcomes().countByGroup("g1"));
        assertEquals(1, outcomes().countByGroup("g2"));
    }

    @Test
    default void erasureReplacesThePrincipalAndKeepsTheEvent() {
        put(event("a", "g1", "t", T0, "alice"));
        put(event("b", "g1", "t", T0.plusSeconds(1), "bob"));

        assertEquals(1, outcomes().readByUser("alice", 10).size());
        assertEquals(1, outcomes().eraseUser("alice", "erased-1"));

        assertTrue(outcomes().readByUser("alice", 10).isEmpty());
        List<OutcomeEvent> all = outcomes().listByGroup("g1", null, 10);
        assertEquals(2, all.size());
        assertEquals("bob", all.get(0).recordedBy());
        assertEquals("erased-1", all.get(1).recordedBy());
    }

    @Test
    default void aMissingPrincipalRoundTripsAsNull() {
        put(event("a", "g1", "t", T0, null));
        assertEquals(null, outcomes().listBySubject("g1", "t", 10).get(0).recordedBy());
    }
}
