/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionRecord;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.HitlPauseType;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.HumanRequestDetail;
import ai.labs.eddi.configs.groups.model.HumanTieBreakConfig.OnTieBreakTimeout;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.utils.LogSanitizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The pause half of {@code tiePolicy: HUMAN_DECIDES}: commits the
 * {@code AWAITING_HUMAN_INPUT} pause a tied vote waits in, resolves it from the
 * decider's choice or from a timeout, and moves the discussion on.
 * <p>
 * Rides I6's human-input machinery whole — the state, the submission endpoint
 * and its access guard (the decider or an admin), the timeout schedule, crash
 * recovery and the inbox — and is shaped like the facilitator escalation: not a
 * speaker's turn ({@code speakerIdx = -1}), answered by a configured principal.
 * What differs is the answer (one of N options) and what follows it: the
 * discussion resumes at the phase AFTER the vote, or pauses for the vote
 * phase's own approval gate when it has one.
 * <p>
 * A tie-break makes no model call, so it is not charged as a turn; a
 * MODERATOR_DECIDES timeout fallback is, exactly like the policy it borrows.
 */
public class GroupTieBreakPauses {

    private static final Logger LOGGER = Logger.getLogger(GroupTieBreakPauses.class);

    static final String METRIC_TIEBREAKS = "eddi.group.vote.tiebreaks";
    static final String RESOLUTION_HUMAN = "human";
    static final String RESOLUTION_TIMEOUT_NONE = "timeout_none";
    static final String RESOLUTION_TIMEOUT_MODERATOR = "timeout_moderator";
    /** The audit category a tie-break resolution is filed under. */
    static final String APPROVAL_CATEGORY = "group.vote_tiebreak";

    private final IAgentGroupStore groupStore;
    private final IGroupConversationStore conversationStore;
    private final AuditLedgerService auditLedgerService;
    private final ConcurrentHashMap<String, DiscussionControlToken> discussionControls;
    private final ExecutorService executorService;
    private final CallerIdentityContext callerIdentityContext;
    private final GroupConversationService groupConversationService;
    private final GroupHitlCoordinator hitlCoordinator;
    private final PhaseExecutionEngine phaseExecutionEngine;
    private final MeterRegistry meterRegistry;
    private final Counter counterGroupHitlPause;
    private final Counter counterGroupHitlResume;

    public GroupTieBreakPauses(IAgentGroupStore groupStore, IGroupConversationStore conversationStore, AuditLedgerService auditLedgerService,
            ConcurrentHashMap<String, DiscussionControlToken> discussionControls, ExecutorService executorService,
            CallerIdentityContext callerIdentityContext, GroupConversationService groupConversationService,
            GroupHitlCoordinator hitlCoordinator, PhaseExecutionEngine phaseExecutionEngine, MeterRegistry meterRegistry,
            Counter counterGroupHitlPause, Counter counterGroupHitlResume) {
        this.groupStore = groupStore;
        this.conversationStore = conversationStore;
        this.auditLedgerService = auditLedgerService;
        this.discussionControls = discussionControls;
        this.executorService = executorService;
        this.callerIdentityContext = callerIdentityContext;
        this.groupConversationService = groupConversationService;
        this.hitlCoordinator = hitlCoordinator;
        this.phaseExecutionEngine = phaseExecutionEngine;
        this.meterRegistry = meterRegistry;
        this.counterGroupHitlPause = counterGroupHitlPause;
        this.counterGroupHitlResume = counterGroupHitlResume;
    }

    // =================================================================
    // Commit
    // =================================================================

    /**
     * Commits the tie-break pause for the VOTE phase that just tallied. The
     * unresolved NONE record (with its tally) is already on {@code gc}, so readers
     * see how the vote went while it waits.
     *
     * @param repeatIdx
     *            the VOTE phase's last repeat
     * @param currentTurnCount
     *            the leg's turn count — not plus one: a human choice spends no turn
     */
    public void commit(GroupConversation gc, int phaseIdx, int repeatIdx, DiscussionPhase phase,
                       HumanTieBreakEngine.PendingTieBreak tieBreak, int currentTurnCount, GroupDiscussionEventListener listener)
            throws IResourceStore.ResourceStoreException {
        OnTieBreakTimeout onTimeout = tieBreak.tieBreak().onTimeout();
        var detail = HumanRequestDetail.tieBreak(tieBreak.options(), tieBreak.transcriptFrom(), onTimeout.name());
        gc.setResumePoint(new GroupConversation.ResumePoint(phaseIdx, repeatIdx, -1, GroupConversation.RESUME_KIND_TIE_BREAK));
        // The pending record's own policy stays SKIP_TURN/ABORT — what every timeout
        // handler understands; the tie-specific resolution rides the detail.
        gc.setPendingHumanInput(new GroupConversation.PendingHumanInput(tieBreak.decider(), tieBreak.deciderDisplayName(), phaseIdx,
                repeatIdx, -1, TranscriptEntryType.HUMAN_INPUT.name(), tieBreak.prompt(),
                onTimeout == OnTieBreakTimeout.ABORT ? "ABORT" : "SKIP_TURN", Instant.now(), detail));
        gc.setState(GroupConversationState.AWAITING_HUMAN_INPUT);
        gc.setPausedAt(Instant.now());
        gc.setPausedAtPhaseIndex(phaseIdx);
        gc.setPausedPhaseName(phase.name());
        gc.setPausedTurnCount(currentTurnCount);
        gc.setHitlPauseType(HitlPauseType.HUMAN_TURN);
        gc.setHitlPauseReason("Vote tied — waiting for " + tieBreak.deciderDisplayName() + " to decide — phase: " + phase.name());
        gc.setHitlApprovalTimeout(tieBreak.timeout());
        gc.setHitlTimeoutPolicy(null);
        RunningDiscussionWrites.updateWhileRunning(conversationStore, gc);

        hitlCoordinator.scheduleHumanTurnTimeout(gc);
        counterGroupHitlPause.increment();
        if (listener != null) {
            listener.onHumanInputRequested(new GroupConversationEventSink.HumanInputRequestedEvent(tieBreak.decider(),
                    tieBreak.deciderDisplayName(), phaseIdx, phase.name(), detail));
        }
        LOGGER.infof("Group discussion %s paused on a tied vote at phase %d — waiting for '%s' to decide",
                LogSanitizer.sanitize(gc.getId()), phaseIdx, LogSanitizer.sanitize(tieBreak.decider()));
    }

    /**
     * A facilitator escalation deferred to the boundary where a vote tied under
     * {@code HUMAN_DECIDES} is dropped with a FACILITATION note: the boundary can
     * pause only once, and the decider is about to be asked anyway. The same
     * precedence the phase approval gate already takes over an escalation. Any
     * other deferred action is returned unchanged.
     */
    public static FacilitatorEngine.FacilitatorAction yieldEscalation(GroupConversation gc, HumanTieBreakEngine.PendingTieBreak tieBreak,
                                                                      FacilitatorEngine.FacilitatorAction deferred, int phaseIdx,
                                                                      DiscussionPhase phase) {
        if (tieBreak == null || deferred == null || deferred.kind() != FacilitatorEngine.FacilitatorAction.Kind.ESCALATE) {
            return deferred;
        }
        var escalation = deferred.escalation();
        gc.getTranscript().add(new TranscriptEntry(null, "Facilitator",
                "Facilitator escalation to " + escalation.principalId() + " suppressed — the vote tied and "
                        + tieBreak.deciderDisplayName() + " is deciding it. The facilitator's question: " + escalation.question(),
                phaseIdx, phase.name(), TranscriptEntryType.FACILITATION, Instant.now(), null, null));
        return null;
    }

    // =================================================================
    // Resolve
    // =================================================================

    /**
     * Resolves the tie from the decider's choice. Validation and the config-drift
     * check run before any mutation, so an invalid choice changes nothing.
     *
     * @throws IllegalArgumentException
     *             if {@code choice} does not name exactly one option, or the
     *             rationale is too long — a 400 at the API
     */
    public GroupConversation resolve(GroupConversation gc, GroupConversation.PendingHumanInput pending, String choice, String rationale,
                                     String resolvedBy, GroupDiscussionEventListener listener, String boundPauseId)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException,
            IResourceStore.ResourceModifiedException {
        List<String> options = pending.detail().options();
        String chosen = HumanTieBreakEngine.matchChoice(choice, options);
        if (chosen == null) {
            throw new IllegalArgumentException(HumanTieBreakEngine.INVALID_CHOICE_PREFIX + ": " + String.join(" | ", options));
        }
        if (rationale != null && rationale.length() > GroupHitlCoordinator.MAX_HUMAN_INPUT_LENGTH) {
            throw new IllegalArgumentException(
                    "Content exceeds the maximum length of " + GroupHitlCoordinator.MAX_HUMAN_INPUT_LENGTH + " characters");
        }
        var context = loadContext(gc, pending);
        var rebuilt = HumanTieBreakEngine.rebuildOutcome(gc, context.phase(), pending.phaseIdx(), transcriptFrom(pending));
        DecisionRecord decision = HumanTieBreakEngine.decisionFor(chosen, pending.displayName(), rebuilt.quorumReached(), rebuilt,
                gc.getDecision(), context.phase().name(), rationale);
        String note = "Tie broken by " + pending.displayName() + ": \"" + chosen + "\""
                + (rationale != null && !rationale.isBlank() ? " — " + rationale.strip() : "");
        var entry = new TranscriptEntry(pending.memberId(), pending.displayName(), note, pending.phaseIdx(), context.phase().name(),
                TranscriptEntryType.HUMAN_INPUT, Instant.now(), null, null);
        return complete(gc, pending, context, entry, decision, null, chosen, resolvedBy, RESOLUTION_HUMAN, "HUMAN_CHOICE", listener,
                boundPauseId);
    }

    /**
     * Resolves an unanswered tie-break. {@code ABORT} never reaches here — the
     * pending record carries the plain {@code ABORT} policy, which the timeout
     * handler applies by cancelling.
     */
    public void onTimeout(GroupConversation gc, GroupConversation.PendingHumanInput pending, String boundPauseId)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException,
            IResourceStore.ResourceModifiedException {
        var context = loadContext(gc, pending);
        boolean moderator = OnTieBreakTimeout.MODERATOR_DECIDES.name().equals(pending.detail().timeoutResolution());
        DecisionRecord none = HumanTieBreakEngine.noDecision(pending.displayName(), gc.getHitlApprovalTimeout(), gc.getDecision(),
                context.phase().name());
        String reason = "No decision from " + pending.displayName()
                + (gc.getHitlApprovalTimeout() != null ? " within " + gc.getHitlApprovalTimeout() : "")
                + (moderator ? " — the moderator decides" : " — the vote stays undecided");
        var entry = new TranscriptEntry(pending.memberId(), pending.displayName(), null, pending.phaseIdx(), context.phase().name(),
                TranscriptEntryType.SKIPPED, Instant.now(), reason, null);
        complete(gc, pending, context, entry, none, moderator ? context : null, null, "system:timeout",
                moderator ? RESOLUTION_TIMEOUT_MODERATOR : RESOLUTION_TIMEOUT_NONE,
                moderator ? "TIMEOUT_MODERATOR" : "TIMEOUT_NO_DECISION", null, boundPauseId);
    }

    /**
     * The group config, runtime phase list and VOTE phase a resolution works on.
     */
    record ResolveContext(AgentGroupConfiguration config, List<DiscussionPhase> phases, DiscussionPhase phase) {
    }

    private ResolveContext loadContext(GroupConversation gc, GroupConversation.PendingHumanInput pending)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        IResourceStore.IResourceId currentGroupId = groupStore.getCurrentResourceId(gc.getGroupId());
        if (currentGroupId == null) {
            throw new IResourceStore.ResourceNotFoundException("Group not found.");
        }
        AgentGroupConfiguration config = groupStore.read(gc.getGroupId(), currentGroupId.getVersion());
        List<DiscussionPhase> phases = groupConversationService.effectivePhases(gc, config);
        GroupConversation.ResumePoint bookmark = gc.getResumePoint();
        if (bookmark == null || bookmark.phaseIdx() != pending.phaseIdx()) {
            throw new GroupDiscussionException("The paused turn's bookmark is missing or inconsistent — cancel the discussion");
        }
        if (pending.phaseIdx() >= phases.size()
                || (gc.getPausedPhaseName() != null && !gc.getPausedPhaseName().equals(phases.get(pending.phaseIdx()).name()))) {
            throw new GroupDiscussionException(
                    "Group config changed while paused — the bookmarked phase no longer matches; fix the config or cancel");
        }
        return new ResolveContext(config, phases, phases.get(pending.phaseIdx()));
    }

    /**
     * The shared tail: record, CAS out of the pause, then move on in the background
     * — run the moderator fallback first when asked, then either pause for the vote
     * phase's own approval gate or resume at the next phase.
     *
     * @param moderatorContext
     *            non-null when the moderator should decide (timeout fallback)
     */
    private GroupConversation complete(GroupConversation gc, GroupConversation.PendingHumanInput pending, ResolveContext context,
                                       TranscriptEntry entry, DecisionRecord decision, ResolveContext moderatorContext, String chosen,
                                       String resolvedBy, String resolution, String reasonCode, GroupDiscussionEventListener listener,
                                       String boundPauseId)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException,
            IResourceStore.ResourceModifiedException {
        String groupConversationId = gc.getId();
        // Saved for the one rollback path below (executor saturation).
        final GroupConversation.ResumePoint savedBookmark = gc.getResumePoint();
        final DecisionRecord savedDecision = gc.getDecision();
        final Instant savedPausedAt = gc.getPausedAt();
        final String savedPausedPhaseName = gc.getPausedPhaseName();
        final int savedPausedPhaseIdx = gc.getPausedAtPhaseIndex();
        final String savedApprovalTimeout = gc.getHitlApprovalTimeout();
        final String savedPauseReason = gc.getHitlPauseReason();

        gc.getTranscript().add(entry);
        if (decision.type() == GroupConversation.DecisionType.VOTE) {
            PhaseExecutionEngine.setDecisionCarryingDissents(gc, decision);
        } else {
            gc.setDecision(mergeNone(savedDecision, decision));
        }
        // The vote phase is finished: nothing to bookmark inside it.
        gc.setResumePoint(null);
        gc.setPendingHumanInput(null);
        gc.setPausedAt(null);
        gc.setPausedAtPhaseIndex(-1);
        gc.setPausedPhaseName(null);
        gc.setHitlPauseType(null);
        gc.setHitlPauseReason(null);
        gc.setHitlTimeoutPolicy(null);
        gc.setHitlApprovalTimeout(null);
        gc.setState(GroupConversationState.IN_PROGRESS);
        gc.setLastModified(Instant.now());
        // Double-submit / submit-vs-timeout / concurrent cancel → 409.
        conversationStore.updateIfStateAndPause(gc, GroupConversationState.AWAITING_HUMAN_INPUT, boundPauseId);

        discussionControls.put(groupConversationId, new DiscussionControlToken());
        hitlCoordinator.deleteGroupHitlTimeoutSchedule(groupConversationId);

        final int phaseIdx = pending.phaseIdx();
        final DiscussionPhase phase = context.phase();
        final AgentGroupConfiguration config = context.config();
        final String question = gc.getResumeQuestion() != null ? gc.getResumeQuestion() : gc.getOriginalQuestion();
        Runnable resumeWork = () -> {
            try {
                if (moderatorContext != null) {
                    decideByModerator(gc, config, phase, phaseIdx, pending, listener);
                } else {
                    phaseExecutionEngine.fireDecisionReached(gc, listener);
                }
                if (phase.requiresApproval()) {
                    // The decision comes first, then the phase's own approval gate —
                    // approving resumes after the vote, as for any approved phase.
                    hitlCoordinator.commitPause(gc, phaseIdx, phase, "PHASE", gc.getPausedTurnCount(), listener, config);
                    hitlCoordinator.removeTokenAndConvertIfSignalled(gc, listener);
                    return;
                }
                groupConversationService.executeDiscussion(gc, config, context.phases(), question, listener, phaseIdx + 1);
            } catch (Exception e) {
                LOGGER.errorf(e, "Resumed group discussion %s failed after its tie-break", groupConversationId);
            }
        };
        try {
            executorService.submit(callerIdentityContext.withIdentity(
                    GroupHitlCoordinator.resumeCallerFor(callerIdentityContext.captureOrCurrent(), gc), resumeWork));
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Could not schedule resumed discussion %s after its tie-break — restoring the pause", groupConversationId);
            synchronized (gc.getTranscript()) {
                gc.getTranscript().remove(entry);
            }
            gc.setDecision(savedDecision);
            gc.setResumePoint(savedBookmark);
            gc.setPendingHumanInput(pending);
            gc.setState(GroupConversationState.AWAITING_HUMAN_INPUT);
            gc.setPausedAt(savedPausedAt);
            gc.setPausedAtPhaseIndex(savedPausedPhaseIdx);
            gc.setPausedPhaseName(savedPausedPhaseName);
            gc.setHitlPauseType(HitlPauseType.HUMAN_TURN);
            gc.setHitlPauseReason(savedPauseReason);
            gc.setHitlApprovalTimeout(savedApprovalTimeout);
            gc.setLastModified(Instant.now());
            try {
                conversationStore.updateIfState(gc, GroupConversationState.IN_PROGRESS);
                hitlCoordinator.scheduleHumanTurnTimeout(gc);
            } catch (Exception restoreEx) {
                LOGGER.errorf(restoreEx, "Failed to restore the tie-break pause for %s", groupConversationId);
            }
            hitlCoordinator.removeTokenAndConvertIfSignalled(gc, listener);
            throw new GroupDiscussionException("Could not schedule the resumed discussion — try again", e);
        }

        counterGroupHitlResume.increment();
        countTieBreak(resolution);
        audit(gc, pending, resolvedBy, chosen, reasonCode);
        if (listener != null) {
            listener.onHitlResume(new GroupConversationEventSink.HitlResumeEvent("TIE_BROKEN", null, resolvedBy));
        }
        try {
            return conversationStore.read(groupConversationId);
        } catch (Exception e) {
            LOGGER.debugf("Post-resume re-read of %s failed (%s) — returning the pre-resume snapshot state", groupConversationId,
                    e.getMessage());
            return gc;
        }
    }

    /**
     * The MODERATOR_DECIDES timeout fallback, gated on turns and cost exactly as
     * the policy is when it runs directly. Charged as one turn.
     */
    private void decideByModerator(GroupConversation gc, AgentGroupConfiguration config, DiscussionPhase phase, int phaseIdx,
                                   GroupConversation.PendingHumanInput pending, GroupDiscussionEventListener listener) {
        ProtocolConfig protocol = config.getProtocol() != null
                ? config.getProtocol()
                : new ProtocolConfig(ProtocolConfig.DEFAULT_AGENT_TIMEOUT_SECONDS, ProtocolConfig.MemberFailurePolicy.SKIP,
                        ProtocolConfig.DEFAULT_MAX_RETRIES, ProtocolConfig.MemberUnavailablePolicy.SKIP);
        int maxTurns = protocol.maxTurns() > 0 ? protocol.maxTurns() : 50;
        var turnCounter = new AtomicInteger(Math.max(gc.getPausedTurnCount(), 0));
        var rebuilt = HumanTieBreakEngine.rebuildOutcome(gc, phase, phaseIdx, transcriptFrom(pending));
        DecisionRecord moderatorDecision = phaseExecutionEngine.resolveTieByModerator(gc, config, phase, protocol, phaseIdx, rebuilt,
                listener, turnCounter, maxTurns);
        if (moderatorDecision != null && moderatorDecision.type() == GroupConversation.DecisionType.VOTE) {
            PhaseExecutionEngine.setDecisionCarryingDissents(gc, moderatorDecision);
        }
        gc.setPausedTurnCount(turnCounter.get());
        phaseExecutionEngine.fireDecisionReached(gc, listener);
    }

    /** A NONE record replacing an earlier one keeps the dissents it carried. */
    private static DecisionRecord mergeNone(DecisionRecord existing, DecisionRecord none) {
        if (existing == null || existing.dissents() == null || existing.dissents().isEmpty()) {
            return none;
        }
        return new DecisionRecord(none.type(), none.outcome(), none.winner(), none.tally(), existing.dissents(), none.method(),
                none.decidedAtPhase(), none.raw());
    }

    private static int transcriptFrom(GroupConversation.PendingHumanInput pending) {
        Integer from = pending.detail() != null ? pending.detail().transcriptFrom() : null;
        return from != null ? from : 0;
    }

    private void countTieBreak(String resolution) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_TIEBREAKS, "policy", "HUMAN_DECIDES", "resolution", resolution).increment();
        }
    }

    /**
     * One {@code hitl.approval} entry. A tie-break is a human turn, not an
     * approval: {@code decisionOutcome} is deliberately absent, and the detail says
     * what was chosen and why the pause ended.
     */
    private void audit(GroupConversation gc, GroupConversation.PendingHumanInput pending, String resolvedBy, String chosen,
                       String reasonCode) {
        if (auditLedgerService == null || !auditLedgerService.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("verdict", "TIE_BROKEN");
            detail.put("approvalCategory", APPROVAL_CATEGORY);
            detail.put("reasonCode", reasonCode);
            detail.put("memberId", pending.memberId());
            detail.put("decidedBy", resolvedBy != null ? resolvedBy : "unknown");
            detail.put("automated", resolvedBy != null && resolvedBy.startsWith("system:"));
            detail.put("options", pending.detail() != null ? pending.detail().options() : List.of());
            if (chosen != null) {
                detail.put("chosen", chosen);
            }
            detail.put("surface", "group");
            auditLedgerService.submit(new AuditEntry(UUID.randomUUID().toString(), gc.getId(), gc.getGroupId(), null, gc.getUserId(), null,
                    -1, "hitl.approval", "hitl", -1, 0L, Map.of(), detail, null, null, List.of(), 0.0, Instant.now(), null, null));
        } catch (Exception e) {
            LOGGER.warnf("Failed to submit tie-break audit entry for group conversation %s: %s", gc.getId(), e.getMessage());
        }
    }
}
