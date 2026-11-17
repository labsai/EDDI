/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthConfigPolicy.Resolved;
import ai.labs.eddi.engine.health.model.HealthEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthEventIngest — the health-outcomes webhook target's events (H5)")
class HealthEventIngestTest {

    static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    static final HealthRefHasher HASHER = HealthRefHasher.withSalt("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    static final Resolved PLAIN = new Resolved(Map.of(), true, Map.of(), false, List.of("orderId"), Map.of("order.placed", "RESOLVED"), false);
    static final Resolved HASHED = new Resolved(Map.of(), true, Map.of(), false, List.of("orderId"), Map.of("order.placed", "RESOLVED"), true);

    IHealthEventStore store;
    HealthConfigPolicy policy;
    HealthSettings settings;
    HealthEventIngest ingest;

    @BeforeEach
    void setUp() {
        store = mock(IHealthEventStore.class);
        policy = mock(HealthConfigPolicy.class);
        when(policy.current("a1")).thenReturn(PLAIN);
        when(policy.current("a2")).thenReturn(HASHED);
        when(store.insert(anyCollection())).thenAnswer(i -> ((Collection<?>) i.getArgument(0)).size());
        settings = HealthSettings.defaults();
        ingest = new HealthEventIngest(store, policy, HASHER, settings, new HealthMetrics(new SimpleMeterRegistry()), Clock.fixed(NOW,
                ZoneOffset.UTC));
    }

    static Map<String, Object> item(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<HealthEvent> stored() {
        ArgumentCaptor<Collection<HealthEvent>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(store).insert(captor.capture());
        return new ArrayList<>(captor.getValue());
    }

    @Test
    @DisplayName("a single event by reference is stored pending, plain, with value and time")
    void singleByRef() {
        HealthEventIngest.Result r = ingest.ingest("int", List.of("a1"), "webhook",
                item("agentId", "a1", "ref", Map.of("orderId", "A-123"), "event", "order.placed", "value", 49.9, "at", "2026-11-11T09:00:00Z"));
        assertEquals(202, r.status());
        assertEquals(1, r.accepted());
        HealthEvent e = stored().get(0);
        assertEquals("orderId=A-123", e.ref());
        assertEquals("A-123", e.refValue());
        assertEquals(49.9, e.value());
        assertEquals(Instant.parse("2026-11-11T09:00:00Z"), e.at());
        assertEquals(NOW, e.receivedAt());
        assertEquals(HealthEvent.PENDING, e.state());
        assertEquals("int", e.integrationId());
    }

    @Test
    @DisplayName("an agent that hashes refs gets the hash only — the plain value is never stored")
    void hashedRef() {
        ingest.ingest("int", List.of("a2"), "order.placed", item("ref", Map.of("orderId", "A-123")));
        HealthEvent e = stored().get(0);
        assertTrue(e.ref().startsWith("orderId#"));
        assertFalse(e.ref().contains("A-123"));
        assertNull(e.refValue());
        assertEquals("order.placed", e.eventType(), "the CloudEvent type is the default event");
        assertEquals("a2", e.agentId(), "the target's only agent is the default");
    }

    @Test
    @DisplayName("hashing without a salt refuses the event rather than store it plain")
    void hashingUnavailable() {
        HealthEventIngest noSalt = new HealthEventIngest(store, policy, HealthRefHasher.withSalt(null), settings,
                new HealthMetrics(new SimpleMeterRegistry()), Clock.fixed(NOW, ZoneOffset.UTC));
        HealthEventIngest.Result r = noSalt.ingest("int", List.of("a2"), "order.placed", item("ref", Map.of("orderId", "A-1")));
        assertEquals(422, r.status());
        assertEquals(HealthEventIngest.REASON_HASHING_UNAVAILABLE, r.refused().get(0).get("reason"));
        verify(store, never()).insert(anyCollection());
    }

    @Test
    @DisplayName("binding: an event for an agent the target does not bind is refused; the others in the batch are stored")
    void agentBinding() {
        HealthEventIngest.Result r = ingest.ingest("int", List.of("a1"), "webhook", List.of(
                item("agentId", "a1", "conversationId", "c-1", "event", "order.placed"),
                item("agentId", "intruder", "conversationId", "c-2", "event", "order.placed")));
        assertEquals(202, r.status());
        assertEquals(1, r.accepted());
        assertEquals(1, r.refused().get(0).get("index"));
        assertEquals(HealthEventIngest.REASON_AGENT_NOT_BOUND, r.refused().get(0).get("reason"));
        assertEquals(List.of("a1"), stored().stream().map(HealthEvent::agentId).toList());
    }

    @Test
    @DisplayName("several bound agents need agentId; the events wrapper is a batch")
    void batchWrapper() {
        HealthEventIngest.Result r = ingest.ingest("int", List.of("a1", "a2"), "webhook",
                item("events", List.of(item("conversationId", "c-1", "event", "order.placed"),
                        item("agentId", "a2", "conversationId", "c-2", "event", "order.placed"))));
        assertEquals(1, r.accepted());
        assertEquals(HealthEventIngest.REASON_AGENT_MISSING, r.refused().get(0).get("reason"));
    }

    @Test
    @DisplayName("each malformed event is refused with its reason")
    void refusals() {
        HealthEventIngest.Result r = ingest.ingest("int", List.of("a1"), "webhook", List.of(
                item("conversationId", "c-1"), // no event type
                item("event", "order.placed"), // neither ref nor conversation
                item("event", "order.placed", "conversationId", "c-1", "ref", Map.of("orderId", "1")), // both
                item("event", "order.placed", "ref", Map.of("ticketId", "1")), // not declared
                item("event", "order.placed", "ref", Map.of("orderId", "1", "x", "2")), // two refs
                item("event", "order placed", "conversationId", "c-1"), // bad type
                item("event", "order.placed", "conversationId", "not a conversation id!"),
                item("event", "order.placed", "conversationId", "c-1", "value", "12"),
                item("event", "order.placed", "conversationId", "c-1", "at", "yesterday"),
                item("event", "order.placed", "conversationId", "c-1", "at", NOW.plus(Duration.ofHours(1)).toString()),
                item("event", "order.placed", "conversationId", "c-1", "at", NOW.minus(Duration.ofDays(200)).toString()),
                "not an object"));
        assertEquals(422, r.status());
        assertEquals(List.of(HealthEventIngest.REASON_EVENT_MISSING, HealthEventIngest.REASON_TARGET_MISSING, HealthEventIngest.REASON_TARGET_MISSING,
                HealthEventIngest.REASON_REF_NOT_DECLARED, HealthEventIngest.REASON_REF_INVALID, HealthEventIngest.REASON_EVENT_INVALID,
                HealthEventIngest.REASON_CONVERSATION_INVALID, HealthEventIngest.REASON_VALUE_INVALID, HealthEventIngest.REASON_AT_INVALID,
                HealthEventIngest.REASON_AT_INVALID, HealthEventIngest.REASON_AT_INVALID, HealthEventIngest.REASON_NOT_AN_OBJECT),
                r.refused().stream().map(m -> m.get("reason")).toList());
    }

    @Test
    @DisplayName("caps: too many events per request is 413; a full per-agent backlog is 429")
    void caps() {
        List<Object> many = new ArrayList<>();
        for (int i = 0; i <= settings.maxEventsPerRequest(); i++) {
            many.add(item("conversationId", "c-" + i, "event", "order.placed"));
        }
        assertEquals(413, ingest.ingest("int", List.of("a1"), "webhook", many).status());

        when(store.countPending("a1")).thenReturn((long) settings.maxPendingEventsPerAgent());
        HealthEventIngest.Result full = ingest.ingest("int", List.of("a1"), "webhook", item("conversationId", "c-1", "event", "order.placed"));
        assertEquals(429, full.status());
        assertEquals(HealthEventIngest.REASON_BACKLOG_FULL, full.refused().get(0).get("reason"));
    }

    @Test
    @DisplayName("ids: the sender's id makes a redelivery the same event; without one, identical events are one")
    void identity() {
        ingest.ingest("int", List.of("a1"), "webhook", List.of(item("id", "x", "conversationId", "c-1", "event", "order.placed"),
                item("id", "x", "conversationId", "c-1", "event", "order.placed", "value", 3),
                item("conversationId", "c-1", "event", "order.placed"), item("conversationId", "c-1", "event", "order.placed"),
                item("conversationId", "c-1", "event", "order.placed", "value", 1)));
        List<String> ids = stored().stream().map(HealthEvent::id).toList();
        assertEquals(ids.get(0), ids.get(1), "same sender id");
        assertEquals(ids.get(2), ids.get(3), "identical items");
        assertNotEquals(ids.get(2), ids.get(4));
        assertNotEquals(ids.get(0), ids.get(2));
    }

    @Test
    @DisplayName("duplicates the store already holds are answered, not counted as new")
    void duplicates() {
        when(store.insert(anyCollection())).thenReturn(0);
        HealthEventIngest.Result r = ingest.ingest("int", List.of("a1"), "webhook", item("conversationId", "c-1", "event", "order.placed"));
        assertEquals(202, r.status());
        assertEquals(0, r.accepted());
        assertEquals(1, r.duplicates());
    }

    @Test
    @DisplayName("bodies that are no event, and a disabled Agent Health, are refused whole")
    void wholeRefusals() {
        assertEquals(422, ingest.ingest("int", List.of("a1"), "webhook", "text").status());
        assertEquals(422, ingest.ingest("int", List.of("a1"), "webhook", List.of()).status());
        HealthEventIngest off = new HealthEventIngest(store, policy, HASHER, new HealthSettings(false, 10, Duration.ofDays(90)),
                new HealthMetrics(new SimpleMeterRegistry()), Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(503, off.ingest("int", List.of("a1"), "webhook", item("conversationId", "c-1", "event", "x")).status());
        verify(store, never()).countPending(anyString());
    }
}
