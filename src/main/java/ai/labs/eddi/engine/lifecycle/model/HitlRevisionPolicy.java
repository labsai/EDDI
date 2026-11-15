/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.model;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.HitlRejectionAction;
import ai.labs.eddi.configs.hitl.model.HitlReviewConfig;

/**
 * The agent's {@code hitlConfig.onRejection} policy, carried onto the live
 * memory for one turn (never persisted, re-read from the pinned agent version
 * each turn like the tool-approval config). {@code null} on memory means the
 * default, {@link HitlRejectionAction#SKIP}, with no answer edits.
 *
 * @param onRejection
 *            what a rejected rule pause does
 * @param maxRevisionsPerTurn
 *            how many revisions one turn may take
 * @param captureDraft
 *            whether the answer before a rule pause is kept so an approver may
 *            edit it ({@code hitlConfig.review.allowAnswerEdits}, since 6.7)
 * @param screenEdits
 *            whether an approver's edit passes the task's output guardrails
 *            ({@code review.screenEdits: OUTPUT_GUARDRAILS}, since 6.7)
 * @since 6.6.0
 */
public record HitlRevisionPolicy(HitlRejectionAction onRejection, int maxRevisionsPerTurn, boolean captureDraft, boolean screenEdits) {

    /** A policy without answer edits — the 6.6 shape. */
    public HitlRevisionPolicy(HitlRejectionAction onRejection, int maxRevisionsPerTurn) {
        this(onRejection, maxRevisionsPerTurn, false, false);
    }

    /** Whether a rejection with a note may revise the answer. */
    public boolean revise() {
        return onRejection == HitlRejectionAction.REVISE && maxRevisionsPerTurn > 0;
    }

    /**
     * The policy of an agent's HITL config, or {@code null} when it is the default
     * (SKIP, no answer edits) — so nothing is captured for an agent that did not
     * opt in.
     */
    public static HitlRevisionPolicy of(AgentConfiguration.HitlConfig hitlConfig) {
        if (hitlConfig == null) {
            return null;
        }
        var review = hitlConfig.getReview();
        boolean captureDraft = review != null && review.answerEditsAllowed();
        boolean screenEdits = captureDraft && review.getScreenEdits() != HitlReviewConfig.ScreenEdits.NONE;
        if (hitlConfig.getOnRejection() != HitlRejectionAction.REVISE) {
            return captureDraft ? new HitlRevisionPolicy(hitlConfig.getOnRejection(), 0, true, screenEdits) : null;
        }
        return new HitlRevisionPolicy(HitlRejectionAction.REVISE, hitlConfig.effectiveMaxRevisionsPerTurn(), captureDraft, screenEdits);
    }
}
