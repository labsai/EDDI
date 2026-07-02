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
    /** Set only for group-surface pauses — the group configuration ID. */
    private String groupId;
    /** Owner of the conversation — used for listing visibility filters. */
    private String userId;
    private Instant pausedAt;
    private String pauseReason;
    private String timeoutPolicy;
    /** ISO-8601 duration of the configured approval timeout (may be null). */
    private String approvalTimeout;

    public PendingApprovalSummary() {
    }

    public PendingApprovalSummary(String conversationId, String agentId, String userId, Instant pausedAt,
            String pauseReason, String timeoutPolicy) {
        this.conversationId = conversationId;
        this.agentId = agentId;
        this.userId = userId;
        this.pausedAt = pausedAt;
        this.pauseReason = pauseReason;
        this.timeoutPolicy = timeoutPolicy;
    }

    public String getUserId() {
        return userId;
    }
    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getGroupId() {
        return groupId;
    }
    public void setGroupId(String groupId) {
        this.groupId = groupId;
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
    public String getApprovalTimeout() {
        return approvalTimeout;
    }
    public void setApprovalTimeout(String approvalTimeout) {
        this.approvalTimeout = approvalTimeout;
    }
}
