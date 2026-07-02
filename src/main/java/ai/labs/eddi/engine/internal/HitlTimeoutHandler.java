/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
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
        String surface = (String) metadata.get("surface");
        Counter.builder("eddi_hitl_timeout_count")
                .tag("surface", surface != null ? surface : "unknown")
                .register(meterRegistry)
                .increment();
        String policyStr = (String) metadata.get("policy");
        if (policyStr == null) {
            LOGGER.error("HITL timeout metadata missing 'policy' key");
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

                if ("group".equals(surface)) {
                    resumeGroup(metadata, decision);
                } else {
                    resumeRegular(metadata, decision);
                }
            }
            case ABORT -> {
                if ("group".equals(surface)) {
                    cancelGroup(metadata);
                } else {
                    cancelRegular(metadata);
                }
            }
            case WAIT_INDEFINITELY -> {
                /* never scheduled */ }
        }
    }

    private void resumeRegular(Map<String, Object> metadata, HitlDecision decision) {
        String conversationId = (String) metadata.get("conversationId");
        try {
            conversationService.resumeConversation(conversationId, decision, null);
            LOGGER.infof("HITL timeout auto-%s for conversation %s", decision.getVerdict(), conversationId);
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to auto-resume conversation %s on HITL timeout", conversationId);
        }
    }

    private void resumeGroup(Map<String, Object> metadata, HitlDecision decision) {
        String gcId = (String) metadata.get("conversationId");
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
        String conversationId = (String) metadata.get("conversationId");
        try {
            conversationService.cancelConversation(conversationId,
                    ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_GRACEFUL, "system:timeout");
            LOGGER.infof("HITL timeout ABORT for conversation %s", conversationId);
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to abort conversation %s on HITL timeout", conversationId);
        }
    }

    private void cancelGroup(Map<String, Object> metadata) {
        String gcId = (String) metadata.get("conversationId");
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
