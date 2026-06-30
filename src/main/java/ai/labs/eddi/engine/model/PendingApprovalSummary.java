/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import java.time.Instant;

/**
 * Summary of a conversation awaiting human approval.
 */
public class PendingApprovalSummary {
    private String conversationId;
    private String agentId;
    private Instant pausedAt;
    private String pauseReason;
    private String timeoutPolicy;

    public PendingApprovalSummary() {
    }

    public PendingApprovalSummary(String conversationId, String agentId, Instant pausedAt,
            String pauseReason, String timeoutPolicy) {
        this.conversationId = conversationId;
        this.agentId = agentId;
        this.pausedAt = pausedAt;
        this.pauseReason = pauseReason;
        this.timeoutPolicy = timeoutPolicy;
    }

    public String getConversationId() {
        return conversationId;
    }
    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }
    public String getAgentId() {
        return agentId;
    }
    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }
    public Instant getPausedAt() {
        return pausedAt;
    }
    public void setPausedAt(Instant pausedAt) {
        this.pausedAt = pausedAt;
    }
    public String getPauseReason() {
        return pauseReason;
    }
    public void setPauseReason(String pauseReason) {
        this.pauseReason = pauseReason;
    }
    public String getTimeoutPolicy() {
        return timeoutPolicy;
    }
    public void setTimeoutPolicy(String timeoutPolicy) {
        this.timeoutPolicy = timeoutPolicy;
    }
}
