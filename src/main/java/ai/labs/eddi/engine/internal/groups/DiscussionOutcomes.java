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
     * Every {@code outcomeReason} the engine writes — the one list (the field
     * stores {@link Enum#name()}).
     */
    public enum Reason {
        /**
         * No member contributed anything: outcome {@code FAILED} when member turns
         * failed or timed out, {@code NO_ANSWER} when they were all skipped.
         */
        NO_MEMBER_ANSWERED,
        /** The synthesis phase ran, but its turn did not answer. */
        SYNTHESIS_FAILED,
        /** The round has a synthesis phase that never ran. */
        SYNTHESIS_NOT_RUN,
        /**
         * {@code protocol.maxTurns} (or a nested group's inherited budget) cut the
         * round short; next to ANSWERED it only says so.
         */
        TURN_LIMIT_REACHED,
        /**
         * {@code protocol.maxCostPerDiscussion} cut the round short; next to ANSWERED
         * it only says so.
         */
        COST_CEILING_REACHED,
        /** The run failed with an error. Outcome {@code FAILED}. */
        DISCUSSION_ERROR,
        /** A human rejected the result at an approval gate (state REJECTED). */
        REJECTED_BY_HUMAN,
        /**
         * An approval gate timed out and its AUTO_REJECT policy rejected the result
         * (state REJECTED).
         */
        APPROVAL_TIMED_OUT,
        /**
         * A TASK approval cycle made no progress and the discussion was failed to
         * guarantee it ends (state FAILED).
         */
        NO_PROGRESS
    }

    public static final String NO_MEMBER_ANSWERED = Reason.NO_MEMBER_ANSWERED.name();
    public static final String SYNTHESIS_FAILED = Reason.SYNTHESIS_FAILED.name();
    public static final String SYNTHESIS_NOT_RUN = Reason.SYNTHESIS_NOT_RUN.name();
    public static final String TURN_LIMIT_REACHED = Reason.TURN_LIMIT_REACHED.name();
    public static final String COST_CEILING_REACHED = Reason.COST_CEILING_REACHED.name();
    public static final String DISCUSSION_ERROR = Reason.DISCUSSION_ERROR.name();
    public static final String REJECTED_BY_HUMAN = Reason.REJECTED_BY_HUMAN.name();
    public static final String APPROVAL_TIMED_OUT = Reason.APPROVAL_TIMED_OUT.name();
    public static final String NO_PROGRESS = Reason.NO_PROGRESS.name();

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

        boolean synthesisRan = round.stream().anyMatch(e -> e != null && e.speakerAgentId() != null && inSynthesisPhase(e, phaseList));

        // A recorded decision of a deciding type (a vote with a winner, a verdict, an
        // agreement) is the round's answer. A NONE record — dissents only, no quorum,
        // a tie, an unreadable verdict — is not.
        if (gc.getDecision() != null && gc.getDecision().type() != GroupConversation.DecisionType.NONE) {
            record(gc, DiscussionOutcome.ANSWERED, cutShort);
            return;
        }
        if (hasWorkPhases && !memberContributed) {
            gc.setSynthesizedAnswer(null);
            // FAILED only when member turns ran and failed (a provider outage). When every
            // member turn was skipped — agents not deployed or unknown, the turn cap, a
            // pause the group cannot honour — nothing broke: there was nobody to answer.
            boolean memberTurnsFailed = round.stream().anyMatch(e -> e != null && e.speakerAgentId() != null && !inSynthesisPhase(e, phaseList)
                    && (e.outcome() == TurnOutcome.FAILED || e.outcome() == TurnOutcome.TIMED_OUT));
            if (memberTurnsFailed) {
                record(gc, DiscussionOutcome.FAILED, NO_MEMBER_ANSWERED);
            } else if (cutShort != null || skippedForTurnBudget(round)) {
                record(gc, DiscussionOutcome.NO_ANSWER, TURN_LIMIT_REACHED);
            } else {
                // The synthesis is the reason only when it ran and failed; a skipped
                // synthesizer is one more member that was not there to answer.
                boolean synthesisFailed = round.stream().anyMatch(e -> e != null && e.speakerAgentId() != null && inSynthesisPhase(e, phaseList)
                        && (e.outcome() == TurnOutcome.FAILED || e.outcome() == TurnOutcome.TIMED_OUT));
                record(gc, DiscussionOutcome.NO_ANSWER, synthesisFailed ? SYNTHESIS_FAILED : NO_MEMBER_ANSWERED);
            }
            return;
        }
        if (gc.getSynthesizedAnswer() != null || (!hasSynthesisPhase && memberContributed)) {
            record(gc, DiscussionOutcome.ANSWERED, cutShort);
            return;
        }
        if (cutShort != null) {
            record(gc, DiscussionOutcome.NO_ANSWER, cutShort);
            return;
        }
        record(gc, DiscussionOutcome.NO_ANSWER, synthesisRan ? SYNTHESIS_FAILED : SYNTHESIS_NOT_RUN);
    }

    /**
     * Whether the round's outcome ends the whole discussion as {@code FAILED} (M3):
     * only when no round of it ever answered. A later round that fails — a
     * continuation during an outage — leaves the discussion {@code COMPLETED}, so
     * it can be continued once the provider is back; {@code outcome} then describes
     * that latest round.
     */
    public static boolean failsDiscussion(GroupConversation gc) {
        return gc.getOutcome() == DiscussionOutcome.FAILED && !gc.hasAnsweredRound();
    }

    private static boolean inSynthesisPhase(TranscriptEntry e, List<DiscussionPhase> phases) {
        return e.phaseIndex() >= 0 && e.phaseIndex() < phases.size() && phases.get(e.phaseIndex()) != null
                && phases.get(e.phaseIndex()).type() == PhaseType.SYNTHESIS;
    }

    private static boolean skippedForTurnBudget(List<TranscriptEntry> round) {
        return round.stream().anyMatch(e -> e != null && e.speakerAgentId() != null && e.outcome() == TurnOutcome.SKIPPED
                && MemberTurnExecutor.REASON_TURN_BUDGET.equals(e.errorReason()));
    }

    /**
     * A human rejected the discussion's result at an approval gate (state
     * {@code REJECTED}).
     */
    public static void rejected(GroupConversation gc) {
        gc.setOutcome(DiscussionOutcome.NO_ANSWER);
        gc.setOutcomeReason(REJECTED_BY_HUMAN);
    }

    /**
     * An approval gate timed out and its policy rejected the result (state
     * {@code REJECTED}).
     */
    public static void approvalTimedOut(GroupConversation gc) {
        gc.setOutcome(DiscussionOutcome.NO_ANSWER);
        gc.setOutcomeReason(APPROVAL_TIMED_OUT);
    }

    /** A TASK approval cycle made no progress (state {@code FAILED}). */
    public static void noProgress(GroupConversation gc) {
        gc.setOutcome(DiscussionOutcome.FAILED);
        gc.setOutcomeReason(NO_PROGRESS);
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
        if (outcome == DiscussionOutcome.ANSWERED) {
            gc.markRoundAnswered();
        } else {
            LOGGER.warnf("Group discussion %s ended %s (%s)", LogSanitizer.sanitize(gc.getId()), outcome, reason);
            Metrics.counter(METER_UNANSWERED, "outcome", outcome.name(), "reason", reason).increment();
        }
    }
}
