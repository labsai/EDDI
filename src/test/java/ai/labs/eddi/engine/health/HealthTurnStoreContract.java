/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.health.model.HealthTurn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link IHealthTurnStore} contract, run against a real database by each
 * implementation's {@code *IT}. Each implementing class must hand out a store
 * over an empty collection/table per test.
 */
public interface HealthTurnStoreContract {

    /** A store over an empty collection/table. */
    IHealthTurnStore store();

    LocalDate DAY = LocalDate.of(2026, 11, 10);
    Instant T0 = Instant.parse("2026-11-10T10:00:00Z");

    static HealthTurn row(String conversation, int step, String exec, String agent, String user, LocalDate day, Instant at, Double cost,
                          boolean paused) {
        return new HealthTurn(conversation, step, exec, agent, 1, null, user, "api", day, at, at, cost, null, 10L, 5L, 15L, 100L, null, paused,
                List.of(new HealthFailure("TIMEOUT", "m", "retry")), false, 2, 1, 0, 0, true, true, paused, List.of("greet", "$weird"), false);
    }

    static HealthTurn row(String conversation, int step, String exec, Instant at, Double cost, boolean paused) {
        return row(conversation, step, exec, "agent", "user", LocalDate.ofInstant(at, ZoneOffset.UTC), at, cost, paused);
    }

    private HealthTurn only(String conversation) {
        List<HealthTurn> rows = store().findByConversation(conversation, 10);
        assertEquals(1, rows.size(), rows.toString());
        return rows.get(0);
    }

    @Test
    @DisplayName("upsert inserts, then replaces the same execution's row — no second row, no increment, every field round-trips")
    default void upsertReplaces() {
        HealthTurn first = row("c1", 1, "e1", T0, 0.10, true);
        store().upsert(first);
        assertEquals(first, only("c1"));

        HealthTurn finished = row("c1", 1, "e1", T0.plusSeconds(30), 0.25, false);
        store().upsert(finished);
        HealthTurn stored = only("c1");
        assertEquals(0.25, stored.costUsd(), "replaced, not added");
        assertFalse(stored.paused());
        assertEquals(List.of("greet", "$weird"), stored.actions());
    }

    @Test
    @DisplayName("a HITL pause on day D resumed on D+1 stays on D, with its first timestamp")
    default void dayIsFirstRecorded() {
        Instant paused = Instant.parse("2026-11-10T23:59:00Z");
        store().upsert(row("c1", 1, "e1", paused, 0.10, true));
        store().upsert(row("c1", 1, "e1", Instant.parse("2026-11-11T08:00:00Z"), 0.30, false));

        HealthTurn stored = only("c1");
        assertEquals(DAY, stored.day());
        assertEquals(paused, stored.timestamp());
        assertEquals(Instant.parse("2026-11-11T08:00:00Z"), stored.updatedAt());
        assertEquals(0.30, stored.costUsd());
    }

    @Test
    @DisplayName("a stale write (older updatedAt) does not overwrite a newer row")
    default void staleWriteIgnored() {
        store().upsert(row("c1", 1, "e1", T0.plusSeconds(60), 0.40, false));
        store().upsert(row("c1", 1, "e1", T0, 0.10, true));

        HealthTurn stored = only("c1");
        assertEquals(0.40, stored.costUsd());
        assertFalse(stored.paused());
    }

    @Test
    @DisplayName("insertIfAbsent never replaces an existing row")
    default void insertIfAbsent() {
        store().upsert(row("c1", 1, "e1", T0, 0.40, false));
        store().insertIfAbsent(row("c1", 1, "e1", T0.plusSeconds(60), 0.99, true));
        assertEquals(0.40, only("c1").costUsd());

        store().insertIfAbsent(row("c2", 1, "e2", T0, 0.05, false));
        assertEquals(0.05, only("c2").costUsd());
    }

    @Test
    @DisplayName("discard is sticky: a later replace keeps it, and a mark before the row arrives survives the row's insert")
    default void discardSticky() {
        store().upsert(row("c1", 1, "e1", T0, 0.10, false));
        assertEquals(1L, store().markDiscarded(row("c1", 1, "e2", T0.plusSeconds(5), 0.2, false), List.of("e1")));
        store().upsert(row("c1", 1, "e1", T0.plusSeconds(10), 0.10, false));
        assertTrue(only("c1").discarded());

        // The mark lands first (another node's queue held the row back).
        store().markDiscarded(row("c2", 2, "e9", T0, 0.2, false), List.of("late"));
        assertTrue(store().findByConversation("c2", 10).isEmpty(), "a marker is not a row");
        store().upsert(row("c2", 1, "late", T0, 0.10, false));
        HealthTurn late = only("c2");
        assertTrue(late.discarded());
        assertEquals(DAY, late.day());
        assertEquals(1, late.stepIndex());
    }

    @Test
    @DisplayName("streaming by agent and day returns that day's rows only, without markers")
    default void streamsAgentDay() {
        store().upsert(row("c1", 0, "a", "agent-1", "u", DAY, T0, 0.1, false));
        store().upsert(row("c1", 1, "b", "agent-1", "u", DAY, T0.plusSeconds(1), 0.1, false));
        store().upsert(row("c2", 0, "c", "agent-1", "u", DAY.minusDays(1), T0.minusSeconds(86_400), 0.1, false));
        store().upsert(row("c3", 0, "d", "agent-2", "u", DAY, T0, 0.1, false));
        store().markDiscarded(row("c1", 2, "x", "agent-1", "u", DAY, T0, 0.1, false), List.of("ghost"));

        List<HealthTurn> streamed = new ArrayList<>();
        store().forEachByAgentAndDay("agent-1", DAY, streamed::add);

        assertEquals(2, streamed.size(), streamed.toString());
        assertEquals(3, store().findByAgentAndDayRange("agent-1", DAY.minusDays(1), DAY, 10).size());
        assertEquals(DAY.minusDays(1), store().findByAgentAndDayRange("agent-1", DAY.minusDays(1), DAY, 10).get(0).day());
    }

    @Test
    @DisplayName("deletes: by user (markers included), by conversation, and by day for retention")
    default void deletes() {
        store().upsert(row("c1", 0, "a", "agent", "alice", DAY, T0, 0.1, false));
        store().upsert(row("c2", 0, "b", "agent", "bob", DAY, T0, 0.1, false));
        store().upsert(row("c3", 0, "c", "agent", "bob", DAY.minusDays(100), T0.minusSeconds(100L * 86_400), 0.1, false));
        store().markDiscarded(row("c1", 1, "d", "agent", "alice", DAY, T0, 0.1, false), List.of("gone"));

        assertEquals(2L, store().deleteByUserId("alice"), "the row and the marker");
        assertTrue(store().findByUserId("alice", 10).isEmpty());

        assertEquals(1L, store().deleteOlderThan(DAY.minusDays(90)));
        assertEquals(1, store().findByUserId("bob", 10).size());

        assertEquals(1L, store().deleteByConversationId("c2"));
        assertTrue(store().findByUserId("bob", 10).isEmpty());
    }

    @Test
    @DisplayName("retention also removes markers whose row never arrived")
    default void retentionTakesOldMarkers() {
        store().markDiscarded(row("c1", 1, "x", "agent", "u", DAY, DAY.minusDays(200).atStartOfDay(ZoneOffset.UTC).toInstant(), 0.1, false),
                List.of("orphan"));
        assertEquals(1L, store().deleteOlderThan(DAY.minusDays(90)));
    }

    @Test
    @DisplayName("a user's rows come newest first and respect the limit")
    default void findByUser() {
        store().upsert(row("c1", 0, "a", "agent", "u", DAY, T0, 0.1, false));
        store().upsert(row("c1", 1, "b", "agent", "u", DAY, T0.plusSeconds(60), 0.1, false));
        List<HealthTurn> rows = store().findByUserId("u", 1);
        assertEquals(1, rows.size());
        assertEquals("b", rows.get(0).executionId());
    }
}
