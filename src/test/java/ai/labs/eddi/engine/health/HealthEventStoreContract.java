/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthEvent;
import ai.labs.eddi.engine.health.model.HealthEventCount;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link IHealthEventStore} contract, run against a real database by each
 * implementation's {@code *IT}. Each implementing class hands out a store over
 * an empty collection/table per test.
 */
public interface HealthEventStoreContract {

    /** A store over an empty collection/table. */
    IHealthEventStore store();

    Instant T0 = Instant.parse("2026-11-10T10:00:00Z");

    static HealthEvent pending(String id, String agent, String type, String ref, String refValue, String conversationId, Double value, Instant at) {
        return new HealthEvent(id, "integration", agent, type, ref, refValue, conversationId, value, at, at.plusSeconds(1),
                LocalDate.ofInstant(at, ZoneOffset.UTC), HealthEvent.PENDING, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("insert stores new events once — a redelivered id is not counted again — and every field round-trips")
    default void insertIsIdempotent() {
        HealthEvent e = pending("e1", "agent", "order.placed", "orderId=A-1", "A-1", null, 49.9, T0);
        assertEquals(1, store().insert(List.of(e)));
        assertEquals(0, store().insert(List.of(e)), "a redelivery");
        assertEquals(1, store().insert(List.of(e, pending("e2", "agent", "order.placed", null, null, "c1", null, T0))));
        List<HealthEvent> found = store().findPending(10);
        assertEquals(2, found.size());
        assertEquals(e, found.get(0), "oldest receipt first, every field kept");
        assertEquals(2, store().countPending("agent"));
        assertEquals(0, store().countPending("other"));
    }

    @Test
    @DisplayName("markMatched records the session and drops the plain value — once; matched events leave the pending set")
    default void markMatched() {
        HealthEvent e = pending("e1", "agent", "order.placed", "orderId=A-1", "A-1", null, null, T0);
        store().insert(List.of(e));
        HealthEvent matched = e.matched("c1", 3, 7, "api", "user", HealthOutcomes.EVIDENCE_EXTERNAL_EVENT, T0.plusSeconds(60));
        assertTrue(store().markMatched(matched));
        assertFalse(store().markMatched(matched), "only while pending");
        assertTrue(store().findPending(10).isEmpty());
        List<HealthEvent> byConversation = store().findMatchedByConversation("c1");
        assertEquals(1, byConversation.size());
        HealthEvent stored = byConversation.get(0);
        assertEquals(3, stored.matchedStep());
        assertEquals("user", stored.userId());
        assertNull(stored.refValue(), "the plain value is not kept once matched");
        assertEquals("orderId=A-1", stored.ref());
        assertEquals(HealthEvent.MATCHED, stored.state());
    }

    @Test
    @DisplayName("counts group matched events per type and evidence (and day), sum values, and honour days, version and origins")
    default void counts() {
        Instant day1 = T0;
        Instant day2 = T0.plusSeconds(86_400);
        store().insert(List.of(pending("a", "agent", "order.placed", "orderId=1", "1", null, 10.0, day1),
                pending("b", "agent", "order.placed", "orderId=2", "2", null, 5.5, day2),
                pending("c", "agent", "order.placed", null, null, "c9", null, day2),
                pending("d", "agent", "ticket.reopened", "ticketId=1", "1", null, null, day2),
                pending("e", "agent", "order.placed", "orderId=3", "3", null, 100.0, day2),
                pending("f", "other", "order.placed", "orderId=4", "4", null, 1.0, day2)));
        store().markMatched(store().findPending(10).stream().filter(e -> e.id().equals("a")).findFirst().orElseThrow()
                .matched("c1", 0, 1, "api", "u", HealthOutcomes.EVIDENCE_EXTERNAL_EVENT, T0));
        store().markMatched(pending("b", "agent", "order.placed", "orderId=2", "2", null, 5.5, day2).matched("c2", 0, 2, "api", "u",
                HealthOutcomes.EVIDENCE_EXTERNAL_EVENT, T0));
        store().markMatched(pending("c", "agent", "order.placed", null, null, "c9", null, day2).matched("c9", 0, 2, "api", "u",
                HealthOutcomes.EVIDENCE_CONVERSATION_ID_EVENT, T0));
        store().markMatched(pending("d", "agent", "ticket.reopened", "ticketId=1", "1", null, null, day2).matched("c3", 0, 2, "email", "u",
                HealthOutcomes.EVIDENCE_EXTERNAL_EVENT, T0));
        store().markMatched(pending("f", "other", "order.placed", "orderId=4", "4", null, 1.0, day2).matched("c4", 0, 2, "api", "u",
                HealthOutcomes.EVIDENCE_EXTERNAL_EVENT, T0));
        // "e" stays pending: never counted.
        LocalDate from = LocalDate.ofInstant(day1, ZoneOffset.UTC);
        LocalDate to = LocalDate.ofInstant(day2, ZoneOffset.UTC);
        List<HealthEventCount> all = store().counts(new HealthUsageQuery("agent", from, to, null, List.of("api")), false);
        HealthEventCount byRef = all.stream()
                .filter(c -> "order.placed".equals(c.eventType()) && HealthOutcomes.EVIDENCE_EXTERNAL_EVENT.equals(c.evidence())).findFirst()
                .orElseThrow();
        assertEquals(2, byRef.events());
        assertEquals(15.5, byRef.valueSum(), 1e-9);
        assertEquals(2, byRef.valued());
        HealthEventCount byConversation = all.stream().filter(c -> HealthOutcomes.EVIDENCE_CONVERSATION_ID_EVENT.equals(c.evidence())).findFirst()
                .orElseThrow();
        assertEquals(1, byConversation.events());
        assertEquals(0, byConversation.valued());
        assertTrue(all.stream().noneMatch(c -> "ticket.reopened".equals(c.eventType())), "another origin");
        assertNull(all.get(0).day());

        List<HealthEventCount> perDay = store().counts(new HealthUsageQuery("agent", from, to, 2, List.of("api")), true);
        assertEquals(2, perDay.stream().mapToLong(HealthEventCount::events).sum(), "version 2 only");
        assertTrue(perDay.stream().allMatch(c -> to.equals(c.day())));
        assertTrue(store().counts(new HealthUsageQuery("agent", from, to, null, List.of()), false).isEmpty());
    }

    @Test
    @DisplayName("expired pending events, a user's, a conversation's and old ones are deleted; others stay")
    default void deletes() {
        store().insert(List.of(pending("old", "agent", "x", null, null, "c1", null, T0.minusSeconds(86_400 * 3)),
                pending("new", "agent", "x", null, null, "c2", null, T0)));
        assertEquals(1, store().deletePendingReceivedBefore(T0.minusSeconds(86_400)));
        assertEquals(List.of("new"), store().findPending(10).stream().map(HealthEvent::id).toList());

        store().markMatched(pending("new", "agent", "x", null, null, "c2", null, T0).matched("c2", 0, 1, "api", "user", "conversation_id_event", T0));
        assertEquals(1, store().findByUserId("user", 10).size());
        assertEquals(1, store().deleteByUserId("user"));
        assertTrue(store().findByUserId("user", 10).isEmpty());

        store().insert(List.of(pending("p", "agent", "x", null, null, "c5", null, T0), pending("q", "agent", "x", null, null, "c6", null, T0)));
        assertEquals(1, store().deleteByConversationId("c5"));
        assertEquals(1, store().delete(List.of("q")));
        assertEquals(0, store().delete(List.of()));

        store().insert(List.of(pending("r", "agent", "x", null, null, "c7", null, T0.minusSeconds(86_400 * 10))));
        assertEquals(1, store().deleteOlderThan(LocalDate.ofInstant(T0, ZoneOffset.UTC).minusDays(2)));
    }
}
