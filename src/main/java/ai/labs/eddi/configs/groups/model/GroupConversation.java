package ai.labs.eddi.configs.groups.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private Map<String, String> memberConversationIds = new LinkedHashMap<>();
    private int currentPhaseIndex;
    private String currentPhaseName;
    private String synthesizedAnswer;
    private int depth;
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
     */
    public record TranscriptEntry(String speakerAgentId, String speakerDisplayName, String content, int phaseIndex, String phaseName,
            TranscriptEntryType type, Instant timestamp, String errorReason, String targetAgentId) {
    }

    public enum TranscriptEntryType {
        QUESTION, OPINION, CRITIQUE, REVISION, CHALLENGE, DEFENSE, ARGUMENT, REBUTTAL, SYNTHESIS, ERROR, SKIPPED
    }

    public enum GroupConversationState {
        CREATED, IN_PROGRESS, SYNTHESIZING, COMPLETED, FAILED
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
        this.memberConversationIds = memberConversationIds;
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
}
