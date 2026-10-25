/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertEvent;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static ai.labs.eddi.engine.alerts.AlertTestData.AGENT;
import static ai.labs.eddi.engine.alerts.AlertTestData.CONVERSATION;
import static ai.labs.eddi.engine.alerts.AlertTestData.NOW;
import static ai.labs.eddi.engine.alerts.AlertTestData.RULE_ID;
import static ai.labs.eddi.engine.alerts.AlertTestData.errorRule;
import static ai.labs.eddi.engine.alerts.AlertTestData.event;
import static ai.labs.eddi.engine.alerts.AlertTestData.pending;
import static ai.labs.eddi.engine.alerts.AlertTestData.withThrottle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AlertMaintenanceService — retries, digests, retention")
class AlertMaintenanceServiceTest {

    private IAlertEventStore events;
    private IAlertRuleStore rules;
    private AlertDeliveryService delivery;
    private AlertMaintenanceService service;

    @BeforeEach
    void setUp() {
        events = mock(IAlertEventStore.class);
        rules = mock(IAlertRuleStore.class);
        delivery = mock(AlertDeliveryService.class);
        service = new AlertMaintenanceService(events, rules, delivery, AlertSettings.defaults(), new AlertMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(events.claimDue(any(), any(), anyInt())).thenReturn(List.of());
        when(rules.findEnabled()).thenReturn(List.of());
        when(events.insert(any())).thenAnswer(inv -> Optional.of(((AlertEvent) inv.getArgument(0)).withId("6aba70011a25f8be7920e0d1")));
    }

    @Test
    @DisplayName("claims due events with a lease and delivers each with its current rule")
    void retries() {
        AlertRule rule = errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.slack("C0123ABC"));
        AlertEvent due = event("e1", rule, AlertEvent.State.PENDING, pending(rule));
        when(events.claimDue(NOW, NOW.plus(Duration.ofMinutes(2)), AlertMaintenanceService.CLAIM_BATCH)).thenReturn(List.of(due));
        when(rules.read(RULE_ID)).thenReturn(Optional.of(rule));

        AlertMaintenanceService.Result result = service.run();

        verify(delivery).deliver(due, rule);
        assertEquals(1, result.retried());
    }

    @Test
    @DisplayName("a digest goes out once the oldest held alert is older than the interval, and lists them all")
    void digestDue() {
        AlertRule rule = withThrottle(errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.slack("C0123ABC")), null,
                AlertRule.DigestMode.HOURLY);
        when(rules.findEnabled()).thenReturn(List.of(rule));
        List<AlertEvent> held = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            AlertEvent e = event("h" + i, rule, AlertEvent.State.DIGEST_PENDING, List.of());
            held.add(new AlertEvent(e.id(), e.ruleId(), e.ruleName(), e.source(), e.kind(), e.agentId(), e.agentVersion(), CONVERSATION,
                    NOW.minus(Duration.ofMinutes(61 - i)), e.summary(), e.match(), List.of(), e.state(), null, null, i == 0 ? 2 : 0, null, null,
                    null, null, 0));
        }
        when(events.findDigestPending(RULE_ID, AlertMaintenanceService.DIGEST_BATCH)).thenReturn(held);

        assertEquals(1, service.run().digests());

        ArgumentCaptor<AlertEvent> digest = ArgumentCaptor.forClass(AlertEvent.class);
        verify(events).insert(digest.capture());
        assertEquals(AlertEvent.Kind.DIGEST, digest.getValue().kind());
        assertEquals(60, digest.getValue().digestCount());
        assertEquals(AlertEvent.MAX_DIGEST_ITEMS, digest.getValue().digestItems().size());
        assertTrue(digest.getValue().digestItems().get(0).contains("(+2 repeats)"));
        assertEquals("digest|" + RULE_ID + "|h0", digest.getValue().dedupeKey());
        assertEquals(AGENT, digest.getValue().agentId());
        verify(events).markDigested(held.stream().map(AlertEvent::id).toList());
        verify(delivery).deliver(any(), eq(rule));
    }

    @Test
    @DisplayName("no digest before the interval has passed")
    void digestNotYet() {
        AlertRule rule = withThrottle(errorRule(List.of(ErrorKind.TURN_ERROR)), null, AlertRule.DigestMode.HOURLY);
        when(rules.findEnabled()).thenReturn(List.of(rule));
        AlertEvent e = event("h0", rule, AlertEvent.State.DIGEST_PENDING, List.of());
        AlertEvent recent = new AlertEvent(e.id(), e.ruleId(), e.ruleName(), e.source(), e.kind(), e.agentId(), e.agentVersion(), CONVERSATION,
                NOW.minus(Duration.ofMinutes(30)), e.summary(), e.match(), List.of(), e.state(), null, null, 0, null, null, null, null, 0);
        when(events.findDigestPending(RULE_ID, AlertMaintenanceService.DIGEST_BATCH)).thenReturn(List.of(recent));

        assertEquals(0, service.run().digests());
        verify(events, never()).insert(any());
    }

    @Test
    @DisplayName("a digest another runner already sent (its dedupe key exists) marks and sends nothing")
    void digestRace() {
        AlertRule rule = withThrottle(errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.slack("C0123ABC")), null,
                AlertRule.DigestMode.DAILY);
        doReturn(Optional.empty()).when(events).insert(any());
        AlertEvent h0 = event("h0", rule, AlertEvent.State.DIGEST_PENDING, List.of());
        AlertEvent h1 = event("h1", rule, AlertEvent.State.DIGEST_PENDING, List.of());
        // The digest another runner wrote covers h0 only; h1 was held after it.
        doReturn(Optional.of(digestCovering("h0"))).when(events).findByDedupeKey("digest|" + RULE_ID + "|h0");
        assertFalse(service.sendDigest(rule, List.of(h0, h1), NOW));
        verify(events).markDigested(List.of("h0"));
        verify(events, never()).markDigested(List.of("h0", "h1"));
        verify(delivery, never()).deliver(any(), any());
    }

    private static AlertEvent digestCovering(String... ids) {
        return new AlertEvent("d1", RULE_ID, "x", AlertSource.CONVERSATION_ERROR, AlertEvent.Kind.DIGEST, AGENT, null, null, NOW, "s", null,
                List.of(), AlertEvent.State.INBOX_ONLY, null, "digest|" + RULE_ID + "|" + ids[0], 0, ids.length, List.of(), null, null, 0, null,
                List.of(ids));
    }

    @Test
    @DisplayName("the digest records the events it covers")
    void digestRecordsCoveredIds() {
        AlertRule rule = withThrottle(errorRule(List.of(ErrorKind.TURN_ERROR)), null, AlertRule.DigestMode.DAILY);
        doReturn(Optional.of(digestCovering("h0", "h1"))).when(events).insert(any());
        service.sendDigest(rule, List.of(event("h0", rule, AlertEvent.State.DIGEST_PENDING, List.of()),
                event("h1", rule, AlertEvent.State.DIGEST_PENDING, List.of())), NOW);
        ArgumentCaptor<AlertEvent> inserted = ArgumentCaptor.forClass(AlertEvent.class);
        verify(events).insert(inserted.capture());
        assertEquals(List.of("h0", "h1"), inserted.getValue().digestEventIds());
    }

    @Test
    @DisplayName("a crash between the digest insert and the mark heals on the next pass: the held events are marked, nothing is resent")
    void digestCrashBetweenInsertAndMark() {
        AlertRule rule = withThrottle(errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.slack("C0123ABC")), null,
                AlertRule.DigestMode.DAILY);
        AlertEvent h0 = event("h0", rule, AlertEvent.State.DIGEST_PENDING, List.of());
        AlertEvent h1 = event("h1", rule, AlertEvent.State.DIGEST_PENDING, List.of());
        // First pass: the insert succeeds, then the mark fails (the node dies there).
        doReturn(Optional.of(h0.withId("d1"))).when(events).insert(any());
        doThrow(new IllegalStateException("connection lost")).when(events).markDigested(any());
        assertThrows(IllegalStateException.class, () -> service.sendDigest(rule, List.of(h0, h1), NOW));
        verify(delivery, never()).deliver(any(), any());

        // Next pass: h2 was held meanwhile; the digest's dedupe key is taken. Only what
        // the written digest covers is marked — h2 waits for the next digest.
        AlertEvent h2 = event("h2", rule, AlertEvent.State.DIGEST_PENDING, List.of());
        doReturn(Optional.empty()).when(events).insert(any());
        doReturn(Optional.of(digestCovering("h0", "h1"))).when(events).findByDedupeKey("digest|" + RULE_ID + "|h0");
        doReturn(2L).when(events).markDigested(any());
        assertFalse(service.sendDigest(rule, List.of(h0, h1, h2), NOW.plusSeconds(60)));
        verify(events, times(2)).markDigested(List.of("h0", "h1"));
        verify(events, never()).markDigested(List.of("h0", "h1", "h2"));
        verify(delivery, never()).deliver(any(), any());
    }

    @Test
    @DisplayName("events past the retention are deleted; an idle pass reports empty")
    void retention() {
        when(events.deleteFiredBefore(NOW.minus(Duration.ofDays(90)))).thenReturn(0L);
        assertTrue(service.run().isEmpty());
        verify(events).deleteFiredBefore(Instant.parse("2026-07-26T12:00:00Z"));
    }
}
