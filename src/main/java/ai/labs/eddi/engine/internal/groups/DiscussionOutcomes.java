/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.DiscussionOutcome;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.GroupConversation.TurnOutcome;
import ai.labs.eddi.utils.LogSanitizer;
import io.micrometer.core.instrument.Metrics;
import org.jboss.logging.Logger;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Decides how a discussion round ended (K1, K3 of the November 2026 review): a
 * round that ran to its end is {@code ANSWERED}, {@code NO_ANSWER} (state stays
 * {@code COMPLETED}) or {@code FAILED} (state {@code FAILED}), with a short
 * stable reason code. Before this, a provider outage completed with the failure
 * placeholder as its answer, and a round whose synthesis never ran completed
 * with {@code synthesizedAnswer: null} and nothing else to tell it from a
 * success.
 * <p>
 * An outcome field next to the state, rather than a new state: the Manager,
 * Workforce, the Slack listener, the MCP tools and stored discussions all
 * switch on the existing state names, and a consumer that does not know the
 * field keeps working; the one case that is plainly a failure — no member
 * answered at all — uses the existing {@code FAILED} state.
 */
public final class DiscussionOutcomes {

    private static final Logger LOGGER = Logger.getLogger(DiscussionOutcomes.class);

    /**
     * No member contributed anything this round: outcome {@code FAILED} when member
     * turns failed or timed out, {@code NO_ANSWER} when they were all skipped.
     */
    public static final String NO_MEMBER_ANSWERED = "NO_MEMBER_ANSWERED";
    /** The synthesis phase ran, but its turn did not answer. */
    public static final String SYNTHESIS_FAILED = "SYNTHESIS_FAILED";
    /** The round has a synthesis phase that never ran. */
    public static final String SYNTHESIS_NOT_RUN = "SYNTHESIS_NOT_RUN";
    /** {@code protocol.maxTurns} cut the round short. */
    public static final String TURN_LIMIT_REACHED = "TURN_LIMIT_REACHED";
    /** {@code protocol.maxCostPerDiscussion} cut the round short. */
    public static final String COST_CEILING_REACHED = "COST_CEILING_REACHED";
    /** The run failed with an error. Outcome {@code FAILED}. */
    public static final String DISCUSSION_ERROR = "DISCUSSION_ERROR";

    /** Rounds that ended without an answer, by outcome and reason. */
    static final String METER_UNANSWERED = "eddi.group.discussion.unanswered";

    /**
     * The entry types a member's own contribution is recorded as — the ones whose
     * presence says "a member answered". The synthesis is not one of them: a
     * synthesis over nothing is not an answer.
     */
    private static final Set<TranscriptEntryType> CONTRIBUTIONS = EnumSet.of(TranscriptEntryType.OPINION, TranscriptEntryType.CRITIQUE,
            TranscriptEntryType.REVISION, TranscriptEntryType.CHALLENGE, TranscriptEntryType.DEFENSE, TranscriptEntryType.ARGUMENT,
            TranscriptEntryType.REBUTTAL, TranscriptEntryType.PLAN, TranscriptEntryType.TASK_RESULT, TranscriptEntryType.PROPOSAL,
            TranscriptEntryType.BARGAIN, TranscriptEntryType.VOTE, TranscriptEntryType.HUMAN_INPUT, TranscriptEntryType.RETRO,
            TranscriptEntryType.BID, TranscriptEntryType.ABSTAINED);

    private DiscussionOutcomes() {
    }

    /**
     * Records the outcome of the round that just ran to its end on {@code gc}.
     * Clears {@code synthesizedAnswer} when no member contributed: a synthesis of
     * nothing is not an answer.
     *
     * @param phases
     *            the phases the round ran
     * @param costCeilingHit
     *            whether the cost ceiling stopped work phases this round
     */
    public static void settle(GroupConversation gc, List<DiscussionPhase> phases, boolean costCeilingHit) {
        List<TranscriptEntry> round;
        synchronized (gc.getTranscript()) {
            int from = Math.max(0, Math.min(gc.getRoundStartTranscriptIndex(), gc.getTranscript().size()));
            round = List.copyOf(gc.getTranscript().subList(from, gc.getTranscript().size()));
        }
        List<DiscussionPhase> phaseList = phases != null ? phases : List.of();
        boolean hasWorkPhases = phaseList.stream().anyMatch(p -> p != null && p.type() != PhaseType.SYNTHESIS);
        boolean hasSynthesisPhase = phaseList.stream().anyMatch(p -> p != null && p.type() == PhaseType.SYNTHESIS);
        boolean memberContributed = round.stream()
                .anyMatch(e -> e != null && e.speakerAgentId() != null && CONTRIBUTIONS.contains(e.type()) && e.outcome() == TurnOutcome.ANSWERED);
        String cutShort = TurnBudget.wasCut(gc) ? TURN_LIMIT_REACHED : costCeilingHit ? COST_CEILING_REACHED : null;

        // A recorded decision (a vote, a verdict, an agreement) is the round's answer.
        if (gc.getDecision() != null && gc.getDecision().type() != GroupConversation.DecisionType.NONE) {
            record(gc, DiscussionOutcome.ANSWERED, cutShort);
            return;
        }
        if (hasWorkPhases && !memberContributed) {
            // FAILED when turns ran and failed (a provider outage); when every turn was
            // skipped by design (agents not deployed, unknown ids, pauses the group
            // cannot honour) nothing broke — there was simply nobody to answer.
            boolean turnsFailed = round.stream().anyMatch(e -> e != null && e.speakerAgentId() != null
                    && (e.outcome() == TurnOutcome.FAILED || e.outcome() == TurnOutcome.TIMED_OUT));
            gc.setSynthesizedAnswer(null);
            record(gc, turnsFailed ? DiscussionOutcome.FAILED : DiscussionOutcome.NO_ANSWER, NO_MEMBER_ANSWERED);
            return;
        }
        if (gc.getSynthesizedAnswer() != null || gc.getDecision() != null || !hasSynthesisPhase) {
            record(gc, DiscussionOutcome.ANSWERED, cutShort);
            return;
        }
        if (cutShort != null) {
            record(gc, DiscussionOutcome.NO_ANSWER, cutShort);
            return;
        }
        boolean synthesisRan = round.stream().anyMatch(e -> e != null && e.speakerAgentId() != null && e.phaseIndex() >= 0
                && e.phaseIndex() < phaseList.size() && phaseList.get(e.phaseIndex()) != null
                && phaseList.get(e.phaseIndex()).type() == PhaseType.SYNTHESIS);
        record(gc, DiscussionOutcome.NO_ANSWER, synthesisRan ? SYNTHESIS_FAILED : SYNTHESIS_NOT_RUN);
    }

    /** Marks a discussion that failed with an error (state {@code FAILED}). */
    public static void failed(GroupConversation gc) {
        gc.setOutcome(DiscussionOutcome.FAILED);
        gc.setOutcomeReason(DISCUSSION_ERROR);
    }

    /** Forgets the previous round's outcome when a new round (or leg) starts. */
    public static void clear(GroupConversation gc) {
        gc.setOutcome(null);
        gc.setOutcomeReason(null);
    }

    private static void record(GroupConversation gc, DiscussionOutcome outcome, String reason) {
        gc.setOutcome(outcome);
        gc.setOutcomeReason(reason);
        if (outcome != DiscussionOutcome.ANSWERED) {
            LOGGER.warnf("Group discussion %s ended %s (%s)", LogSanitizer.sanitize(gc.getId()), outcome, reason);
            Metrics.counter(METER_UNANSWERED, "outcome", outcome.name(), "reason", reason).increment();
        }
    }
}
