/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthConfigPolicy.Resolved;
import ai.labs.eddi.engine.health.HealthSessionEvaluator.Input;
import ai.labs.eddi.engine.health.HealthSessionEvaluator.Ratings;
import ai.labs.eddi.engine.health.HealthSessionEvaluatorTest.Turn;
import ai.labs.eddi.engine.health.model.HealthEvent;
import ai.labs.eddi.engine.health.model.HealthOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.health.HealthSessionEvaluatorTest.T0;
import static ai.labs.eddi.engine.health.HealthSessionEvaluatorTest.turn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HealthSessionEvaluator — an external outcome event outranks every other source (H5)")
class HealthExternalOutcomeTest {

    static final Duration DAY = Duration.ofHours(24);
    static final Instant LATER = T0.plus(Duration.ofDays(3));

    static final Resolved CONFIG = new Resolved(Map.of("order_confirmed", "RESOLVED"), true, Map.of(), false, List.of("orderId"),
            Map.of("order.placed", "RESOLVED", "ticket.reopened", "FAILED", "order.viewed", "NONE"), false);

    static HealthEvent event(String type, int step, Instant at, boolean byConversation) {
        return new HealthEvent(type + step + at, "i", "agent", type, byConversation ? null : "orderId=A-1", null, "c1", null, at, at,
                LocalDate.ofInstant(at, ZoneOffset.UTC), HealthEvent.MATCHED, step, 1, "api", "user",
                byConversation ? HealthOutcomes.EVIDENCE_CONVERSATION_ID_EVENT : HealthOutcomes.EVIDENCE_EXTERNAL_EVENT, at);
    }

    static List<HealthOutcome> judge(List<Turn> turns, Instant now, HealthEvent... events) {
        return HealthSessionEvaluator.evaluate(new Input("c1", turns.stream().map(Turn::row).toList(), "api", v -> DAY, v -> CONFIG, null, null,
                Ratings.NONE, now, List.of(events)));
    }

    static List<Turn> exchange(String... lastActions) {
        return new ArrayList<>(List.of(turn(0, T0).noInput(), turn(1, T0.plusSeconds(60)).actions(lastActions)));
    }

    @Test
    @DisplayName("a mapped event beats the agent's own mapped action — FAILED over RESOLVED")
    void beatsAgentAction() {
        HealthOutcome s = judge(exchange("order_confirmed"), LATER, event("ticket.reopened", 0, T0.plusSeconds(3_600), false)).get(0);
        assertEquals(HealthOutcomes.FAILED, s.outcome());
        assertEquals(HealthOutcomes.EVIDENCE_EXTERNAL_EVENT, s.evidence());
        assertFalse(s.provisional());
    }

    @Test
    @DisplayName("a mapped event beats agent_closed, self_reported, structural failure and silence")
    void beatsEverythingElse() {
        Instant at = T0.plusSeconds(120);
        assertEquals(HealthOutcomes.RESOLVED, judge(exchange("CONVERSATION_END"), LATER, event("order.placed", 0, at, false)).get(0).outcome());
        List<Turn> reported = exchange();
        reported.get(1).reported("ESCALATED");
        assertEquals(HealthOutcomes.RESOLVED, judge(reported, LATER, event("order.placed", 0, at, false)).get(0).outcome());
        List<Turn> failed = exchange();
        failed.get(1).failed();
        HealthOutcome s = judge(failed, LATER, event("order.placed", 0, at, true)).get(0);
        assertEquals(HealthOutcomes.RESOLVED, s.outcome());
        assertEquals(HealthOutcomes.EVIDENCE_CONVERSATION_ID_EVENT, s.evidence(), "by conversation id");
        assertEquals(HealthOutcomes.FAILED, judge(exchange(), LATER, event("ticket.reopened", 0, at, false)).get(0).outcome(), "over silence");
    }

    @Test
    @DisplayName("of several events the one that happened last decides, whatever order they arrive in")
    void lastEventWins() {
        HealthEvent placed = event("order.placed", 0, T0.plusSeconds(100), false);
        HealthEvent reopened = event("ticket.reopened", 0, T0.plusSeconds(200), false);
        assertEquals(HealthOutcomes.FAILED, judge(exchange(), LATER, placed, reopened).get(0).outcome());
        assertEquals(HealthOutcomes.FAILED, judge(exchange(), LATER, reopened, placed).get(0).outcome());
    }

    @Test
    @DisplayName("an event declared NONE, or undeclared, is counted but decides nothing")
    void unmappedEventsDecideNothing() {
        HealthOutcome s = judge(exchange("order_confirmed"), LATER, event("order.viewed", 0, T0.plusSeconds(100), false),
                event("order.shipped", 0, T0.plusSeconds(200), false)).get(0);
        assertEquals(HealthOutcomes.RESOLVED, s.outcome());
        assertEquals(HealthOutcomes.EVIDENCE_AGENT_ACTION, s.evidence());
    }

    @Test
    @DisplayName("inside the window the external outcome is known but provisional")
    void provisionalWhileOpen() {
        HealthOutcome s = judge(exchange(), T0.plusSeconds(600), event("order.placed", 0, T0.plusSeconds(100), false)).get(0);
        assertEquals(HealthOutcomes.RESOLVED, s.outcome());
        assertTrue(s.provisional());
    }

    @Test
    @DisplayName("an event belongs to the session holding its step — the other session is judged without it")
    void eventsBelongToTheirSession() {
        List<Turn> turns = exchange();
        turns.add(turn(2, T0.plus(Duration.ofDays(2))));
        List<HealthOutcome> sessions = judge(turns, LATER.plus(Duration.ofDays(2)), event("ticket.reopened", 2, T0.plus(Duration.ofDays(2)), false));
        assertEquals(2, sessions.size());
        assertEquals(HealthOutcomes.ASSUMED_RESOLVED, sessions.get(0).outcome());
        assertEquals(HealthOutcomes.FAILED, sessions.get(1).outcome());
    }

    @Test
    @DisplayName("a session row carries the references its turns ended with, once each")
    void sessionCarriesRefs() {
        List<Turn> turns = exchange();
        List<HealthOutcome> sessions = HealthSessionEvaluator.evaluate(new Input("c1",
                List.of(turns.get(0).row().withRefs(List.of("orderId=A-1")), turns.get(1).row().withRefs(List.of("orderId=A-1", "orderId=A-2"))),
                "api", v -> DAY, v -> CONFIG, null, null, Ratings.NONE, LATER));
        assertEquals(List.of("orderId=A-1", "orderId=A-2"), sessions.get(0).refs());
    }
}
