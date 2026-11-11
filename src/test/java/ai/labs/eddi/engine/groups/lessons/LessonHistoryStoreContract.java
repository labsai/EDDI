/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.lessons;

import ai.labs.eddi.engine.groups.lessons.LessonHistoryEntry.Action;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract of {@link ILessonHistoryStore}, run against a real database by
 * the MongoDB and PostgreSQL ITs. Each implementing class hands out an empty
 * store.
 */
public interface LessonHistoryStoreContract {

    Instant T0 = Instant.parse("2026-11-01T10:00:00Z");

    ILessonHistoryStore history();

    static LessonHistoryEntry entry(String id, String groupId, Instant at) {
        return new LessonHistoryEntry(id, groupId, "retro:" + id, "text of " + id, Action.RETIRED, "wrong", null, List.of("o1", "o2"), "run-1",
                T0.minusSeconds(3600), at);
    }

    @Test
    default void insertIsIdempotentAndRoundTrips() {
        LessonHistoryEntry e = new LessonHistoryEntry("h1", "g1", "retro:abc", "Ship on Tuesdays", Action.MERGED, "same as retro:def", "retro:def",
                List.of(), "run-9", T0.minusSeconds(60), T0);
        assertTrue(history().insertIfAbsent(e));
        assertFalse(history().insertIfAbsent(e), "the same id stores once");

        List<LessonHistoryEntry> read = history().listByGroup("g1", 10);
        assertEquals(1, read.size());
        assertEquals(e, read.get(0));
    }

    @Test
    default void evidenceSurvivesTheRoundTrip() {
        history().insertIfAbsent(entry("h1", "g1", T0));
        assertEquals(List.of("o1", "o2"), history().listByGroup("g1", 10).get(0).evidence());
    }

    @Test
    default void readsAreScopedToTheTeamAndNewestFirst() {
        history().insertIfAbsent(entry("old", "g1", T0));
        history().insertIfAbsent(entry("new", "g1", T0.plusSeconds(100)));
        history().insertIfAbsent(entry("other", "g2", T0));

        assertEquals(List.of("new", "old"), history().listByGroup("g1", 10).stream().map(LessonHistoryEntry::id).toList());
        assertEquals(List.of("new"), history().listByGroup("g1", 1).stream().map(LessonHistoryEntry::id).toList());
        assertEquals(2, history().countByGroup("g1"));
        assertEquals(1, history().countByGroup("g2"));
    }

    @Test
    default void deleteOldestBeyondKeepsTheNewestAndOnlyOfThatTeam() {
        for (int i = 0; i < 5; i++) {
            history().insertIfAbsent(entry("g1-" + i, "g1", T0.plusSeconds(i)));
        }
        history().insertIfAbsent(entry("g2-0", "g2", T0));

        assertEquals(3, history().deleteOldestBeyond("g1", 2));
        assertEquals(List.of("g1-4", "g1-3"), history().listByGroup("g1", 10).stream().map(LessonHistoryEntry::id).toList());
        assertEquals(1, history().countByGroup("g2"));
        assertEquals(0, history().deleteOldestBeyond("g1", 2));
    }

    @Test
    default void deleteByGroupRemovesOnlyThatTeam() {
        history().insertIfAbsent(entry("a", "g1", T0));
        history().insertIfAbsent(entry("b", "g2", T0));

        assertEquals(1, history().deleteByGroup("g1"));
        assertEquals(0, history().countByGroup("g1"));
        assertEquals(1, history().countByGroup("g2"));
    }
}
