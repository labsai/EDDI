/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictionUnavailableException;
import ai.labs.eddi.engine.health.model.HealthEvent;
import ai.labs.eddi.engine.health.model.HealthOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthEventMatcher — matching events to sessions, late matches, expiry and GDPR (H5)")
class HealthEventMatcherTest {

    static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    static final HealthRefHasher HASHER = HealthRefHasher.withSalt("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    IHealthEventStore events;
    IHealthOutcomeStore outcomes;
    GdprComplianceService gdpr;
    HealthErasureFence fence;
    SimpleMeterRegistry registry;
    HealthEventMatcher matcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        events = mock(IHealthEventStore.class);
        outcomes = mock(IHealthOutcomeStore.class);
        gdpr = mock(GdprComplianceService.class);
        Instance<GdprComplianceService> gdprInstance = mock(Instance.class);
        when(gdprInstance.isResolvable()).thenReturn(true);
        when(gdprInstance.get()).thenReturn(gdpr);
        fence = new HealthErasureFence(Clock.fixed(NOW, ZoneOffset.UTC));
        registry = new SimpleMeterRegistry();
        matcher = new HealthEventMatcher(events, outcomes, HASHER, fence, gdprInstance, HealthSettings.defaults(), new HealthMetrics(registry),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(events.markMatched(any())).thenReturn(true);
    }

    static HealthOutcome session(String conversation, int sessionNo, String agent, Instant startedAt, String user) {
        return new HealthOutcome(conversation, sessionNo, agent, 3, user, "api", true, startedAt, startedAt.plusSeconds(60), 2, 0.1, 0, "OPEN", null,
                true, LocalDate.ofInstant(startedAt, ZoneOffset.UTC), null, 0, List.of(), NOW, List.of("orderId=A-1"));
    }

    static HealthEvent byRef(String ref, String refValue, Instant at) {
        return new HealthEvent("e1", "i", "agent", "order.placed", ref, refValue, null, null, at, at, LocalDate.ofInstant(at, ZoneOffset.UTC),
                HealthEvent.PENDING, null, null, null, null, null, null);
    }

    static HealthEvent byConversation(String conversationId, Instant at) {
        return new HealthEvent("e2", "i", "agent", "order.placed", null, null, conversationId, null, at, at, LocalDate.ofInstant(at, ZoneOffset.UTC),
                HealthEvent.PENDING, null, null, null, null, null, null);
    }

    private double counted(String outcome) {
        return registry.counter(HealthMetrics.EVENTS, "outcome", outcome).count();
    }

    @Test
    @DisplayName("by reference: the session that set it — earliest per conversation — of the latest conversation before the event")
    void matchesTheSettingSession() {
        Instant at = NOW.minus(Duration.ofHours(1));
        when(outcomes.findByRefs(eq("agent"), anyCollection(), anyInt())).thenReturn(List.of(
                session("c-new", 9, "agent", NOW.minus(Duration.ofHours(3)), "u2"), // carried into a later session
                session("c-new", 4, "agent", NOW.minus(Duration.ofHours(5)), "u2"), // set here
                session("c-old", 0, "agent", NOW.minus(Duration.ofDays(2)), "u1"),
                session("c-after", 0, "agent", NOW.minus(Duration.ofMinutes(10)), "u3"))); // started after the event
        assertEquals(HealthEventMatcher.Outcome.MATCHED, matcher.tryMatch(byRef("orderId=A-1", "A-1", at)));
        ArgumentCaptor<HealthEvent> matched = ArgumentCaptor.forClass(HealthEvent.class);
        verify(events).markMatched(matched.capture());
        assertEquals("c-new", matched.getValue().conversationId());
        assertEquals(4, matched.getValue().matchedStep());
        assertEquals("u2", matched.getValue().userId());
        assertEquals(HealthOutcomes.EVIDENCE_EXTERNAL_EVENT, matched.getValue().evidence());
        assertEquals(3, matched.getValue().agentVersion());
        verify(outcomes).markDue("c-new", List.of(4), NOW);
        assertEquals(1, counted(HealthMetrics.EVENT_MATCHED));
    }

    @Test
    @DisplayName("a plain reference is looked up in both forms; a hashed one only as stored")
    @SuppressWarnings("unchecked")
    void lookupKeys() {
        when(outcomes.findByRefs(anyString(), anyCollection(), anyInt())).thenReturn(List.of());
        matcher.tryMatch(byRef("orderId=A-1", "A-1", NOW));
        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
        verify(outcomes).findByRefs(eq("agent"), keys.capture(), anyInt());
        assertEquals(HealthRefs.matchKeys("orderId", "A-1", HASHER), Set.copyOf(keys.getValue()));

        IHealthOutcomeStore hashedOnly = mock(IHealthOutcomeStore.class);
        when(hashedOnly.findByRefs(anyString(), anyCollection(), anyInt())).thenReturn(List.of());
        HealthEventMatcher m = new HealthEventMatcher(events, hashedOnly, HASHER, fence, null, HealthSettings.defaults(),
                new HealthMetrics(new SimpleMeterRegistry()), Clock.fixed(NOW, ZoneOffset.UTC));
        m.tryMatch(byRef("orderId#abc", null, NOW));
        verify(hashedOnly).findByRefs(eq("agent"), eq(Set.of("orderId#abc")), anyInt());
    }

    @Test
    @DisplayName("by conversation id: the session started last before the event; another agent's conversation never matches")
    void matchesByConversation() {
        when(outcomes.findByConversation("c1")).thenReturn(List.of(session("c1", 0, "agent", NOW.minus(Duration.ofDays(3)), "u"),
                session("c1", 5, "agent", NOW.minus(Duration.ofDays(1)), "u"), session("c1", 9, "agent", NOW.minus(Duration.ofMinutes(1)), "u")));
        assertEquals(HealthEventMatcher.Outcome.MATCHED, matcher.tryMatch(byConversation("c1", NOW.minus(Duration.ofHours(2)))));
        ArgumentCaptor<HealthEvent> matched = ArgumentCaptor.forClass(HealthEvent.class);
        verify(events).markMatched(matched.capture());
        assertEquals(5, matched.getValue().matchedStep());
        assertEquals(HealthOutcomes.EVIDENCE_CONVERSATION_ID_EVENT, matched.getValue().evidence());

        when(outcomes.findByConversation("c2")).thenReturn(List.of(session("c2", 0, "someone-else", NOW.minus(Duration.ofDays(1)), "u")));
        assertEquals(HealthEventMatcher.Outcome.PENDING, matcher.tryMatch(byConversation("c2", NOW)));
    }

    @Test
    @DisplayName("an event before every session goes to the conversation's first one")
    void earlyEventGoesToFirstSession() {
        when(outcomes.findByConversation("c1")).thenReturn(List.of(session("c1", 3, "agent", NOW, "u"), session("c1", 0, "agent", NOW, "u")));
        matcher.tryMatch(byConversation("c1", NOW.minus(Duration.ofDays(2))));
        ArgumentCaptor<HealthEvent> matched = ArgumentCaptor.forClass(HealthEvent.class);
        verify(events).markMatched(matched.capture());
        assertEquals(0, matched.getValue().matchedStep());
    }

    @Test
    @DisplayName("no session yet: the event stays pending for a late match")
    void staysPending() {
        when(outcomes.findByRefs(anyString(), anyCollection(), anyInt())).thenReturn(List.of());
        assertEquals(HealthEventMatcher.Outcome.PENDING, matcher.tryMatch(byRef("orderId=A-1", "A-1", NOW)));
        verify(events, never()).markMatched(any());
        verify(events, never()).delete(any());
    }

    @Test
    @DisplayName("a pass first drops the events older than the match window, counted expired, then tries the pending ones")
    void expiresThenMatches() {
        when(events.deletePendingReceivedBefore(NOW.minus(Duration.ofHours(72)))).thenReturn(3L);
        when(events.findPending(anyInt())).thenReturn(List.of(byConversation("c1", NOW)));
        when(outcomes.findByConversation("c1")).thenReturn(List.of(session("c1", 0, "agent", NOW.minus(Duration.ofHours(1)), "u")));
        HealthEventMatcher.Result result = matcher.run(NOW.plusSeconds(30));
        assertEquals(3, result.expired());
        assertEquals(1, result.matched());
        assertEquals(3, counted(HealthMetrics.EVENT_EXPIRED));
    }

    @Test
    @DisplayName("Art. 18: an event whose user's processing is restricted is dropped, never matched")
    void restrictedUserDropped() {
        when(outcomes.findByConversation("c1")).thenReturn(List.of(session("c1", 0, "agent", NOW.minus(Duration.ofHours(1)), "restricted")));
        when(gdpr.isProcessingRestricted("restricted")).thenReturn(true);
        assertEquals(HealthEventMatcher.Outcome.DROPPED, matcher.tryMatch(byConversation("c1", NOW)));
        verify(events).delete(List.of("e2"));
        verify(events, never()).markMatched(any());
        verify(outcomes, never()).markDue(anyString(), any(), any());
        assertEquals(1, counted(HealthMetrics.EVENT_RESTRICTED));
    }

    @Test
    @DisplayName("a restriction that cannot be read keeps the event pending (fail closed)")
    void restrictionUnreadableStaysPending() {
        when(outcomes.findByConversation("c1")).thenReturn(List.of(session("c1", 0, "agent", NOW.minus(Duration.ofHours(1)), "u")));
        when(gdpr.isProcessingRestricted("u")).thenThrow(new ProcessingRestrictionUnavailableException("down", null));
        assertEquals(HealthEventMatcher.Outcome.PENDING, matcher.tryMatch(byConversation("c1", NOW)));
        verify(events, never()).markMatched(any());
        verify(events, never()).delete(any());
    }

    @Test
    @DisplayName("an event of a user being erased is dropped")
    void erasedUserDropped() {
        fence.stopInFlightWork("erased");
        when(outcomes.findByConversation("c1")).thenReturn(List.of(session("c1", 0, "agent", NOW.minus(Duration.ofHours(1)), "erased")));
        assertEquals(HealthEventMatcher.Outcome.DROPPED, matcher.tryMatch(byConversation("c1", NOW)));
        verify(events).delete(List.of("e2"));
        assertEquals(1, counted(HealthMetrics.EVENT_ERASED));
    }

    @Test
    @DisplayName("an event without a reference or conversation matches nothing")
    void nothingToMatch() {
        assertNull(matcher.match(byConversation(null, NOW)));
    }
}
