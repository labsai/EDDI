/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.oversight.autonomy.SuspendedGate;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Gate-side helpers of the mandatory, argument-aware approval hook
 * ({@link MandatoryApprovalRule}).
 * <p>
 * Pure: no CDI, no store. The tool loop calls {@link #evaluate} through
 * {@link ToolApprovalGate}, then {@link #unpausableReason} for each call that
 * came back mandatory, and refuses the call when the conversation cannot pause
 * for the approval it needs — a mandatory approval never degrades to "allowed".
 */
public final class MandatoryApprovals {

    private static final Logger LOGGER = Logger.getLogger(MandatoryApprovals.class);

    /** Prefix of the {@code gateReason} recorded on a mandatorily gated call. */
    public static final String GATE_REASON_PREFIX = "mandatory:";

    /**
     * Refusal: the turn is a group member's, whose pauses are never shown to a
     * human.
     */
    public static final String UNPAUSABLE_GROUP_TURN = "this call needs a human approval, which a group member turn cannot wait for";
    /** Refusal: nobody is the conversation's user, so nobody can consent. */
    public static final String UNPAUSABLE_NO_USER = "this call needs the confirmation of the conversation's user, and this conversation has none";
    /** Refusal: an oversight approval, but the agent's config names no approver. */
    public static final String UNPAUSABLE_NO_GATE = "this call needs a human approval, and this agent has no tool-approval gate to ask one";

    private MandatoryApprovals() {
    }

    /**
     * The approval {@code request} needs under {@code rules}, or {@code null}.
     * Every rule is asked; the strictest approver wins. A rule that throws gates
     * the call with {@link ApproverRule#CONVERSATION_USER} — fail closed.
     */
    public static MandatoryApproval evaluate(List<MandatoryApprovalRule> rules, ToolExecutionRequest request) {
        if (rules == null || rules.isEmpty() || request == null) {
            return null;
        }
        MandatoryApproval result = null;
        for (MandatoryApprovalRule rule : rules) {
            if (rule == null) {
                continue;
            }
            MandatoryApproval candidate;
            try {
                Optional<MandatoryApproval> answer = rule.evaluate(request);
                candidate = answer != null ? answer.orElse(null) : null;
            } catch (RuntimeException e) {
                LOGGER.warnf("A mandatory-approval rule failed on tool '%s' (%s); the call is gated for the conversation user's "
                        + "confirmation", sanitize(request.name()), e.getClass().getSimpleName());
                candidate = MandatoryApproval.ruleFailed();
            }
            result = MandatoryApproval.stricter(result, candidate);
        }
        return result;
    }

    /**
     * Whether the effective tool-approval config gates anything at all: a non-empty
     * {@code requireApproval}, an earned-autonomy suspension, or the fail-closed
     * {@link ToolApprovalsConfig#UNDETERMINED} sentinel. Later packages offer a
     * file-carrying argument only when this holds (I9).
     */
    public static boolean hasActiveGate(ToolApprovalsConfig effective) {
        if (effective == null) {
            return false;
        }
        if (ToolApprovalsConfig.isUndetermined(effective)) {
            return true;
        }
        boolean patterns = effective.getRequireApproval() != null && !effective.getRequireApproval().isEmpty();
        return patterns || !SuspendedGate.none(effective);
    }

    /**
     * Why this conversation cannot pause for {@code approval}, or {@code null} when
     * it can. Each arm is a context where nobody could ever give the decision, so
     * pausing would only wedge the turn (or end in an automatic rejection):
     * <ul>
     * <li>a group member's turn ({@code groupConversationId} set) — member pauses
     * are rejected by the group, never shown to a human;</li>
     * <li>{@link ApproverRule#CONVERSATION_USER} without a conversation user — a
     * run that no user started (no user id) has nobody to consent;</li>
     * <li>{@link ApproverRule#ANY_PERMITTED_APPROVER} without an active gate
     * ({@link #hasActiveGate}) — the config names no approver.</li>
     * </ul>
     */
    public static String unpausableReason(MandatoryApproval approval, ToolApprovalsConfig effective, String conversationUserId,
                                          String groupConversationId) {
        if (approval == null) {
            return null;
        }
        if (groupConversationId != null && !groupConversationId.isBlank()) {
            return UNPAUSABLE_GROUP_TURN;
        }
        if (approval.approver() == ApproverRule.CONVERSATION_USER && (conversationUserId == null || conversationUserId.isBlank())) {
            return UNPAUSABLE_NO_USER;
        }
        if (approval.approver() == ApproverRule.ANY_PERMITTED_APPROVER && !hasActiveGate(effective)) {
            return UNPAUSABLE_NO_GATE;
        }
        return null;
    }

    /** The model-facing result of a call refused because it cannot pause. */
    public static String refusalResult(String reason) {
        return "{\"status\":\"NOT_EXECUTED\",\"reason\":\"" + reason + " — do not retry\"}";
    }
}
