/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.hitl.HitlSchedules;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Handles HITL approval timeout expiry. Called by ScheduleFireExecutor when a
 * schedule with hitlType="hitl_timeout" fires.
 * <p>
 * Two outcomes matter to the scheduler, and they used to be one. A decision
 * that was applied, or that has nothing left to apply to (the conversation is
 * gone, it is no longer paused, or it paused again on a different request), is
 * done. A decision that could not be applied <em>yet</em> — a store blip while
 * loading the conversation, this node draining for shutdown, the agent not
 * resolvable, another decision on the same pause still in flight — leaves the
 * pause in place and throws {@link RetryLaterException}, so the fire is
 * recorded FAILED and the timeout is re-armed instead of being logged COMPLETED
 * and disabled for good.
 */
@ApplicationScoped
public class HitlTimeoutHandler {

    private static final Logger LOGGER = Logger.getLogger(HitlTimeoutHandler.class);

    @Inject
    IConversationService conversationService;

    @Inject
    IGroupConversationService groupConversationService;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * The timeout's decision could not be applied yet, and the pause it belongs to
     * is still waiting. The schedule must stay armed.
     */
    public static final class RetryLaterException extends RuntimeException {
        public RetryLaterException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public void handleTimeout(Map<String, Object> metadata) {
        String surface = (String) metadata.get(HitlSchedules.METADATA_SURFACE_KEY);
        Counter.builder("eddi_hitl_timeout_count")
                .tag("surface", surface != null ? surface : "unknown")
                .register(meterRegistry)
                .increment();
        String policyStr = (String) metadata.get(HitlSchedules.METADATA_POLICY_KEY);
        if (policyStr == null) {
            LOGGER.error("HITL timeout metadata missing 'policy' key");
            return;
        }
        // I6: human-turn timeouts carry OnHumanTimeout policies (SKIP_TURN/ABORT),
        // not HitlTimeoutPolicy values — branch on the surface BEFORE the parse.
        if (HitlSchedules.SURFACE_GROUP_HUMAN.equals(surface)) {
            handleHumanTurnTimeout(metadata, policyStr);
            return;
        }
        HitlTimeoutPolicy policy;
        try {
            policy = HitlTimeoutPolicy.valueOf(policyStr);
        } catch (IllegalArgumentException e) {
            LOGGER.errorf("Unknown HITL timeout policy: %s", policyStr);
            return;
        }

        switch (policy) {
            case AUTO_REJECT, AUTO_APPROVE -> {
                var verdict = policy == HitlTimeoutPolicy.AUTO_APPROVE
                        ? HitlDecision.HitlVerdict.APPROVED
                        : HitlDecision.HitlVerdict.REJECTED;
                var decision = new HitlDecision();
                decision.setVerdict(verdict);
                decision.setDecidedBy("system:timeout");
                decision.setNote("Automatic " + verdict.name().toLowerCase() + " due to timeout (policy: " + policyStr + ")");
                // Bind the decision to the pause the timeout was armed for: a late fire
                // must not decide a newer pause (it is refused as a pause mismatch).
                Object pauseId = metadata.get(HitlSchedules.METADATA_PAUSE_ID_KEY);
                if (pauseId != null) {
                    decision.setPauseId(pauseId.toString());
                }

                if (HitlSchedules.SURFACE_GROUP.equals(surface)) {
                    resumeGroup(metadata, decision);
                } else {
                    resumeRegular(metadata, decision);
                }
            }
            case ABORT -> {
                if (HitlSchedules.SURFACE_GROUP.equals(surface)) {
                    cancelGroup(metadata);
                } else {
                    cancelRegular(metadata);
                }
            }
            case WAIT_INDEFINITELY -> {
                /* never scheduled */ }
        }
    }

    /**
     * I6: an expired human turn resolves per the group's {@code humanMemberConfig}
     * — SKIP_TURN records a SKIPPED entry and moves on; ABORT cancels the
     * discussion (the same graceful cancel the approval ABORT policy uses).
     */
    private void handleHumanTurnTimeout(Map<String, Object> metadata, String policyStr) {
        String gcId = (String) metadata.get(HitlSchedules.METADATA_CONVERSATION_ID_KEY);
        // Bound to the pause the timeout was armed for: a late fire must neither
        // skip a newer human turn nor cancel a discussion that has moved on.
        String pauseId = pauseIdOf(metadata);
        try {
            if ("ABORT".equals(policyStr)) {
                boolean cancelled = groupConversationService.cancelPausedDiscussion(gcId,
                        ControlSignal.CANCEL_GRACEFUL, pauseId);
                if (!cancelled && groupStillOnPause(gcId, GroupConversation.GroupConversationState.AWAITING_HUMAN_INPUT, pauseId)) {
                    throw new IllegalStateException("the paused-only cancel did not apply, but the human turn is still waiting");
                }
                LOGGER.infof("Human-turn timeout ABORT for group conversation %s%s", gcId,
                        cancelled ? "" : " skipped — no longer awaiting the turn it was armed for");
                return;
            }
            if (!"SKIP_TURN".equals(policyStr)) {
                LOGGER.errorf("Unknown human-turn timeout policy '%s' for %s — treating as SKIP_TURN", policyStr, gcId);
            }
            groupConversationService.skipHumanTurnOnTimeout(gcId, pauseId);
        } catch (Exception e) {
            if (!groupPauseStillWaiting(gcId, GroupConversation.GroupConversationState.AWAITING_HUMAN_INPUT)) {
                LOGGER.infof("Human-turn timeout for group conversation %s skipped — it is no longer awaiting input (%s)", gcId,
                        e.getMessage());
                return;
            }
            LOGGER.warnf("Human-turn timeout (%s) for group conversation %s could not be applied yet (%s) — "
                    + "the pause is kept and the timeout stays armed", policyStr, gcId, e.getMessage());
            throw new RetryLaterException("Human-turn timeout could not be applied yet: " + e.getMessage(), e);
        }
    }

    private void resumeRegular(Map<String, Object> metadata, HitlDecision decision) {
        String conversationId = (String) metadata.get(HitlSchedules.METADATA_CONVERSATION_ID_KEY);
        try {
            conversationService.resumeConversation(conversationId, decision, null);
            LOGGER.infof("HITL timeout auto-%s for conversation %s", decision.getVerdict(), conversationId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            LOGGER.infof("HITL timeout for conversation %s skipped — the conversation no longer exists", conversationId);
        } catch (IConversationService.PlanMismatchException e) {
            // Caught before its parent: the pause is the current one, but the plan
            // stored with it no longer hashes to the approved one, so no decision —
            // the timeout's or a reviewer's — can ever apply. Waiting on it is forever.
            endUndecidableRegularPause(conversationId, decision, REASON_PLAN_CHANGED, e);
        } catch (IConversationService.PauseMismatchException e) {
            LOGGER.infof("HITL timeout for conversation %s skipped — it was armed for an earlier pause; "
                    + "the current pause has its own timeout", conversationId);
        } catch (Exception e) {
            // Anything else — an IllegalArgumentException included — is not a verdict
            // on this pause: it may be a broken deployment, and cancelling every pause
            // whose timeout fires would turn that into data loss. Retried while the
            // pause waits.
            if (!regularPauseStillWaiting(conversationId)) {
                LOGGER.infof("HITL timeout for conversation %s skipped — it is no longer awaiting a decision (%s)",
                        conversationId, e.getMessage());
                return;
            }
            LOGGER.warnf("HITL timeout auto-%s for conversation %s could not be applied yet (%s) — "
                    + "the pause is kept and the timeout stays armed", decision.getVerdict(), conversationId, e.getMessage());
            throw new RetryLaterException("HITL timeout decision could not be applied yet: " + e.getMessage(), e);
        }
    }

    /**
     * Counter: a timeout whose decision can never apply ended its pause instead.
     */
    static final String METRIC_UNDECIDABLE = "eddi_hitl_timeout_undecidable";
    static final String REASON_PLAN_CHANGED = "plan_changed";
    /** The timeout row names no pause, so it may not end one. */
    static final String REASON_UNBOUND = "unbound_plan_changed";

    /**
     * The regular resume refused the timeout's decision because the plan stored
     * with the pause no longer hashes to the committed one — no decision, the
     * timeout's or a reviewer's, can ever apply to it. The pause the timeout was
     * armed for is cancelled (the paused-only cancel, bound to that pause and
     * audited as {@code system:timeout}; the conversation ends
     * {@code EXECUTION_INTERRUPTED}) so it ends with an explicit outcome instead of
     * waiting forever.
     * <p>
     * Only this verdict leads here. The group surface has no equivalent:
     * {@code HitlPlanBinding.check} accepts a {@code system:*} decision without a
     * plan hash, so a group timeout never meets a plan mismatch.
     * <p>
     * A cancel that does not apply while the pause still waits is retried, by the
     * same rule as {@link #cancelRegular}: a decision on the pause still in flight
     * (IN_PROGRESS) may fail and restore it, and only this fire would then end it.
     * <p>
     * A row armed by an older build names no pause and is not allowed to end one:
     * its fire is counted ({@code reason=unbound_plan_changed}) and completes. The
     * pause keeps waiting; the next recovery pass finds the spent row and arms a
     * timeout bound to the pause {@code SPENT_REARM_BACKOFF} after this fire, and
     * that one ends it — on a cluster about an hour later, on a single node at its
     * next start, unless a person cancels the conversation first.
     */
    private void endUndecidableRegularPause(String conversationId, HitlDecision decision, String reason, Exception cause) {
        if (decision.getPauseId() == null) {
            // A row armed by an older build names no pause: it decides whatever pause is
            // current, so it has no standing to END one — the cancel would skip the
            // pause-identity check and could end a newer pause nobody timed out on.
            LOGGER.errorf("HITL timeout auto-%s for conversation %s can never apply (%s: %s) but it names no pause — "
                    + "not cancelling the current pause: recovery arms a timeout bound to it about an hour after this fire "
                    + "(on a single node: at its next start); cancel the conversation to end it now",
                    decision.getVerdict(), conversationId, reason, cause.getMessage());
            countUndecidable(HitlSchedules.SURFACE_REGULAR, REASON_UNBOUND);
            return;
        }
        LOGGER.errorf("HITL timeout auto-%s for conversation %s can never apply (%s: %s) — cancelling the pause it was armed for",
                decision.getVerdict(), conversationId, reason, cause.getMessage());
        IConversationService.CancelOutcome outcome;
        try {
            outcome = conversationService.cancelPausedConversation(conversationId, "system:timeout", decision.getPauseId());
        } catch (Exception e) {
            if (!regularPauseStillWaiting(conversationId)) {
                return;
            }
            throw new RetryLaterException("Undecidable HITL pause could not be cancelled yet: " + e.getMessage(), e);
        }
        if (outcome == IConversationService.CancelOutcome.CANCELLED) {
            countUndecidable(HitlSchedules.SURFACE_REGULAR, reason);
            LOGGER.infof("HITL timeout for conversation %s: the undecidable pause was cancelled", conversationId);
        } else if (outcome == IConversationService.CancelOutcome.NOTHING_TO_CANCEL
                && regularStillOnPause(conversationId, decision.getPauseId())) {
            // IN_PROGRESS: a decision is in flight and may fail and restore the pause.
            // AWAITING_HUMAN on this very pause: that restore already happened. Read
            // fresh from the store and bound to the pause id, so a newer pause (or a
            // stale cached state) completes the fire instead of retrying it.
            throw new RetryLaterException("Undecidable HITL pause could not be cancelled yet: the pause is still waiting "
                    + "or a decision on it is still in flight", null);
        } else {
            LOGGER.infof("HITL timeout for conversation %s: the undecidable pause is no longer waiting (%s)", conversationId,
                    outcome);
        }
    }

    private void countUndecidable(String surface, String reason) {
        Counter.builder(METRIC_UNDECIDABLE).tag("surface", surface).tag("reason", reason).register(meterRegistry).increment();
    }

    /**
     * Whether the discussion still waits in {@code waitingState} on the pause
     * {@code pauseId} (any pause when the timeout carries none) — a paused-only
     * cancel that returned false there lost a race and must be retried, not
     * completed.
     */
    private boolean groupStillOnPause(String gcId, GroupConversation.GroupConversationState waitingState, String pauseId) {
        try {
            GroupConversation gc = groupConversationService.readGroupConversation(gcId);
            return gc != null && gc.getState() == waitingState
                    && (pauseId == null || pauseId.equals(HitlDecision.pauseIdOf(gc.getPausedAt())));
        } catch (IResourceStore.ResourceNotFoundException notFound) {
            return false;
        } catch (Exception stateFailure) {
            return true;
        }
    }

    /**
     * Whether the regular conversation still has a pause this timeout may decide.
     * AWAITING_HUMAN is the failed-before-execution case (the resume restored the
     * pause). IN_PROGRESS is a concurrent decision still running: if it fails it
     * restores the pause and counts on this timeout, and if it succeeds it deletes
     * this schedule, so a retry is harmless either way. A state that cannot be read
     * is treated as still waiting — dropping a finite policy is the failure this
     * exists to prevent.
     */
    private boolean regularPauseStillWaiting(String conversationId) {
        try {
            ConversationState state = conversationService.getConversationState(conversationId);
            return state == ConversationState.AWAITING_HUMAN || state == ConversationState.IN_PROGRESS;
        } catch (Exception stateFailure) {
            return true;
        }
    }

    private void resumeGroup(Map<String, Object> metadata, HitlDecision decision) {
        String gcId = (String) metadata.get(HitlSchedules.METADATA_CONVERSATION_ID_KEY);
        try {
            var request = new GroupApprovalRequest();
            request.setDecision(decision);
            groupConversationService.resumeDiscussion(gcId, request, null);
            LOGGER.infof("HITL timeout auto-%s for group conversation %s", decision.getVerdict(), gcId);
        } catch (IGroupConversationService.GroupPauseMismatchException e) {
            LOGGER.infof("HITL timeout for group conversation %s skipped — it was armed for an earlier pause", gcId);
        } catch (Exception e) {
            if (!groupPauseStillWaiting(gcId, GroupConversation.GroupConversationState.AWAITING_APPROVAL)) {
                LOGGER.infof("HITL timeout for group conversation %s skipped — it is no longer awaiting approval (%s)", gcId,
                        e.getMessage());
                return;
            }
            LOGGER.warnf("HITL timeout auto-%s for group conversation %s could not be applied yet (%s) — "
                    + "the pause is kept and the timeout stays armed", decision.getVerdict(), gcId, e.getMessage());
            throw new RetryLaterException("HITL timeout decision could not be applied yet: " + e.getMessage(), e);
        }
    }

    private boolean groupPauseStillWaiting(String groupConversationId, GroupConversation.GroupConversationState waitingState) {
        try {
            GroupConversation gc = groupConversationService.readGroupConversation(groupConversationId);
            return gc != null && gc.getState() == waitingState;
        } catch (IResourceStore.ResourceNotFoundException notFound) {
            return false;
        } catch (Exception stateFailure) {
            return true;
        }
    }

    /**
     * ABORT cancels the PAUSE the timeout was armed for, and nothing else. It used
     * to be the full cancel: a fire claimed just before an approver resumed then
     * signalled the resumed turn to stop (the cancel signals in-flight work and
     * accepts IN_PROGRESS), or — after a resume and a new pause — aborted a pause
     * whose own timeout had not expired. Paused-only and bound to the pause id, a
     * late fire finds nothing of its own to cancel and completes.
     */
    private void cancelRegular(Map<String, Object> metadata) {
        String conversationId = (String) metadata.get(HitlSchedules.METADATA_CONVERSATION_ID_KEY);
        String pauseId = pauseIdOf(metadata);
        IConversationService.CancelOutcome outcome;
        try {
            outcome = conversationService.cancelPausedConversation(conversationId, "system:timeout", pauseId);
        } catch (Exception e) {
            if (!regularPauseStillWaiting(conversationId)) {
                LOGGER.infof("HITL timeout ABORT for conversation %s skipped — it is no longer awaiting a decision (%s)",
                        conversationId, e.getMessage());
                return;
            }
            LOGGER.warnf("HITL timeout ABORT for conversation %s could not be applied yet (%s) — "
                    + "the pause is kept and the timeout stays armed", conversationId, e.getMessage());
            throw new RetryLaterException("HITL timeout abort could not be applied yet: " + e.getMessage(), e);
        }
        if (outcome == IConversationService.CancelOutcome.CANCELLED) {
            LOGGER.infof("HITL timeout ABORT for conversation %s", conversationId);
        } else if (outcome == IConversationService.CancelOutcome.NOTHING_TO_CANCEL && regularStillOnPause(conversationId, pauseId)) {
            // A decision on this pause is in flight (a resume's AWAITING_HUMAN ->
            // IN_PROGRESS CAS), or it already failed and restored the pause between the
            // cancel's compare-and-set and this read (AWAITING_HUMAN on this very pause).
            // A failed decision counts on this timeout, whose row it has not deleted; a
            // successful one deletes the row and the retry never fires. Completing here
            // would consume the one-shot fire and leave a restored pause with no timeout.
            LOGGER.warnf("HITL timeout ABORT for conversation %s could not be applied yet (a decision on the pause is in flight) — "
                    + "the pause is kept and the timeout stays armed", conversationId);
            throw new RetryLaterException("HITL timeout abort could not be applied yet: a decision on the pause is in flight", null);
        } else {
            LOGGER.infof("HITL timeout ABORT for conversation %s skipped — the pause it was armed for is no longer waiting (%s)",
                    conversationId, outcome);
        }
    }

    /**
     * Whether the regular conversation is still on the pause {@code pauseId} (any
     * pause when the timeout carries none) or a decision on it is in flight — read
     * from the store, not the state cache, since it decides whether a one-shot fire
     * is consumed. A paused-only cancel that found nothing to cancel there lost a
     * race and is retried; a conversation paused again on another request is not
     * this fire's, so it completes. A read that fails counts as still waiting.
     */
    private boolean regularStillOnPause(String conversationId, String pauseId) {
        ConversationMemorySnapshot snapshot;
        try {
            var tail = conversationService.getConversationMemoryTail(conversationId);
            snapshot = tail != null ? tail.snapshot() : null;
        } catch (IResourceStore.ResourceNotFoundException notFound) {
            return false;
        } catch (Exception readFailure) {
            return true;
        }
        if (snapshot == null) {
            return false;
        }
        ConversationState state = snapshot.getConversationState();
        if (state == ConversationState.IN_PROGRESS) {
            return true;
        }
        return state == ConversationState.AWAITING_HUMAN
                && (pauseId == null || pauseId.equals(HitlDecision.pauseIdOf(snapshot.getHitlPausedAt())));
    }

    private void cancelGroup(Map<String, Object> metadata) {
        String gcId = (String) metadata.get(HitlSchedules.METADATA_CONVERSATION_ID_KEY);
        try {
            boolean cancelled = groupConversationService.cancelPausedDiscussion(gcId,
                    ControlSignal.CANCEL_GRACEFUL, pauseIdOf(metadata));
            if (cancelled) {
                LOGGER.infof("HITL timeout ABORT for group conversation %s", gcId);
            } else if (groupStillOnPause(gcId, GroupConversation.GroupConversationState.AWAITING_APPROVAL, pauseIdOf(metadata))) {
                // Still waiting on this very pause: the cancel lost a race (a running
                // leg's token, a concurrent write). Completing would drop the ABORT.
                throw new IllegalStateException("the paused-only cancel did not apply, but the pause is still waiting");
            } else {
                LOGGER.infof("HITL timeout ABORT for group conversation %s skipped — no longer awaiting the approval "
                        + "it was armed for", gcId);
            }
        } catch (Exception e) {
            if (!groupPauseStillWaiting(gcId, GroupConversation.GroupConversationState.AWAITING_APPROVAL)) {
                LOGGER.infof("HITL timeout ABORT for group conversation %s skipped — it is no longer awaiting approval (%s)", gcId,
                        e.getMessage());
                return;
            }
            LOGGER.warnf("HITL timeout ABORT for group conversation %s could not be applied yet (%s) — "
                    + "the pause is kept and the timeout stays armed", gcId, e.getMessage());
            throw new RetryLaterException("HITL timeout abort could not be applied yet: " + e.getMessage(), e);
        }
    }

    private static String pauseIdOf(Map<String, Object> metadata) {
        Object pauseId = metadata.get(HitlSchedules.METADATA_PAUSE_ID_KEY);
        return pauseId != null ? pauseId.toString() : null;
    }
}
