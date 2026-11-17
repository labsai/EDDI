/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthConfigPolicy.Resolved;
import ai.labs.eddi.engine.health.model.HealthEvent;
import ai.labs.eddi.engine.health.model.HealthOutcome;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthOutcomeEvaluator — matched business events reach the evaluation (H5)")
class HealthOutcomeEvaluatorEventsTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final int LIMIT = HealthOutcomeEvaluator.MAX_RANGE_TURNS + 1;

    private IHealthTurnStore turns;
    private IHealthOutcomeStore outcomes;
    private IHealthEventStore events;
    private HealthOutcomeEvaluator evaluator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        turns = mock(IHealthTurnStore.class);
        outcomes = mock(IHealthOutcomeStore.class);
        events = mock(IHealthEventStore.class);
        HealthConfigPolicy policy = mock(HealthConfigPolicy.class);
        when(policy.of(anyString(), any())).thenReturn(new Resolved(Map.of(), true, Map.of(), false, List.of("orderId"),
                Map.of("ticket.reopened", "FAILED"), false));
        when(policy.windowOf(anyString(), any())).thenReturn(Duration.ofHours(24));
        when(outcomes.watermark()).thenReturn(Optional.empty());
        evaluator = new HealthOutcomeEvaluator(turns, outcomes, policy, mock(IConversationRatingStore.class),
                new HealthErasureFence(Clock.fixed(NOW, ZoneOffset.UTC)), HealthSettings.defaults(), new HealthMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC));
        Instance<IHealthEventStore> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(events);
        evaluator.eventStore = instance;
    }

    static HealthEvent matched(int step) {
        Instant at = NOW.minus(Duration.ofDays(1));
        return new HealthEvent("e" + step, "i", "agent", "ticket.reopened", "orderId=A-1", null, "c1", null, at, at,
                LocalDate.ofInstant(at, ZoneOffset.UTC), HealthEvent.MATCHED, step, 1, "api", "user", HealthOutcomes.EVIDENCE_EXTERNAL_EVENT, at);
    }

    @Test
    @DisplayName("a matched event in the range decides the session — external_event over silence")
    @SuppressWarnings("unchecked")
    void eventDecides() {
        when(turns.findByConversationSteps("c1", 0, null, LIMIT)).thenReturn(HealthOutcomeEvaluatorTest.closedConversation("c1"));
        when(events.findMatchedByConversation("c1")).thenReturn(List.of(matched(0)));
        evaluator.evaluate("c1", null);
        ArgumentCaptor<Collection<HealthOutcome>> rows = ArgumentCaptor.forClass(Collection.class);
        verify(outcomes).upsert(rows.capture());
        HealthOutcome s = rows.getValue().iterator().next();
        assertEquals(HealthOutcomes.FAILED, s.outcome());
        assertEquals(HealthOutcomes.EVIDENCE_EXTERNAL_EVENT, s.evidence());
    }

    @Test
    @DisplayName("events outside the range read are left out; a read failure fails the evaluation (retried) rather than judge without them")
    void rangeAndFailure() {
        when(events.findMatchedByConversation("c1")).thenReturn(List.of(matched(0), matched(5), matched(9)));
        assertEquals(List.of(5), evaluator.events("c1", 3, 9).stream().map(HealthEvent::matchedStep).toList());
        assertEquals(List.of(5, 9), evaluator.events("c1", 3, null).stream().map(HealthEvent::matchedStep).toList());
        when(events.findMatchedByConversation("c2")).thenThrow(new HealthStoreException("down", null));
        when(turns.findByConversationSteps("c2", 0, null, LIMIT)).thenReturn(HealthOutcomeEvaluatorTest.closedConversation("c2"));
        assertThrows(HealthStoreException.class, () -> evaluator.evaluate("c2", null));
        evaluator.eventStore = null;
        assertTrue(evaluator.events("c1", 0, null).isEmpty());
    }
}
