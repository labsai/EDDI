/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code AgentConfiguration.hitlConfig.review}: what an approver may do to a
 * paused answer beyond approve and reject, and how decisions of this agent are
 * classified in the audit ledger.
 * <p>
 * Absent = the behaviour before 6.7: a RULE pause can only be approved or
 * rejected, tool-call amendments work as they always did, the approval category
 * is derived ({@code rule:<agentId>}) and the built-in reason-code vocabulary
 * applies. Every field is optional.
 *
 * <pre>{@code
 * "review": {
 *   "allowAnswerEdits": true,
 *   "screenEdits": "OUTPUT_GUARDRAILS",
 *   "maxEditChars": 16000,
 *   "approvalCategory": "github.reply",
 *   "reasonCodes": { "FALSE_CLAIM": "States something untrue", "TONE": "Wrong tone" }
 * }
 * }</pre>
 *
 * @since 6.7.0
 */
public class HitlReviewConfig {

    /** Default for {@link #maxEditChars}. */
    public static final int DEFAULT_MAX_EDIT_CHARS = 16_000;
    /** Upper bound for {@link #maxEditChars}. */
    public static final int MAX_EDIT_CHARS_CEILING = 65_536;

    /** How an approver's edited answer is screened before it replaces the draft. */
    public enum ScreenEdits {
        /**
         * The LLM task's own {@code outputGuardrails} run on the edited text, as on a
         * live answer. A {@code reprompt} finding cannot ask a human again, so it acts
         * as {@code block}; {@code redact} rewrites the text; {@code block} refuses the
         * edit and the turn pauses again on the unchanged draft.
         */
        OUTPUT_GUARDRAILS,
        /** The edited text is stored as written. */
        NONE
    }

    /**
     * Whether an approver may approve a RULE pause with an edited answer
     * ({@code editedAnswer} on the decision). Default {@code false}: editing writes
     * text in the agent's voice, a power a plain approval gate never granted.
     */
    private Boolean allowAnswerEdits;
    private ScreenEdits screenEdits = ScreenEdits.OUTPUT_GUARDRAILS;
    /** Upper bound, in characters, of an edited answer. 1..65536, default 16000. */
    private Integer maxEditChars;
    /**
     * The approval category recorded for this agent's RULE pauses. Absent = derived
     * {@code rule:<agentId>}. Lower-case, {@code [a-z0-9._:/-]}, at most 128
     * characters.
     */
    private String approvalCategory;
    /**
     * Reason-code vocabulary for rejections and edits: code → label. Absent = the
     * built-in vocabulary ({@code FALSE_CLAIM, FACTUAL_ERROR, TONE, OFF_POLICY,
     * FORMAT, SCOPE, OTHER}); present = replaces it.
     */
    private Map<String, String> reasonCodes;

    public Boolean getAllowAnswerEdits() {
        return allowAnswerEdits;
    }

    public void setAllowAnswerEdits(Boolean allowAnswerEdits) {
        this.allowAnswerEdits = allowAnswerEdits;
    }

    /** {@link #allowAnswerEdits}, absent = false. */
    @JsonIgnore
    public boolean answerEditsAllowed() {
        return Boolean.TRUE.equals(allowAnswerEdits);
    }

    public ScreenEdits getScreenEdits() {
        return screenEdits;
    }

    public void setScreenEdits(ScreenEdits screenEdits) {
        // "screenEdits": null keeps the safe default, like timeoutPolicy
        if (screenEdits != null) {
            this.screenEdits = screenEdits;
        }
    }

    public Integer getMaxEditChars() {
        return maxEditChars;
    }

    public void setMaxEditChars(Integer maxEditChars) {
        this.maxEditChars = maxEditChars;
    }

    /** {@link #maxEditChars}, or its default when absent. */
    @JsonIgnore
    public int effectiveMaxEditChars() {
        return maxEditChars != null ? maxEditChars : DEFAULT_MAX_EDIT_CHARS;
    }

    public String getApprovalCategory() {
        return approvalCategory;
    }

    public void setApprovalCategory(String approvalCategory) {
        this.approvalCategory = approvalCategory;
    }

    public Map<String, String> getReasonCodes() {
        return reasonCodes;
    }

    public void setReasonCodes(Map<String, String> reasonCodes) {
        this.reasonCodes = reasonCodes != null ? new LinkedHashMap<>(reasonCodes) : null;
    }
}
