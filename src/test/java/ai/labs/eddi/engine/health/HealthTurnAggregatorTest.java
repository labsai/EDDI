/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("HealthTurnAggregator — rows added up per version, origin and day")
class HealthTurnAggregatorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    static HealthTurn row(String conversation, int step, String exec, Integer version, String origin, Double cost, Double discardedCost,
                          String errorKind, List<HealthFailure> failures, boolean userInput, boolean discarded) {
        return new HealthTurn(conversation, step, exec, "agent", version, null, "user", origin, DAY, Instant.parse("2026-11-10T10:00:00Z"),
                Instant.parse("2026-11-10T10:00:00Z"), cost, discardedCost, 10L, 5L, 15L, 100L, errorKind, false, failures, false, 2, 1, 1, 0,
                userInput, true, false, List.of("a"), discarded);
    }

    @Test
    @DisplayName("undo: the discarded row counts only towards spend, so spend equals the conversation's cost and turns are not doubled")
    void undoDoesNotDoubleCount() {
        var aggregator = new HealthTurnAggregator();
        // Step 1 ran (0.10), was undone, and its replacement ran (0.20) carrying 0.10
        // as discarded cost.
        aggregator.accept(row("c1", 1, "e1", 1, "api", 0.10, null, null, List.of(), true, true));
        aggregator.accept(row("c1", 1, "e2", 1, "api", 0.20, 0.10, null, List.of(), true, false));
        aggregator.accept(row("c1", 0, "e0", 1, "api", null, null, null, List.of(), false, false));

        HealthTurnAggregate result = aggregator.result().get(0);

        assertEquals(2, result.turns());
        assertEquals(1, result.conversations());
        assertEquals(0.30, result.costUsd(), 1e-9, "conversationCost(steps, redoCache) of the conversation");
        assertEquals(1, result.userInputTurns());
    }

    @Test
    @DisplayName("failures, error kinds, tools and durations are summed; groups split by version and origin")
    void sums() {
        var aggregator = new HealthTurnAggregator();
        aggregator.accept(row("c1", 1, "e1", 1, "api", 0.1, null, "TURN_ERROR",
                List.of(new HealthFailure("TRANSIENT", null, "retry"), new HealthFailure("AUTH", "m", "none")), true, false));
        aggregator.accept(row("c2", 1, "e2", 1, "api", 0.1, null, "TURN_DEADLINE", List.of(new HealthFailure("TRANSIENT", "m", "cascade")), true,
                false));
        aggregator.accept(row("c3", 1, "e3", 2, "api", 0.1, null, null, List.of(), true, false));
        aggregator.accept(row("c4", 1, "e4", 1, "schedule", 0.1, null, null, List.of(), true, false));
        aggregator.accept(null);
        // A discard marker whose row never came: no timestamp, no turn.
        aggregator.accept(new HealthTurn("c9", -1, "e9", "agent", null, null, "user", null, null, null, null, null, null, null, null, null, null,
                null, false, List.of(), false, 0, 0, 0, 0, false, false, false, List.of(), true));

        List<HealthTurnAggregate> result = aggregator.result();

        assertEquals(3, result.size());
        HealthTurnAggregate v1api = result.get(0);
        assertEquals(1, v1api.agentVersion());
        assertEquals("api", v1api.origin());
        assertEquals(2, v1api.turns());
        assertEquals(2, v1api.failedTurns());
        assertEquals(Map.of("TURN_ERROR", 1L, "TURN_DEADLINE", 1L), v1api.errorKinds());
        assertEquals(Map.of("TRANSIENT", 2L, "AUTH", 1L), v1api.failuresByClass());
        assertEquals(2, v1api.recoveredFailures());
        assertEquals(4, v1api.toolCalls());
        assertEquals(2, v1api.toolFailures());
        assertEquals(2, v1api.toolRefusals());
        assertEquals(30, v1api.totalTokens());
        assertEquals(200, v1api.durationMsTotal());
        assertEquals(2, v1api.durationCount());
        assertEquals("schedule", result.get(1).origin());
        assertEquals(2, result.get(2).agentVersion());
    }
}
