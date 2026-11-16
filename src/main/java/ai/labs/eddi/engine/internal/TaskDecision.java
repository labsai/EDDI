/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.lifecycle.model.HitlDecision;

/**
 * One task's decision in a group TASK approval ({@code taskDecisions}, the
 * richer sibling of {@code taskApprovals}): verdict, an optional note, an
 * optional edited result (APPROVED only, {@code review.allowResultEdits}) and
 * an optional reason code.
 *
 * @since 6.7.0
 */
public class TaskDecision {

    private HitlDecision.HitlVerdict verdict;
    private String note;
    private String editedResult;
    private String reasonCode;

    public HitlDecision.HitlVerdict getVerdict() {
        return verdict;
    }

    public void setVerdict(HitlDecision.HitlVerdict verdict) {
        this.verdict = verdict;
    }

    /** The reviewer's note for this task; a rejection's feedback under RETRY. */
    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    /** The result the approver wrote in place of the agent's. */
    public String getEditedResult() {
        return editedResult;
    }

    public void setEditedResult(String editedResult) {
        this.editedResult = editedResult;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }
}
