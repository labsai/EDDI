/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.outcomes;

import ai.labs.eddi.engine.groups.outcomes.OutcomeEvent.SubjectType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract of {@link IOutcomeEventStore}, run against a real database by
 * the MongoDB and PostgreSQL ITs. Each implementing class hands out an empty
 * store per test.
 */
public interface OutcomeStoreContract {

    Instant T0 = Instant.parse("2026-11-01T10:00:00Z");

    IOutcomeEventStore outcomes();

    static OutcomeEvent event(String id, String groupId, String subjectId, Instant recordedAt, String by) {
        return new OutcomeEvent(id, groupId, SubjectType.TASK, subjectId, "reply_received", "replies", 3.0, "count", "note " + id, "crm", by,
                recordedAt.minusSeconds(60), recordedAt, null);
    }

    @Test
    default void insertIsIdempotentAndRoundTrips() {
        OutcomeEvent e = event("e1", "g1", "t1", T0, "alice");
        assertTrue(outcomes().insertIfAbsent(e));
        assertFalse(outcomes().insertIfAbsent(e), "the same id stores once");

        List<OutcomeEvent> read = outcomes().listBySubject("g1", "t1", 10);
        assertEquals(1, read.size());
        assertEquals(e, read.get(0));
    }

    @Test
    default void readsAreScopedToTheTeam() {
        outcomes().insertIfAbsent(event("a", "g1", "t1", T0, "alice"));
        outcomes().insertIfAbsent(event("b", "g2", "t1", T0, "alice"));

        assertEquals(List.of("a"), outcomes().listBySubject("g1", "t1", 10).stream().map(OutcomeEvent::id).toList());
        assertEquals(1, outcomes().countByGroup("g1"));
        assertEquals(1, outcomes().countBySubject("g2", "t1"));
        assertEquals(0, outcomes().countBySubject("g1", "other"));
        assertEquals(List.of("b"), outcomes().listBySubjects("g2", List.of("t1", "t9"), 10).stream().map(OutcomeEvent::id).toList());
        assertTrue(outcomes().listBySubjects("g1", List.of(), 10).isEmpty());
    }

    @Test
    default void listsNewestFirstAndHonoursSinceAndLimit() {
        outcomes().insertIfAbsent(event("old", "g1", "t1", T0, "a"));
        outcomes().insertIfAbsent(event("mid", "g1", "t2", T0.plusSeconds(100), "a"));
        outcomes().insertIfAbsent(event("new", "g1", "t3", T0.plusSeconds(200), "a"));

        assertEquals(List.of("new", "mid", "old"), outcomes().listByGroup("g1", null, 10).stream().map(OutcomeEvent::id).toList());
        assertEquals(List.of("new", "mid"), outcomes().listByGroup("g1", T0.plusSeconds(100), 10).stream().map(OutcomeEvent::id).toList());
        assertEquals(List.of("new"), outcomes().listByGroup("g1", null, 1).stream().map(OutcomeEvent::id).toList());
    }

    @Test
    default void deleteOldestBeyondKeepsTheNewestAndOnlyOfThatTeam() {
        for (int i = 0; i < 5; i++) {
            outcomes().insertIfAbsent(event("g1-" + i, "g1", "t", T0.plusSeconds(i), "a"));
        }
        outcomes().insertIfAbsent(event("g2-0", "g2", "t", T0, "a"));

        assertEquals(3, outcomes().deleteOldestBeyond("g1", 2));
        assertEquals(List.of("g1-4", "g1-3"), outcomes().listByGroup("g1", null, 10).stream().map(OutcomeEvent::id).toList());
        assertEquals(1, outcomes().countByGroup("g2"));
        assertEquals(0, outcomes().deleteOldestBeyond("g1", 2));
    }

    @Test
    default void deleteRecordedBeforeAndByGroup() {
        outcomes().insertIfAbsent(event("old", "g1", "t", T0, "a"));
        outcomes().insertIfAbsent(event("new", "g1", "t", T0.plusSeconds(500), "a"));
        outcomes().insertIfAbsent(event("other", "g2", "t", T0, "a"));

        assertEquals(1, outcomes().deleteRecordedBefore("g1", T0.plusSeconds(100)));
        assertEquals(List.of("new"), outcomes().listByGroup("g1", null, 10).stream().map(OutcomeEvent::id).toList());
        assertEquals(1, outcomes().deleteByGroup("g1"));
        assertEquals(0, outcomes().countByGroup("g1"));
        assertEquals(1, outcomes().countByGroup("g2"));
    }

    @Test
    default void erasureReplacesThePrincipalAndKeepsTheEvent() {
        outcomes().insertIfAbsent(event("a", "g1", "t", T0, "alice"));
        outcomes().insertIfAbsent(event("b", "g1", "t", T0.plusSeconds(1), "bob"));

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
        outcomes().insertIfAbsent(event("a", "g1", "t", T0, null));
        assertEquals(null, outcomes().listBySubject("g1", "t", 10).get(0).recordedBy());
    }
}
