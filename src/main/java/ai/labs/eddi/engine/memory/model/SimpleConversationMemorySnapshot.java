/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.TurnError;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

public class SimpleConversationMemorySnapshot {
    private String conversationId;
    private String agentId;
    private Integer agentVersion;
    /**
     * The agent's display name, for the people in the conversation. Set only on a
     * REST conversation read, and only for a caller who may use the agent;
     * {@code null} everywhere else. See
     * {@code ai.labs.eddi.engine.internal.AgentDisplayNameResolver}.
     */
    private String agentName;
    private String userId;
    private Deployment.Environment environment;
    private ConversationState conversationState;
    /**
     * Why an ENDED conversation ended, when known — see
     * {@link ConversationMemorySnapshot#getEndReason()}. Lets a client tell "this
     * assistant was updated" apart from any other end.
     */
    private String endReason;
    /**
     * Why the turn failed, when {@link #conversationState} is {@code ERROR} and the
     * failing task reported a reason; {@code null} (and therefore absent from the
     * JSON) otherwise. The same sanitized description the streaming endpoint sends
     * as its {@code task_failed} event, so a plain REST caller no longer receives
     * HTTP 200 with an ERROR state and nothing to say why. The full per-task digest
     * stays in the turn's {@code conversationOutputs[…].taskErrors}.
     * <p>
     * It is the one {@link TurnError} object everywhere: the failing task
     * ({@code taskId}, {@code taskType}, {@code errorType}) together with the
     * machine-readable {@code code}, {@code retryable} and {@code retryAfterMs}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private TurnError error;
    private Instant hitlPausedAt;
    /**
     * Task 13: HITL pause type ("TOOL_CALL" | "RULE" | null) carried onto the
     * simple snapshot so delegated/MCP surfaces and the group member-turn path can
     * additively surface it. Mirrors
     * {@code ConversationMemorySnapshot.hitlPauseType}.
     */
    private String hitlPauseType;
    /**
     * Task 13: the gated tool-call batch for a TOOL_CALL pause — consumers read
     * tool NAMES only (never arguments). Null for RULE pauses. Mirrors
     * {@code ConversationMemorySnapshot.hitlPendingToolCalls}.
     */
    private PendingToolCallBatch hitlPendingToolCalls;
    /**
     * The conversation's own tracked dollar cost so far: the sum of
     * {@code audit:cost} over <em>every</em> step, not just the steps this
     * projection returns. {@code null} when no step carried a cost.
     * <p>
     * In-process only, hence {@link JsonIgnore}. {@code audit:cost} is a per-step
     * value and the {@code returnDetailed} projection withholds every
     * {@code audit:*} key from callers, so an in-process consumer that needs a
     * conversation's spend (a group member turn, for the group cost ceiling) had no
     * way to read it. Carrying the total here gives that consumer a running total
     * without widening what any REST, MCP or SSE caller receives.
     */
    @JsonIgnore
    private Double conversationCost;
    private boolean undoAvailable;
    private boolean redoAvailable;
    private List<ConversationOutput> conversationOutputs = new LinkedList<>();
    /**
     * Declared as the plain {@link Map}, matching the sibling
     * {@code ConversationMemorySnapshot}, NOT as
     * {@code IConversationMemory.IConversationProperties}.
     * <p>
     * The wire format is the same either way — {@code ConversationProperties} is a
     * {@code LinkedHashMap} — but the interface broke the generated OpenAPI
     * document: smallrye emitted {@code $ref: IConversationProperties} for it and
     * never generated the schema, leaving this the one dangling reference in the
     * spec. Any client that dereferences (swagger-parser does, so EDDI's own
     * {@code setup-api} wizard did while reading EDDI's spec) errors on it.
     */
    private Map<String, Property> conversationProperties = new LinkedHashMap<>();
    private List<SimpleConversationStep> conversationSteps = new LinkedList<>();

    public static class SimpleConversationStep {
        private List<ConversationStepData> conversationStep = new LinkedList<>();
        private Date timestamp;

        public List<ConversationStepData> getConversationStep() {
            return conversationStep;
        }

        public void setConversationStep(List<ConversationStepData> conversationStep) {
            this.conversationStep = conversationStep;
        }

        public Date getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Date timestamp) {
            this.timestamp = timestamp;
        }
    }

    public static class ConversationStepData {
        private String key;
        private Object value;
        private Date timestamp;
        private String originWorkflowId;

        public ConversationStepData(String key, Object value, Date timestamp, String originWorkflowId) {
            this.key = key;
            this.value = value;
            this.timestamp = timestamp;
            this.originWorkflowId = originWorkflowId;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public Object getValue() {
            return value;
        }

        public void setValue(Object value) {
            this.value = value;
        }

        public Date getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Date timestamp) {
            this.timestamp = timestamp;
        }

        public String getOriginWorkflowId() {
            return originWorkflowId;
        }

        public void setOriginWorkflowId(String originWorkflowId) {
            this.originWorkflowId = originWorkflowId;
        }
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

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public Integer getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(Integer agentVersion) {
        this.agentVersion = agentVersion;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Deployment.Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Deployment.Environment environment) {
        this.environment = environment;
    }

    public ConversationState getConversationState() {
        return conversationState;
    }

    public void setConversationState(ConversationState conversationState) {
        this.conversationState = conversationState;
    }

    public String getEndReason() {
        return endReason;
    }

    public TurnError getError() {
        return error;
    }

    public void setError(TurnError error) {
        this.error = error;
    }

    public void setEndReason(String endReason) {
        this.endReason = endReason;
    }

    public Instant getHitlPausedAt() {
        return hitlPausedAt;
    }

    public void setHitlPausedAt(Instant hitlPausedAt) {
        this.hitlPausedAt = hitlPausedAt;
    }

    public String getHitlPauseType() {
        return hitlPauseType;
    }

    public void setHitlPauseType(String hitlPauseType) {
        this.hitlPauseType = hitlPauseType;
    }

    public PendingToolCallBatch getHitlPendingToolCalls() {
        return hitlPendingToolCalls;
    }

    public void setHitlPendingToolCalls(PendingToolCallBatch hitlPendingToolCalls) {
        this.hitlPendingToolCalls = hitlPendingToolCalls;
    }

    public boolean isUndoAvailable() {
        return undoAvailable;
    }

    public void setUndoAvailable(boolean undoAvailable) {
        this.undoAvailable = undoAvailable;
    }

    @JsonIgnore
    public Double getConversationCost() {
        return conversationCost;
    }

    @JsonIgnore
    public void setConversationCost(Double conversationCost) {
        this.conversationCost = conversationCost;
    }

    public boolean isRedoAvailable() {
        return redoAvailable;
    }

    public void setRedoAvailable(boolean redoAvailable) {
        this.redoAvailable = redoAvailable;
    }

    public List<ConversationOutput> getConversationOutputs() {
        return conversationOutputs;
    }

    public void setConversationOutputs(List<ConversationOutput> conversationOutputs) {
        this.conversationOutputs = conversationOutputs;
    }

    public Map<String, Property> getConversationProperties() {
        return conversationProperties;
    }

    public void setConversationProperties(Map<String, Property> conversationProperties) {
        this.conversationProperties = conversationProperties;
    }

    public List<SimpleConversationStep> getConversationSteps() {
        return conversationSteps;
    }

    public void setConversationSteps(List<SimpleConversationStep> conversationSteps) {
        this.conversationSteps = conversationSteps;
    }
}
