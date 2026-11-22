/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.engine.hitl.tools.MandatoryApprovalAttribution.ActingAs;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall;
import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.ResolutionPrincipal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decision-side half of the mandatory approval hook: whether a decision may
 * approve the mandatorily gated calls of a pause ({@link ApproverRule}).
 * <p>
 * Pure — the resume path ({@code ConversationHitlService}) calls it on the
 * snapshot the resume runs, before anything executes, and on a refusal puts the
 * pause back untouched. Rejections are never refused: any approver may say no.
 * <ul>
 * <li>An automatic decision ({@code decidedBy} starting with {@code system:} —
 * a timeout policy, the no-progress guard) never approves a mandatory
 * call.</li>
 * <li>{@link ApproverRule#ANY_PERMITTED_APPROVER}: whoever the resume endpoint
 * already admitted (owner, administrator, approver role, channel).</li>
 * <li>{@link ApproverRule#CONVERSATION_USER}: the conversation's user, when the
 * conversation's provenance is {@code VERIFIED}; or an administrator acting for
 * them with a non-blank note as the reason. Nobody else — and without an
 * approver identity (auth disabled, an unauthenticated channel) nobody.</li>
 * </ul>
 */
public final class MandatoryApproverCheck {

    /** Refusal: an automatic decision tried to approve a mandatory call. */
    public static final String REFUSED_AUTOMATED = "This call needs a human approval; an automatic decision cannot approve it.";
    /**
     * Refusal: the approver is neither the conversation's user nor an
     * administrator.
     */
    public static final String REFUSED_NOT_USER = "Only the user of this conversation or an administrator can confirm this.";
    /**
     * Refusal: the approver is the conversation's user, but nobody verified that
     * identity when the conversation was created.
     */
    public static final String REFUSED_UNVERIFIED_USER = "This conversation's user identity was not verified when it started, so only an "
            + "administrator can confirm this.";
    /** Refusal: an administrator approved for the user without a reason. */
    public static final String REFUSED_ADMIN_WITHOUT_REASON = "An administrator confirming this for the user of the conversation must give a "
            + "reason in the decision's note.";
    /**
     * Refusal (400, before the pause is consumed): amended arguments of a mandatory
     * call.
     */
    public static final String REFUSED_AMENDMENT = "The arguments of a call that needs a mandatory approval cannot be amended — approve "
            + "or reject it as proposed.";

    private MandatoryApproverCheck() {
    }

    /**
     * Enforces the approver rule of every mandatory call the decision approves.
     *
     * @param approver
     *            the identity of whoever decides, or null when none was captured
     * @param conversationUserId
     *            the conversation's user
     * @param provenance
     *            the conversation's resolution provenance
     * @return how the approval was accepted, or null when the decision approves no
     *         mandatory call (nothing to enforce)
     * @throws MandatoryApprovalRefusedException
     *             naming why, with fixed text; nothing has run
     */
    public static MandatoryApprovalAttribution enforce(PendingToolCallBatch batch, HitlDecision decision, CallerIdentity approver,
                                                       String conversationUserId, ResolutionPrincipal.Provenance provenance) {
        return strictest(batch, enforceByCall(batch, decision, approver, conversationUserId, provenance));
    }

    /**
     * As {@link #enforce}, answering how each approved mandatory call's approval
     * was accepted (call id to attribution) - each call is executed and recorded
     * with its own, since a batch may mix approver rules. Empty when the decision
     * approves no mandatory call.
     *
     * @throws MandatoryApprovalRefusedException
     *             when the approver rule of any approved mandatory call refuses
     */
    public static Map<String, MandatoryApprovalAttribution> enforceByCall(PendingToolCallBatch batch, HitlDecision decision,
                                                                          CallerIdentity approver, String conversationUserId,
                                                                          ResolutionPrincipal.Provenance provenance) {
        Map<String, MandatoryApprovalAttribution> byCall = new LinkedHashMap<>();
        if (batch == null || batch.getCalls() == null || decision == null) {
            return byCall;
        }
        for (PendingToolCall call : batch.getCalls()) {
            if (call == null || !call.isMandatory() || !approves(decision, call)) {
                continue;
            }
            byCall.put(call.getCallId(), check(ruleOf(call), decision, noteFor(decision, call), approver, conversationUserId, provenance));
        }
        return byCall;
    }

    /**
     * The attribution of the call with the strictest approver rule in
     * {@code byCall} (the first on a tie), or null when there is none - what the
     * decision's audit entry records.
     */
    public static MandatoryApprovalAttribution strictest(PendingToolCallBatch batch, Map<String, MandatoryApprovalAttribution> byCall) {
        if (batch == null || batch.getCalls() == null || byCall == null || byCall.isEmpty()) {
            return null;
        }
        MandatoryApprovalAttribution strictest = null;
        ApproverRule strictestRule = null;
        for (PendingToolCall call : batch.getCalls()) {
            MandatoryApprovalAttribution accepted = call == null ? null : byCall.get(call.getCallId());
            if (accepted == null) {
                continue;
            }
            ApproverRule rule = ruleOf(call);
            if (strictestRule == null || rule.ordinal() > strictestRule.ordinal()) {
                strictestRule = rule;
                strictest = accepted;
            }
        }
        return strictest;
    }

    /**
     * Refuses amended arguments on a mandatory call — the approval is for the call
     * as its rule saw it. Called with the other {@code toolDecisions} checks,
     * before the pause is consumed.
     *
     * @throws IllegalArgumentException
     *             with {@link #REFUSED_AMENDMENT}
     */
    public static void refuseAmendments(PendingToolCallBatch batch, HitlDecision decision) {
        if (batch == null || batch.getCalls() == null || decision == null || decision.getToolDecisions() == null) {
            return;
        }
        for (PendingToolCall call : batch.getCalls()) {
            if (call == null || !call.isMandatory()) {
                continue;
            }
            ToolCallDecision cd = decision.getToolDecisions().get(call.getCallId());
            if (cd != null && cd.getAmendedArguments() != null) {
                throw new IllegalArgumentException(REFUSED_AMENDMENT);
            }
        }
    }

    /** The approver rule of a persisted call; an unknown value is the strictest. */
    public static ApproverRule ruleOf(PendingToolCall call) {
        ApproverRule parsed = ApproverRule.parse(call.getMandatoryApprover());
        return parsed != null ? parsed : ApproverRule.CONVERSATION_USER;
    }

    /**
     * Whether the decision approves this call (per-call verdict, else top-level).
     */
    public static boolean approves(HitlDecision decision, PendingToolCall call) {
        Map<String, ToolCallDecision> perCall = decision.getToolDecisions();
        ToolCallDecision cd = perCall != null ? perCall.get(call.getCallId()) : null;
        HitlDecision.HitlVerdict verdict = cd != null && cd.getVerdict() != null ? cd.getVerdict() : decision.getVerdict();
        return verdict == HitlDecision.HitlVerdict.APPROVED;
    }

    private static String noteFor(HitlDecision decision, PendingToolCall call) {
        Map<String, ToolCallDecision> perCall = decision.getToolDecisions();
        ToolCallDecision cd = perCall != null ? perCall.get(call.getCallId()) : null;
        if (cd != null && cd.getNote() != null && !cd.getNote().isBlank()) {
            return cd.getNote();
        }
        return decision.getNote();
    }

    static MandatoryApprovalAttribution check(ApproverRule rule, HitlDecision decision, String note, CallerIdentity approver,
                                              String conversationUserId, ResolutionPrincipal.Provenance provenance) {
        String decidedBy = decision.getDecidedBy();
        if (decidedBy != null && decidedBy.startsWith("system:")) {
            throw new MandatoryApprovalRefusedException(REFUSED_AUTOMATED);
        }
        if (rule == ApproverRule.ANY_PERMITTED_APPROVER) {
            String who = approver != null && approver.userId() != null ? approver.userId() : decidedBy;
            return new MandatoryApprovalAttribution(who, null, ActingAs.APPROVER);
        }
        if (approver == null) {
            throw new MandatoryApprovalRefusedException(REFUSED_NOT_USER);
        }
        boolean sameUser = CallerIdentityContext.isSameUser(approver, conversationUserId);
        if (sameUser && provenance == ResolutionPrincipal.Provenance.VERIFIED) {
            return new MandatoryApprovalAttribution(conversationUserId, null, ActingAs.USER);
        }
        if (approver.admin()) {
            if (note == null || note.isBlank()) {
                throw new MandatoryApprovalRefusedException(REFUSED_ADMIN_WITHOUT_REASON);
            }
            String adminId = approver.userId() != null ? approver.userId() : decidedBy;
            return new MandatoryApprovalAttribution(adminId, conversationUserId, ActingAs.ADMIN_ON_BEHALF);
        }
        throw new MandatoryApprovalRefusedException(sameUser ? REFUSED_UNVERIFIED_USER : REFUSED_NOT_USER);
    }
}
