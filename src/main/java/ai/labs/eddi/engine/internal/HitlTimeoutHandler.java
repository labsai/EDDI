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
        try {
            if ("ABORT".equals(policyStr)) {
                boolean cancelled = groupConversationService.cancelDiscussion(gcId,
                        ControlSignal.CANCEL_GRACEFUL);
                LOGGER.infof("Human-turn timeout ABORT for group conversation %s%s", gcId,
                        cancelled ? "" : " skipped — already terminal");
                return;
            }
            if (!"SKIP_TURN".equals(policyStr)) {
                LOGGER.errorf("Unknown human-turn timeout policy '%s' for %s — treating as SKIP_TURN", policyStr, gcId);
            }
            groupConversationService.skipHumanTurnOnTimeout(gcId);
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to resolve timed-out human turn for group conversation %s", gcId);
        }
    }

    private void resumeRegular(Map<String, Object> metadata, HitlDecision decision) {
        String conversationId = (String) metadata.get(HitlSchedules.METADATA_CONVERSATION_ID_KEY);
        try {
            conversationService.resumeConversation(conversationId, decision, null);
            LOGGER.infof("HITL timeout auto-%s for conversation %s", decision.getVerdict(), conversationId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            LOGGER.infof("HITL timeout for conversation %s skipped — the conversation no longer exists", conversationId);
        } catch (IConversationService.PauseMismatchException e) {
            LOGGER.infof("HITL timeout for conversation %s skipped — it was armed for an earlier pause; "
                    + "the current pause has its own timeout", conversationId);
        } catch (IllegalArgumentException e) {
            LOGGER.errorf(e, "HITL timeout decision for conversation %s was refused as invalid", conversationId);
        } catch (Exception e) {
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
            if (!groupPauseStillWaiting(gcId)) {
                LOGGER.infof("HITL timeout for group conversation %s skipped — it is no longer awaiting approval (%s)", gcId,
                        e.getMessage());
                return;
            }
            LOGGER.warnf("HITL timeout auto-%s for group conversation %s could not be applied yet (%s) — "
                    + "the pause is kept and the timeout stays armed", decision.getVerdict(), gcId, e.getMessage());
            throw new RetryLaterException("HITL timeout decision could not be applied yet: " + e.getMessage(), e);
        }
    }

    private boolean groupPauseStillWaiting(String groupConversationId) {
        try {
            GroupConversation gc = groupConversationService.readGroupConversation(groupConversationId);
            return gc != null && gc.getState() == GroupConversation.GroupConversationState.AWAITING_APPROVAL;
        } catch (IResourceStore.ResourceNotFoundException notFound) {
            return false;
        } catch (Exception stateFailure) {
            return true;
        }
    }

    private void cancelRegular(Map<String, Object> metadata) {
        String conversationId = (String) metadata.get(HitlSchedules.METADATA_CONVERSATION_ID_KEY);
        try {
            conversationService.cancelConversation(conversationId,
                    ControlSignal.CANCEL_GRACEFUL, "system:timeout");
            LOGGER.infof("HITL timeout ABORT for conversation %s", conversationId);
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to abort conversation %s on HITL timeout", conversationId);
        }
    }

    private void cancelGroup(Map<String, Object> metadata) {
        String gcId = (String) metadata.get(HitlSchedules.METADATA_CONVERSATION_ID_KEY);
        try {
            boolean cancelled = groupConversationService.cancelDiscussion(gcId,
                    ControlSignal.CANCEL_GRACEFUL);
            if (cancelled) {
                LOGGER.infof("HITL timeout ABORT for group conversation %s", gcId);
            } else {
                LOGGER.infof("HITL timeout ABORT for group conversation %s skipped — already terminal", gcId);
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to abort group conversation %s on HITL timeout", gcId);
        }
    }
}
