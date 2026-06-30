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

    public enum HitlVerdict {
        APPROVED, REJECTED
    }

    private HitlVerdict verdict;
    private String note;
    /** userId — set server-side from SecurityIdentity (not trusted from body). */
    private String decidedBy;

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
}
