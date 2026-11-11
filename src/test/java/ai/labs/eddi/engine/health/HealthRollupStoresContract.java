/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthDirtyDay;
import ai.labs.eddi.engine.health.model.HealthMarker;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import ai.labs.eddi.engine.health.model.HealthUsage;
import ai.labs.eddi.engine.health.model.HealthUsagePoint;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The H2b stores — daily rows, the dirty-day queue, change markers — and the
 * adoption counts of the turn store, run against a real database by each
 * datastore's {@code *IT}. Every store must be over an empty collection/table.
 */
public interface HealthRollupStoresContract {

    IHealthTurnStore turns();

    IHealthDailyStore daily();

    IHealthDirtyDayStore dirty();

    IHealthMarkerStore markers();

    LocalDate DAY = LocalDate.of(2026, 11, 10);

    static HealthTurn turn(String conversation, int step, String exec, String user, LocalDate day, Integer version, String origin,
                           boolean userInput, boolean agentOutput, boolean discarded) {
        Instant at = day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(step * 60L);
        return new HealthTurn(conversation, step, exec, "agent", version, null, user, origin, day, at, at, 0.01, null, 10L, 5L, 15L, 900L, null,
                false, List.of(), false, 0, 0, 0, 0, userInput, agentOutput, false, List.of(), discarded);
    }

    static HealthTurnAggregate aggregate(Integer version, String origin, LocalDate day, long turns, Map<String, Long> classes) {
        return new HealthTurnAggregate("agent", version, origin, day, turns, 2, 3, 1, Map.of("TURN_ERROR", 1L), 0, 1, classes, 1, 0.25, 900, 1200,
                turns, 4, 1, 0, 2, 1, 0, 2, turns, List.of(1L, 2L, 3L), 4000, 5, 1);
    }

    // ------------------------------------------------------------------ daily rows

    @Test
    @DisplayName("replaceDay inserts, replaces and removes the agent-day's other rows; every counter round-trips")
    default void dailyReplace() {
        HealthTurnAggregate v1 = aggregate(1, "api", DAY, 10, Map.of("RATE_LIMITED", 3L));
        HealthTurnAggregate none = aggregate(null, "schedule", DAY, 4, Map.of());
        daily().replaceDay("agent", DAY, List.of(v1, none));
        daily().replaceDay("agent", DAY.minusDays(1), List.of(aggregate(1, "api", DAY.minusDays(1), 7, Map.of())));

        List<HealthTurnAggregate> found = sorted(daily().find("agent", DAY, DAY));
        assertEquals(List.of(none, v1).stream().sorted(order()).toList(), found);

        HealthTurnAggregate v2 = aggregate(2, "api", DAY, 12, Map.of());
        daily().replaceDay("agent", DAY, List.of(v2));
        daily().replaceDay("agent", DAY, List.of(v2));
        assertEquals(List.of(v2), daily().find("agent", DAY, DAY));
        assertEquals(1, daily().find("agent", DAY.minusDays(1), DAY.minusDays(1)).size(), "another day is untouched");

        daily().replaceDay("agent", DAY, List.of());
        assertTrue(daily().find("agent", DAY, DAY).isEmpty());
    }

    @Test
    @DisplayName("rows of several agents, the first day of each version, and retention by day")
    default void dailyQueries() {
        daily().replaceDay("agent", DAY.minusDays(3), List.of(aggregate(1, "api", DAY.minusDays(3), 5, Map.of())));
        daily().replaceDay("agent", DAY, List.of(aggregate(1, "api", DAY, 5, Map.of()), aggregate(2, "api", DAY, 5, Map.of())));
        HealthTurnAggregate other = new HealthTurnAggregate("other", 1, "api", DAY, 1, 1, 1, 0, Map.of(), 0, 0, Map.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, List.of(), 4000, 0, 0);
        daily().replaceDay("other", DAY, List.of(other));

        assertEquals(4, daily().findForAgents(List.of("agent", "other"), DAY.minusDays(5), DAY).size());
        assertEquals(1, daily().findForAgents(List.of("other"), DAY, DAY).size());
        assertTrue(daily().findForAgents(List.of(), DAY, DAY).isEmpty());
        assertEquals(Map.of(1, DAY.minusDays(3), 2, DAY), daily().firstDayByVersion("agent"));

        assertEquals(1, daily().deleteOlderThan(DAY.minusDays(1)));
        assertEquals(Map.of(1, DAY, 2, DAY), daily().firstDayByVersion("agent"));
    }

    private static Comparator<HealthTurnAggregate> order() {
        return Comparator.comparing((HealthTurnAggregate a) -> a.agentVersion() == null ? -1 : a.agentVersion())
                .thenComparing(HealthTurnAggregate::origin);
    }

    private static List<HealthTurnAggregate> sorted(List<HealthTurnAggregate> rows) {
        return rows.stream().sorted(order()).toList();
    }

    // ------------------------------------------------------------------ dirty days

    @Test
    @DisplayName("a dirty day is one entry; a re-mark gives it a new token, so clearing with the old token keeps it")
    default void dirtyTokens() {
        dirty().mark("agent", List.of(DAY, DAY.minusDays(1)));
        dirty().mark("agent", List.of(DAY));
        assertEquals(2, dirty().count());
        List<HealthDirtyDay> oldest = dirty().oldest(10);
        assertEquals(2, oldest.size());
        HealthDirtyDay today = oldest.stream().filter(d -> d.day().equals(DAY)).findFirst().orElseThrow();

        dirty().mark("agent", List.of(DAY));
        assertFalse(dirty().clear(today), "marked again meanwhile");
        HealthDirtyDay fresh = dirty().oldest(10).stream().filter(d -> d.day().equals(DAY)).findFirst().orElseThrow();
        assertTrue(dirty().clear(fresh));
        assertEquals(1, dirty().count());
        assertEquals(DAY.minusDays(1), dirty().earliestPending("agent").orElseThrow());
        assertTrue(dirty().earliestPending("nobody").isEmpty());
    }

    // ------------------------------------------------------------------ markers

    @Test
    @DisplayName("markers: the agent's and the deployment-wide ones in range, oldest first; retention by time")
    default void markerQueries() {
        Instant t = DAY.atStartOfDay(ZoneOffset.UTC).toInstant();
        markers().append(new HealthMarker("m-1", "agent", t.plusSeconds(60), HealthMarker.KIND_DEPLOYMENT, "v2 deployed (production)", 2));
        markers().append(new HealthMarker("m-2", null, t, HealthMarker.KIND_GLOBAL_VARIABLE_CHANGE, "A global variable was changed", null));
        markers().append(new HealthMarker("m-3", "other", t, HealthMarker.KIND_DEPLOYMENT, "v1 deployed (production)", 1));
        markers().append(new HealthMarker("m-4", "agent", t.minusSeconds(86_400 * 10L), HealthMarker.KIND_DEPLOYMENT, "old", 1));

        List<HealthMarker> found = markers().find("agent", t, t.plusSeconds(86_400), 10);
        assertEquals(List.of("m-2", "m-1"), found.stream().map(HealthMarker::id).toList());
        assertEquals(new HealthMarker("m-1", "agent", t.plusSeconds(60), HealthMarker.KIND_DEPLOYMENT, "v2 deployed (production)", 2),
                found.get(1));
        assertEquals(1, markers().deleteOlderThan(t.minusSeconds(86_400)));
    }

    // ------------------------------------------------------------------ usage

    @Test
    @DisplayName("usage: sessions, engagement (input after the first output), distinct and returning users; discarded rows ignored")
    default void usage() {
        // c1 (alice): greeting, then a question — engaged.
        turns().upsert(turn("c1", 0, "e0", "alice", DAY, 1, "api", false, true, false));
        turns().upsert(turn("c1", 1, "e1", "alice", DAY, 1, "api", true, true, false));
        // c2 (bob): one question and its answer — not engaged.
        turns().upsert(turn("c2", 0, "e0", "bob", DAY, 1, "api", true, true, false));
        // c3 (alice, the day before): makes alice a returning user.
        turns().upsert(turn("c3", 0, "e0", "alice", DAY.minusDays(1), 1, "api", true, true, false));
        // c4: discarded only — not a session. c5: a schedule turn — another origin.
        turns().upsert(turn("c4", 0, "e0", "carol", DAY, 1, "api", true, true, true));
        turns().upsert(turn("c5", 0, "e0", "dave", DAY, 2, "schedule", true, true, false));

        HealthUsage usage = turns().usage(new HealthUsageQuery("agent", DAY, DAY, null, List.of("api")), DAY.minusDays(30));
        assertEquals(new HealthUsage(2, 1, 3, 2, 1), usage);

        assertEquals(new HealthUsage(1, 0, 1, 1, 0), turns().usage(new HealthUsageQuery("agent", DAY, DAY, 2, List.of("schedule")), DAY));
        assertEquals(HealthUsage.EMPTY, turns().usage(new HealthUsageQuery("agent", DAY, DAY, null, List.of()), DAY));

        List<HealthUsagePoint> byDay = turns().dailyUsage(new HealthUsageQuery("agent", DAY.minusDays(1), DAY, null, List.of("api", "schedule")),
                "none");
        assertEquals(Set.of(new HealthUsagePoint(DAY, null, 3, 1, 4, 3), new HealthUsagePoint(DAY.minusDays(1), null, 1, 0, 1, 1)),
                Set.copyOf(byDay));
        List<HealthUsagePoint> byOrigin = turns().dailyUsage(new HealthUsageQuery("agent", DAY, DAY, null, List.of("api", "schedule")), "origin");
        assertEquals(Set.of(new HealthUsagePoint(DAY, "api", 2, 1, 3, 2), new HealthUsagePoint(DAY, "schedule", 1, 0, 1, 1)), Set.copyOf(byOrigin));
        List<HealthUsagePoint> byVersion = turns().dailyUsage(new HealthUsageQuery("agent", DAY, DAY, null, List.of("api", "schedule")), "version");
        assertEquals(Set.of("1", "2"), Set.copyOf(byVersion.stream().map(HealthUsagePoint::group).toList()));

        assertEquals(Map.of("agent", 3L), turns().sessionsByAgent(List.of("agent", "nobody"), DAY, DAY, List.of("api", "schedule")));
        assertTrue(turns().sessionsByAgent(List.of(), DAY, DAY, List.of("api")).isEmpty());
    }

    @Test
    @DisplayName("daysOf answers the stored (first-recorded) day of the executions, skipping unknown ones and markers")
    default void daysOf() {
        turns().upsert(turn("c1", 0, "e0", "alice", DAY.minusDays(1), 1, "api", false, true, false));
        // A resume the next day replaces the row but keeps its day.
        HealthTurn resumed = turn("c1", 0, "e0", "alice", DAY, 1, "api", true, true, false);
        turns().upsert(resumed);
        turns().markDiscarded(resumed, List.of("never-written"));
        assertEquals(Set.of(DAY.minusDays(1)), turns().daysOf("c1", List.of("e0", "never-written", "e9")));
        assertTrue(turns().daysOf("c1", List.of()).isEmpty());
    }
}
