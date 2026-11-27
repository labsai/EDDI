/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard.Admission;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard.ErasureIncompleteException;
import ai.labs.eddi.engine.gdpr.erasure.ErasureMetrics;
import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore;
import ai.labs.eddi.engine.gdpr.erasure.InMemoryErasureTombstoneStore;
import ai.labs.eddi.engine.health.HealthConfigPolicy.Resolved;
import ai.labs.eddi.engine.health.HealthPeriods.Window;
import ai.labs.eddi.engine.health.model.HealthEvent;
import ai.labs.eddi.engine.health.model.HealthOutcome;
import ai.labs.eddi.engine.health.model.HealthSessionQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The business events in the erasure guarantee, wired for real: a real
 * {@link ErasureGuard} over in-memory tombstones with the real
 * {@link HealthErasureWriter}, the stores stubbed. The matcher's own guarded
 * write is covered by {@code HealthEventMatcherTest}.
 */
@DisplayName("Business events through the real erasure guard (H5)")
class HealthEventErasureTest {

    static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    InMemoryErasureTombstoneStore tombstones;
    ErasureGuard guard;
    IHealthTurnStore turns;
    IHealthEventStore events;
    HealthEventMatcher matcher;
    HealthEventIngest ingest;
    /** The events the stubbed event store holds for conversation "conv". */
    AtomicLong storedEvents;

    @BeforeEach
    void setUp() throws Exception {
        tombstones = new InMemoryErasureTombstoneStore();
        turns = mock(IHealthTurnStore.class);
        events = mock(IHealthEventStore.class);
        storedEvents = new AtomicLong(2);
        when(events.countByConversationId("conv")).thenAnswer(inv -> storedEvents.get());
        HealthTurnRecorder recorder = mock(HealthTurnRecorder.class);
        when(recorder.awaitDrained(any())).thenReturn(true);
        matcher = mock(HealthEventMatcher.class);
        when(matcher.awaitDrained(any())).thenReturn(true);
        ingest = mock(HealthEventIngest.class);
        when(ingest.awaitDrained(any())).thenReturn(true);
        HealthErasureWriter writer = new HealthErasureWriter(recorder, turns, null, null, null, null, instance(events), instance(matcher),
                instance(ingest));
        guard = new ErasureGuard(tombstones, List.of(writer), new ErasureMetrics(new SimpleMeterRegistry()), Duration.ofDays(400),
                Duration.ofSeconds(1), Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    static <T> Instance<T> instance(T bean) {
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(bean);
        return instance;
    }

    @Nested
    @DisplayName("HealthErasureWriter")
    class Writer {

        @Test
        @DisplayName("a guarded conversation delete erases the conversation's events and verifies none is left")
        void conversationDeleteErasesEvents() {
            when(events.deleteByConversationId("conv")).thenAnswer(inv -> storedEvents.getAndSet(0));
            assertDoesNotThrow(() -> guard.eraseConversation("conv"));
            verify(events).deleteByConversationId("conv");
        }

        @Test
        @DisplayName("events left after the delete fail the conversation's erasure")
        void remainingEventsFailTheErasure() {
            when(events.deleteByConversationId("conv")).thenReturn(0L);
            assertThrows(ErasureIncompleteException.class, () -> guard.eraseConversation("conv"));
        }

        @Test
        @DisplayName("a recorded event removal is retried through removeRow and deletes exactly that event")
        void pendingEventRemovalIsRetried() {
            Admission admission = guard.beforeWrite("user", "conv", NOW.minusSeconds(3600));
            guard.tombstoneConversation("conv");
            assertTrue(guard.afterWrite(HealthErasureWriter.NAME, HealthErasureWriter.eventRowId("conv", "ev1"), admission, () -> {
                throw new HealthStoreException("down", null);
            }));
            assertEquals(1, tombstones.pendingCount());
            assertEquals(1, guard.retryPending(null, null, "conv"));
            verify(events).deleteInConversation("ev1", "conv");
            verify(events, never()).delete(any());
            assertEquals(0, tombstones.pendingCount());
        }

        @Test
        @DisplayName("the drain barrier waits for the matcher and the ingest")
        void drainWaitsForMatchesAndIngests() throws Exception {
            HealthErasureWriter writer = new HealthErasureWriter(mockRecorder(), turns, null, null, null, null, instance(events),
                    instance(matcher), instance(ingest));
            assertTrue(writer.awaitDrained(Duration.ofSeconds(1)));
            when(matcher.awaitDrained(any())).thenReturn(false);
            assertFalse(writer.awaitDrained(Duration.ofSeconds(1)), "a match in flight");
            when(matcher.awaitDrained(any())).thenReturn(true);
            when(ingest.awaitDrained(any())).thenReturn(false);
            assertFalse(writer.awaitDrained(Duration.ofSeconds(1)), "an ingest in flight");
        }

        private HealthTurnRecorder mockRecorder() throws InterruptedException {
            HealthTurnRecorder recorder = mock(HealthTurnRecorder.class);
            when(recorder.awaitDrained(any())).thenReturn(true);
            return recorder;
        }
    }

    @Nested
    @DisplayName("HealthEventGdprParticipant")
    class Participant {

        @Test
        @DisplayName("a user erasure verifies with a committed count that no event of the user is left")
        void verifiesNoEventLeft() {
            HealthEventGdprParticipant participant = new HealthEventGdprParticipant(events, guard);
            when(events.deleteByUserId("user")).thenReturn(3L);
            when(events.countByUserId("user")).thenReturn(1L);
            assertThrows(IllegalStateException.class, () -> participant.erase("user"), "a row the delete missed fails the step");
            when(events.countByUserId("user")).thenReturn(0L);
            assertEquals(3L, participant.erase("user"));
        }
    }

    @Nested
    @DisplayName("HealthEventIngest")
    class Ingest {

        HealthEventIngest real;
        SimpleMeterRegistry registry;

        @BeforeEach
        void setUp() {
            HealthConfigPolicy policy = mock(HealthConfigPolicy.class);
            when(policy.current("a1")).thenReturn(new Resolved(Map.of(), true, Map.of(), false, List.of("orderId"), Map.of(), false));
            when(events.insert(anyCollection())).thenAnswer(i -> ((Collection<?>) i.getArgument(0)).size());
            registry = new SimpleMeterRegistry();
            real = new HealthEventIngest(events, policy, HealthEventMatcherTest.HASHER, HealthSettings.defaults(), new HealthMetrics(registry),
                    Clock.fixed(NOW, ZoneOffset.UTC));
            real.erasureGuard = guard;
        }

        private Map<String, Object> byConversation(String conversationId) {
            return Map.of("conversationId", conversationId, "event", "order.placed");
        }

        @Test
        @DisplayName("an event naming a deleted conversation is refused and never stored")
        void tombstonedConversationRefused() {
            guard.tombstoneConversation("conv");
            HealthEventIngest.Result r = real.ingest("int", List.of("a1"), "webhook", byConversation("conv"));
            assertEquals(0, r.accepted());
            assertEquals(HealthEventIngest.REASON_CONVERSATION_UNAVAILABLE, r.refused().get(0).get("reason"));
            assertEquals(422, r.status());
            verify(events, never()).insert(anyCollection());
        }

        @Test
        @DisplayName("tombstones that cannot be read refuse retryably: erasure_check_unavailable, and 503 when that is every refusal")
        void unreadableTombstonesRefuseRetryably() {
            IErasureTombstoneStore broken = mock(IErasureTombstoneStore.class);
            when(broken.erasedAt(any(), any(), any())).thenThrow(new IllegalStateException("down"));
            real.erasureGuard = new ErasureGuard(broken, List.of(), new ErasureMetrics(new SimpleMeterRegistry()), Duration.ofDays(400),
                    Duration.ofSeconds(1), Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
            HealthEventIngest.Result r = real.ingest("int", List.of("a1"), "webhook", byConversation("conv"));
            assertEquals(503, r.status());
            assertEquals(HealthEventIngest.REASON_ERASURE_CHECK_UNAVAILABLE, r.refused().get(0).get("reason"));
            verify(events, never()).insert(anyCollection());
        }

        @Test
        @DisplayName("a delete that starts while the event is stored removes it again")
        void deleteDuringTheInsertRemovesTheEvent() {
            when(events.insert(anyCollection())).thenAnswer(i -> {
                guard.tombstoneConversation("conv");
                return ((Collection<?>) i.getArgument(0)).size();
            });
            HealthEventIngest.Result r = real.ingest("int", List.of("a1"), "webhook", byConversation("conv"));
            // Only the row of that conversation: never a by-id delete that could reach
            // another conversation's event sharing the sender's id.
            verify(events).deleteInConversation(anyString(), eq("conv"));
            verify(events, never()).delete(any());
            assertEquals(0, r.accepted(), "a removed event is not reported as accepted");
            assertEquals(1, registry.counter(HealthMetrics.EVENTS, "outcome", HealthMetrics.EVENT_ERASED).count());
            assertEquals(0, registry.counter(HealthMetrics.EVENTS, "outcome", HealthMetrics.EVENT_ACCEPTED).count());
        }

        @Test
        @DisplayName("an ingest waits in the drain barrier while it stores")
        void ingestRunsInTheBarrier() throws Exception {
            boolean[] drainedDuring = {true};
            when(events.insert(anyCollection())).thenAnswer(i -> {
                drainedDuring[0] = real.awaitDrained(Duration.ofMillis(50));
                return ((Collection<?>) i.getArgument(0)).size();
            });
            real.ingest("int", List.of("a1"), "webhook", byConversation("conv"));
            assertFalse(drainedDuring[0]);
            assertTrue(real.awaitDrained(Duration.ofMillis(50)));
        }

        @Test
        @DisplayName("an insert that fails part-way still gets the post-write check")
        void failedInsertStillChecked() {
            when(events.insert(anyCollection())).thenAnswer(i -> {
                guard.tombstoneConversation("conv");
                throw new HealthStoreException("partial", null);
            });
            assertThrows(HealthStoreException.class, () -> real.ingest("int", List.of("a1"), "webhook", byConversation("conv")));
            verify(events).deleteInConversation(anyString(), eq("conv"));
        }

        @Test
        @DisplayName("an event naming a reference names nobody and is not guarded")
        void referenceEventUnguarded() {
            guard.tombstoneConversation("conv");
            HealthEventIngest.Result r = real.ingest("int", List.of("a1"), "webhook",
                    Map.of("ref", Map.of("orderId", "A-1"), "event", "order.placed"));
            assertEquals(1, r.accepted());
            verify(events, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("HealthExport")
    class Export {

        IHealthOutcomeStore outcomes;
        HealthExport export;
        final LocalDate today = LocalDate.now(ZoneOffset.UTC);
        final Window window = new Window(today.minusDays(1), today);

        @BeforeEach
        void setUp() {
            outcomes = mock(IHealthOutcomeStore.class);
            export = new HealthExport(mock(IHealthDailyStore.class), outcomes, null, mock(HealthConfigPolicy.class), HealthSettings.defaults());
            export.erasureGuard = guard;
        }

        private HealthOutcome session(String conversation, String user) {
            Instant t = NOW.minus(Duration.ofHours(2));
            return new HealthOutcome(conversation, 0, "agent", 2, user, "api", true, t, t.plusSeconds(60), 3, 0.2, 0, "RESOLVED", "silence", false,
                    today, null, 0, List.of(), t, List.of("orderId=A-1"));
        }

        private JsonNode json() throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            export.sessions("agent", window, Set.of(2), List.of("api"), "json").write(out);
            return new ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8));
        }

        private List<String> conversations(JsonNode json) {
            List<String> ids = new ArrayList<>();
            json.get("items").forEach(item -> ids.add(item.get("conversationId").asText()));
            return ids;
        }

        @Test
        @DisplayName("rows of an erased user and of a deleted conversation are withheld")
        void erasedRowsWithheld() throws Exception {
            when(outcomes.sessions(any(HealthSessionQuery.class)))
                    .thenReturn(List.of(session("c-kept", "u"), session("c-user", "erased"), session("c-deleted", "u")));
            guard.tombstoneUser("erased", List.of());
            guard.tombstoneConversation("c-deleted");
            assertEquals(List.of("c-kept"), conversations(json()));
        }

        @Test
        @DisplayName("an erasure that starts after the page was read still withholds its rows")
        void erasureAfterThePageWasRead() throws Exception {
            when(outcomes.sessions(any(HealthSessionQuery.class))).thenAnswer(inv -> {
                List<HealthOutcome> page = List.of(session("c1", "u"), session("c2", "erased"));
                // The store answered; the erasure begins before the rows are written out.
                guard.tombstoneUser("erased", List.of("c2"));
                return page;
            });
            assertEquals(List.of("c1"), conversations(json()));
        }

        private HealthOutcome session(String conversation, int sessionNo, String user, Instant startedAt) {
            return new HealthOutcome(conversation, sessionNo, "agent", 2, user, "api", true, startedAt, NOW.plus(Duration.ofHours(3)), 3, 0.2, 0,
                    "RESOLVED", "silence", false, today, null, 0, List.of(), NOW, List.of());
        }

        @Test
        @DisplayName("a session without a start counts as captured before any erasure, also with a later sibling in the page")
        void unknownStartIsTheStrictest() throws Exception {
            when(outcomes.sessions(any(HealthSessionQuery.class)))
                    .thenReturn(List.of(session("c-x", 0, "erased", null), session("c-x", 1, "erased", NOW.plus(Duration.ofHours(1)))));
            guard.tombstoneUser("erased", List.of());
            assertEquals(List.of(), conversations(json()));
        }

        @Test
        @DisplayName("tombstones that cannot be read withhold every row (fail closed)")
        void unreadableTombstonesWithhold() throws Exception {
            IErasureTombstoneStore broken = mock(IErasureTombstoneStore.class);
            when(broken.erasedAt(any(), any(), any())).thenThrow(new IllegalStateException("down"));
            export.erasureGuard = new ErasureGuard(broken, List.of(), new ErasureMetrics(new SimpleMeterRegistry()), Duration.ofDays(400),
                    Duration.ofSeconds(1), Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
            when(outcomes.sessions(any(HealthSessionQuery.class))).thenReturn(List.of(session("c1", "u")));
            assertEquals(List.of(), conversations(json()));
        }

        @Test
        @DisplayName("a verdict is taken per page: an erasure between two pages withholds the later page's rows")
        void verdictPerPage() throws Exception {
            Instant started = NOW.minus(Duration.ofHours(2));
            List<HealthOutcome> first = new ArrayList<>();
            first.add(session("c-late", 0, "late", started));
            for (int i = 1; i <= HealthExport.PAGE; i++) {
                first.add(session("c" + i, 0, "u", started));
            }
            int[] calls = {0};
            when(outcomes.sessions(any(HealthSessionQuery.class))).thenAnswer(inv -> {
                if (calls[0]++ == 0) {
                    return first;
                }
                // The first page has been written; the erasure begins before the second.
                guard.tombstoneUser("late", List.of("c-late"));
                return List.of(session("c-late", 1, "late", started));
            });
            List<String> written = conversations(json());
            assertEquals(HealthExport.PAGE, written.size(), "the first page in full, nothing of the second");
            assertEquals(1, written.stream().filter("c-late"::equals).count());
        }

        @Test
        @DisplayName("a verdict older than the bound is taken again before the next row of the same conversation is handed on")
        void staleVerdictTakenAgain() throws Exception {
            export.verdictMaxAgeNanos = 0;
            Instant started = NOW.minus(Duration.ofHours(2));
            when(outcomes.sessions(any(HealthSessionQuery.class)))
                    .thenReturn(List.of(session("c1", 0, "late", started), session("c1", 1, "late", started)));
            List<Integer> written = new ArrayList<>();
            export.forEachSession("agent", window, Set.of(2), List.of("api"), s -> {
                written.add(s.sessionNo());
                // The erasure begins while the first row is being written out.
                guard.tombstoneUser("late", List.of("c1"));
            });
            assertEquals(List.of(0), written);
        }
    }
}
