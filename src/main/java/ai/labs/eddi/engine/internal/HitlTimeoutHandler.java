/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.engine.hitl.HitlSchedules;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Handles HITL approval timeout expiry. Called by ScheduleFireExecutor when a
 * schedule with hitlType="hitl_timeout" fires.
 */
@ApplicationScoped
public class HitlTimeoutHandler {

    private static final Logger LOGGER = Logger.getLogger(HitlTimeoutHandler.class);

    @Inject
    ai.labs.eddi.engine.api.IConversationService conversationService;

    @Inject
    ai.labs.eddi.engine.api.IGroupConversationService groupConversationService;

    @Inject
    MeterRegistry meterRegistry;

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
                        ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_GRACEFUL);
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
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to auto-resume conversation %s on HITL timeout", conversationId);
        }
    }

    private void resumeGroup(Map<String, Object> metadata, HitlDecision decision) {
        String gcId = (String) metadata.get(HitlSchedules.METADATA_CONVERSATION_ID_KEY);
        try {
            var request = new GroupApprovalRequest();
            request.setDecision(decision);
            groupConversationService.resumeDiscussion(gcId, request, null);
            LOGGER.infof("HITL timeout auto-%s for group conversation %s", decision.getVerdict(), gcId);
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to auto-resume group conversation %s on HITL timeout", gcId);
        }
    }

    private void cancelRegular(Map<String, Object> metadata) {
        String conversationId = (String) metadata.get(HitlSchedules.METADATA_CONVERSATION_ID_KEY);
        try {
            conversationService.cancelConversation(conversationId,
                    ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_GRACEFUL, "system:timeout");
            LOGGER.infof("HITL timeout ABORT for conversation %s", conversationId);
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to abort conversation %s on HITL timeout", conversationId);
        }
    }

    private void cancelGroup(Map<String, Object> metadata) {
        String gcId = (String) metadata.get(HitlSchedules.METADATA_CONVERSATION_ID_KEY);
        try {
            boolean cancelled = groupConversationService.cancelDiscussion(gcId,
                    ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_GRACEFUL);
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
