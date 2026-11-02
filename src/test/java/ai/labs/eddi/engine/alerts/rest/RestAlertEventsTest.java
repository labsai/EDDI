/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.alerts.AlertDeliveryService;
import ai.labs.eddi.engine.alerts.AlertDispatcher;
import ai.labs.eddi.engine.alerts.IAlertEventStore;
import ai.labs.eddi.engine.alerts.IAlertRuleStore;
import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertEvent.Delivery;
import ai.labs.eddi.engine.alerts.model.AlertEventFilter;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.api.AlertEventQueryParams;
import ai.labs.eddi.engine.api.IRestAlertEvents.AcknowledgeRequest;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
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
import static ai.labs.eddi.engine.alerts.AlertTestData.OTHER_AGENT;
import static ai.labs.eddi.engine.alerts.AlertTestData.errorRule;
import static ai.labs.eddi.engine.alerts.AlertTestData.event;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("/alerts/events")
class RestAlertEventsTest {

    private ResourceAccessGuard guard;
    private IAlertEventStore store;
    private AlertDispatcher dispatcher;
    private RestAlertEvents rest;

    @BeforeEach
    void setUp() {
        guard = mock(ResourceAccessGuard.class);
        ConversationAccessGuard conversationGuard = mock(ConversationAccessGuard.class);
        when(conversationGuard.callerActor(anyString())).thenReturn("erin");
        store = mock(IAlertEventStore.class);
        dispatcher = mock(AlertDispatcher.class);
        rest = new RestAlertEvents(store, mock(IAlertRuleStore.class), mock(AlertDeliveryService.class), dispatcher,
                new AlertAccess(guard, conversationGuard), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void asEditor() {
        when(guard.seesEverything()).thenReturn(false);
    }

    private static AlertEvent eventFor(String id, String agentId) {
        AlertEvent e = event(id, errorRule(List.of(ErrorKind.TURN_ERROR)), AlertEvent.State.DELIVERED, List.of());
        return new AlertEvent(e.id(), e.ruleId(), e.ruleName(), e.source(), e.kind(), agentId, e.agentVersion(), e.conversationId(), e.firedAt(),
                e.summary(), e.match(), e.deliveries(), e.state(), e.nextAttemptAt(), e.dedupeKey(), 0, null, null, null, null, 2);
    }

    @SuppressWarnings("unchecked")
    private static void assertError(int status, String code, Executable call) {
        WebApplicationException e = assertThrows(WebApplicationException.class, call);
        assertEquals(status, e.getResponse().getStatus());
        assertEquals(code, ((Map<String, String>) e.getResponse().getEntity()).get("error"));
    }

    @Test
    @DisplayName("an editor must name an agent and hold EDIT on it — on the list, the count and the bulk acknowledgement")
    void scoping() {
        asEditor();
        AlertEventQueryParams none = new AlertEventQueryParams();
        assertError(400, AlertErrors.AGENT_ID_REQUIRED, () -> rest.listEvents(none, 0, 20));
        assertError(400, AlertErrors.AGENT_ID_REQUIRED, () -> rest.unacknowledgedCount(" "));
        assertError(400, AlertErrors.AGENT_ID_REQUIRED, () -> rest.acknowledgeEvents(none, null));
        doThrow(new ForbiddenException("no")).when(guard).requireAccess(OTHER_AGENT, AccessLevel.EDIT, "agent");
        assertThrows(ForbiddenException.class, () -> rest.listEvents(AlertEventQueryParams.ofAgent(OTHER_AGENT), 0, 20));
        verify(store, never()).list(any(), anyInt(), anyInt());
        verify(store, never()).acknowledgeMatching(any(), any(), any());
    }

    @Test
    @DisplayName("filters are parsed and scoped; an unknown state is INVALID_PARAMETER")
    void filters() {
        asEditor();
        AlertEventQueryParams q = AlertEventQueryParams.ofAgent(AGENT);
        q.source = "rating";
        q.acknowledged = false;
        q.to = "2026-10-24";
        rest.listEvents(q, 0, 500);
        ArgumentCaptor<AlertEventFilter> filter = ArgumentCaptor.forClass(AlertEventFilter.class);
        verify(store).list(filter.capture(), eq(0), eq(100));
        assertEquals(AGENT, filter.getValue().agentId());
        assertEquals(AlertSource.RATING, filter.getValue().source());
        assertEquals(Instant.parse("2026-10-25T00:00:00Z"), filter.getValue().to());
        q.state = "sideways";
        assertError(400, AlertErrors.INVALID_PARAMETER, () -> rest.listEvents(q, 0, 20));
    }

    @Test
    @DisplayName("acknowledging records the caller; an event of an agent without EDIT, or an agent-less digest, is refused")
    void acknowledge() {
        asEditor();
        when(store.read("e1")).thenReturn(Optional.of(eventFor("e1", AGENT)));
        rest.acknowledgeEvent("e1");
        verify(store).acknowledge("e1", "erin", NOW);

        when(store.read("digest")).thenReturn(Optional.of(eventFor("digest", null)));
        assertError(403, AlertErrors.NOT_PERMITTED, () -> rest.acknowledgeEvent("digest"));
        verify(store, never()).acknowledge(eq("digest"), any(), any());
        assertError(404, AlertErrors.EVENT_NOT_FOUND, () -> rest.acknowledgeEvent("missing"));
    }

    @Test
    @DisplayName("a bulk acknowledgement by ids is all or nothing: one forbidden id acknowledges none")
    void bulkAllOrNothing() {
        asEditor();
        when(store.read("e1")).thenReturn(Optional.of(eventFor("e1", AGENT)));
        when(store.read("e2")).thenReturn(Optional.of(eventFor("e2", OTHER_AGENT)));
        doThrow(new ForbiddenException("no")).when(guard).requireAccess(OTHER_AGENT, AccessLevel.EDIT, "agent");

        assertThrows(ForbiddenException.class, () -> rest.acknowledgeEvents(null, new AcknowledgeRequest(List.of("e1", "e2"))));
        verify(store, never()).acknowledgeIds(any(), any(), any());

        when(store.acknowledgeIds(List.of("e1"), "erin", NOW)).thenReturn(1L);
        assertEquals(1L, rest.acknowledgeEvents(null, new AcknowledgeRequest(List.of("e1", "e1"))).acknowledged());
    }

    @Test
    @DisplayName("a present ids list is the selector: empty, or with an empty entry, is a 400 and never falls back to the filter")
    void bulkEmptyIdsRefused() {
        when(guard.seesEverything()).thenReturn(true);
        AlertEventQueryParams all = new AlertEventQueryParams();
        assertError(400, AlertErrors.INVALID_PARAMETER, () -> rest.acknowledgeEvents(all, new AcknowledgeRequest(List.of())));
        assertError(400, AlertErrors.INVALID_PARAMETER, () -> rest.acknowledgeEvents(all, new AcknowledgeRequest(List.of("e1", " "))));
        assertError(400, AlertErrors.INVALID_PARAMETER,
                () -> rest.acknowledgeEvents(all, new AcknowledgeRequest(Arrays.asList("e1", null))));
        verify(store, never()).acknowledgeMatching(any(), any(), any());
        verify(store, never()).acknowledgeIds(any(), any(), any());

        // Leaving ids out (no body, or a body without ids) is the filter path.
        rest.acknowledgeEvents(all, null);
        rest.acknowledgeEvents(all, new AcknowledgeRequest(null));
        verify(store, times(2)).acknowledgeMatching(any(), eq("erin"), eq(NOW));
    }

    @Test
    @DisplayName("retry puts failed targets back to pending and hands delivery to the worker; nothing failed is NOTHING_TO_RETRY")
    void retry() {
        when(guard.seesEverything()).thenReturn(true);
        DeliveryTarget hook = DeliveryTarget.webhook("https://hooks.example.com/x", "${vault:k}");
        AlertRule rule = errorRule(List.of(ErrorKind.TURN_ERROR), hook);
        AlertEvent failed = event("e1", rule, AlertEvent.State.FAILED,
                List.of(new Delivery(hook.key(), hook.type(), hook.label(), AlertEvent.DeliveryStatus.FAILED, 5, NOW, "HTTP 500", null)));
        when(store.read("e1")).thenReturn(Optional.of(failed));
        when(store.updateDelivery(any(), anyLong())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));

        AlertEvent reset = rest.retryEvent("e1");

        assertEquals(AlertEvent.State.PENDING, reset.state());
        verify(store).updateDelivery(any(), eq(4L));
        verify(dispatcher).submit(any());

        when(store.read("e2")).thenReturn(Optional.of(eventFor("e2", AGENT)));
        assertError(409, AlertErrors.NOTHING_TO_RETRY, () -> rest.retryEvent("e2"));
    }

    @Test
    @DisplayName("an administrator lists across agents")
    void admin() {
        when(guard.seesEverything()).thenReturn(true);
        rest.listEvents(null, null, null);
        ArgumentCaptor<AlertEventFilter> filter = ArgumentCaptor.forClass(AlertEventFilter.class);
        verify(store).list(filter.capture(), eq(0), eq(20));
        assertNull(filter.getValue().agentId());
    }
}
