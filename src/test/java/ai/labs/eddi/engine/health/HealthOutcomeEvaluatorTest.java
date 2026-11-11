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
import ai.labs.eddi.engine.ratings.ConversationRatingWithdrawn;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthOutcomeEvaluator — the feed, due sessions, ranges, fences and ratings")
class HealthOutcomeEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final int LIMIT = HealthOutcomeEvaluator.MAX_RANGE_TURNS + 1;

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
        when(policy.require(anyString(), any())).thenReturn(Resolved.DEFAULTS);
        when(policy.windowOf(anyString(), any())).thenReturn(Duration.ofHours(24));
        when(outcomes.watermark()).thenReturn(Optional.empty());
        evaluator = new HealthOutcomeEvaluator(turns, outcomes, policy, ratings, fence, HealthSettings.defaults(), new HealthMetrics(registry),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static HealthTurn turn(String conversation, int step, Instant at, boolean input, boolean question, String user) {
        return new HealthTurn(conversation, step, "e" + step, "agent", 1, null, user, "api", LocalDate.ofInstant(at, ZoneOffset.UTC), at, at, 0.01,
                null, null, null, null, 100L, null, false, List.of(), false, 0, 0, 0, 0, input, true, question, List.of("act"), false);
    }

    static HealthTurn discarded(String conversation, int step, Instant at) {
        return turn(conversation, step, at, true, false, "user").asDiscarded();
    }

    /** A greeting and an answered question, two days ago: closed, silence. */
    static List<HealthTurn> closedConversation(String conversation) {
        Instant at = NOW.minus(Duration.ofDays(2));
        return List.of(turn(conversation, 0, at, false, false, "user"), turn(conversation, 1, at.plusSeconds(60), true, false, "user"));
    }

    static HealthOutcome stored(String conversation, int sessionNo, Instant startedAt) {
        return new HealthOutcome(conversation, sessionNo, "agent", 1, "user", "api", true, startedAt, startedAt.plusSeconds(600), 1, 0.01, 0,
                "ASSUMED_RESOLVED", "silence", false, LocalDate.ofInstant(startedAt, ZoneOffset.UTC), null, 0, List.of(), startedAt);
    }

    @SuppressWarnings("unchecked")
    private List<HealthOutcome> upserted() {
        ArgumentCaptor<Collection<HealthOutcome>> rows = ArgumentCaptor.forClass(Collection.class);
        verify(outcomes, atLeastOnce()).upsert(rows.capture());
        List<HealthOutcome> all = new ArrayList<>();
        rows.getAllValues().forEach(all::addAll);
        return all;
    }

    @SuppressWarnings("unchecked")
    private Set<Integer> deleted() {
        ArgumentCaptor<Collection<Integer>> keys = ArgumentCaptor.forClass(Collection.class);
        verify(outcomes, atLeastOnce()).deleteSessions(eq("c1"), keys.capture());
        Set<Integer> all = new HashSet<>();
        keys.getAllValues().forEach(all::addAll);
        return all;
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

    @Nested
    @DisplayName("the feed")
    class Feed {

        @Test
        @DisplayName("the first pass reads the feed from the retention on, judges each conversation and moves the watermark to a minute ago")
        void firstPassBackfills() {
            when(turns.conversationsUpdatedBetween(any(), any(), any(), anyInt()))
                    .thenReturn(List.of(new ConversationActivity("c1", NOW.minus(Duration.ofDays(2)))));
            when(turns.findByConversationSteps("c1", 0, null, LIMIT)).thenReturn(closedConversation("c1"));

            HealthOutcomeEvaluator.Result result = evaluator.run(NOW.plusSeconds(60));

            verify(turns).conversationsUpdatedBetween(NOW.minus(Duration.ofDays(90)), null, NOW.minus(HealthOutcomeEvaluator.GRACE),
                    HealthOutcomeEvaluator.FEED_PAGE);
            HealthOutcome s = upserted().get(0);
            assertEquals(HealthOutcomes.ASSUMED_RESOLVED, s.outcome());
            assertEquals(HealthOutcomes.EVIDENCE_SILENCE, s.evidence());
            assertTrue(s.engaged());
            assertEquals(NOW, s.evaluatedAt(), "the evaluation's start: a mark after it survives the upsert");
            verify(outcomes).setWatermark(NOW.minus(HealthOutcomeEvaluator.GRACE));
            assertEquals(1, result.conversations());
            assertEquals(1, result.sessions());
            assertEquals(1.0, registry.get(HealthMetrics.OUTCOME_SESSIONS).counter().count());
        }

        @Test
        @DisplayName("M2: every pass re-reads the overlap behind the watermark, so a row that reached the store late is judged")
        void overlapCatchesLateRows() {
            Instant watermark = NOW.minus(Duration.ofMinutes(10));
            when(outcomes.watermark()).thenReturn(Optional.of(watermark));
            // Captured before the watermark, written after it moved past.
            Instant lateCapture = watermark.minus(Duration.ofMinutes(5));
            when(turns.conversationsUpdatedBetween(eq(watermark.minus(Duration.ofMinutes(15))), isNull(), any(), anyInt()))
                    .thenReturn(List.of(new ConversationActivity("c1", lateCapture)));
            when(turns.findByConversationSteps("c1", 0, null, LIMIT)).thenReturn(closedConversation("c1"));

            evaluator.run(NOW.plusSeconds(60));

            assertEquals(1, upserted().size());
            verify(outcomes).setWatermark(NOW.minus(HealthOutcomeEvaluator.GRACE));
        }

        @Test
        @DisplayName("M2: the watermark never moves back past what was stored, except to retry a failure")
        void watermarkMonotonic() {
            Instant watermark = NOW.minus(Duration.ofMinutes(2));
            when(outcomes.watermark()).thenReturn(Optional.of(watermark));
            when(turns.conversationsUpdatedBetween(any(), any(), any(), anyInt())).thenReturn(List.of());
            evaluator.run(NOW.plusSeconds(60));
            verify(outcomes).setWatermark(NOW.minus(HealthOutcomeEvaluator.GRACE));
        }

        @Test
        @DisplayName("M2: a failed evaluation holds the watermark at its capture, so the next pass retries it")
        void failureHoldsWatermark() {
            when(turns.conversationsUpdatedBetween(any(), any(), any(), anyInt())).thenReturn(List.of(
                    new ConversationActivity("bad", NOW.minusSeconds(300)), new ConversationActivity("c1", NOW.minusSeconds(120))));
            when(outcomes.findByConversation("bad")).thenThrow(new HealthStoreException("down", null));
            when(turns.findByConversationSteps("c1", 0, null, LIMIT)).thenReturn(closedConversation("c1"));

            HealthOutcomeEvaluator.Result result = evaluator.run(NOW.plusSeconds(60));

            assertEquals(1, result.failed());
            verify(outcomes).setWatermark(NOW.minusSeconds(300));
        }

        @Test
        @DisplayName("M2: a failure holds the watermark for the retry limit from its first failure, however old its capture — then it is released")
        void failureRetriedFromFirstFailure() {
            MovableClock clock = new MovableClock();
            evaluator = new HealthOutcomeEvaluator(turns, outcomes, policy, ratings, fence, HealthSettings.defaults(), new HealthMetrics(registry),
                    clock);
            // A turn of the backfill, captured two days ago.
            Instant captured = NOW.minus(Duration.ofDays(2));
            when(turns.conversationsUpdatedBetween(any(), any(), any(), anyInt())).thenReturn(List.of(new ConversationActivity("bad", captured)));
            when(outcomes.findByConversation("bad")).thenThrow(new HealthStoreException("down", null));

            evaluator.run(NOW.plusSeconds(60));
            verify(outcomes).setWatermark(captured);

            clock.now = NOW.plus(HealthOutcomeEvaluator.MAX_RETRY);
            evaluator.run(clock.now.plusSeconds(60));
            verify(outcomes).setWatermark(clock.now.minus(HealthOutcomeEvaluator.GRACE));
        }

        @Test
        @DisplayName("M2: re-reading the overlap takes at most half of the pass, so a busy overlap cannot keep the feed from moving past the "
                + "watermark")
        void overlapCannotStallTheFeed() {
            MovableClock clock = new MovableClock();
            evaluator = new HealthOutcomeEvaluator(turns, outcomes, policy, ratings, fence, HealthSettings.defaults(), new HealthMetrics(registry),
                    clock);
            Instant watermark = NOW.minus(Duration.ofMinutes(10));
            when(outcomes.watermark()).thenReturn(Optional.of(watermark));
            ConversationActivity beyond = new ConversationActivity("n1", watermark.plusSeconds(60));
            when(turns.conversationsUpdatedBetween(eq(watermark.minus(Duration.ofMinutes(15))), isNull(), any(), anyInt())).thenReturn(List
                    .of(new ConversationActivity("o1", watermark.minusSeconds(120)), new ConversationActivity("o2", watermark.minusSeconds(60)),
                            beyond));
            when(turns.conversationsUpdatedBetween(eq(watermark), isNull(), any(), anyInt())).thenReturn(List.of(beyond));
            // The overlap is slow: o1 takes past half of the pass, o2 would take the rest.
            when(outcomes.findByConversation("o1")).thenAnswer(inv -> {
                clock.now = NOW.plusSeconds(40);
                return List.of();
            });
            when(outcomes.findByConversation("o2")).thenAnswer(inv -> {
                clock.now = NOW.plusSeconds(70);
                return List.of();
            });
            when(turns.findByConversationSteps(anyString(), anyInt(), any(), anyInt())).thenReturn(List.of());

            evaluator.run(NOW.plusSeconds(60));

            verify(outcomes, never()).findByConversation("o2");
            verify(outcomes).findByConversation("n1");
            verify(outcomes).setWatermark(NOW.minus(HealthOutcomeEvaluator.GRACE));
        }

        @Test
        @DisplayName("a full page continues after its last conversation's (newest capture, id) pair — no gap, no repeat")
        void pagesWithoutGap() {
            Instant watermark = NOW.minus(Duration.ofHours(1));
            Instant since = watermark.minus(Duration.ofMinutes(15));
            when(outcomes.watermark()).thenReturn(Optional.of(watermark));
            List<ConversationActivity> full = new ArrayList<>();
            for (int i = 0; i < HealthOutcomeEvaluator.FEED_PAGE; i++) {
                full.add(new ConversationActivity("c" + i, since.plusSeconds(i)));
            }
            Instant last = since.plusSeconds(HealthOutcomeEvaluator.FEED_PAGE - 1L);
            when(turns.conversationsUpdatedBetween(eq(since), isNull(), any(), anyInt())).thenReturn(full);
            String lastId = "c" + (HealthOutcomeEvaluator.FEED_PAGE - 1);
            when(turns.conversationsUpdatedBetween(eq(last), eq(lastId), any(), anyInt())).thenReturn(List.of());
            when(turns.findByConversationSteps(anyString(), anyInt(), any(), anyInt())).thenReturn(List.of());

            evaluator.run(NOW.plusSeconds(60));

            verify(turns).conversationsUpdatedBetween(eq(last), eq(lastId), any(), anyInt());
            verify(outcomes).setWatermark(NOW.minus(HealthOutcomeEvaluator.GRACE));
        }

        @Test
        @DisplayName("a pass that runs out of time resumes at the conversation it did not reach")
        void stopsAtTheDeadline() {
            MovableClock clock = new MovableClock();
            evaluator = new HealthOutcomeEvaluator(turns, outcomes, policy, ratings, fence, HealthSettings.defaults(), new HealthMetrics(registry),
                    clock);
            when(turns.conversationsUpdatedBetween(any(), any(), any(), anyInt())).thenReturn(
                    List.of(new ConversationActivity("c1", NOW.minusSeconds(600)), new ConversationActivity("c2", NOW.minusSeconds(500))));
            when(outcomes.findByConversation("c1")).thenAnswer(inv -> {
                clock.now = NOW.plusSeconds(120);
                return List.of();
            });
            when(turns.findByConversationSteps(anyString(), anyInt(), any(), anyInt())).thenReturn(List.of());

            evaluator.run(NOW.plusSeconds(60));

            verify(outcomes).setWatermark(NOW.minusSeconds(500));
            verify(outcomes, never()).findByConversation("c2");
        }

        @Test
        @DisplayName("a pass whose time is already up reads nothing and leaves the watermark")
        void nothingPastTheDeadline() {
            HealthOutcomeEvaluator.Result result = evaluator.run(NOW);
            verify(turns, never()).conversationsUpdatedBetween(any(), any(), any(), anyInt());
            verify(outcomes, never()).setWatermark(any());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("M4: due sessions are judged before the feed, so a backfill cannot starve them")
        void dueFirst() {
            HealthOutcome due = stored("c1", 0, NOW.minus(Duration.ofDays(2)));
            when(outcomes.findDue(any(), anyInt())).thenReturn(List.of(due));
            when(outcomes.findByConversation("c1")).thenReturn(List.of(due));
            when(turns.findByConversationSteps("c1", 0, null, LIMIT)).thenReturn(closedConversation("c1"));
            List<String> order = new ArrayList<>();
            doAnswer(inv -> {
                order.add("due");
                return List.of(due);
            }).when(outcomes).findDue(any(), anyInt());
            doAnswer(inv -> {
                order.add("feed");
                return List.of();
            }).when(turns).conversationsUpdatedBetween(any(), any(), any(), anyInt());
            evaluator.run(NOW.plusSeconds(60));
            assertEquals(List.of("due", "feed"), order);
        }
    }

    @Nested
    @DisplayName("ranges and keys")
    class Ranges {

        /** 10 sessions of 150 turns, two days apart: steps 0..1499. */
        List<HealthTurn> longConversation() {
            List<HealthTurn> rows = new ArrayList<>();
            Instant start = NOW.minus(Duration.ofDays(30));
            for (int session = 0; session < 10; session++) {
                Instant at = start.plus(Duration.ofDays(2L * session));
                for (int i = 0; i < 150; i++) {
                    rows.add(turn("c1", session * 150 + i, at.plusSeconds(60L * i), i > 0, false, "user"));
                }
            }
            return rows;
        }

        void serve(List<HealthTurn> rows) {
            when(turns.findByConversationSteps(eq("c1"), anyInt(), any(), anyInt())).thenAnswer(inv -> {
                int from = inv.getArgument(1);
                Integer to = inv.getArgument(2);
                int limit = inv.getArgument(3);
                return rows.stream().filter(t -> t.stepIndex() >= from && (to == null || t.stepIndex() < to)).limit(limit).toList();
            });
        }

        List<HealthOutcome> storedSessions(List<HealthTurn> rows) {
            List<HealthOutcome> sessions = new ArrayList<>();
            for (int session = 0; session < 10; session++) {
                sessions.add(stored("c1", session * 150, rows.get(session * 150).timestamp()));
            }
            return sessions;
        }

        @Test
        @DisplayName("M3: a rating on a 1,500-turn conversation re-judges only its own session; every other session stays")
        void ratingOnLongConversation() {
            List<HealthTurn> rows = longConversation();
            serve(rows);
            List<HealthOutcome> sessions = storedSessions(rows);
            when(outcomes.findByConversation("c1")).thenReturn(sessions);

            // A thumbs-down on step 760, in the session starting at 750.
            evaluator.ratingChanged("c1", 760, NOW);
            verify(outcomes).markDue("c1", List.of(750), NOW);

            HealthOutcome due = sessions.get(5);
            when(outcomes.findDue(any(), anyInt())).thenReturn(List.of(due));
            when(turns.conversationsUpdatedBetween(any(), any(), any(), anyInt())).thenReturn(List.of());
            evaluator.run(NOW.plusSeconds(60));

            verify(turns).findByConversationSteps("c1", 750, 900, LIMIT);
            assertEquals(List.of(750), upserted().stream().map(HealthOutcome::sessionNo).toList());
            assertEquals(150, upserted().get(0).turns());
            assertTrue(deleted().isEmpty(), "no other session is touched");
        }

        @Test
        @DisplayName("M3: more turns since the last stored session than one read takes: complete sessions keyed by their first step, the "
                + "cut one left alone")
        void incrementalPastTheCap() {
            List<HealthTurn> rows = longConversation();
            serve(rows);
            when(outcomes.findByConversation("c1")).thenReturn(List.of(stored("c1", 0, rows.get(0).timestamp())));
            evaluator.maxRangeTurns(1_000);

            evaluator.evaluate("c1", null);

            verify(turns).findByConversationSteps("c1", 0, null, 1_001);
            assertEquals(List.of(0, 150, 300, 450, 600, 750), upserted().stream().map(HealthOutcome::sessionNo).toList(),
                    "the session at 900 runs past the 1,000 rows read");
            assertTrue(upserted().stream().allMatch(s -> s.turns() == 150));
            assertTrue(deleted().isEmpty());
        }

        @Test
        @DisplayName("a conversation is read from its latest stored session on; a session whose first turn was undone is re-keyed and its old "
                + "row deleted")
        void incrementalFromLatestSession() {
            Instant earlier = NOW.minus(Duration.ofDays(5));
            HealthOutcome first = new HealthOutcome("c1", 0, "agent", 1, "user", "email", true, earlier, earlier, 2, 0.02, 0, "ABANDONED",
                    "inferred", false, LocalDate.ofInstant(earlier, ZoneOffset.UTC), null, 0, List.of(), earlier);
            HealthOutcome latest = new HealthOutcome("c1", 4, "agent", 1, "user", "email", true, earlier, earlier, 1, 0.01, 0, "OPEN", null, true,
                    LocalDate.ofInstant(earlier, ZoneOffset.UTC), earlier, 0, List.of(), earlier);
            when(outcomes.findByConversation("c1")).thenReturn(List.of(first, latest));
            Instant at = NOW.minus(Duration.ofDays(2));
            when(turns.findByConversationSteps("c1", 4, null, LIMIT))
                    .thenReturn(List.of(discarded("c1", 4, at.minusSeconds(60)), turn("c1", 5, at, true, true, "user")));

            assertEquals(1, evaluator.evaluate("c1", null));

            HealthOutcome s = upserted().get(0);
            assertEquals(5, s.sessionNo());
            assertEquals("email", s.origin(), "the origin earlier sessions found");
            assertTrue(s.engaged(), "the first output was at step 0, in session 0");
            assertEquals(HealthOutcomes.ABANDONED, s.outcome());
            assertEquals(Set.of(4), deleted());
            verify(policy, atLeastOnce()).windowOf(eq("email"), any());
        }

        @Test
        @DisplayName("m2: a stored session whose first turns the retention removed keeps its key")
        void retentionKeepsKey() {
            HealthOutcome latest = stored("c1", 4, NOW.minus(Duration.ofDays(89)));
            when(outcomes.findByConversation("c1")).thenReturn(List.of(latest));
            Instant at = NOW.minus(Duration.ofDays(2));
            when(turns.findByConversationSteps("c1", 4, null, LIMIT)).thenReturn(List.of(turn("c1", 6, at, true, false, "user")));
            evaluator.evaluate("c1", null);
            assertEquals(4, upserted().get(0).sessionNo());
            assertTrue(upserted().get(0).engaged(), "its stored first output (step 0) counts, though its rows are gone");
            assertTrue(deleted().isEmpty());
        }

        @Test
        @DisplayName("m1: every turn of the latest session undone: it is deleted and the previous session judged again")
        void undoIntoEarlierSession() {
            Instant at = NOW.minus(Duration.ofDays(2));
            when(outcomes.findByConversation("c1")).thenReturn(List.of(stored("c1", 0, at), stored("c1", 5, at.plusSeconds(600))));
            when(turns.findByConversationSteps("c1", 5, null, LIMIT)).thenReturn(List.of(discarded("c1", 5, at.plusSeconds(600))));
            List<HealthTurn> all = new ArrayList<>(closedConversation("c1"));
            all.add(discarded("c1", 5, at.plusSeconds(600)));
            when(turns.findByConversationSteps("c1", 0, null, LIMIT)).thenReturn(all);

            evaluator.evaluate("c1", null);

            assertTrue(deleted().contains(5));
            assertEquals(List.of(0), upserted().stream().map(HealthOutcome::sessionNo).toList());
        }

        @Test
        @DisplayName("every turn from the only session on undone: its sessions are deleted, nothing written")
        void everythingUndone() {
            HealthOutcome latest = stored("c1", 3, NOW);
            when(outcomes.findByConversation("c1")).thenReturn(List.of(latest));
            when(turns.findByConversationSteps("c1", 3, null, LIMIT)).thenReturn(List.of());
            assertEquals(0, evaluator.evaluate("c1", null));
            verify(outcomes).deleteSessions("c1", Set.of(3));
            verify(outcomes, never()).upsert(any());
        }

        @Test
        @DisplayName("a healthConfig that cannot be read fails the evaluation: nothing is written under defaults, it is retried")
        void configUnavailable() {
            when(policy.require(anyString(), any())).thenThrow(new HealthStoreException("down", null));
            when(turns.findByConversationSteps("c1", 0, null, LIMIT)).thenReturn(closedConversation("c1"));
            assertThrows(HealthStoreException.class, () -> evaluator.evaluate("c1", null));
            verify(outcomes, never()).upsert(any());
        }

        @Test
        @DisplayName("m2: each session is judged by its own version's healthConfig")
        void ownVersionConfig() {
            Resolved v1 = new Resolved(Map.of(), false, Map.of(), false);
            when(policy.require("agent", 1)).thenReturn(v1);
            when(policy.require("agent", 2)).thenReturn(Resolved.DEFAULTS);
            when(turns.findByConversationSteps("c1", 0, null, LIMIT)).thenReturn(closedConversation("c1"));
            evaluator.evaluate("c1", null);
            assertEquals(HealthOutcomes.ENDED_QUIETLY, upserted().get(0).outcome(), "v1 turned the silence assumption off");
        }
    }

    @Nested
    @DisplayName("fences and ratings")
    class FencesAndRatings {

        @Test
        @DisplayName("nothing is written for a user an erasure is fencing")
        void fencedUser() {
            fence.stopInFlightWork("user");
            when(turns.findByConversationSteps("c1", 0, null, LIMIT)).thenReturn(closedConversation("c1"));
            assertEquals(0, evaluator.evaluate("c1", null));
            verify(outcomes, never()).upsert(any());
        }

        @Test
        @DisplayName("a fence raised while the conversation was evaluated deletes what was just written")
        void fenceRaisedMeanwhile() {
            when(turns.findByConversationSteps("c1", 0, null, LIMIT)).thenReturn(closedConversation("c1"));
            doAnswer(inv -> {
                fence.stopInFlightWork("user");
                return null;
            }).when(outcomes).upsert(any());
            assertEquals(0, evaluator.evaluate("c1", null));
            verify(outcomes).deleteByConversationId("c1");
        }

        @Test
        @DisplayName("due sessions are judged in their own range; a failing conversation does not stop the others")
        void dueSessions() {
            when(turns.conversationsUpdatedBetween(any(), any(), any(), anyInt())).thenReturn(List.of());
            HealthOutcome b = stored("c1", 2, NOW.minus(Duration.ofDays(2)));
            HealthOutcome a = stored("c1", 6, NOW.minus(Duration.ofDays(1)));
            HealthOutcome broken = stored("c2", 0, NOW);
            when(outcomes.findDue(any(), anyInt())).thenReturn(List.of(b, broken));
            when(outcomes.findByConversation("c1")).thenReturn(List.of(b, a));
            when(outcomes.findByConversation("c2")).thenThrow(new HealthStoreException("down", null));
            when(turns.findByConversationSteps("c1", 2, 6, LIMIT)).thenReturn(closedConversation("c1"));

            HealthOutcomeEvaluator.Result result = evaluator.run(NOW.plusSeconds(60));

            verify(turns).findByConversationSteps("c1", 2, 6, LIMIT);
            assertEquals(1, result.failed());
            assertEquals(1, result.conversations());
            assertEquals(1.0, registry.get(HealthMetrics.OUTCOME_FAILURES).counter().count());
        }

        @Test
        @DisplayName("m3/m4: a saved conversation rating marks the session it was given in — handed off, never on the request thread")
        void ratingSaved() {
            List<Runnable> queued = new ArrayList<>();
            evaluator.ratingExecutor(queued::add);
            Instant at = NOW.minus(Duration.ofDays(1));
            when(outcomes.findByConversation("c1"))
                    .thenReturn(List.of(stored("c1", 0, at.minus(Duration.ofDays(3))), stored("c1", 9, at.minusSeconds(60)), stored("c1", 20, NOW)));
            ConversationRating rating = new ConversationRating("r", "c1", -1, "agent", 1, "production", "api", "user", null, 0, 0.0, List.of(), null,
                    at, at);
            evaluator.onRatingSaved(new ConversationRatingSaved(rating));
            verify(outcomes, never()).markDue(anyString(), any(), any());
            assertEquals(1, queued.size());
            queued.get(0).run();
            verify(outcomes).markDue("c1", List.of(9), NOW);
        }

        @Test
        @DisplayName("a changed conversation rating marks every session: the one it was in before is not known")
        void ratingChangedMarksAll() {
            Instant created = NOW.minus(Duration.ofDays(3));
            when(outcomes.findByConversation("c1"))
                    .thenReturn(List.of(stored("c1", 0, created.minusSeconds(60)), stored("c1", 9, NOW.minusSeconds(60))));
            ConversationRating rating = new ConversationRating("r", "c1", -1, "agent", 1, "production", "api", "user", null, 0, 0.0, List.of(), null,
                    created, NOW);
            evaluator.onRatingSaved(new ConversationRatingSaved(rating));
            verify(outcomes).markDue(eq("c1"), isNull(), eq(NOW));

            ConversationRating reply = new ConversationRating("r2", "c1", 3, "agent", 1, "production", "api", "user", null, 0, 0.0, List.of(), null,
                    created, NOW);
            evaluator.onRatingSaved(new ConversationRatingSaved(reply));
            verify(outcomes).markDue("c1", List.of(0), NOW);
        }

        @Test
        @DisplayName("m3: a withdrawn conversation rating marks every session; a withdrawn reply rating its own; failures are swallowed")
        void ratingWithdrawn() {
            when(outcomes.findByConversation("c1")).thenReturn(List.of(stored("c1", 0, NOW), stored("c1", 9, NOW)));
            evaluator.onRatingWithdrawn(new ConversationRatingWithdrawn("c1", -1));
            verify(outcomes).markDue(eq("c1"), isNull(), eq(NOW));
            evaluator.onRatingWithdrawn(new ConversationRatingWithdrawn("c1", 12));
            verify(outcomes).markDue("c1", List.of(9), NOW);
            when(outcomes.markDue(anyString(), anyCollection(), any())).thenThrow(new HealthStoreException("down", null));
            evaluator.ratingChanged("c1", 3, NOW);
            evaluator.ratingChanged(" ", 3, NOW);
            verify(outcomes, times(1)).markDue("c1", List.of(0), NOW);
        }

        @Test
        @DisplayName("ratings: a negative conversation rating in the session's span, or a negative reply rating on its steps, is negative")
        void lazyRatings() {
            Instant from = NOW.minus(Duration.ofDays(2));
            Instant until = from.plus(Duration.ofDays(1));
            ConversationRating conversation = new ConversationRating("r1", "c1", -1, "agent", 1, "production", "api", "user", null, 1, 0.2,
                    List.of(), null, from.plusSeconds(10), from.plusSeconds(10));
            ConversationRating reply = new ConversationRating("r2", "c1", 7, "agent", 1, "production", "api", "user", null, 0, 0.0, List.of(), null,
                    NOW, NOW);
            ConversationRating positive = new ConversationRating("r3", "c1", 3, "agent", 1, "production", "api", "user", null, 1, 1.0, List.of(),
                    null, NOW, NOW);
            when(ratings.readByConversation("c1")).thenReturn(List.of(conversation, reply, positive));
            HealthSessionEvaluator.Ratings lazy = evaluator.new LazyRatings("c1");
            assertTrue(lazy.negative(0, 2, from, until), "the conversation rating fell into this session");
            assertFalse(lazy.negative(3, 5, until, until.plusSeconds(60)), "positive reply, conversation rating elsewhere");
            assertTrue(lazy.negative(6, 8, until, until.plusSeconds(60)), "the thumbs-down on step 7");
            verify(ratings, times(1)).readByConversation("c1");

            when(ratings.readByConversation("c2")).thenThrow(new RuntimeException("down"));
            assertThrows(HealthStoreException.class, () -> evaluator.new LazyRatings("c2").negative(0, 9, from, until),
                    "unknown is not \"not negative\": the evaluation fails and is retried");
        }

        @Test
        @DisplayName("origin: the first stored non-api origin up to the session read, else the rows' first non-api origin")
        void originOfConversation() {
            HealthOutcome storedOrigin = new HealthOutcome("c1", 0, "agent", 1, "user", "schedule", false, NOW, NOW, 1, 0, 0, null, null, false,
                    null, null, 0, List.of(), NOW);
            assertEquals("schedule", HealthOutcomeEvaluator.origin(List.of(storedOrigin), 3, List.of()));
            HealthTurn api = turn("c1", 0, NOW, false, false, "u");
            assertEquals("api", HealthOutcomeEvaluator.origin(List.of(), 0, List.of(api)));
        }
    }

    @Test
    @DisplayName("M4: the lag gauge reads how far the watermark is behind")
    void lagGauge() {
        when(outcomes.watermark()).thenReturn(Optional.of(NOW.minusSeconds(90)));
        SimpleMeterRegistry fresh = new SimpleMeterRegistry();
        new HealthOutcomeEvaluator(turns, outcomes, policy, ratings, fence, HealthSettings.defaults(), new HealthMetrics(fresh),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(90.0, fresh.get(HealthMetrics.OUTCOME_LAG).gauge().value());
    }
}
