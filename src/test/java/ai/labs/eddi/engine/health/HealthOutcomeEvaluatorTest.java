/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthConfigPolicy.Resolved;
import ai.labs.eddi.engine.health.model.ConversationActivity;
import ai.labs.eddi.engine.health.model.HealthOutcome;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.ratings.ConversationRatingSaved;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthOutcomeEvaluator — the feed, due sessions, fences and ratings")
class HealthOutcomeEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private IHealthTurnStore turns;
    private IHealthOutcomeStore outcomes;
    private HealthConfigPolicy policy;
    private IConversationRatingStore ratings;
    private HealthErasureFence fence;
    private SimpleMeterRegistry registry;
    private HealthOutcomeEvaluator evaluator;

    @BeforeEach
    void setUp() {
        turns = mock(IHealthTurnStore.class);
        outcomes = mock(IHealthOutcomeStore.class);
        policy = mock(HealthConfigPolicy.class);
        ratings = mock(IConversationRatingStore.class);
        fence = new HealthErasureFence(Clock.fixed(NOW, ZoneOffset.UTC));
        registry = new SimpleMeterRegistry();
        when(policy.of(anyString(), any())).thenReturn(Resolved.DEFAULTS);
        when(policy.windowOf(anyString(), any())).thenReturn(Duration.ofHours(24));
        when(outcomes.watermark()).thenReturn(Optional.empty());
        evaluator = new HealthOutcomeEvaluator(turns, outcomes, policy, ratings, fence, HealthSettings.defaults(), new HealthMetrics(registry),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static HealthTurn turn(String conversation, int step, Instant at, boolean input, boolean question, String user) {
        return new HealthTurn(conversation, step, "e" + step, "agent", 1, null, user, "api", LocalDate.ofInstant(at, ZoneOffset.UTC), at, at, 0.01,
                null, null, null, null, 100L, null, false, List.of(), false, 0, 0, 0, 0, input, true, question, List.of("act"), false);
    }

    /** A greeting and an answered question, two days ago: closed, silence. */
    static List<HealthTurn> closedConversation(String conversation) {
        Instant at = NOW.minus(Duration.ofDays(2));
        return List.of(turn(conversation, 0, at, false, false, "user"), turn(conversation, 1, at.plusSeconds(60), true, false, "user"));
    }

    @SuppressWarnings("unchecked")
    private List<HealthOutcome> upserted() {
        ArgumentCaptor<Collection<HealthOutcome>> rows = ArgumentCaptor.forClass(Collection.class);
        verify(outcomes).upsert(rows.capture());
        return new ArrayList<>(rows.getValue());
    }

    @Test
    @DisplayName("the first pass reads the feed from the retention on, judges each conversation and moves the watermark to a minute ago")
    void firstPassBackfills() {
        when(turns.conversationsUpdatedBetween(any(), any(), anyInt()))
                .thenReturn(List.of(new ConversationActivity("c1", NOW.minus(Duration.ofDays(2)))));
        when(outcomes.findByConversation("c1")).thenReturn(List.of());
        when(turns.findByConversationFromStep("c1", 0, HealthOutcomeEvaluator.MAX_SESSION_TURNS)).thenReturn(closedConversation("c1"));

        HealthOutcomeEvaluator.Result result = evaluator.run(NOW.plusSeconds(60));

        verify(turns).conversationsUpdatedBetween(NOW.minus(Duration.ofDays(90)), NOW.minus(HealthOutcomeEvaluator.GRACE),
                HealthOutcomeEvaluator.FEED_PAGE);
        HealthOutcome s = upserted().get(0);
        assertEquals(HealthOutcomes.ASSUMED_RESOLVED, s.outcome());
        assertEquals(HealthOutcomes.EVIDENCE_SILENCE, s.evidence());
        assertTrue(s.engaged());
        verify(outcomes).setWatermark(NOW.minus(HealthOutcomeEvaluator.GRACE));
        assertEquals(1, result.conversations());
        assertEquals(1, result.sessions());
        assertEquals(1.0, registry.get(HealthMetrics.OUTCOME_SESSIONS).counter().count());
    }

    @Test
    @DisplayName("a full page continues from its last conversation's newest write, inclusive — no gap")
    void pagesWithoutGap() {
        Instant watermark = NOW.minus(Duration.ofHours(1));
        when(outcomes.watermark()).thenReturn(Optional.of(watermark));
        List<ConversationActivity> full = new ArrayList<>();
        for (int i = 0; i < HealthOutcomeEvaluator.FEED_PAGE; i++) {
            full.add(new ConversationActivity("c" + i, watermark.plusSeconds(i)));
        }
        Instant last = watermark.plusSeconds(HealthOutcomeEvaluator.FEED_PAGE - 1L);
        when(turns.conversationsUpdatedBetween(eq(watermark), any(), anyInt())).thenReturn(full);
        when(turns.conversationsUpdatedBetween(eq(last), any(), anyInt())).thenReturn(List.of());
        when(outcomes.findByConversation(anyString())).thenReturn(List.of());
        when(turns.findByConversationFromStep(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        evaluator.run(NOW.plusSeconds(60));

        verify(outcomes).setWatermark(last);
        verify(outcomes).setWatermark(NOW.minus(HealthOutcomeEvaluator.GRACE));
    }

    /** A clock the test moves. */
    static final class MovableClock extends Clock {
        volatile Instant now = NOW;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("a pass that runs out of time resumes at the conversation it did not reach")
    void stopsAtTheDeadline() {
        MovableClock clock = new MovableClock();
        evaluator = new HealthOutcomeEvaluator(turns, outcomes, policy, ratings, fence, HealthSettings.defaults(), new HealthMetrics(registry),
                clock);
        when(turns.conversationsUpdatedBetween(any(), any(), anyInt()))
                .thenReturn(List.of(new ConversationActivity("c1", NOW.minusSeconds(600)), new ConversationActivity("c2", NOW.minusSeconds(500))));
        when(outcomes.findByConversation("c1")).thenAnswer(inv -> {
            clock.now = NOW.plusSeconds(120);
            return List.of();
        });
        when(turns.findByConversationFromStep(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        evaluator.run(NOW.plusSeconds(60));

        verify(outcomes).setWatermark(NOW.minusSeconds(500));
        verify(outcomes, never()).findByConversation("c2");
        verify(outcomes, never()).findDue(any(), anyInt());
    }

    @Test
    @DisplayName("a pass whose time is already up reads nothing and leaves the watermark")
    void nothingPastTheDeadline() {
        HealthOutcomeEvaluator.Result result = evaluator.run(NOW);
        verify(turns, never()).conversationsUpdatedBetween(any(), any(), anyInt());
        verify(outcomes, never()).setWatermark(any());
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("a conversation is read from its latest stored session on; sessions no longer found from there are deleted")
    void incrementalFromLatestSession() {
        Instant earlier = NOW.minus(Duration.ofDays(5));
        HealthOutcome first = new HealthOutcome("c1", 0, "agent", 1, "user", "email", true, earlier, earlier, 2, 0.02, 0, "ABANDONED", "inferred",
                false, LocalDate.ofInstant(earlier, ZoneOffset.UTC), null, 0, List.of(), earlier);
        HealthOutcome latest = new HealthOutcome("c1", 4, "agent", 1, "user", "email", true, earlier, earlier, 1, 0.01, 0, "OPEN", null, true,
                LocalDate.ofInstant(earlier, ZoneOffset.UTC), earlier, 0, List.of(), earlier);
        when(outcomes.findByConversation("c1")).thenReturn(List.of(first, latest));
        // Session 4's first turn was undone; the conversation now continues at step 5.
        Instant at = NOW.minus(Duration.ofDays(2));
        when(turns.findByConversationFromStep("c1", 4, HealthOutcomeEvaluator.MAX_SESSION_TURNS))
                .thenReturn(List.of(turn("c1", 5, at, true, true, "user")));

        assertEquals(1, evaluator.evaluate("c1", null));

        HealthOutcome s = upserted().get(0);
        assertEquals(5, s.sessionNo());
        assertEquals("email", s.origin(), "the origin earlier sessions found");
        assertTrue(s.engaged(), "the first output was at step 0, in session 0");
        assertEquals(HealthOutcomes.ABANDONED, s.outcome());
        verify(outcomes).deleteSessions("c1", Set.of(4));
        verify(policy).windowOf(eq("email"), any());
    }

    @Test
    @DisplayName("every turn from the latest session on undone: its sessions are deleted, nothing written")
    void everythingUndone() {
        HealthOutcome latest = new HealthOutcome("c1", 3, "agent", 1, "user", "api", true, NOW, NOW, 1, 0.01, 0, "OPEN", null, true,
                LocalDate.ofInstant(NOW, ZoneOffset.UTC), NOW, 0, List.of(), NOW);
        when(outcomes.findByConversation("c1")).thenReturn(List.of(latest));
        when(turns.findByConversationFromStep("c1", 3, HealthOutcomeEvaluator.MAX_SESSION_TURNS)).thenReturn(List.of());
        assertEquals(0, evaluator.evaluate("c1", null));
        verify(outcomes).deleteSessions("c1", Set.of(3));
        verify(outcomes, never()).upsert(any());
    }

    @Test
    @DisplayName("nothing is written for a user an erasure is fencing")
    void fencedUser() {
        fence.stopInFlightWork("user");
        when(outcomes.findByConversation("c1")).thenReturn(List.of());
        when(turns.findByConversationFromStep("c1", 0, HealthOutcomeEvaluator.MAX_SESSION_TURNS)).thenReturn(closedConversation("c1"));
        assertEquals(0, evaluator.evaluate("c1", null));
        verify(outcomes, never()).upsert(any());
    }

    @Test
    @DisplayName("a fence raised while the conversation was evaluated deletes what was just written")
    void fenceRaisedMeanwhile() {
        when(outcomes.findByConversation("c1")).thenReturn(List.of());
        when(turns.findByConversationFromStep("c1", 0, HealthOutcomeEvaluator.MAX_SESSION_TURNS)).thenReturn(closedConversation("c1"));
        doAnswer(inv -> {
            fence.stopInFlightWork("user");
            return null;
        }).when(outcomes).upsert(any());
        assertEquals(0, evaluator.evaluate("c1", null));
        verify(outcomes).deleteByConversationId("c1");
    }

    @Test
    @DisplayName("due sessions are judged again from the earliest due one; a failing conversation does not stop the others")
    void dueSessions() {
        when(turns.conversationsUpdatedBetween(any(), any(), anyInt())).thenReturn(List.of());
        HealthOutcome a = new HealthOutcome("c1", 6, "agent", 1, "user", "api", true, NOW, NOW, 1, 0, 0, "OPEN", null, true, null, NOW, 0, List.of(),
                NOW);
        HealthOutcome b = new HealthOutcome("c1", 2, "agent", 1, "user", "api", true, NOW, NOW, 1, 0, 0, "ASSUMED_RESOLVED", "silence", false, null,
                NOW, 0, List.of(), NOW);
        HealthOutcome broken = new HealthOutcome("c2", 0, "agent", 1, "user", "api", true, NOW, NOW, 1, 0, 0, "OPEN", null, true, null, NOW, 0,
                List.of(), NOW);
        when(outcomes.findDue(NOW, HealthOutcomeEvaluator.DUE_PAGE)).thenReturn(List.of(a, b, broken));
        when(outcomes.findByConversation("c1")).thenReturn(List.of(b, a));
        when(outcomes.findByConversation("c2")).thenThrow(new HealthStoreException("down", null));
        when(turns.findByConversationFromStep("c1", 2, HealthOutcomeEvaluator.MAX_SESSION_TURNS)).thenReturn(closedConversation("c1"));

        HealthOutcomeEvaluator.Result result = evaluator.run(NOW.plusSeconds(60));

        verify(turns).findByConversationFromStep("c1", 2, HealthOutcomeEvaluator.MAX_SESSION_TURNS);
        assertEquals(1, result.failed());
        assertEquals(1, result.conversations());
        assertEquals(1.0, registry.get(HealthMetrics.OUTCOME_FAILURES).counter().count());
    }

    @Test
    @DisplayName("a saved rating makes the conversation's sessions due now; a store failure is swallowed")
    void ratingSaved() {
        ConversationRating rating = new ConversationRating("r", "c1", -1, "agent", 1, "production", "api", "user", null, 0, 0.0, List.of(), null,
                NOW, NOW);
        evaluator.onRatingSaved(new ConversationRatingSaved(rating));
        verify(outcomes).markDue("c1", NOW);
        when(outcomes.markDue(anyString(), any())).thenThrow(new HealthStoreException("down", null));
        evaluator.ratingSaved("c1");
        evaluator.ratingSaved(" ");
        verify(outcomes, times(2)).markDue("c1", NOW);
    }

    @Test
    @DisplayName("ratings: a negative conversation rating in the session's span, or a negative reply rating on its steps, is negative")
    void lazyRatings() {
        Instant from = NOW.minus(Duration.ofDays(2));
        Instant until = from.plus(Duration.ofDays(1));
        ConversationRating conversation = new ConversationRating("r1", "c1", -1, "agent", 1, "production", "api", "user", null, 1, 0.2, List.of(),
                null, from.plusSeconds(10), from.plusSeconds(10));
        ConversationRating reply = new ConversationRating("r2", "c1", 7, "agent", 1, "production", "api", "user", null, 0, 0.0, List.of(), null,
                NOW, NOW);
        ConversationRating positive = new ConversationRating("r3", "c1", 3, "agent", 1, "production", "api", "user", null, 1, 1.0, List.of(), null,
                NOW, NOW);
        when(ratings.readByConversation("c1")).thenReturn(List.of(conversation, reply, positive));
        HealthSessionEvaluator.Ratings lazy = evaluator.new LazyRatings("c1");
        assertTrue(lazy.negative(0, 2, from, until), "the conversation rating fell into this session");
        assertFalse(lazy.negative(3, 5, until, until.plusSeconds(60)), "positive reply, conversation rating elsewhere");
        assertTrue(lazy.negative(6, 8, until, until.plusSeconds(60)), "the thumbs-down on step 7");
        verify(ratings, times(1)).readByConversation("c1");

        when(ratings.readByConversation("c2")).thenThrow(new RuntimeException("down"));
        assertFalse(evaluator.new LazyRatings("c2").negative(0, 9, from, until), "unknown is not negative");
    }

    @Test
    @DisplayName("origin: the first stored non-api origin up to the session read, else the rows' first non-api origin")
    void originOfConversation() {
        HealthOutcome stored = new HealthOutcome("c1", 0, "agent", 1, "user", "schedule", false, NOW, NOW, 1, 0, 0, null, null, false, null, null, 0,
                List.of(), NOW);
        assertEquals("schedule", HealthOutcomeEvaluator.origin(List.of(stored), 3, List.of()));
        HealthTurn api = turn("c1", 0, NOW, false, false, "u");
        assertEquals("api", HealthOutcomeEvaluator.origin(List.of(), 0, List.of(api)));
    }
}
