/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.model;

/**
 * Per-tool-call verdict inside a {@link HitlDecision} resume body (TOOL_CALL
 * pauses only). Lets a reviewer approve/reject individual gated tool calls
 * within the same pending batch, optionally amending the arguments of an
 * approved call before it executes.
 */
public class ToolCallDecision {

    /** Upper bound for the free-text per-call reviewer note. */
    public static final int MAX_NOTE_LENGTH = 1024;

    private HitlDecision.HitlVerdict verdict;
    private String note;
    private String amendedArguments;

    public HitlDecision.HitlVerdict getVerdict() {
        return verdict;
    }

    public void setVerdict(HitlDecision.HitlVerdict verdict) {
        this.verdict = verdict;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getAmendedArguments() {
        return amendedArguments;
    }

    public void setAmendedArguments(String amendedArguments) {
        this.amendedArguments = amendedArguments;
    }
}
