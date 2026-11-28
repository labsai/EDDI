/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

/**
 * Why one specific tool call needs a human decision regardless of the agent's
 * approval patterns, and whose decision it needs.
 * <p>
 * Built by server code (a {@link MandatoryApprovalRule}), never from model
 * text: the summary is shown to the approver next to the redacted arguments, so
 * it must not be something the model could have written, and it must not carry
 * a storage reference or any other capability.
 *
 * @param reasonCode
 *            stable, machine-readable, e.g. {@code "file-egress"}; shown to the
 *            approver and recorded on the pending call
 * @param approver
 *            who may approve
 * @param summaryForApprover
 *            one server-built sentence for the approver ("Sends 1 file out of
 *            this deployment"); capped at {@link #SUMMARY_MAX_CHARS}
 */
public record MandatoryApproval(String reasonCode, ApproverRule approver, String summaryForApprover) {

    /** Cap on {@link #summaryForApprover}, ellipsis included. */
    public static final int SUMMARY_MAX_CHARS = 500;
    /** Cap on {@link #reasonCode}. */
    public static final int REASON_CODE_MAX_CHARS = 64;

    /**
     * Reason code of the approval a rule that failed to evaluate demands: the gate
     * cannot tell what the call carries, so it asks the strictest question.
     */
    public static final String REASON_RULE_FAILED = "approval-check-failed";

    public MandatoryApproval {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode is required");
        }
        reasonCode = reasonCode.strip();
        if (reasonCode.length() > REASON_CODE_MAX_CHARS) {
            reasonCode = reasonCode.substring(0, REASON_CODE_MAX_CHARS);
        }
        if (approver == null) {
            throw new IllegalArgumentException("approver is required");
        }
        if (summaryForApprover != null && summaryForApprover.length() > SUMMARY_MAX_CHARS) {
            summaryForApprover = summaryForApprover.substring(0, SUMMARY_MAX_CHARS - 1) + "…";
        }
    }

    /** What a rule that threw demands: the conversation user, fail closed. */
    static MandatoryApproval ruleFailed() {
        return new MandatoryApproval(REASON_RULE_FAILED, ApproverRule.CONVERSATION_USER,
                "An approval check on this call could not be evaluated, so it needs the conversation user's confirmation.");
    }

    /**
     * The stricter of two approvals of the same call: the stricter approver wins,
     * and on a tie the first one stays.
     */
    static MandatoryApproval stricter(MandatoryApproval current, MandatoryApproval candidate) {
        if (current == null) {
            return candidate;
        }
        if (candidate == null) {
            return current;
        }
        return candidate.approver().ordinal() > current.approver().ordinal() ? candidate : current;
    }
}
