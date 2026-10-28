/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.AlertSenders.AlertDeliveryException;
import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertEvent.Delivery;
import ai.labs.eddi.engine.alerts.model.AlertEvent.DeliveryStatus;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static ai.labs.eddi.engine.alerts.AlertTestData.NOW;
import static ai.labs.eddi.engine.alerts.AlertTestData.errorRule;
import static ai.labs.eddi.engine.alerts.AlertTestData.event;
import static ai.labs.eddi.engine.alerts.AlertTestData.pending;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AlertDeliveryService — per-target status, backoff, give-up")
class AlertDeliveryServiceTest {

    private static final DeliveryTarget SLACK = DeliveryTarget.slack("C0123ABC");
    private static final DeliveryTarget HOOK = DeliveryTarget.webhook("https://hooks.example.com/a", "${vault:hook}");

    private IAlertEventStore store;
    private AlertSenders senders;
    private AlertDeliveryService service;

    @BeforeEach
    void setUp() {
        store = mock(IAlertEventStore.class);
        senders = mock(AlertSenders.class);
        service = new AlertDeliveryService(store, senders, AlertSettings.defaults(), new AlertMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(store.updateDelivery(any(), anyLong())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
        // Each send is claimed first; by default the claim is free.
        when(store.claimSend(any(), any(), any(), any())).thenReturn(true);
        // Delivery reloads the event before each send; by default the store holds it
        // unchanged.
        when(store.read(anyString()))
                .thenAnswer(inv -> Optional.of(event(inv.getArgument(0), errorRule(List.of(ErrorKind.TURN_ERROR)), AlertEvent.State.PENDING,
                        List.of())));
    }

    private static AlertEvent withMatch(AlertEvent e, AlertMatch match) {
        return new AlertEvent(e.id(), e.ruleId(), e.ruleName(), AlertSource.RATING, e.kind(), e.agentId(), e.agentVersion(), e.conversationId(),
                e.firedAt(), e.summary(), match, e.deliveries(), e.state(), e.nextAttemptAt(), e.dedupeKey(), e.duplicates(), e.digestCount(),
                e.digestItems(), e.acknowledgedBy(), e.acknowledgedAt(), e.version());
    }

    private static final AlertMatch WITH_COMMENT = new AlertMatch(List.of("comment"), null, "r1", -1, "FIVE_STAR", 1, 0.0, null,
            "It kept looping on my order number.");
    private static final AlertMatch REDACTED = new AlertMatch(List.of("comment"), null, "r1", -1, "FIVE_STAR", 1, 0.0, null, null);

    @Test
    @DisplayName("first delivery sends the event as stored now: an excerpt blanked since the insert is not sent")
    void sendsTheStoredVersion() throws Exception {
        AlertRule rule = errorRule(List.of(ErrorKind.TURN_ERROR), SLACK);
        AlertEvent held = withMatch(event("e1", rule, AlertEvent.State.PENDING, pending(rule)), WITH_COMMENT);
        when(store.read("e1")).thenReturn(Optional.of(withMatch(held, REDACTED)));

        service.deliver(held, rule);

        ArgumentCaptor<AlertEvent> sent = ArgumentCaptor.forClass(AlertEvent.class);
        verify(senders).send(sent.capture(), eq(SLACK));
        assertNull(sent.getValue().match().commentExcerpt());
    }

    @Test
    @DisplayName("a retry of a redacted event sends the redacted version")
    void retrySendsRedacted() throws Exception {
        AlertRule rule = errorRule(List.of(ErrorKind.TURN_ERROR), HOOK);
        List<Delivery> retrying = List.of(new Delivery(HOOK.key(), HOOK.type(), HOOK.label(), DeliveryStatus.PENDING, 2, NOW, "HTTP 503", NOW));
        AlertEvent claimed = withMatch(event("e2", rule, AlertEvent.State.PENDING, retrying), WITH_COMMENT);
        when(store.read("e2")).thenReturn(Optional.of(withMatch(claimed, REDACTED)));

        service.deliver(claimed, rule);

        ArgumentCaptor<AlertEvent> sent = ArgumentCaptor.forClass(AlertEvent.class);
        verify(senders).send(sent.capture(), eq(HOOK));
        assertNull(sent.getValue().match().commentExcerpt());
    }

    @Test
    @DisplayName("an event deleted since it was claimed is not sent")
    void goneIsSkipped() throws Exception {
        AlertRule rule = errorRule(List.of(ErrorKind.TURN_ERROR), SLACK);
        when(store.read("e3")).thenReturn(Optional.empty());
        assertEquals(Optional.empty(), service.deliver(event("e3", rule, AlertEvent.State.PENDING, pending(rule)), rule));
        verify(senders, never()).send(any(), any());
        verify(store, never()).updateDelivery(any(), anyLong());
    }

    @Test
    @DisplayName("every target takes it: DELIVERED, nothing pending; the update is conditional on the held version")
    void delivered() {
        AlertRule rule = errorRule(List.of(ErrorKind.TURN_ERROR), SLACK, HOOK);
        AlertEvent event = event("e1", rule, AlertEvent.State.PENDING, pending(rule));

        AlertEvent after = service.deliver(event, rule).orElseThrow();

        assertEquals(AlertEvent.State.DELIVERED, after.state());
        assertNull(after.nextAttemptAt());
        after.deliveries().forEach(d -> assertEquals(DeliveryStatus.DELIVERED, d.status()));
        verify(store).updateDelivery(any(), eq(4L));
    }

    @Test
    @DisplayName("a failure is retried after 30s × 4^(n-1), and the event waits for the earliest retry")
    void backoff() throws Exception {
        AlertRule rule = errorRule(List.of(ErrorKind.TURN_ERROR), SLACK, HOOK);
        doThrow(new AlertDeliveryException("HTTP 503")).when(senders).send(any(), eq(HOOK));

        AlertEvent after = service.deliver(event("e1", rule, AlertEvent.State.PENDING, pending(rule)), rule).orElseThrow();

        assertEquals(AlertEvent.State.PENDING, after.state());
        Delivery hook = after.deliveries().get(1);
        assertEquals(DeliveryStatus.PENDING, hook.status());
        assertEquals(1, hook.attempts());
        assertEquals("HTTP 503", hook.lastError());
        assertEquals(NOW.plusSeconds(30), hook.nextAttemptAt());
        assertEquals(NOW.plusSeconds(30), after.nextAttemptAt());
        assertEquals(DeliveryStatus.DELIVERED, after.deliveries().get(0).status());

        assertEquals(Duration.ofSeconds(120), AlertSettings.defaults().backoff(2));
        assertEquals(Duration.ofSeconds(480), AlertSettings.defaults().backoff(3));
        assertEquals(Duration.ofHours(6), AlertSettings.defaults().backoff(50));
    }

    @Test
    @DisplayName("the last attempt gives up: FAILED, nothing pending; a delivered target is not sent again")
    void giveUp() throws Exception {
        AlertRule rule = errorRule(List.of(ErrorKind.TURN_ERROR), SLACK, HOOK);
        doThrow(new AlertDeliveryException("HTTP 500")).when(senders).send(any(), eq(HOOK));
        List<Delivery> deliveries = List.of(
                new Delivery(SLACK.key(), SLACK.type(), SLACK.label(), DeliveryStatus.DELIVERED, 1, NOW, null, null),
                new Delivery(HOOK.key(), HOOK.type(), HOOK.label(), DeliveryStatus.PENDING, 4, NOW, "HTTP 500", NOW));

        AlertEvent after = service.deliver(event("e1", rule, AlertEvent.State.PENDING, deliveries), rule).orElseThrow();

        assertEquals(AlertEvent.State.FAILED, after.state());
        assertEquals(DeliveryStatus.FAILED, after.deliveries().get(1).status());
        assertEquals(5, after.deliveries().get(1).attempts());
        assertNull(after.nextAttemptAt());
        verify(senders, never()).send(any(), eq(SLACK));
    }

    @Test
    @DisplayName("a retry not yet due is left alone")
    void notYetDue() throws Exception {
        AlertRule rule = errorRule(List.of(ErrorKind.TURN_ERROR), HOOK);
        List<Delivery> deliveries = List.of(new Delivery(HOOK.key(), HOOK.type(), HOOK.label(), DeliveryStatus.PENDING, 1, NOW, "x",
                NOW.plusSeconds(60)));
        service.deliver(event("e1", rule, AlertEvent.State.PENDING, deliveries), rule);
        verify(senders, never()).send(any(), any());
    }

    @Test
    @DisplayName("a deleted rule, or a target removed from it, fails the delivery instead of sending somewhere stale")
    void staleTargets() throws Exception {
        AlertRule rule = errorRule(List.of(ErrorKind.TURN_ERROR), HOOK);
        AlertEvent deleted = service.deliver(event("e1", rule, AlertEvent.State.PENDING, pending(rule)), null).orElseThrow();
        assertEquals("The rule was deleted", deleted.deliveries().get(0).lastError());
        assertEquals(AlertEvent.State.FAILED, deleted.state());

        AlertRule withoutHook = errorRule(List.of(ErrorKind.TURN_ERROR), SLACK);
        AlertEvent removed = service.deliver(event("e1", rule, AlertEvent.State.PENDING, pending(rule)), withoutHook).orElseThrow();
        assertEquals("The target is no longer on the rule", removed.deliveries().get(0).lastError());
        verify(senders, never()).send(any(), any());
    }

    @Test
    @DisplayName("retry from the inbox: failed targets back to pending, due now, attempts reset")
    void resetFailed() {
        AlertRule rule = errorRule(List.of(ErrorKind.TURN_ERROR), HOOK);
        List<Delivery> deliveries = List.of(new Delivery(HOOK.key(), HOOK.type(), HOOK.label(), DeliveryStatus.FAILED, 5, NOW, "HTTP 500", null));
        AlertEvent reset = AlertDeliveryService.resetFailed(event("e1", rule, AlertEvent.State.FAILED, deliveries), NOW);
        assertEquals(AlertEvent.State.PENDING, reset.state());
        assertEquals(NOW, reset.nextAttemptAt());
        assertEquals(0, reset.deliveries().get(0).attempts());
    }
}
