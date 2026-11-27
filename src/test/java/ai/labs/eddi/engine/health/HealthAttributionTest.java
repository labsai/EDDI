/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthAttribution.Party;
import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HealthAttribution — agent-caused vs. platform failures")
class HealthAttributionTest {

    static HealthTurn turn(String errorKind, List<HealthFailure> failures, Long durationMs, boolean paused) {
        return new HealthTurn("c", 1, "e", "agent", 1, null, "u", "api", LocalDate.of(2026, 11, 10), Instant.parse("2026-11-10T10:00:00Z"),
                Instant.parse("2026-11-10T10:00:00Z"), 0.1, null, 10L, 5L, 15L, durationMs, errorKind, paused, failures, false, 0, 0, 0, 0, true,
                true, false, List.of(), false);
    }

    @ParameterizedTest(name = "{0} with {1}/{2} -> {3}")
    @CsvSource({
            // errorKind, failure class, recovery, party
            "TURN_ERROR, , , AGENT", "TURN_ERROR, TRANSIENT, none, PLATFORM", "TURN_ERROR, RATE_LIMITED, none, PLATFORM",
            "TURN_DEADLINE, TIMEOUT, none, PLATFORM", "TURN_DEADLINE, , , AGENT", "TURN_ERROR, AUTH, none, AGENT",
            "TURN_ERROR, QUOTA_EXHAUSTED, none, AGENT", "TURN_ERROR, CONTEXT_TOO_LONG, none, AGENT", "TURN_ERROR, TRANSIENT, retry, AGENT",
            "COST_CEILING, TRANSIENT, none, AGENT", "TURN_INTERRUPTED, , , PLATFORM", "TENANT_BUDGET, , , PLATFORM"})
    void attributes(String kind, String cls, String recovery, Party expected) {
        List<HealthFailure> failures = cls == null ? List.of() : List.of(new HealthFailure(cls, "m", recovery));
        assertEquals(expected, HealthAttribution.attribute(turn(kind, failures, 100L, false)));
    }

    @Test
    @DisplayName("an unrecovered agent-class failure next to a platform one makes the turn the agent's")
    void mixed() {
        var failures = List.of(new HealthFailure("TRANSIENT", "a", "none"), new HealthFailure("BAD_REQUEST", "b", "none"));
        assertEquals(Party.AGENT, HealthAttribution.attribute(turn("TURN_ERROR", failures, 100L, false)));
        assertEquals(Party.NONE, HealthAttribution.attribute(turn(null, failures, 100L, false)));
    }

    @Test
    @DisplayName("a clean turn whose rate-limited attempt a retry recovered is still touched by the platform")
    void recoveredIsAffected() {
        assertTrue(HealthAttribution.platformAffected(turn(null, List.of(new HealthFailure("RATE_LIMITED", null, "retry")), 100L, false)));
        assertFalse(HealthAttribution.platformAffected(turn(null, List.of(new HealthFailure("AUTH", null, "fallback")), 100L, false)));
        assertFalse(HealthAttribution.platformAffected(turn(null, List.of(), 100L, false)));
    }

    @Test
    @DisplayName("the aggregator splits failed turns by party and fills duration buckets and Apdex counts; paused turns are not timed")
    void aggregatorCounts() {
        HealthTurnAggregator aggregator = new HealthTurnAggregator(1_000);
        aggregator.accept(turn("TURN_ERROR", List.of(new HealthFailure("TRANSIENT", null, "none")), 500L, false));
        aggregator.accept(turn("TURN_ERROR", List.of(), 2_000L, false));
        aggregator.accept(turn(null, List.of(new HealthFailure("RATE_LIMITED", null, "retry")), 5_000L, false));
        aggregator.accept(turn(null, List.of(), 90_000L, true));
        HealthTurnAggregate a = aggregator.result().get(0);
        assertEquals(4, a.turns());
        assertEquals(1, a.agentFailedTurns());
        assertEquals(1, a.platformFailedTurns());
        assertEquals(2, a.platformAffectedTurns());
        assertEquals(3, a.durationCount(), "the paused turn is not a latency sample");
        assertEquals(1, a.apdexSatisfied(), "500 ms <= T");
        assertEquals(1, a.apdexTolerating(), "2 s <= 4T; 5 s is frustrated");
        assertEquals(1_000, a.apdexTMs());
        assertEquals(HealthTurnAggregator.DURATION_BOUNDS_MS.length + 1, a.durationBuckets().size());
        assertEquals(1L, a.durationBuckets().get(HealthTurnAggregator.bucketOf(500)));
        assertEquals(4, a.tokenTurns());
    }

    @Test
    @DisplayName("bucketOf: inclusive upper bounds, overflow past the last")
    void buckets() {
        assertEquals(0, HealthTurnAggregator.bucketOf(0));
        assertEquals(0, HealthTurnAggregator.bucketOf(100));
        assertEquals(1, HealthTurnAggregator.bucketOf(101));
        assertEquals(HealthTurnAggregator.DURATION_BOUNDS_MS.length, HealthTurnAggregator.bucketOf(10_000_000));
    }
}
