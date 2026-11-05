/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code AgentGroupConfiguration.hitlConfig.review}: whether an approver may
 * edit what a paused group produced, and how its decisions are classified in
 * the audit ledger.
 * <p>
 * Absent = the behaviour before 6.7: tasks and phases are approved or rejected
 * as they are, the category is derived ({@code group:<groupId>:phase:<name>} or
 * {@code group:<groupId>:task:<name>}) and the built-in reason codes apply.
 *
 * <pre>{@code
 * "review": {
 *   "allowResultEdits": true,
 *   "maxEditChars": 16000,
 *   "phaseCategories": { "Execution": "directory.submission" },
 *   "reasonCodes": { "FALSE_CLAIM": "States something untrue" }
 * }
 * }</pre>
 *
 * @since 6.7.0
 */
public class GroupHitlReviewConfig {

    /**
     * Whether an approver may replace a TASK's result ({@code taskDecisions[id]
     * .editedResult}) or a SYNTHESIS phase's output ({@code decision.editedAnswer}
     * on a PHASE pause) when approving. Default {@code false}.
     */
    private Boolean allowResultEdits;
    /** Upper bound, in characters, of an edited result. 1..65536, default 16000. */
    private Integer maxEditChars;
    /**
     * Phase name → approval category for the PHASE and TASK pauses of that phase.
     */
    private Map<String, String> phaseCategories;
    /** As {@link HitlReviewConfig#getReasonCodes()}. */
    private Map<String, String> reasonCodes;

    public Boolean getAllowResultEdits() {
        return allowResultEdits;
    }

    public void setAllowResultEdits(Boolean allowResultEdits) {
        this.allowResultEdits = allowResultEdits;
    }

    /** {@link #allowResultEdits}, absent = false. */
    @JsonIgnore
    public boolean resultEditsAllowed() {
        return Boolean.TRUE.equals(allowResultEdits);
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
        return maxEditChars != null ? maxEditChars : HitlReviewConfig.DEFAULT_MAX_EDIT_CHARS;
    }

    public Map<String, String> getPhaseCategories() {
        return phaseCategories;
    }

    public void setPhaseCategories(Map<String, String> phaseCategories) {
        this.phaseCategories = phaseCategories != null ? new LinkedHashMap<>(phaseCategories) : null;
    }

    public Map<String, String> getReasonCodes() {
        return reasonCodes;
    }

    public void setReasonCodes(Map<String, String> reasonCodes) {
        this.reasonCodes = reasonCodes != null ? new LinkedHashMap<>(reasonCodes) : null;
    }
}
