/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;

import java.util.List;
import java.util.Map;

/**
 * Durable record of an LLM tool-call batch interrupted by a HITL tool pause.
 * <p>
 * Persisted as a first-class typed field on {@link ConversationMemorySnapshot}
 * (planning Invariant 1 — never Object-typed step data). On resume, the
 * serialized transcript is replayed and the gated calls' verdicts are applied;
 * the {@code pauseEpoch} keys the write-ahead execution journal so an approval
 * is executed at most once even across crashes.
 */
public class PendingToolCallBatch {

    // Size caps (bytes unless noted) — single source of truth for every writer.
    public static final int TRANSCRIPT_MAX_BYTES_DEFAULT = 2_000_000;
    public static final int ARGS_RAW_MAX_BYTES = 262_144;
    public static final int ARGS_REDACTED_MAX_BYTES = 32_768;
    public static final int AMENDED_ARGS_MAX_BYTES = 32_768;
    public static final int TRACE_ENTRY_MAX_BYTES = 65_536;

    /** A single gated tool call awaiting a human verdict. */
    public static class PendingToolCall {
        private String callId; // provider id, or "gen-" + UUID when absent
        private String toolName;
        private String source; // builtin|http|mcp|a2a|dynamic|memory|recall|unknown
        private String argumentsRaw; // capped; needed for execution + fallback rebuild
        private boolean argsTruncated; // true => call may NOT be executed on resume (auto-error result)
        private String argumentsRedacted; // SecretRedactionFilter'd, capped — the ONLY field approver surfaces read
        private String gateReason; // the matched pattern, e.g. "mcp:*"
        private String matchedRule; // toolApprovals.rules[].match that tuned this call, or null

        public String getCallId() {
            return callId;
        }

        public void setCallId(String callId) {
            this.callId = callId;
        }

        public String getToolName() {
            return toolName;
        }

        public void setToolName(String toolName) {
            this.toolName = toolName;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getArgumentsRaw() {
            return argumentsRaw;
        }

        public void setArgumentsRaw(String argumentsRaw) {
            this.argumentsRaw = argumentsRaw;
        }

        public boolean isArgsTruncated() {
            return argsTruncated;
        }

        public void setArgsTruncated(boolean argsTruncated) {
            this.argsTruncated = argsTruncated;
        }

        public String getArgumentsRedacted() {
            return argumentsRedacted;
        }

        public void setArgumentsRedacted(String argumentsRedacted) {
            this.argumentsRedacted = argumentsRedacted;
        }

        public String getGateReason() {
            return gateReason;
        }

        public void setGateReason(String gateReason) {
            this.gateReason = gateReason;
        }

        public String getMatchedRule() {
            return matchedRule;
        }

        public void setMatchedRule(String matchedRule) {
            this.matchedRule = matchedRule;
        }
    }

    private String pauseEpoch; // UUID per pause — journal key component
    private String llmTaskId; // LlmConfiguration.Task.getId() — identity binding
    private int llmTaskIndex; // index within llmConfig.tasks() at pause time
    private String workflowId; // informational
    private String chatTranscriptJson; // ChatTranscriptCodec output; null when omitted
    private boolean transcriptOmitted;
    private List<PendingToolCall> calls;
    private List<String> executedUngatedCallNames; // approver visibility: side effects that already ran
    private int iterationIndex; // loop iteration at pause — budget continuity
    private List<String> activatedToolNames; // LAZY-mode reactivation
    private List<Map<String, Object>> traceSoFar; // per-entry result capped
    private String fingerprint; // sha256(sorted toolName + "|" + arguments)
    private int autoApproveCount; // consecutive system approvals, carried across re-pauses
    private int pauseCountThisTurn; // enforced against maxPausesPerTurn
    /**
     * The EXACT effective tool-approval config that gated this batch — the
     * task-level override when the paused task set one, else the agent-level
     * default. Persisted so the post-pause policy resolvers in
     * {@code ConversationService} (timeout, no-progress, max-auto-approvals) and
     * {@code Conversation.resolvePendingMessage} read the same task-scoped config
     * that produced the pause instead of re-deriving from the agent level only.
     * <p>
     * Nullable for backward compatibility: a legacy persisted batch (pre-fix) or a
     * RULE pause leaves this null, and readers fall back to the agent-level config
     * exactly as before. Deliberately excluded from the fix-#4 names-only
     * projection ({@code ConversationMemoryUtilities.namesOnlyPendingToolCalls}) —
     * it is config, not user data, and the generic read path does not need it.
     */
    private ToolApprovalsConfig effectiveToolApprovals;
    /**
     * The single {@code toolApprovals.rules} entry governing THIS pause — resolved
     * at gate time by {@code ToolApprovalRules}, where the gated calls' names,
     * sources and endpoints are all still in hand.
     * <p>
     * Resolved once and persisted rather than re-derived downstream: after the
     * pause, {@code ConversationService} (timeout policy) and
     * {@code Conversation.resolvePendingMessage} see only this batch, whose
     * {@link PendingToolCall}s carry a name and source but no endpoint — so
     * {@code http.post:/agentstore/agents} could not be re-matched there, and a
     * silently different answer on the two sides of a pause is exactly the class of
     * bug this field removes.
     * <p>
     * Null when the config has no rules or none matched; readers then fall back to
     * the {@link #effectiveToolApprovals} scalars, which is also what every batch
     * persisted before this field existed does.
     */
    private ToolApprovalsConfig.ApprovalRule effectiveRule;

    public String getPauseEpoch() {
        return pauseEpoch;
    }

    public void setPauseEpoch(String pauseEpoch) {
        this.pauseEpoch = pauseEpoch;
    }

    public String getLlmTaskId() {
        return llmTaskId;
    }

    public void setLlmTaskId(String llmTaskId) {
        this.llmTaskId = llmTaskId;
    }

    public int getLlmTaskIndex() {
        return llmTaskIndex;
    }

    public void setLlmTaskIndex(int llmTaskIndex) {
        this.llmTaskIndex = llmTaskIndex;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public String getChatTranscriptJson() {
        return chatTranscriptJson;
    }

    public void setChatTranscriptJson(String chatTranscriptJson) {
        this.chatTranscriptJson = chatTranscriptJson;
    }

    public boolean isTranscriptOmitted() {
        return transcriptOmitted;
    }

    public void setTranscriptOmitted(boolean transcriptOmitted) {
        this.transcriptOmitted = transcriptOmitted;
    }

    public List<PendingToolCall> getCalls() {
        return calls;
    }

    public void setCalls(List<PendingToolCall> calls) {
        this.calls = calls;
    }

    public List<String> getExecutedUngatedCallNames() {
        return executedUngatedCallNames;
    }

    public void setExecutedUngatedCallNames(List<String> executedUngatedCallNames) {
        this.executedUngatedCallNames = executedUngatedCallNames;
    }

    public int getIterationIndex() {
        return iterationIndex;
    }

    public void setIterationIndex(int iterationIndex) {
        this.iterationIndex = iterationIndex;
    }

    public List<String> getActivatedToolNames() {
        return activatedToolNames;
    }

    public void setActivatedToolNames(List<String> activatedToolNames) {
        this.activatedToolNames = activatedToolNames;
    }

    public List<Map<String, Object>> getTraceSoFar() {
        return traceSoFar;
    }

    public void setTraceSoFar(List<Map<String, Object>> traceSoFar) {
        this.traceSoFar = traceSoFar;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public int getAutoApproveCount() {
        return autoApproveCount;
    }

    public void setAutoApproveCount(int autoApproveCount) {
        this.autoApproveCount = autoApproveCount;
    }

    public int getPauseCountThisTurn() {
        return pauseCountThisTurn;
    }

    public void setPauseCountThisTurn(int pauseCountThisTurn) {
        this.pauseCountThisTurn = pauseCountThisTurn;
    }

    public ToolApprovalsConfig getEffectiveToolApprovals() {
        return effectiveToolApprovals;
    }

    public void setEffectiveToolApprovals(ToolApprovalsConfig effectiveToolApprovals) {
        this.effectiveToolApprovals = effectiveToolApprovals;
    }

    public ToolApprovalsConfig.ApprovalRule getEffectiveRule() {
        return effectiveRule;
    }

    public void setEffectiveRule(ToolApprovalsConfig.ApprovalRule effectiveRule) {
        this.effectiveRule = effectiveRule;
    }
}
