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
    // #39: verdict casing (e.g. {"verdict":"approved"}) is handled by
    // HitlVerdict.@JsonCreator, which is case-insensitive on every surface and
    // yields a null verdict for an unrecognized value (reported as the friendly
    // 400 upstream). Default Jackson deserialization is used here so ALL
    // HitlDecision fields — including toolDecisions — are preserved; a custom
    // request-local deserializer would silently drop any field it did not copy.
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
