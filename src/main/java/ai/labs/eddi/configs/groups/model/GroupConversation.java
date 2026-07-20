/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.engine.memory.model.Attachment;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transcript record for a group conversation. Persisted with a single-version
 * model via {@code AbstractResourceStore} (DB-agnostic).
 *
 * @author ginccc
 */
public class GroupConversation {
    private String id;
    private String groupId;
    private String userId;
    private GroupConversationState state;
    private String originalQuestion;
    /**
     * The question driving the CURRENT run's phases. Equals
     * {@link #originalQuestion} for the initial round; a continuation round sets it
     * to the follow-up question so an HITL resume re-runs the remaining phases with
     * the right question. Kept separate from {@code originalQuestion} (which the UI
     * renders as the conversation title) so continuations don't rewrite that title.
     * {@code null} on legacy documents — resume falls back to
     * {@code originalQuestion}.
     */
    private String resumeQuestion;
    private List<TranscriptEntry> transcript = Collections.synchronizedList(new ArrayList<>());
    private Map<String, String> memberConversationIds = new ConcurrentHashMap<>();
    /**
     * Maps agentId → displayName for all group members. Populated at discussion
     * start.
     */
    private Map<String, String> memberDisplayNames = new LinkedHashMap<>();
    private int currentPhaseIndex;
    private String currentPhaseName;
    private String synthesizedAnswer;
    private int depth;
    /** Current discussion round (1-based). Incremented by continueDiscussion(). */
    private int round = 1;
    private SharedTaskList taskList;
    /** Agents dynamically added during the discussion (recruited or created). */
    private List<AgentGroupConfiguration.GroupMember> dynamicMembers = Collections.synchronizedList(new ArrayList<>());
    /** Agent IDs created during this discussion (for lifecycle cleanup). */
    private List<String> createdAgentIds = Collections.synchronizedList(new ArrayList<>());
    /** Agent IDs explicitly retained by the creating agent (skip cleanup). */
    private Set<String> retainedAgentIds = ConcurrentHashMap.newKeySet();
    private int pausedAtPhaseIndex = -1;
    private int pausedTurnCount = 0;
    private String pausedPhaseName;
    private Instant pausedAt;
    private HitlPauseType hitlPauseType;
    /** Human-readable reason for the pause (from HITL gate). */
    private String hitlPauseReason;
    /** Timeout policy copied from config at pause time (Phase 6d). */
    private HitlTimeoutPolicy hitlTimeoutPolicy;
    /**
     * Approval timeout duration (ISO-8601) copied from config at pause time (Phase
     * 6d).
     */
    private String hitlApprovalTimeout;
    /**
     * Fingerprint of the task state at the previous TASK-granularity pause (#4). A
     * resume that re-pauses at the SAME phase with an identical fingerprint made no
     * progress — the discussion is failed instead of re-pausing, guaranteeing
     * termination of the pause→approve→pause loop. Null until the first TASK pause;
     * cleared on successful completion.
     */
    private String hitlLastPauseFingerprint;
    private Instant created;
    private Instant lastModified;

    /**
     * Transient reference to the group's dynamic agent configuration. Set by
     * {@code GroupConversationService.executeDiscussion()} at the start of a
     * discussion so that {@code executeAgentTurn()} can pass it to member agents
     * via context. Never persisted to MongoDB or serialized to REST.
     */
    @JsonIgnore
    private transient AgentGroupConfiguration.DynamicAgentConfig dynamicAgentConfig;

    /**
     * Transient attachments for this discussion. Set at fan-out by
     * {@code GroupConversationService} — inline files are materialized into the
     * blob store (owned by this group conversation) and each member conversation is
     * granted access. Not persisted to the transcript document; the blobs live in
     * {@code IAttachmentStore} bound to this conversation's id.
     */
    @JsonIgnore
    private transient List<Attachment> attachments;

    /**
     * A single entry in the discussion transcript. Each entry records one agent's
     * contribution during a specific phase.
     *
     * @param speakerAgentId
     *            the agent that produced this entry
     * @param speakerDisplayName
     *            human-readable name
     * @param content
     *            the agent's response text
     * @param phaseIndex
     *            which phase produced this (0-indexed)
     * @param phaseName
     *            human-readable phase name, e.g. "Peer Critique"
     * @param type
     *            entry classification
     * @param timestamp
     *            when the entry was created
     * @param errorReason
     *            error detail if type is ERROR or SKIPPED
     * @param targetAgentId
     *            who this entry addresses (for CRITIQUE), null if broadcast
     * @param signature
     *            Base64-encoded Ed25519 signature if the agent has
     *            {@code signInterAgentMessages=true}, null otherwise
     * @param signatureNonce
     *            UUID nonce for replay protection (null if unsigned)
     * @param signatureTimestampMs
     *            epoch milliseconds when the envelope was signed (null if unsigned)
     * @param signatureKeyVersion
     *            version of the signing key used (null if unsigned)
     */
    public record TranscriptEntry(String speakerAgentId, String speakerDisplayName, String content, int phaseIndex, String phaseName,
            TranscriptEntryType type, Instant timestamp, String errorReason, String targetAgentId, String signature,
            String signatureNonce, Long signatureTimestampMs, Integer signatureKeyVersion) {

        /**
         * Backward-compatible constructor without any signature fields.
         */
        public TranscriptEntry(String speakerAgentId, String speakerDisplayName, String content, int phaseIndex, String phaseName,
                TranscriptEntryType type, Instant timestamp, String errorReason, String targetAgentId) {
            this(speakerAgentId, speakerDisplayName, content, phaseIndex, phaseName,
                    type, timestamp, errorReason, targetAgentId, null, null, null, null);
        }

        /**
         * Backward-compatible constructor with signature only (no envelope data).
         */
        public TranscriptEntry(String speakerAgentId, String speakerDisplayName, String content, int phaseIndex, String phaseName,
                TranscriptEntryType type, Instant timestamp, String errorReason, String targetAgentId, String signature) {
            this(speakerAgentId, speakerDisplayName, content, phaseIndex, phaseName,
                    type, timestamp, errorReason, targetAgentId, signature, null, null, null);
        }

        /**
         * Check whether this entry has full envelope data (signature + nonce +
         * timestamp) suitable for cryptographic verification.
         */
        public boolean hasEnvelopeData() {
            return signature != null && signatureNonce != null && signatureTimestampMs != null;
        }
    }

    public enum TranscriptEntryType {
        QUESTION, OPINION, CRITIQUE, REVISION, CHALLENGE, DEFENSE, ARGUMENT, REBUTTAL, SYNTHESIS, ERROR, SKIPPED,
        /** Task plan output from the PLAN phase. */
        PLAN,
        /** Task execution result from the EXECUTE phase. */
        TASK_RESULT,
        /** Verification assessment from the VERIFY phase. */
        VERIFICATION,
        /** User-to-member or member-to-user follow-up exchange between rounds. */
        FOLLOW_UP
    }

    public enum GroupConversationState {
        CREATED, IN_PROGRESS, SYNTHESIZING, COMPLETED, FAILED,
        /** Discussion was cancelled before completion — HITL foundation (Phase 9b). */
        CANCELLED,
        /** Paused for human approval — HITL foundation (Phase 9b). */
        AWAITING_APPROVAL,
        /**
         * Terminal — member conversations ended, ephemeral agents cleaned up, no
         * further follow-ups.
         */
        CLOSED
    }

    public enum HitlPauseType {
        PHASE, TASK
    }

    // --- Getters/Setters ---

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public GroupConversationState getState() {
        return state;
    }

    public void setState(GroupConversationState state) {
        this.state = state;
    }

    public String getOriginalQuestion() {
        return originalQuestion;
    }

    public void setOriginalQuestion(String originalQuestion) {
        this.originalQuestion = originalQuestion;
    }

    public String getResumeQuestion() {
        return resumeQuestion;
    }

    public void setResumeQuestion(String resumeQuestion) {
        this.resumeQuestion = resumeQuestion;
    }

    public List<TranscriptEntry> getTranscript() {
        return transcript;
    }

    public void setTranscript(List<TranscriptEntry> transcript) {
        this.transcript = transcript != null
                ? Collections.synchronizedList(new ArrayList<>(transcript))
                : Collections.synchronizedList(new ArrayList<>());
    }

    public Map<String, String> getMemberConversationIds() {
        return memberConversationIds;
    }

    public void setMemberConversationIds(Map<String, String> memberConversationIds) {
        this.memberConversationIds = memberConversationIds != null
                ? new ConcurrentHashMap<>(memberConversationIds)
                : new ConcurrentHashMap<>();
    }

    public int getCurrentPhaseIndex() {
        return currentPhaseIndex;
    }

    public void setCurrentPhaseIndex(int currentPhaseIndex) {
        this.currentPhaseIndex = currentPhaseIndex;
    }

    public String getCurrentPhaseName() {
        return currentPhaseName;
    }

    public void setCurrentPhaseName(String currentPhaseName) {
        this.currentPhaseName = currentPhaseName;
    }

    public String getSynthesizedAnswer() {
        return synthesizedAnswer;
    }

    public void setSynthesizedAnswer(String synthesizedAnswer) {
        this.synthesizedAnswer = synthesizedAnswer;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public int getRound() {
        return round;
    }

    public void setRound(int round) {
        this.round = round;
    }

    public Instant getCreated() {
        return created;
    }

    public void setCreated(Instant created) {
        this.created = created;
    }

    public Instant getLastModified() {
        return lastModified;
    }

    public void setLastModified(Instant lastModified) {
        this.lastModified = lastModified;
    }

    public SharedTaskList getTaskList() {
        return taskList;
    }

    public void setTaskList(SharedTaskList taskList) {
        this.taskList = taskList;
    }

    public List<AgentGroupConfiguration.GroupMember> getDynamicMembers() {
        return dynamicMembers;
    }

    public void setDynamicMembers(List<AgentGroupConfiguration.GroupMember> dynamicMembers) {
        this.dynamicMembers = dynamicMembers != null
                ? Collections.synchronizedList(new ArrayList<>(dynamicMembers))
                : Collections.synchronizedList(new ArrayList<>());
    }

    /**
     * Add a dynamically recruited or created member to the conversation.
     * Thread-safe.
     */
    public void addDynamicMember(AgentGroupConfiguration.GroupMember member) {
        dynamicMembers.add(member);
    }

    public List<String> getCreatedAgentIds() {
        return createdAgentIds;
    }

    public void setCreatedAgentIds(List<String> createdAgentIds) {
        this.createdAgentIds = createdAgentIds != null
                ? Collections.synchronizedList(new ArrayList<>(createdAgentIds))
                : Collections.synchronizedList(new ArrayList<>());
    }

    public Set<String> getRetainedAgentIds() {
        return retainedAgentIds;
    }

    public void setRetainedAgentIds(Set<String> retainedAgentIds) {
        Set<String> newSet = ConcurrentHashMap.newKeySet();
        if (retainedAgentIds != null) {
            newSet.addAll(retainedAgentIds);
        }
        this.retainedAgentIds = newSet;
    }

    @JsonIgnore
    public AgentGroupConfiguration.DynamicAgentConfig getDynamicAgentConfig() {
        return dynamicAgentConfig;
    }

    public void setDynamicAgentConfig(AgentGroupConfiguration.DynamicAgentConfig dynamicAgentConfig) {
        this.dynamicAgentConfig = dynamicAgentConfig;
    }

    public Map<String, String> getMemberDisplayNames() {
        return Collections.unmodifiableMap(memberDisplayNames);
    }

    public void setMemberDisplayNames(Map<String, String> memberDisplayNames) {
        this.memberDisplayNames = memberDisplayNames != null
                ? new LinkedHashMap<>(memberDisplayNames)
                : new LinkedHashMap<>();
    }

    /**
     * Register a member's display name (agentId → displayName). Used at discussion
     * start to populate the map. This is the supported mutation path —
     * {@link #getMemberDisplayNames()} returns an unmodifiable view.
     */
    public void addMemberDisplayName(String agentId, String displayName) {
        this.memberDisplayNames.put(agentId, displayName);
    }

    /**
     * Computed property derived from {@link #state} — tells clients which
     * operations are available. It is serialized (so REST/MCP clients see it, and
     * it therefore also lands in the stored document), but {@code READ_ONLY} access
     * means it is never read back in: the value is always recomputed from
     * {@code state}, so a stale value in an old document cannot be trusted or used.
     */
    @JsonProperty(value = "availableActions", access = JsonProperty.Access.READ_ONLY)
    public List<String> getAvailableActions() {
        if (state == null) {
            return List.of();
        }
        return switch (state) {
            case COMPLETED -> List.of("followup", "continue", "close");
            // FAILED and CANCELLED are terminal but closeable — close ends member
            // conversations and reclaims ephemeral agents.
            case FAILED, CANCELLED -> List.of("close");
            case IN_PROGRESS, SYNTHESIZING, CREATED, AWAITING_APPROVAL -> List.of();
            case CLOSED -> List.of();
        };
    }

    @JsonIgnore
    public List<Attachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<Attachment> attachments) {
        this.attachments = attachments;
    }

    @JsonIgnore
    public boolean isPaused() {
        return pausedAt != null;
    }

    public int getPausedAtPhaseIndex() {
        return pausedAtPhaseIndex;
    }

    public void setPausedAtPhaseIndex(int pausedAtPhaseIndex) {
        this.pausedAtPhaseIndex = pausedAtPhaseIndex;
    }

    public int getPausedTurnCount() {
        return pausedTurnCount;
    }

    public void setPausedTurnCount(int pausedTurnCount) {
        this.pausedTurnCount = pausedTurnCount;
    }

    public String getPausedPhaseName() {
        return pausedPhaseName;
    }

    public void setPausedPhaseName(String pausedPhaseName) {
        this.pausedPhaseName = pausedPhaseName;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public void setPausedAt(Instant pausedAt) {
        this.pausedAt = pausedAt;
    }

    public HitlPauseType getHitlPauseType() {
        return hitlPauseType;
    }

    public void setHitlPauseType(HitlPauseType hitlPauseType) {
        this.hitlPauseType = hitlPauseType;
    }

    public String getHitlPauseReason() {
        return hitlPauseReason;
    }

    public void setHitlPauseReason(String hitlPauseReason) {
        this.hitlPauseReason = hitlPauseReason;
    }

    public HitlTimeoutPolicy getHitlTimeoutPolicy() {
        return hitlTimeoutPolicy;
    }

    public void setHitlTimeoutPolicy(HitlTimeoutPolicy hitlTimeoutPolicy) {
        this.hitlTimeoutPolicy = hitlTimeoutPolicy;
    }

    public String getHitlApprovalTimeout() {
        return hitlApprovalTimeout;
    }

    public void setHitlApprovalTimeout(String hitlApprovalTimeout) {
        this.hitlApprovalTimeout = hitlApprovalTimeout;
    }

    public String getHitlLastPauseFingerprint() {
        return hitlLastPauseFingerprint;
    }

    public void setHitlLastPauseFingerprint(String hitlLastPauseFingerprint) {
        this.hitlLastPauseFingerprint = hitlLastPauseFingerprint;
    }
}
