/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl.model;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;

import java.util.List;

/**
 * Config-driven tool approval gating (tool-level HITL). Used in two homes:
 * agent-level default ({@code AgentConfiguration.HitlConfig.toolApprovals}) and
 * per-task override ({@code LlmConfiguration.Task.toolApprovals} —
 * full-replace, no merging).
 * <p>
 * All fields are nullable; readers apply the documented defaults. An absent or
 * empty {@code requireApproval} list disables the gate entirely (backward
 * compatible).
 */
public class ToolApprovalsConfig {
    /** Glob patterns of tools requiring approval, e.g. "mcp:*", "delete_*". */
    private List<String> requireApproval;
    /** Exemptions — always beat requireApproval. */
    private List<String> exempt;
    /** Max tool pauses per turn (default 3, valid 1..10). Fail-closed at cap. */
    private Integer maxPausesPerTurn;
    /**
     * Max consecutive system (timeout) auto-approvals per turn (default 2, 0..10).
     */
    private Integer maxAutoApprovalsPerTurn;
    /**
     * WAIT_FOR_HUMAN (default) | AUTO_REJECT | ABORT — fires on
     * identical-fingerprint re-pause after a system decision.
     */
    private String onNoProgress;
    /** Tool-pause timeout override; see effective-policy rule. */
    private String approvalTimeout;
    /**
     * Tool-pause timeout policy override; inherited AUTO_APPROVE is demoted to
     * WAIT_INDEFINITELY.
     */
    private HitlTimeoutPolicy timeoutPolicy;
    /**
     * Approver-facing reason; "{toolNames}" placeholder is substituted. ≤500 chars.
     */
    private String pauseReason;
    /**
     * End-user-facing message stored as public output at pause commit.
     * "{toolNames}" substituted. ≤500 chars.
     */
    private String pendingMessage;
    /**
     * REJECT (default) | INBOX (reserved, 400 in v1) — behavior inside group turns.
     */
    private String inGroupTurns;
    /**
     * Optional per-tool friction, most-specific-first. See {@link ApprovalRule} —
     * rules tune HOW a gated call is reviewed, never WHETHER it is gated.
     */
    private List<ApprovalRule> rules;

    /**
     * Per-tool override of the approval scalars above, addressed by the same
     * pattern language as {@code requireApproval} (see
     * {@code ToolApprovalPatterns}): a bare tool name, {@code source:name}, or —
     * for httpcall tools — {@code source.method:path}, e.g.
     * {@code http.post:/agentstore/agents}.
     * <p>
     * <b>A rule tunes friction; it never gates or ungates.</b> Whether a call needs
     * approval is decided solely by {@code requireApproval}/{@code exempt}. A rule
     * able to ungate would mean a config could grant capability by adding an entry,
     * i.e. enumerating upward — the opposite of the gate's design, which allows an
     * unmatched call and therefore only ever survives by gating broadly and
     * exempting narrowly.
     * <p>
     * Every field is optional and falls back <i>individually</i> to the enclosing
     * {@link ToolApprovalsConfig} scalar, so a rule that sets only
     * {@code pauseReason} still inherits the configured timeout policy.
     */
    public static class ApprovalRule {
        /** Pattern selecting the tool calls this rule applies to. Required. */
        private String match;
        /** Overrides {@link ToolApprovalsConfig#getTimeoutPolicy()} for this rule. */
        private HitlTimeoutPolicy timeoutPolicy;
        /** Overrides {@link ToolApprovalsConfig#getApprovalTimeout()}. */
        private String approvalTimeout;
        /** Overrides {@link ToolApprovalsConfig#getPauseReason()}. ≤500 chars. */
        private String pauseReason;
        /** Overrides {@link ToolApprovalsConfig#getPendingMessage()}. ≤500 chars. */
        private String pendingMessage;

        public String getMatch() {
            return match;
        }

        public void setMatch(String match) {
            this.match = match;
        }

        public HitlTimeoutPolicy getTimeoutPolicy() {
            return timeoutPolicy;
        }

        public void setTimeoutPolicy(HitlTimeoutPolicy timeoutPolicy) {
            this.timeoutPolicy = timeoutPolicy;
        }

        public String getApprovalTimeout() {
            return approvalTimeout;
        }

        public void setApprovalTimeout(String approvalTimeout) {
            this.approvalTimeout = approvalTimeout;
        }

        public String getPauseReason() {
            return pauseReason;
        }

        public void setPauseReason(String pauseReason) {
            this.pauseReason = pauseReason;
        }

        public String getPendingMessage() {
            return pendingMessage;
        }

        public void setPendingMessage(String pendingMessage) {
            this.pendingMessage = pendingMessage;
        }
    }

    public List<ApprovalRule> getRules() {
        return rules;
    }

    public void setRules(List<ApprovalRule> rules) {
        this.rules = rules;
    }

    public List<String> getRequireApproval() {
        return requireApproval;
    }

    public void setRequireApproval(List<String> requireApproval) {
        this.requireApproval = requireApproval;
    }

    public List<String> getExempt() {
        return exempt;
    }

    public void setExempt(List<String> exempt) {
        this.exempt = exempt;
    }

    public Integer getMaxPausesPerTurn() {
        return maxPausesPerTurn;
    }

    public void setMaxPausesPerTurn(Integer maxPausesPerTurn) {
        this.maxPausesPerTurn = maxPausesPerTurn;
    }

    public Integer getMaxAutoApprovalsPerTurn() {
        return maxAutoApprovalsPerTurn;
    }

    public void setMaxAutoApprovalsPerTurn(Integer maxAutoApprovalsPerTurn) {
        this.maxAutoApprovalsPerTurn = maxAutoApprovalsPerTurn;
    }

    public String getOnNoProgress() {
        return onNoProgress;
    }

    public void setOnNoProgress(String onNoProgress) {
        this.onNoProgress = onNoProgress;
    }

    public String getApprovalTimeout() {
        return approvalTimeout;
    }

    public void setApprovalTimeout(String approvalTimeout) {
        this.approvalTimeout = approvalTimeout;
    }

    public HitlTimeoutPolicy getTimeoutPolicy() {
        return timeoutPolicy;
    }

    public void setTimeoutPolicy(HitlTimeoutPolicy timeoutPolicy) {
        this.timeoutPolicy = timeoutPolicy;
    }

    public String getPauseReason() {
        return pauseReason;
    }

    public void setPauseReason(String pauseReason) {
        this.pauseReason = pauseReason;
    }

    public String getPendingMessage() {
        return pendingMessage;
    }

    public void setPendingMessage(String pendingMessage) {
        this.pendingMessage = pendingMessage;
    }

    public String getInGroupTurns() {
        return inGroupTurns;
    }

    public void setInGroupTurns(String inGroupTurns) {
        this.inGroupTurns = inGroupTurns;
    }
}
