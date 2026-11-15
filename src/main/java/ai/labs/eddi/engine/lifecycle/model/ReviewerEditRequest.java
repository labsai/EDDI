/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.model;

/**
 * An approver's edit of the answer a RULE pause kept: replace the draft the
 * candidate describes with {@code editedText}.
 *
 * @param candidate
 *            where the draft sits
 * @param editedText
 *            the approver's final answer — data, never template source
 * @param decidedBy
 *            who approved
 * @param screen
 *            whether the task's output guardrails screen the edit
 *            ({@code review.screenEdits: OUTPUT_GUARDRAILS})
 * @since 6.7.0
 */
public record ReviewerEditRequest(HitlRevisionCandidate candidate, String editedText, String decidedBy, boolean screen) {

    /**
     * What happened to an edit.
     *
     * @param applied
     *            whether the edit replaced the draft
     * @param text
     *            the text stored (after a {@code redact} guardrail); null when not
     *            applied
     * @param refusal
     *            {@code SCREENED_OUT} or {@code CONFIG_DRIFT} when not applied
     * @param guardrail
     *            the guardrail that blocked it, when screened out
     */
    public record Outcome(boolean applied, String text, String refusal, String guardrail) {

        public static Outcome applied(String text) {
            return new Outcome(true, text, null, null);
        }

        public static Outcome refused(String refusal, String guardrail) {
            return new Outcome(false, null, refusal, guardrail);
        }
    }
}
