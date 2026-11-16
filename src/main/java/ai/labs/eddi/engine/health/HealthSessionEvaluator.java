/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthConfigPolicy.Resolved;
import ai.labs.eddi.engine.health.model.HealthOutcome;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.lifecycle.IConversation;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Splits a conversation's turn rows into sessions and decides how each ended —
 * a pure function of the rows, the agent's {@code healthConfig} and the clock,
 * so every rule has a unit test (plan §4.4).
 * <p>
 * <b>Sessions.</b> A conversation splits wherever a turn starts more than the
 * inactivity window (of its origin, under the {@code healthConfig} of the
 * version the earlier session ran on) after the previous turn ended. A turn's
 * time is taken from its row: it started {@code durationMs} before its first
 * capture ({@code timestamp}) and was last active at the later of that and its
 * last capture ({@code updatedAt}) — so a HITL resume, which keeps the first
 * {@code timestamp}, keeps its session open for a full window after the resume,
 * and a session that had closed while paused reopens to {@code OPEN}. A session
 * is <em>open</em> until its window has passed since its last activity; a turn
 * that arrives later starts a new session. Of several rows of one step (an
 * unsettled, insert-only capture next to the settled one), the settled row is
 * the turn.
 * <p>
 * <b>Outcome</b>, highest precedence first:
 * <ol>
 * <li><b>explicit</b> — the last action of the session mapped in
 * {@code healthConfig.outcomes} ({@code agent_action}); otherwise the last
 * claim of {@code report_conversation_outcome} ({@code self_reported}, weak).
 * Known while the session is open, so the row carries it {@code provisional}
 * until the window passes (or the agent ends the conversation);</li>
 * <li><b>structural</b> — the last turn emitted {@code CONVERSATION_END} or
 * {@code STOP_CONVERSATION} and did not fail: {@code ASSUMED_RESOLVED} from
 * {@code agent_closed}, final at once (the conversation is over); the last turn
 * failed and nothing followed within the window: {@code FAILED},
 * {@code structural};</li>
 * <li><b>inferred</b>, once the window has passed — the last turn is still
 * waiting on a human approver: {@code ENDED_QUIETLY}; the last output asked a
 * question: {@code ABANDONED}; a negative rating: {@code ENDED_QUIETLY};
 * otherwise {@code ASSUMED_RESOLVED} from {@code silence} when
 * {@code assumeResolvedOnSilence} (the default), else
 * {@code ENDED_QUIETLY}.</li>
 * </ol>
 * Until the window has passed and without an explicit or agent-closed signal,
 * the session is {@code OPEN}. Sessions of an origin that is not interactive
 * (schedules, webhooks, A2A, groups, delegation, agent tests) are counted but
 * never judged: their outcome is {@code null}. A conversation's
 * {@code endReason} ({@code idle}, {@code agent-version-retired}) is never
 * evidence — it is not even read.
 */
public final class HealthSessionEvaluator {

    /** At most this many of the last turn's actions are kept on the row. */
    static final int MAX_LAST_ACTIONS = HealthTurnFactory.MAX_ACTIONS;

    /**
     * Whether a person rated a session negatively — looked up only when it matters.
     */
    @FunctionalInterface
    public interface Ratings {
        /**
         * @param fromStep
         *            the session's first step
         * @param toStep
         *            its last step
         * @param from
         *            when it started
         * @param until
         *            when its window closes
         */
        boolean negative(int fromStep, int toStep, Instant from, Instant until);

        Ratings NONE = (fromStep, toStep, from, until) -> false;
    }

    /**
     * What the evaluation of one conversation (or one range of it) needs.
     *
     * @param turns
     *            the rows to judge, any order; discarded rows and discard markers
     *            are skipped, and of several rows of one step the settled one wins
     * @param origin
     *            the conversation's origin
     * @param windowOf
     *            the inactivity window under the {@code healthConfig} of an agent
     *            version — a session is split and closed by the window of the
     *            version it ran on
     * @param configOf
     *            the resolved {@code healthConfig} of an agent version
     * @param knownFirstOutputStep
     *            the conversation's first output step as earlier sessions saw it,
     *            or {@code null}
     * @param firstSessionNo
     *            the key of the first session, or {@code null} for the step index
     *            of its first turn (a caller whose first rows were removed by the
     *            retention passes the session's stored key, so it is never
     *            re-keyed)
     */
    public record Input(String conversationId, List<HealthTurn> turns, String origin, Function<Integer, Duration> windowOf,
            Function<Integer, Resolved> configOf, Integer knownFirstOutputStep, Integer firstSessionNo, Ratings ratings, Instant now) {
    }

    private HealthSessionEvaluator() {
    }

    /** Whether sessions of {@code origin} are judged for effectiveness. */
    public static boolean evaluated(String origin) {
        return HealthOrigins.INTERACTIVE.contains(origin);
    }

    /**
     * The rows that are turns: recorded, not discarded, one per step — the settled
     * row when an unsettled (insert-only) capture of the same step exists, else the
     * newest — ordered by step.
     */
    static List<HealthTurn> turnsOf(List<HealthTurn> rows) {
        Map<Integer, HealthTurn> byStep = new TreeMap<>();
        for (HealthTurn t : rows) {
            if (t == null || !t.recorded() || t.discarded()) {
                continue;
            }
            byStep.merge(t.stepIndex(), t, HealthSessionEvaluator::preferred);
        }
        return new ArrayList<>(byStep.values());
    }

    private static HealthTurn preferred(HealthTurn a, HealthTurn b) {
        if (a.possiblyUnsettled() != b.possiblyUnsettled()) {
            return a.possiblyUnsettled() ? b : a;
        }
        return b.activeUntil().isAfter(a.activeUntil()) ? b : a;
    }

    /** The conversation's sessions, oldest first. */
    public static List<HealthOutcome> evaluate(Input in) {
        List<HealthTurn> live = turnsOf(in.turns());
        if (live.isEmpty()) {
            return List.of();
        }
        Integer firstOutput = in.knownFirstOutputStep();
        for (HealthTurn t : live) {
            if (t.agentOutput() && (firstOutput == null || t.stepIndex() < firstOutput)) {
                firstOutput = t.stepIndex();
            }
        }
        List<List<HealthTurn>> sessions = new ArrayList<>();
        List<HealthTurn> current = new ArrayList<>();
        Instant previousEnd = null;
        Integer previousVersion = null;
        for (HealthTurn t : live) {
            // The window of the session that would end here — of the version it ran on.
            if (previousEnd != null && Duration.between(previousEnd, t.startedAt()).compareTo(in.windowOf().apply(previousVersion)) > 0) {
                sessions.add(current);
                current = new ArrayList<>();
                previousEnd = null;
            }
            current.add(t);
            Instant end = t.activeUntil();
            previousEnd = previousEnd == null || end.isAfter(previousEnd) ? end : previousEnd;
            previousVersion = t.agentVersion();
        }
        sessions.add(current);
        List<HealthOutcome> out = new ArrayList<>(sessions.size());
        for (int i = 0; i < sessions.size(); i++) {
            List<HealthTurn> rows = sessions.get(i);
            int sessionNo = i == 0 && in.firstSessionNo() != null ? in.firstSessionNo() : rows.get(0).stepIndex();
            out.add(session(in, rows, sessionNo, firstOutput));
        }
        return out;
    }

    private static HealthOutcome session(Input in, List<HealthTurn> rows, int sessionNo, Integer firstOutput) {
        HealthTurn last = rows.get(rows.size() - 1);
        Instant startedAt = null;
        Instant endedAt = null;
        double cost = 0;
        int failures = 0;
        boolean engaged = false;
        for (HealthTurn t : rows) {
            startedAt = startedAt == null || t.startedAt().isBefore(startedAt) ? t.startedAt() : startedAt;
            endedAt = endedAt == null || t.activeUntil().isAfter(endedAt) ? t.activeUntil() : endedAt;
            cost += positive(t.costUsd()) + positive(t.discardedCostUsd());
            if (t.failed() && !t.interrupted()) {
                failures++;
            }
            if (t.userInput() && firstOutput != null && t.stepIndex() > firstOutput) {
                engaged = true;
            }
        }
        Instant closesAt = endedAt.plus(in.windowOf().apply(last.agentVersion()));
        boolean closed = !in.now().isBefore(closesAt);
        Decision decision = evaluated(in.origin())
                ? decide(rows, last, closed, in.configOf().apply(last.agentVersion()), in.ratings(), sessionNo, startedAt, closesAt)
                : new Decision(null, null, !closed);
        Instant dueAt = decision.provisional() ? closesAt : null;
        List<String> actions = last.actions();
        List<String> lastActions = actions.size() > MAX_LAST_ACTIONS ? actions.subList(actions.size() - MAX_LAST_ACTIONS, actions.size()) : actions;
        return new HealthOutcome(in.conversationId(), sessionNo, last.agentId(), last.agentVersion(), last.userId(), in.origin(), engaged, startedAt,
                endedAt, rows.size(), cost, failures, decision.outcome(), decision.evidence(), decision.provisional(),
                LocalDate.ofInstant(endedAt, ZoneOffset.UTC), dueAt, firstOutput, lastActions, in.now());
    }

    /** An outcome, its evidence, and whether it can still change. */
    record Decision(String outcome, String evidence, boolean provisional) {
    }

    /** The precedence of the class comment, for one judged session. */
    static Decision decide(List<HealthTurn> rows, HealthTurn last, boolean closed, Resolved config, Ratings ratings, int sessionNo, Instant startedAt,
                           Instant closesAt) {
        Resolved c = config == null ? Resolved.DEFAULTS : config;
        // 1. Explicit: the last mapped action, else the last self-report.
        String mapped = null;
        String reported = null;
        for (HealthTurn t : rows) {
            for (String action : t.actions()) {
                String outcome = c.outcomes().get(action);
                if (outcome != null) {
                    mapped = outcome;
                }
            }
            if (t.selfReportedOutcome() != null) {
                reported = t.selfReportedOutcome();
            }
        }
        // The agent ended the conversation after a successful turn: nothing can
        // follow, so whatever the session ended with is final at once.
        boolean agentClosed = (last.actions().contains(IConversation.CONVERSATION_END) || last.actions().contains(IConversation.STOP_CONVERSATION))
                && !last.failed() && !last.paused();
        boolean settled = closed || agentClosed;
        if (mapped != null) {
            return new Decision(mapped, HealthOutcomes.EVIDENCE_AGENT_ACTION, !settled);
        }
        if (reported != null) {
            return new Decision(reported, HealthOutcomes.EVIDENCE_SELF_REPORTED, !settled);
        }
        // 2. Structural.
        if (agentClosed) {
            return new Decision(HealthOutcomes.ASSUMED_RESOLVED, HealthOutcomes.EVIDENCE_AGENT_CLOSED, false);
        }
        if (!closed) {
            return new Decision(HealthOutcomes.OPEN, null, true);
        }
        // A cancelled turn (TURN_INTERRUPTED) is the person's or the platform's stop,
        // not the agent failing: it is judged like a quiet ending below.
        if (last.failed() && !last.interrupted()) {
            return new Decision(HealthOutcomes.FAILED, HealthOutcomes.EVIDENCE_STRUCTURAL, false);
        }
        // 3. Inferred, after the window.
        if (last.paused() || last.interrupted()) {
            // Waiting on an approver, or stopped mid-turn, is not the person's silence.
            return new Decision(HealthOutcomes.ENDED_QUIETLY, HealthOutcomes.EVIDENCE_INFERRED, false);
        }
        if (last.lastOutputWasQuestion()) {
            return new Decision(HealthOutcomes.ABANDONED, HealthOutcomes.EVIDENCE_INFERRED, false);
        }
        Ratings r = ratings == null ? Ratings.NONE : ratings;
        if (r.negative(sessionNo, last.stepIndex(), startedAt, closesAt)) {
            return new Decision(HealthOutcomes.ENDED_QUIETLY, HealthOutcomes.EVIDENCE_INFERRED, false);
        }
        return c.assumeResolvedOnSilence()
                ? new Decision(HealthOutcomes.ASSUMED_RESOLVED, HealthOutcomes.EVIDENCE_SILENCE, false)
                : new Decision(HealthOutcomes.ENDED_QUIETLY, HealthOutcomes.EVIDENCE_INFERRED, false);
    }

    private static double positive(Double value) {
        return value != null && Double.isFinite(value) && value > 0 ? value : 0.0;
    }
}
