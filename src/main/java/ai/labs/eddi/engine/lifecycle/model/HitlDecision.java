/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.model;

/**
 * Human decision on a paused conversation or group discussion.
 * Jackson-deserialized from the REST request body.
 */
public class HitlDecision {

    /**
     * Upper bound for the free-text reviewer note — the single source of truth for
     * every surface that accepts a decision body (regular resume, group approve,
     * channel integrations).
     */
    public static final int MAX_NOTE_LENGTH = 4096;

    public enum HitlVerdict {
        APPROVED, REJECTED;

        /**
         * Case-insensitive parsing on every surface — "approved" and "APPROVED" are the
         * same human intent; rejecting on casing is needless 400 noise.
         */
        @com.fasterxml.jackson.annotation.JsonCreator
        public static HitlVerdict fromString(String value) {
            return value == null ? null : HitlVerdict.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

    private HitlVerdict verdict;
    private String note;
    /** userId — set server-side from SecurityIdentity (not trusted from body). */
    private String decidedBy;
    /**
     * Per-tool-call verdicts, keyed by {@code callId} — TOOL_CALL pauses only.
     * Calls not listed here inherit the top-level {@link #verdict}.
     */
    private java.util.Map<String, ToolCallDecision> toolDecisions;

    public HitlVerdict getVerdict() {
        return verdict;
    }

    public void setVerdict(HitlVerdict verdict) {
        this.verdict = verdict;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public java.util.Map<String, ToolCallDecision> getToolDecisions() {
        return toolDecisions;
    }

    public void setToolDecisions(java.util.Map<String, ToolCallDecision> toolDecisions) {
        this.toolDecisions = toolDecisions;
    }
}
