/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static ai.labs.eddi.engine.alerts.AlertTestData.AGENT;
import static ai.labs.eddi.engine.alerts.AlertTestData.CONVERSATION;
import static ai.labs.eddi.engine.alerts.AlertTestData.NOW;
import static ai.labs.eddi.engine.alerts.AlertTestData.RULE_ID;
import static ai.labs.eddi.engine.alerts.AlertTestData.errorRule;
import static ai.labs.eddi.engine.alerts.AlertTestData.rating;
import static ai.labs.eddi.engine.alerts.AlertTestData.ratingRule;
import static ai.labs.eddi.engine.alerts.AlertTestData.withThrottle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AlertEngine — dedupe, throttle, outbox, delivery")
class AlertEngineTest {

    private AlertRuleCache cache;
    private IAlertEventStore store;
    private AlertDeliveryService delivery;
    private SimpleMeterRegistry registry;
    private AlertEngine engine;

    @BeforeEach
    void setUp() {
        cache = mock(AlertRuleCache.class);
        store = mock(IAlertEventStore.class);
        delivery = mock(AlertDeliveryService.class);
        registry = new SimpleMeterRegistry();
        engine = new AlertEngine(cache, store, delivery, AlertSettings.defaults(), new AlertMetrics(registry), null,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(store.insert(any())).thenAnswer(inv -> Optional.of(((AlertEvent) inv.getArgument(0)).withId("6aba70011a25f8be7920e001")));
        when(store.updateDelivery(any(), anyLong())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
        when(delivery.deliver(any(), any())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
    }

    @Test
    @DisplayName("the outbox row is written before delivery, with a claim lease, and then delivered")
    void outboxBeforeDelivery() {
        AlertRule rule = ratingRule(0.5, null, null, DeliveryTarget.slack("C0123ABC"));
        when(cache.rulesFor(AGENT)).thenReturn(List.of(rule));
        when(store.incrementWindow(eq(RULE_ID), any(), any())).thenReturn(1L);

        engine.evaluateRating(rating(1, null, List.of()));

        ArgumentCaptor<AlertEvent> inserted = ArgumentCaptor.forClass(AlertEvent.class);
        var order = inOrder(store, delivery);
        order.verify(store).insert(inserted.capture());
        order.verify(delivery).deliver(any(), eq(rule));
        assertEquals(AlertEvent.State.PENDING, inserted.getValue().state());
        assertEquals(NOW.plus(Duration.ofMinutes(2)), inserted.getValue().nextAttemptAt());
        assertEquals(1.0, registry.counter(AlertMetrics.FIRED, "source", "RATING").count());
    }

    @Test
    @DisplayName("a duplicate in the window (refused by the store) is counted and never delivered")
    void dedupe() {
        AlertRule rule = errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.slack("C0123ABC"));
        when(cache.rulesFor(AGENT)).thenReturn(List.of(rule));
        doReturn(Optional.empty()).when(store).insert(any());

        engine.evaluateTurn(new TurnOutcome(CONVERSATION, AGENT, 2, ErrorKind.TURN_ERROR));

        verify(store, never()).incrementWindow(any(), any(), any());
        verify(delivery, never()).deliver(any(), any());
        assertEquals(1.0, registry.counter(AlertMetrics.DEDUPLICATED).count());
    }

    @Test
    @DisplayName("the dedupe key is rule, agent, conversation and the epoch-aligned window")
    void dedupeKey() {
        AlertRule rule = withThrottle(errorRule(List.of(ErrorKind.TURN_ERROR)), new AlertRule.Throttle(60, 10), null);
        when(cache.rulesFor(AGENT)).thenReturn(List.of(rule));
        when(store.incrementWindow(any(), any(), any())).thenReturn(1L);
        engine = new AlertEngine(cache, store, delivery, AlertSettings.defaults(), new AlertMetrics(registry), null,
                Clock.fixed(Instant.parse("2026-10-24T12:34:56Z"), ZoneOffset.UTC));

        engine.evaluateTurn(new TurnOutcome(CONVERSATION, AGENT, 2, ErrorKind.TURN_ERROR));

        ArgumentCaptor<AlertEvent> inserted = ArgumentCaptor.forClass(AlertEvent.class);
        verify(store).insert(inserted.capture());
        Instant window = Instant.parse("2026-10-24T12:00:00Z");
        assertEquals(RULE_ID + "|" + AGENT + "|" + CONVERSATION + "|" + window.toEpochMilli(), inserted.getValue().dedupeKey());
        verify(store).incrementWindow(RULE_ID, window, window.plus(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("past the cap an alert is held for the digest — or kept in the inbox only when the rule has no digest")
    void throttle() {
        AlertRule hourly = withThrottle(errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.slack("C0123ABC")),
                new AlertRule.Throttle(60, 3), AlertRule.DigestMode.HOURLY);
        when(store.incrementWindow(any(), any(), any())).thenReturn(4L);

        Optional<AlertEvent> held = engine.fire(hourly, AlertMatch.error(ErrorKind.TURN_ERROR), AGENT, 2,
                CONVERSATION);
        assertEquals(AlertEvent.State.DIGEST_PENDING, held.orElseThrow().state());
        assertNull(held.get().nextAttemptAt());

        AlertRule none = withThrottle(hourly, new AlertRule.Throttle(60, 3), AlertRule.DigestMode.NONE);
        assertEquals(AlertEvent.State.INBOX_ONLY,
                engine.fire(none, AlertMatch.error(ErrorKind.TURN_ERROR), AGENT, 2, CONVERSATION).orElseThrow()
                        .state());
        verify(delivery, never()).deliver(any(), any());
        assertEquals(2.0, registry.counter(AlertMetrics.THROTTLED).count());
    }

    @Test
    @DisplayName("exactly at the cap it is still delivered")
    void atTheCap() {
        AlertRule rule = withThrottle(errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.slack("C0123ABC")), new AlertRule.Throttle(60, 3),
                null);
        when(store.incrementWindow(any(), any(), any())).thenReturn(3L);
        engine.fire(rule, AlertMatch.error(ErrorKind.TURN_ERROR), AGENT, 2, CONVERSATION);
        verify(delivery).deliver(any(), eq(rule));
    }

    @Test
    @DisplayName("a rule without targets records an inbox-only event with no lease")
    void inboxOnly() {
        AlertRule rule = errorRule(List.of(ErrorKind.TURN_ERROR));
        when(store.incrementWindow(any(), any(), any())).thenReturn(1L);
        AlertEvent event = engine.fire(rule, AlertMatch.error(ErrorKind.TURN_ERROR), AGENT, 2, CONVERSATION)
                .orElseThrow();
        assertEquals(AlertEvent.State.INBOX_ONLY, event.state());
        assertTrue(event.deliveries().isEmpty());
        verify(delivery, never()).deliver(any(), any());
    }

    @Test
    @DisplayName("windows are aligned to the epoch")
    void windowAlignment() {
        assertEquals(Instant.parse("2026-10-24T12:30:00Z"),
                AlertEngine.windowStart(Instant.parse("2026-10-24T12:44:59Z"), Duration.ofMinutes(15)));
    }
}
