/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertCondition;
import ai.labs.eddi.engine.alerts.model.AlertCondition.HealthTrigger;
import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertScope;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.alerts.model.MatchOp;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ai.labs.eddi.engine.alerts.AlertTestData.AGENT;
import static ai.labs.eddi.engine.alerts.AlertTestData.NOW;
import static ai.labs.eddi.engine.alerts.AlertTestData.RULE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AlertSource.HEALTH — validation, dedupe per condition, throttle, payload")
class HealthAlertSourceTest {

    static AlertRule health(AlertCondition condition, DeliveryTarget... targets) {
        return new AlertRule(RULE_ID, "Health", true, AlertScope.agent(AGENT), AlertSource.HEALTH, condition, Arrays.asList(targets),
                new AlertRule.Throttle(60, 2), AlertRule.DigestMode.HOURLY, null, null, null, null);
    }

    private static List<String> problems(AlertRule rule) {
        return AlertRuleValidator.validate(rule);
    }

    @Test
    @DisplayName("a HEALTH rule needs triggers; statuses are WATCH or NEEDS_ATTENTION and only with STATUS_CHANGE")
    void validation() {
        assertEquals(List.of(), problems(health(AlertCondition.health(List.of(HealthTrigger.STATUS_CHANGE), null))));
        assertEquals(List.of(), problems(health(AlertCondition.health(List.of(HealthTrigger.values()), List.of("NEEDS_ATTENTION")))));
        assertTrue(problems(health(AlertCondition.health(null, null))).get(0).contains("needs condition.healthTriggers"));
        assertTrue(problems(health(AlertCondition.health(List.of(HealthTrigger.BURN_RATE, HealthTrigger.BURN_RATE), null))).get(0)
                .contains("must not repeat"));
        assertTrue(problems(health(AlertCondition.health(List.of(HealthTrigger.BURN_RATE), List.of("WATCH")))).get(0)
                .contains("only with the STATUS_CHANGE"));
        assertTrue(problems(health(AlertCondition.health(List.of(HealthTrigger.STATUS_CHANGE), List.of("HEALTHY")))).get(0)
                .contains("each status is one of"));
        assertTrue(problems(health(AlertCondition.health(List.of(HealthTrigger.STATUS_CHANGE), List.of()))).get(0)
                .contains("at least one status"));
        AlertRule mixed = health(new AlertCondition(0.5, null, null, List.of(ErrorKind.TURN_ERROR), null, null, null, null,
                List.of(HealthTrigger.STATUS_CHANGE), null));
        assertTrue(problems(mixed).stream().anyMatch(p -> p.contains("do not belong to HEALTH rules")));
        AlertRule outputFields = health(new AlertCondition(null, null, null, null, "actions", MatchOp.EQ, "x", null,
                List.of(HealthTrigger.STATUS_CHANGE), null));
        assertTrue(problems(outputFields).stream().anyMatch(p -> p.contains("belong to OUTPUT_MATCH rules")));
        // …and the other sources refuse the health fields.
        AlertRule rating = new AlertRule(RULE_ID, "x", true, AlertScope.agent(AGENT), AlertSource.RATING,
                new AlertCondition(0.5, null, null, null, null, null, null, null, List.of(HealthTrigger.BURN_RATE), null), List.of(), null, null,
                null, null, null, null);
        assertTrue(problems(rating).stream().anyMatch(p -> p.contains("belong to HEALTH rules")));
        assertTrue(problems(new AlertRule(RULE_ID, "x", true, AlertScope.agent(AGENT), null, null, List.of(), null, null, null, null, null, null))
                .get(0).contains("HEALTH"));
    }

    private IAlertEventStore store;
    private AlertDeliveryService delivery;
    private SimpleMeterRegistry registry;
    private AlertEngine engine;

    @BeforeEach
    void setUp() {
        store = mock(IAlertEventStore.class);
        delivery = mock(AlertDeliveryService.class);
        registry = new SimpleMeterRegistry();
        engine = new AlertEngine(mock(AlertRuleCache.class), store, delivery, AlertSettings.defaults(), new AlertMetrics(registry), null, null,
                null, Clock.fixed(Instant.parse("2026-10-24T12:34:56Z"), ZoneOffset.UTC));
        when(store.insert(any())).thenAnswer(inv -> Optional.of(((AlertEvent) inv.getArgument(0)).withId("6aba70011a25f8be7920e001")));
        when(store.updateDelivery(any(), anyLong())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
        when(delivery.deliver(any(), any())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
    }

    @Test
    @DisplayName("each condition has its own dedupe key; no conversation; the summary names what changed; counted as HEALTH")
    void fire() {
        AlertRule rule = health(AlertCondition.health(List.of(HealthTrigger.STATUS_CHANGE), null), DeliveryTarget.slack("C0123ABC"));
        when(store.incrementWindow(any(), any(), any())).thenReturn(1L);
        AlertMatch match = AlertMatch.health(List.of("status WATCH → NEEDS_ATTENTION", "Resolution 41 %"), "NEEDS_ATTENTION", "WATCH",
                null);

        engine.fireHealth(rule, match, AGENT, "status:NEEDS_ATTENTION");

        ArgumentCaptor<AlertEvent> inserted = ArgumentCaptor.forClass(AlertEvent.class);
        verify(store).insert(inserted.capture());
        AlertEvent event = inserted.getValue();
        long window = Instant.parse("2026-10-24T12:00:00Z").toEpochMilli();
        assertEquals(RULE_ID + "|" + AGENT + "|health:status:NEEDS_ATTENTION|" + window, event.dedupeKey());
        assertEquals(null, event.conversationId());
        assertEquals(AlertSource.HEALTH, event.source());
        assertEquals("Health: status WATCH → NEEDS_ATTENTION, Resolution 41 % (agent " + AGENT + ")", event.summary());
        assertEquals(1.0, registry.counter(AlertMetrics.FIRED, "source", "HEALTH").count());
        verify(delivery).deliver(any(), eq(rule));
    }

    @Test
    @DisplayName("past the rule's cap a HEALTH alert waits for the digest, like every other")
    void throttled() {
        AlertRule rule = health(AlertCondition.health(List.of(HealthTrigger.BURN_RATE), null), DeliveryTarget.slack("C0123ABC"));
        when(store.incrementWindow(any(), any(), any())).thenReturn(3L);
        AlertEvent held = engine.fireHealth(rule, AlertMatch.health(List.of("burn"), "WATCH", null, "agentFailureRate"), AGENT, "burn")
                .orElseThrow();
        assertEquals(AlertEvent.State.DIGEST_PENDING, held.state());
        verify(delivery, times(0)).deliver(any(), any());
    }

    @Test
    @DisplayName("the delivered payload carries the status, the previous one and the measure")
    void payload() {
        AlertEvent event = new AlertEvent("e1", RULE_ID, "Health", AlertSource.HEALTH, AlertEvent.Kind.ALERT, AGENT, null, null, NOW, "s",
                AlertMatch.health(List.of("trend"), "WATCH", "HEALTHY", "resolutionRate"), List.of(), AlertEvent.State.PENDING, null, "k", 0, null,
                null, null, null, 0, null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> match = (Map<String, Object>) AlertPayloads.webhook(event, Optional.empty()).get("match");
        assertEquals("WATCH", match.get("healthStatus"));
        assertEquals("HEALTHY", match.get("previousHealthStatus"));
        assertEquals("resolutionRate", match.get("measureId"));
    }
}
