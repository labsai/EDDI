/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl.model;

/**
 * One block of designer-authored context shown to the approver next to the
 * approval — the draft, its sources, the claims it makes, the devil's
 * advocate's objection.
 * <p>
 * {@code template} is a runtime template (author-written config, rendered on
 * the restricted engine) over the conversation's data plus {@code hitl.*},
 * {@code call.*} (rule-level blocks only) and {@code group.*} (group pauses
 * only). It is rendered <b>once, when the pause is committed</b>, and the
 * result is what every surface shows. A block whose render is blank is dropped,
 * which makes a block conditional by writing the condition into the template.
 * <p>
 * Used by {@code hitlConfig.approvalContext},
 * {@code hitlConfig.toolApprovals.rules[].context} and the group
 * {@code hitlConfig.approvalContext}. Absent = nothing rendered, nothing
 * stored, the card unchanged.
 *
 * @since 6.7.0
 */
public class ApprovalContextBlock {

    /** Default for {@link #maxChars} when absent. */
    public static final int DEFAULT_MAX_CHARS = 1000;

    /** Heading of the block, e.g. "Sources". Plain text, at most 80 characters. */
    private String label;
    /** The template. At most 4,000 characters. */
    private String template;
    /**
     * Cut-off for the rendered text, 50..2,900 (Slack allows 3,000 characters per
     * section). Absent = {@value #DEFAULT_MAX_CHARS}.
     */
    private Integer maxChars;

    public ApprovalContextBlock() {
    }

    public ApprovalContextBlock(String label, String template, Integer maxChars) {
        this.label = label;
        this.template = template;
        this.maxChars = maxChars;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    public Integer getMaxChars() {
        return maxChars;
    }

    public void setMaxChars(Integer maxChars) {
        this.maxChars = maxChars;
    }

    /** {@link #maxChars}, or its default when absent. */
    public int effectiveMaxChars() {
        return maxChars != null ? maxChars : DEFAULT_MAX_CHARS;
    }
}
