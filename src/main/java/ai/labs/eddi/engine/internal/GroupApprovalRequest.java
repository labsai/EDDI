/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.lifecycle.model.HitlDecision;

import java.util.Map;

/**
 * REST request body for group discussion approval.
 */
public class GroupApprovalRequest {
    private HitlDecision decision;
    /** taskId → "APPROVED" or "REJECTED" */
    private Map<String, String> taskApprovals;

    public HitlDecision getDecision() {
        return decision;
    }

    public void setDecision(HitlDecision decision) {
        this.decision = decision;
    }

    public Map<String, String> getTaskApprovals() {
        return taskApprovals;
    }

    public void setTaskApprovals(Map<String, String> taskApprovals) {
        this.taskApprovals = taskApprovals;
    }
}
