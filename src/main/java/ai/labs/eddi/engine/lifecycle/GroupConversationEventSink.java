/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle;

import ai.labs.eddi.configs.groups.model.GroupConversation;

import java.util.List;

/**
 * SSE event definitions for group conversation streaming. Each record
 * represents a distinct event type that can be sent over a Server-Sent Events
 * connection.
 *
 * @author ginccc
 */
public final class GroupConversationEventSink {

    private GroupConversationEventSink() {
    }

    // --- Event type constants ---

    public static final String EVENT_GROUP_START = "group_start";
    public static final String EVENT_PHASE_START = "phase_start";
    public static final String EVENT_SPEAKER_START = "speaker_start";
    public static final String EVENT_TOKEN = "token";
    public static final String EVENT_SPEAKER_COMPLETE = "speaker_complete";
    public static final String EVENT_PHASE_COMPLETE = "phase_complete";
    public static final String EVENT_SYNTHESIS_START = "synthesis_start";
    public static final String EVENT_SYNTHESIS_COMPLETE = "synthesis_complete";
    public static final String EVENT_GROUP_COMPLETE = "group_complete";
    public static final String EVENT_GROUP_ERROR = "group_error";
    public static final String EVENT_TASK_PLAN_CREATED = "task_plan_created";
    public static final String EVENT_TASK_VERIFIED = "task_verified";
    public static final String EVENT_ROUND_START = "round_start";
    public static final String EVENT_CANCELLED = "cancelled";
    public static final String EVENT_AWAITING_APPROVAL = "awaiting_approval";
    public static final String EVENT_HITL_RESUME = "hitl_resume";
    /**
     * A member agent's own conversation paused for human approval
     * (PAUSE_CONVERSATION) mid-turn — unsupported inside a group discussion. The
     * member turn is recorded SKIPPED and its stranded pause is cancelled.
     */
    public static final String EVENT_MEMBER_PAUSE_SKIPPED = "member_pause_skipped";
    /**
     * A {@link GroupConversation.DecisionRecord} was set on the discussion (Wave 0,
     * F3). No feature fires this yet — I3 (verdicts), I11 (agreements), I14 (votes)
     * and I18 (awards) are the eventual producers.
     */
    public static final String EVENT_DECISION_REACHED = "decision_reached";
    /**
     * A convergence check ran after a phase repeat (I2). Fires on every check,
     * converged or not, so an observer can see a phase approaching agreement rather
     * than only the moment it stops.
     */
    public static final String EVENT_CONVERGENCE_CHECKED = "convergence_checked";
    /**
     * A phase stopped repeating early because its participants converged (I2).
     * Always preceded by an {@link #EVENT_CONVERGENCE_CHECKED} for the same repeat.
     */
    public static final String EVENT_CONVERGENCE_REACHED = "convergence_reached";
    /**
     * A HUMAN group member's turn is up (I6): the discussion paused
     * ({@code AWAITING_HUMAN_INPUT}) until that member submits their response — or
     * the group's {@code humanMemberConfig} timeout policy resolves the turn.
     * Distinct from {@link #EVENT_AWAITING_APPROVAL}: this is "you're up", not
     * "approve/reject".
     */
    public static final String EVENT_HUMAN_INPUT_REQUESTED = "human_input_requested";
    /**
     * A RETRO phase harvested lessons into team-owned group memory (I8). Fires even
     * with zero lessons stored — "the retro ran and found nothing durable" is
     * itself signal for an observer.
     */
    public static final String EVENT_RETRO_RECORDED = "retro_recorded";
    /**
     * A member created or updated a shared artifact (I17). Fired by the turn
     * executor after the turn that made the write — tools have no listener
     * reference, so accepted writes ride the live discussion's artifact-change
     * queue until the executor drains it.
     */
    public static final String EVENT_ARTIFACT_UPDATED = "artifact_updated";
    /**
     * A member turn's dollar cost landed in the discussion's ledger. Fires after
     * every turn that produced a cost attribution, so an observer can watch spend
     * accrue instead of only learning the total once the document is persisted.
     * <p>
     * Emitted for <em>system</em> spend too (the I9 transcript summarizer, the
     * stance summarizer), which is attributed to a synthetic ledger key rather than
     * a member agent — {@link CostUpdatedEvent#attributionKey()} carries that key
     * verbatim, so a consumer that indexes costs by member must tolerate a key that
     * matches no member.
     */
    public static final String EVENT_COST_UPDATED = "cost_updated";
    /**
     * A member's one-line stance was (re)computed. Fires at phase boundaries, not
     * per turn — a stance is a summary of everything that member has said so far,
     * so recomputing it mid-phase would spend on a view nothing displays yet.
     */
    public static final String EVENT_STANCE_UPDATED = "stance_updated";

    // --- Event payloads ---

    public record GroupStartEvent(String groupConversationId, String groupId, String question, String style, int totalPhases,
            List<String> memberAgentIds) {
    }

    public record PhaseStartEvent(int phaseIndex, String phaseName, String phaseType, String participants) {
    }

    public record SpeakerStartEvent(String agentId, String displayName, int phaseIndex, String phaseName) {
    }

    public record TokenEvent(String agentId, String token) {
    }

    public record SpeakerCompleteEvent(String agentId, String displayName, String response, int phaseIndex, String phaseName,
            String targetAgentId, String targetDisplayName) {

        /** Backward-compatible constructor (no target). */
        public SpeakerCompleteEvent(String agentId, String displayName, String response, int phaseIndex, String phaseName) {
            this(agentId, displayName, response, phaseIndex, phaseName, null, null);
        }
    }

    public record PhaseCompleteEvent(int phaseIndex, String phaseName) {
    }

    public record SynthesisStartEvent(String moderatorAgentId) {
    }

    public record GroupCompleteEvent(GroupConversation.GroupConversationState state, String synthesizedAnswer) {
    }

    public record GroupErrorEvent(String error) {
    }

    public record TaskPlanCreatedEvent(List<TaskSummary> tasks, boolean preConfigured) {
    }

    public record TaskVerifiedEvent(String taskId, String taskSubject, boolean passed, String feedback) {
    }

    public record TaskSummary(String id, String subject, String assignedTo, int priority) {
    }

    public record RoundStartEvent(String groupConversationId, int round, String question, int phaseCount) {
    }

    public record CancelledEvent(String reason, String cancelledBy) {
    }

    public record HitlPauseEvent(int phaseIndex, String phaseName, String reason, String granularity) {
    }

    public record HitlResumeEvent(String verdict, String note, String decidedBy) {
    }

    /**
     * A HUMAN member's turn is up (I6). Carries identifiers only — the rendered
     * prompt lives on the conversation's {@code pendingHumanInput}, which the
     * member's UI reads; an SSE frame is the wrong place for a full transcript
     * rendering.
     */
    public record HumanInputRequestedEvent(String memberId, String displayName, int phaseIndex, String phaseName) {
    }

    /**
     * Emitted when a member agent's private conversation requested human approval
     * (PAUSE_CONVERSATION) during its group turn. Member-level HITL is not
     * supported inside a group discussion in v1 — the turn is recorded SKIPPED and
     * the stranded member pause is cancelled.
     */
    public record MemberPauseSkippedEvent(String agentId, String displayName, int phaseIndex, String phaseName, String reason) {
    }

    /**
     * Emitted when a {@link GroupConversation.DecisionRecord} is set on the
     * discussion (Wave 0, F3).
     */
    public record DecisionReachedEvent(GroupConversation.DecisionRecord decision) {
    }

    /**
     * A convergence check completed for one phase repeat (I2).
     *
     * @param agreementScore
     *            the judge's 0..1 score, or {@code -1} when no judge ran (the
     *            all-abstained path, or a parse failure)
     * @param converged
     *            whether this check ended the phase's repeats
     * @param reason
     *            one-line explanation, already display-ready
     */
    public record ConvergenceCheckedEvent(int phaseIndex, String phaseName, int repeat, double agreementScore,
            boolean converged, String reason) {
    }

    /**
     * A phase stopped repeating early because it converged (I2).
     *
     * @param repeatsSkipped
     *            how many further repeats the phase was configured for but will not
     *            run — the concrete saving
     */
    public record ConvergenceReachedEvent(int phaseIndex, String phaseName, int repeat, int repeatsSkipped, String reason) {
    }

    /** A RETRO phase stored lessons into team-owned group memory (I8). */
    public record RetroRecordedEvent(String groupId, String phaseName, int lessonsStored) {
    }

    /**
     * A member created or updated a shared artifact (I17). Carries metadata only,
     * never the content — an SSE observer reads the artifact through the REST
     * payload, and content can be a quarter megabyte.
     *
     * @param created
     *            {@code true} for a fresh artifact (v1), {@code false} for an
     *            accepted update
     */
    public record ArtifactUpdatedEvent(String artifactId, String name, String type, long version, String editorAgentId,
            String status, boolean created) {
    }

    /**
     * A cost attribution landed in the discussion ledger.
     * <p>
     * Carries the <em>cumulative</em> figures rather than the delta, exactly as
     * {@code GroupCostLedger} stores them: that ledger records by replacement so a
     * duplicate attribution for the same turn is idempotent, and a delta-carrying
     * event would throw that property away — a reconnecting client that replayed
     * one frame twice would double-count. A consumer overwrites its stored value
     * for {@code attributionKey} and takes {@code totalCost} as given.
     *
     * @param attributionKey
     *            the ledger key: a member's agentId for a member turn, a synthetic
     *            {@code system:…} key for the discussion's own machinery, or
     *            {@code agentId:childConversationId} for a nested GROUP member
     * @param displayName
     *            the member's display name, or {@code null} for a system key (which
     *            names no member)
     * @param attributedCost
     *            that key's cumulative cost in USD
     * @param totalCost
     *            the discussion's re-summed total in USD
     */
    public record CostUpdatedEvent(String attributionKey, String displayName, double attributedCost, double totalCost) {
    }

    /**
     * A member's one-line stance was (re)computed (the overview dashboard's "who
     * thinks what" band).
     *
     * @param agentId
     *            the member the stance belongs to
     * @param displayName
     *            human-readable name, for surfaces with no roster to hand
     * @param stance
     *            the one-line summary, already trimmed to the configured length
     * @param llmGenerated
     *            {@code true} when a configured summarizer produced it,
     *            {@code false} when it is the lead-sentence extraction fallback —
     *            the UI distinguishes the two, because an extracted line is the
     *            member's own words and a generated one is not
     * @param coveredContributions
     *            how many of that member's own contributions the stance reflects,
     *            so a late-joining client can tell a fresh stance from a stale one
     */
    public record StanceUpdatedEvent(String agentId, String displayName, String stance, boolean llmGenerated,
            int coveredContributions) {
    }
}
