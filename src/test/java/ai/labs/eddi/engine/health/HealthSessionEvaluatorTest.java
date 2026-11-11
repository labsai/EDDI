/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthConfigPolicy.Resolved;
import ai.labs.eddi.engine.health.HealthSessionEvaluator.Input;
import ai.labs.eddi.engine.health.HealthSessionEvaluator.Ratings;
import ai.labs.eddi.engine.health.model.HealthOutcome;
import ai.labs.eddi.engine.health.model.HealthTurn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HealthSessionEvaluator — sessions, windows, engagement and the outcome precedence")
class HealthSessionEvaluatorTest {

    static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
    static final Duration DAY = Duration.ofHours(24);
    static final Duration THREE_DAYS = Duration.ofHours(72);

    /** A turn: what the evaluator reads of a row. */
    static final class Turn {
        int step;
        Instant at;
        boolean input = true;
        boolean output = true;
        boolean question;
        String errorKind;
        boolean paused;
        boolean discarded;
        List<String> actions = List.of();
        String selfReported;
        Integer version = 1;
        String origin = "api";
        double cost = 0.01;
        Instant updatedAt;

        Turn(int step, Instant at) {
            this.step = step;
            this.at = at;
        }

        Turn noInput() {
            input = false;
            return this;
        }

        Turn noOutput() {
            output = false;
            return this;
        }

        Turn question() {
            question = true;
            return this;
        }

        Turn failed() {
            errorKind = "TURN_ERROR";
            return this;
        }

        Turn kind(String kind) {
            errorKind = kind;
            return this;
        }

        /** Captured again later — a HITL resume keeps the first timestamp. */
        Turn updated(Instant at) {
            updatedAt = at;
            return this;
        }

        Turn paused() {
            paused = true;
            return this;
        }

        Turn discarded() {
            discarded = true;
            return this;
        }

        Turn actions(String... names) {
            actions = List.of(names);
            return this;
        }

        Turn reported(String outcome) {
            selfReported = outcome;
            return this;
        }

        Turn version(int v) {
            version = v;
            return this;
        }

        Turn origin(String o) {
            origin = o;
            return this;
        }

        HealthTurn row() {
            return new HealthTurn("c1", step, "e" + step + (discarded ? "x" : ""), "agent", version, null, "user", origin,
                    LocalDate.ofInstant(at, ZoneOffset.UTC), at, updatedAt == null ? at : updatedAt, cost, null, 1L, 1L, 2L, 1_000L, errorKind,
                    paused,
                    List.of(), false, 0, 0, 0, 0,
                    input, output, question, actions, discarded, selfReported);
        }
    }

    static Turn turn(int step, Instant at) {
        return new Turn(step, at);
    }

    /** A greeting at T0 (no input), then a question and an answer at T0+1min. */
    static List<Turn> greetingAndOneExchange() {
        return new ArrayList<>(List.of(turn(0, T0).noInput(), turn(1, T0.plusSeconds(60))));
    }

    static List<HealthOutcome> evaluate(List<Turn> turns, String origin, Duration window, Resolved config, Instant now) {
        return evaluate(turns, origin, window, v -> config, null, Ratings.NONE, now);
    }

    static List<HealthOutcome> evaluate(List<Turn> turns, String origin, Duration window, Function<Integer, Resolved> configOf, Integer knownFirst,
                                        Ratings ratings, Instant now) {
        return HealthSessionEvaluator.evaluate(new Input("c1", turns.stream().map(Turn::row).toList(), origin, v -> window, configOf, knownFirst,
                null, ratings, now));
    }

    static HealthOutcome only(List<HealthOutcome> sessions) {
        assertEquals(1, sessions.size(), sessions.toString());
        return sessions.get(0);
    }

    static Resolved mapping(Map<String, String> outcomes) {
        return new Resolved(outcomes, true, Map.of(), false);
    }

    @Nested
    @DisplayName("segmentation")
    class Segmentation {

        @Test
        @DisplayName("a gap longer than the window starts a new session keyed by its first step; a shorter one does not")
        void splitsAtTheWindow() {
            List<Turn> turns = greetingAndOneExchange();
            turns.add(turn(2, T0.plus(Duration.ofHours(23))));
            turns.add(turn(3, T0.plus(Duration.ofHours(48))));
            List<HealthOutcome> sessions = evaluate(turns, "api", DAY, Resolved.DEFAULTS, T0.plus(Duration.ofDays(10)));
            assertEquals(List.of(0, 3), sessions.stream().map(HealthOutcome::sessionNo).toList());
            assertEquals(3, sessions.get(0).turns());
            assertEquals(1, sessions.get(1).turns());
            assertEquals(T0.plus(Duration.ofHours(23)), sessions.get(0).endedAt(), "the last turn's capture: the timestamp is taken when it ends");
            assertEquals(T0.minusSeconds(1), sessions.get(0).startedAt(), "the first turn's capture less its duration");
            assertEquals(LocalDate.of(2026, 11, 2), sessions.get(0).day(), "the day the session ended");
        }

        @Test
        @DisplayName("e-mail's 72 h window keeps a two-day reply in the same session")
        void emailWindow() {
            List<Turn> turns = greetingAndOneExchange();
            turns.forEach(t -> t.origin("email"));
            turns.add(turn(2, T0.plus(Duration.ofHours(60))).origin("email"));
            assertEquals(1, evaluate(turns, "email", THREE_DAYS, Resolved.DEFAULTS, T0.plus(Duration.ofDays(10))).size());
            assertEquals(2, evaluate(turns, "email", DAY, Resolved.DEFAULTS, T0.plus(Duration.ofDays(10))).size(), "the chat window would split it");
        }

        @Test
        @DisplayName("discarded rows (undone executions) are not turns: they neither count nor bridge a gap")
        void discardedRowsSkipped() {
            List<Turn> turns = greetingAndOneExchange();
            turns.add(turn(2, T0.plus(Duration.ofHours(20))).discarded());
            turns.add(turn(2, T0.plus(Duration.ofHours(30))));
            List<HealthOutcome> sessions = evaluate(turns, "api", DAY, Resolved.DEFAULTS, T0.plus(Duration.ofDays(10)));
            assertEquals(2, sessions.size(), "the undone turn at 20 h does not bridge to 30 h");
            assertEquals(2, sessions.get(0).turns());
        }

        @Test
        @DisplayName("sums the session's spend (undone executions included) and its failed turns")
        void spendAndFailures() {
            List<Turn> turns = greetingAndOneExchange();
            turns.add(turn(2, T0.plusSeconds(120)).failed());
            HealthOutcome s = only(evaluate(turns, "api", DAY, Resolved.DEFAULTS, T0.plus(Duration.ofDays(2))));
            assertEquals(0.03, s.costUsd(), 1e-9);
            assertEquals(1, s.failures());
            assertEquals(3, s.turns());
        }

        @Test
        @DisplayName("an empty or all-discarded conversation has no sessions")
        void nothing() {
            assertTrue(evaluate(List.of(), "api", DAY, Resolved.DEFAULTS, T0).isEmpty());
            assertTrue(evaluate(List.of(turn(0, T0).discarded()), "api", DAY, Resolved.DEFAULTS, T0).isEmpty());
        }
    }

    @Nested
    @DisplayName("open, provisional and resume")
    class OpenSessions {

        @Test
        @DisplayName("inside its window a session without an explicit signal is OPEN, provisional and due when the window closes")
        void openInsideWindow() {
            HealthOutcome s = only(evaluate(greetingAndOneExchange(), "api", DAY, Resolved.DEFAULTS, T0.plus(Duration.ofHours(5))));
            assertEquals(HealthOutcomes.OPEN, s.outcome());
            assertNull(s.evidence());
            assertTrue(s.provisional());
            assertEquals(s.endedAt().plus(DAY), s.dueAt());
        }

        @Test
        @DisplayName("once the window passed, the same rows close the session: final, nothing due")
        void closesAfterWindow() {
            HealthOutcome s = only(evaluate(greetingAndOneExchange(), "api", DAY, Resolved.DEFAULTS, T0.plus(Duration.ofHours(25))));
            assertEquals(HealthOutcomes.ASSUMED_RESOLVED, s.outcome());
            assertFalse(s.provisional());
            assertNull(s.dueAt());
        }

        @Test
        @DisplayName("a mapped action decides the outcome inside the window, provisionally; a later turn in the window re-decides it")
        void provisionalExplicit() {
            Resolved config = mapping(Map.of("order_confirmed", "RESOLVED", "handoff_human", "ESCALATED"));
            List<Turn> turns = greetingAndOneExchange();
            turns.get(1).actions("order_confirmed");
            HealthOutcome s = only(evaluate(turns, "api", DAY, config, T0.plus(Duration.ofHours(1))));
            assertEquals(HealthOutcomes.RESOLVED, s.outcome());
            assertTrue(s.provisional(), "the person may still come back inside the window");

            turns.add(turn(2, T0.plus(Duration.ofHours(2))).actions("handoff_human"));
            HealthOutcome after = only(evaluate(turns, "api", DAY, config, T0.plus(Duration.ofHours(3))));
            assertEquals(HealthOutcomes.ESCALATED, after.outcome(), "the last mapped action counts");
        }

        @Test
        @DisplayName("a turn after the window starts a new session; the closed one keeps its outcome")
        void resumeAfterClose() {
            List<Turn> turns = greetingAndOneExchange();
            turns.get(1).question();
            Instant now = T0.plus(Duration.ofHours(30));
            HealthOutcome closed = only(evaluate(turns, "api", DAY, Resolved.DEFAULTS, now));
            assertEquals(HealthOutcomes.ABANDONED, closed.outcome());

            turns.add(turn(2, now));
            List<HealthOutcome> sessions = evaluate(turns, "api", DAY, Resolved.DEFAULTS, now.plusSeconds(60));
            assertEquals(2, sessions.size());
            assertEquals(closed.outcome(), sessions.get(0).outcome());
            assertEquals(closed.sessionNo(), sessions.get(0).sessionNo());
            assertEquals(HealthOutcomes.OPEN, sessions.get(1).outcome());
            assertEquals(2, sessions.get(1).sessionNo());
        }
    }

    @Nested
    @DisplayName("engagement")
    class Engagement {

        @Test
        @DisplayName("a greeting followed by one person's turn is engaged")
        void greetingThenTurn() {
            assertTrue(only(evaluate(greetingAndOneExchange(), "api", DAY, Resolved.DEFAULTS, T0)).engaged());
        }

        @Test
        @DisplayName("one question and its answer, with no greeting, is not: no person's turn after the first output")
        void singleExchange() {
            assertFalse(only(evaluate(List.of(turn(0, T0)), "api", DAY, Resolved.DEFAULTS, T0)).engaged());
        }

        @Test
        @DisplayName("a later session is engaged by one turn when the conversation's first output came earlier")
        void laterSessionUsesConversationsFirstOutput() {
            List<Turn> later = List.of(turn(7, T0));
            assertFalse(only(evaluate(later, "api", DAY, v -> Resolved.DEFAULTS, null, Ratings.NONE, T0)).engaged());
            HealthOutcome s = only(evaluate(later, "api", DAY, v -> Resolved.DEFAULTS, 0, Ratings.NONE, T0));
            assertTrue(s.engaged(), "the agent spoke at step 0, in an earlier session");
            assertEquals(0, s.firstOutputStep());
        }
    }

    @Nested
    @DisplayName("origins")
    class Origins {

        @Test
        @DisplayName("non-interactive sessions are counted but never judged: no outcome, no evidence")
        void notEvaluated() {
            for (String origin : List.of("schedule", "webhook", "a2a", "group-member", "delegated", "agent-test")) {
                List<Turn> turns = greetingAndOneExchange();
                turns.get(1).actions("CONVERSATION_END");
                HealthOutcome s = only(evaluate(turns, origin, DAY, Resolved.DEFAULTS, T0.plus(Duration.ofDays(3))));
                assertNull(s.outcome(), origin);
                assertNull(s.evidence(), origin);
                assertFalse(s.provisional(), origin);
            }
        }

        @Test
        @DisplayName("every interactive origin is judged")
        void interactive() {
            for (String origin : List.of("api", "v1", "mcp", "email", "slack")) {
                assertEquals(HealthOutcomes.ASSUMED_RESOLVED,
                        only(evaluate(greetingAndOneExchange(), origin, DAY, Resolved.DEFAULTS, T0.plus(Duration.ofDays(3)))).outcome(), origin);
            }
        }
    }

    @Nested
    @DisplayName("precedence")
    class Precedence {

        final Instant closed = T0.plus(Duration.ofDays(3));

        HealthOutcome judge(List<Turn> turns, Resolved config) {
            return only(evaluate(turns, "api", DAY, config, closed));
        }

        @Test
        @DisplayName("1. a mapped action beats a self-report, a failed last turn and a question")
        void mappedActionFirst() {
            List<Turn> turns = greetingAndOneExchange();
            turns.get(1).actions("order_confirmed").reported("FAILED");
            turns.add(turn(2, T0.plusSeconds(120)).failed().question());
            HealthOutcome s = judge(turns, mapping(Map.of("order_confirmed", "RESOLVED")));
            assertEquals(HealthOutcomes.RESOLVED, s.outcome());
            assertEquals(HealthOutcomes.EVIDENCE_AGENT_ACTION, s.evidence());
        }

        @Test
        @DisplayName("a mapped CONVERSATION_END says how the agent closed it")
        void mappedConversationEnd() {
            List<Turn> turns = greetingAndOneExchange();
            turns.get(1).actions("CONVERSATION_END");
            HealthOutcome s = judge(turns, mapping(Map.of("CONVERSATION_END", "DEFLECTED")));
            assertEquals(HealthOutcomes.DEFLECTED, s.outcome());
            assertEquals(HealthOutcomes.EVIDENCE_AGENT_ACTION, s.evidence());
        }

        @Test
        @DisplayName("2. the session's last self-report beats structural and inferred signals")
        void selfReportSecond() {
            List<Turn> turns = greetingAndOneExchange();
            turns.get(1).reported("ESCALATED");
            turns.add(turn(2, T0.plusSeconds(120)).reported("RESOLVED").question());
            HealthOutcome s = judge(turns, Resolved.DEFAULTS);
            assertEquals(HealthOutcomes.RESOLVED, s.outcome(), "last write wins");
            assertEquals(HealthOutcomes.EVIDENCE_SELF_REPORTED, s.evidence());
        }

        @Test
        @DisplayName("3. CONVERSATION_END or STOP_CONVERSATION after a successful turn: ASSUMED_RESOLVED / agent_closed, final at once")
        void agentClosed() {
            for (String action : List.of("CONVERSATION_END", "STOP_CONVERSATION")) {
                List<Turn> turns = greetingAndOneExchange();
                turns.get(1).actions(action).question();
                HealthOutcome s = only(evaluate(turns, "api", DAY, Resolved.DEFAULTS, T0.plus(Duration.ofMinutes(5))));
                assertEquals(HealthOutcomes.ASSUMED_RESOLVED, s.outcome(), action);
                assertEquals(HealthOutcomes.EVIDENCE_AGENT_CLOSED, s.evidence(), action);
                assertFalse(s.provisional(), "the conversation ended; nothing can follow");
                assertNull(s.dueAt());
            }
        }

        @Test
        @DisplayName("a mapped outcome on a turn that ended the conversation is final at once too")
        void explicitThenClosed() {
            List<Turn> turns = greetingAndOneExchange();
            turns.get(1).actions("order_confirmed", "CONVERSATION_END");
            HealthOutcome s = only(evaluate(turns, "api", DAY, mapping(Map.of("order_confirmed", "RESOLVED")), T0.plus(Duration.ofMinutes(5))));
            assertEquals(HealthOutcomes.RESOLVED, s.outcome());
            assertFalse(s.provisional());
        }

        @Test
        @DisplayName("CONVERSATION_END on a failed last turn is no agent_closed: the failure decides")
        void closedButFailed() {
            List<Turn> turns = greetingAndOneExchange();
            turns.get(1).actions("CONVERSATION_END").failed();
            HealthOutcome s = judge(turns, Resolved.DEFAULTS);
            assertEquals(HealthOutcomes.FAILED, s.outcome());
            assertEquals(HealthOutcomes.EVIDENCE_STRUCTURAL, s.evidence());
        }

        @Test
        @DisplayName("4. the last turn failed and nothing followed: FAILED / structural — only once the window passed")
        void lastTurnFailed() {
            List<Turn> turns = greetingAndOneExchange();
            turns.get(1).failed().question();
            assertEquals(HealthOutcomes.OPEN, only(evaluate(turns, "api", DAY, Resolved.DEFAULTS, T0.plus(Duration.ofHours(2)))).outcome(),
                    "the person may still retry");
            HealthOutcome s = judge(turns, Resolved.DEFAULTS);
            assertEquals(HealthOutcomes.FAILED, s.outcome());
            assertEquals(HealthOutcomes.EVIDENCE_STRUCTURAL, s.evidence());
        }

        @Test
        @DisplayName("an earlier failure that the session recovered from is no FAILED")
        void earlierFailureRecovered() {
            List<Turn> turns = greetingAndOneExchange();
            turns.get(1).failed();
            turns.add(turn(2, T0.plusSeconds(120)));
            assertEquals(HealthOutcomes.ASSUMED_RESOLVED, judge(turns, Resolved.DEFAULTS).outcome());
        }

        @Test
        @DisplayName("5. a question left open after the window: ABANDONED / inferred")
        void abandoned() {
            List<Turn> turns = greetingAndOneExchange();
            turns.get(1).question();
            HealthOutcome s = judge(turns, Resolved.DEFAULTS);
            assertEquals(HealthOutcomes.ABANDONED, s.outcome());
            assertEquals(HealthOutcomes.EVIDENCE_INFERRED, s.evidence());
        }

        @Test
        @DisplayName("a turn still waiting on an approver is not silence: ENDED_QUIETLY")
        void pausedLastTurn() {
            List<Turn> turns = greetingAndOneExchange();
            turns.get(1).paused().actions("CONVERSATION_END");
            HealthOutcome s = judge(turns, Resolved.DEFAULTS);
            assertEquals(HealthOutcomes.ENDED_QUIETLY, s.outcome());
            assertEquals(HealthOutcomes.EVIDENCE_INFERRED, s.evidence());
        }

        @Test
        @DisplayName("6. quiet after a successful turn: ASSUMED_RESOLVED / silence by default")
        void silence() {
            HealthOutcome s = judge(greetingAndOneExchange(), Resolved.DEFAULTS);
            assertEquals(HealthOutcomes.ASSUMED_RESOLVED, s.outcome());
            assertEquals(HealthOutcomes.EVIDENCE_SILENCE, s.evidence());
        }

        @Test
        @DisplayName("assumeResolvedOnSilence: false records ENDED_QUIETLY instead")
        void silenceOff() {
            HealthOutcome s = judge(greetingAndOneExchange(), new Resolved(Map.of(), false, Map.of(), false));
            assertEquals(HealthOutcomes.ENDED_QUIETLY, s.outcome());
            assertEquals(HealthOutcomes.EVIDENCE_INFERRED, s.evidence());
        }

        @Test
        @DisplayName("a negative rating turns silence into ENDED_QUIETLY; ratings are only asked when silence would decide")
        void negativeRating() {
            AtomicInteger asked = new AtomicInteger();
            Ratings negative = (from, to, start, until) -> {
                asked.incrementAndGet();
                return true;
            };
            HealthOutcome s = only(evaluate(greetingAndOneExchange(), "api", DAY, v -> Resolved.DEFAULTS, null, negative, closed));
            assertEquals(HealthOutcomes.ENDED_QUIETLY, s.outcome());
            assertEquals(1, asked.get());

            List<Turn> question = greetingAndOneExchange();
            question.get(1).question();
            only(evaluate(question, "api", DAY, v -> Resolved.DEFAULTS, null, negative, closed));
            assertEquals(1, asked.get(), "ABANDONED was decided without them");
        }

        @Test
        @DisplayName("the outcome follows the configuration of the version the session ended on")
        void configOfLastVersion() {
            List<Turn> turns = greetingAndOneExchange();
            turns.get(0).version(1);
            turns.get(1).version(2).actions("done");
            Function<Integer, Resolved> configs = v -> v == 2 ? mapping(Map.of("done", "RESOLVED")) : Resolved.DEFAULTS;
            HealthOutcome s = only(evaluate(turns, "api", DAY, configs, null, Ratings.NONE, closed));
            assertEquals(HealthOutcomes.RESOLVED, s.outcome());
            assertEquals(2, s.agentVersion());
        }

        @Test
        @DisplayName("the last turn's actions are kept for mapping suggestions")
        void lastActions() {
            List<Turn> turns = greetingAndOneExchange();
            turns.get(1).actions("a", "b");
            assertEquals(List.of("a", "b"), judge(turns, Resolved.DEFAULTS).lastActions());
        }
    }

    @Nested
    @DisplayName("review fixes: resumes, unsettled rows, per-version windows, stable keys")
    class ReviewFixes {

        @Test
        @DisplayName("M1: a HITL resume keeps the first timestamp; the session is open for a full window after the resume")
        void resumeReopens() {
            List<Turn> paused = greetingAndOneExchange();
            paused.get(1).paused();
            HealthOutcome closedWhilePaused = only(evaluate(paused, "api", DAY, Resolved.DEFAULTS, T0.plus(Duration.ofHours(25))));
            assertEquals(HealthOutcomes.ENDED_QUIETLY, closedWhilePaused.outcome());

            // The approver resumes 30 h later: same row, same timestamp, a new updatedAt.
            Instant resumed = T0.plus(Duration.ofHours(30));
            List<Turn> after = greetingAndOneExchange();
            after.get(1).updated(resumed);
            HealthOutcome reopened = only(evaluate(after, "api", DAY, Resolved.DEFAULTS, resumed.plusSeconds(60)));
            assertEquals(HealthOutcomes.OPEN, reopened.outcome(), "reopened, not silence at once");
            assertTrue(reopened.provisional());
            assertEquals(resumed, reopened.endedAt());
            assertEquals(resumed.plus(DAY), reopened.dueAt());
            assertEquals(HealthOutcomes.ASSUMED_RESOLVED,
                    only(evaluate(after, "api", DAY, Resolved.DEFAULTS, resumed.plus(DAY).plusSeconds(1))).outcome());
        }

        @Test
        @DisplayName("M1: a resume inside the window moves the close to a window after the resume")
        void resumeInsideWindow() {
            Instant resumed = T0.plus(Duration.ofHours(20));
            List<Turn> turns = greetingAndOneExchange();
            turns.get(1).updated(resumed);
            HealthOutcome s = only(evaluate(turns, "api", DAY, Resolved.DEFAULTS, T0.plus(Duration.ofHours(30))));
            assertEquals(HealthOutcomes.OPEN, s.outcome(), "would have closed at T0+24h by the first capture");
            assertEquals(resumed.plus(DAY), s.dueAt());
        }

        @Test
        @DisplayName("m5: of two rows of one step the settled one is the turn; a cancelled duplicate inflates nothing")
        void duplicateUnsettledRow() {
            List<Turn> turns = greetingAndOneExchange();
            Turn cancelled = turn(1, T0.plusSeconds(90)).kind("TURN_INTERRUPTED");
            turns.add(cancelled);
            HealthOutcome s = only(evaluate(turns, "api", DAY, Resolved.DEFAULTS, T0.plus(Duration.ofDays(3))));
            assertEquals(2, s.turns());
            assertEquals(0, s.failures());
            assertEquals(HealthOutcomes.ASSUMED_RESOLVED, s.outcome());
        }

        @Test
        @DisplayName("m5: a last turn the person cancelled is no structural FAILED")
        void cancelledLastTurn() {
            List<Turn> turns = greetingAndOneExchange();
            turns.get(1).kind("TURN_INTERRUPTED");
            HealthOutcome s = only(evaluate(turns, "api", DAY, Resolved.DEFAULTS, T0.plus(Duration.ofDays(3))));
            assertEquals(HealthOutcomes.ENDED_QUIETLY, s.outcome());
            assertEquals(0, s.failures());
            List<Turn> deadline = greetingAndOneExchange();
            deadline.get(1).kind("TURN_DEADLINE");
            assertEquals(HealthOutcomes.FAILED, only(evaluate(deadline, "api", DAY, Resolved.DEFAULTS, T0.plus(Duration.ofDays(3)))).outcome(),
                    "the agent's own deadline is its failure");
        }

        @Test
        @DisplayName("m2: a session is split and closed by the window of the version it ran on")
        void windowPerVersion() {
            List<Turn> turns = new ArrayList<>(List.of(turn(0, T0).noInput().version(1), turn(1, T0.plusSeconds(60)).version(1),
                    turn(2, T0.plus(Duration.ofHours(3))).version(2), turn(3, T0.plus(Duration.ofHours(5))).version(2)));
            Function<Integer, Duration> windows = v -> v == 1 ? Duration.ofHours(1) : DAY;
            List<HealthOutcome> sessions = HealthSessionEvaluator.evaluate(new Input("c1", turns.stream().map(Turn::row).toList(), "api", windows,
                    v -> Resolved.DEFAULTS, null, null, Ratings.NONE, T0.plus(Duration.ofHours(6))));
            assertEquals(List.of(0, 2), sessions.stream().map(HealthOutcome::sessionNo).toList(), "v1's 1 h window split it; v2's 24 h did not");
            assertEquals(HealthOutcomes.ASSUMED_RESOLVED, sessions.get(0).outcome(), "v1's window has passed");
            assertEquals(HealthOutcomes.OPEN, sessions.get(1).outcome(), "v2's has not");
        }

        @Test
        @DisplayName("m2: a stored key passed by the caller is kept for the first session")
        void keepsKey() {
            List<Turn> turns = List.of(turn(5, T0), turn(6, T0.plusSeconds(60)));
            HealthOutcome s = only(HealthSessionEvaluator.evaluate(new Input("c1", turns.stream().map(Turn::row).toList(), "api", v -> DAY,
                    v -> Resolved.DEFAULTS, 0, 3, Ratings.NONE, T0)));
            assertEquals(3, s.sessionNo());
        }
    }
}
