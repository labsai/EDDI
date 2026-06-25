/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
    private List<TranscriptEntry> transcript = new ArrayList<>();
    private Map<String, String> memberConversationIds = new ConcurrentHashMap<>();
    private int currentPhaseIndex;
    private String currentPhaseName;
    private String synthesizedAnswer;
    private int depth;
    private SharedTaskList taskList;
    /** Agents dynamically added during the discussion (recruited or created). */
    private List<AgentGroupConfiguration.GroupMember> dynamicMembers = Collections.synchronizedList(new ArrayList<>());
    /** Agent IDs created during this discussion (for lifecycle cleanup). */
    private List<String> createdAgentIds = Collections.synchronizedList(new ArrayList<>());
    /** Agent IDs explicitly retained by the creating agent (skip cleanup). */
    private Set<String> retainedAgentIds = ConcurrentHashMap.newKeySet();
    private Instant created;
    private Instant lastModified;

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
        VERIFICATION
    }

    public enum GroupConversationState {
        CREATED, IN_PROGRESS, SYNTHESIZING, COMPLETED, FAILED,
        /** Paused for human approval — HITL foundation (Phase 9b). */
        AWAITING_APPROVAL
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

    public List<TranscriptEntry> getTranscript() {
        return transcript;
    }

    public void setTranscript(List<TranscriptEntry> transcript) {
        this.transcript = transcript;
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
}
