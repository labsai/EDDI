/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.engine.audit.IAuditEntryCollector;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.configs.properties.model.Property;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

/**
 * @author ginccc
 */
public interface IConversationMemory extends Serializable {
    String getConversationId();

    String getAgentId();

    Integer getAgentVersion();

    String getUserId();

    List<ConversationOutput> getConversationOutputs();

    IConversationProperties getConversationProperties();

    IWritableConversationStep getCurrentStep();

    IConversationStepStack getPreviousSteps();

    IConversationStepStack getAllSteps();

    int size();

    void undoLastStep();

    boolean isUndoAvailable();

    boolean isRedoAvailable();

    void redoLastStep();

    ConversationState getConversationState();

    void setConversationState(ConversationState conversationState);

    Stack<IConversationStep> getRedoCache();

    /**
     * Get the event sink for streaming SSE events. Returns {@code null} when no
     * streaming is requested (standard say endpoint).
     */
    default ConversationEventSink getEventSink() {
        return null;
    }

    /**
     * Set the event sink for this conversation turn. Called from
     * {@code ConversationService.sayStreaming()} before lifecycle execution.
     */
    default void setEventSink(ConversationEventSink eventSink) {
        // no-op by default
    }

    /**
     * Get the audit entry collector for this conversation turn. Returns
     * {@code null} when auditing is disabled.
     */
    default IAuditEntryCollector getAuditCollector() {
        return null;
    }

    /**
     * Set the audit entry collector for this conversation turn. Called from
     * {@code ConversationService} before lifecycle execution.
     */
    default void setAuditCollector(IAuditEntryCollector auditCollector) {
        // no-op by default
    }

    /**
     * Get the user memory configuration for this conversation. Returns {@code null}
     * when persistent user memory is disabled.
     */
    default AgentConfiguration.UserMemoryConfig getUserMemoryConfig() {
        return null;
    }

    /**
     * Set the user memory configuration. Called from {@code Conversation.init()}
     * when the agent has user memory enabled.
     */
    default void setUserMemoryConfig(AgentConfiguration.UserMemoryConfig config) {
        // no-op by default
    }

    /**
     * Get the memory policy configuration for this conversation. Returns
     * {@code null} when no memory policy is configured on the agent.
     *
     * @since 6.0.0
     */
    default AgentConfiguration.MemoryPolicy getMemoryPolicy() {
        return null;
    }

    /**
     * Set the memory policy configuration. Called from {@code Conversation.init()}
     * when the agent has a memory policy configured.
     *
     * @since 6.0.0
     */
    default void setMemoryPolicy(AgentConfiguration.MemoryPolicy memoryPolicy) {
        // no-op by default
    }

    /**
     * Mark this conversation as cancelled. Cooperative cancellation flag for HITL
     * framework — tasks should check {@link #isCancelled()} and abort gracefully.
     *
     * @since 6.0.0
     */
    default void setCancelled(boolean cancelled) {
    }

    /**
     * Check whether this conversation has been cancelled.
     *
     * @since 6.0.0
     */
    default boolean isCancelled() {
        return false;
    }

    // === HITL pause bookmark ===

    /** Workflow ID where the pipeline paused. */
    default String getHitlPausedWorkflowId() {
        return null;
    }
    default void setHitlPausedWorkflowId(String workflowId) {
    }

    /**
     * Absolute task index within the paused workflow (the task that triggered
     * PAUSE).
     */
    default int getHitlPausedAbsoluteTaskIndex() {
        return -1;
    }
    default void setHitlPausedAbsoluteTaskIndex(int index) {
    }

    /** Timestamp when the conversation was paused. */
    default Instant getHitlPausedAt() {
        return null;
    }
    default void setHitlPausedAt(Instant pausedAt) {
    }

    /** Human-readable reason for the pause. */
    default String getHitlPauseReason() {
        return null;
    }
    default void setHitlPauseReason(String reason) {
    }

    /**
     * Timeout policy — a {@code HitlTimeoutPolicy} enum name: WAIT_INDEFINITELY,
     * AUTO_APPROVE, AUTO_REJECT, or ABORT.
     */
    default String getHitlTimeoutPolicy() {
        return null;
    }
    default void setHitlTimeoutPolicy(String policy) {
    }

    /** Approval timeout duration (ISO-8601, e.g. "PT30M"). */
    default String getHitlApprovalTimeout() {
        return null;
    }
    default void setHitlApprovalTimeout(String timeout) {
    }

    // === Tool-level HITL (tool-call pause) ===

    /**
     * Pause-type discriminator: null/"RULE" = behavior-rule pause, "TOOL_CALL" =
     * gated tool pause.
     */
    default String getHitlPauseType() {
        return null;
    }
    default void setHitlPauseType(String pauseType) {
    }

    /**
     * The interrupted tool-call batch (durable); null unless a tool pause is
     * active.
     */
    default ai.labs.eddi.engine.memory.model.PendingToolCallBatch getHitlPendingToolCalls() {
        return null;
    }
    default void setHitlPendingToolCalls(ai.labs.eddi.engine.memory.model.PendingToolCallBatch batch) {
    }

    /**
     * Agent-level tool-approval config carried onto memory at conversation start
     * (NOT persisted).
     */
    default ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig getAgentToolApprovalsConfig() {
        return null;
    }
    default void setAgentToolApprovalsConfig(ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig config) {
    }

    /**
     * The human decision being applied during an in-JVM tool-pause resume (NOT
     * persisted).
     */
    default ai.labs.eddi.engine.lifecycle.model.HitlDecision getHitlResumeDecision() {
        return null;
    }
    default void setHitlResumeDecision(ai.labs.eddi.engine.lifecycle.model.HitlDecision decision) {
    }

    interface IConversationStepStack {
        <T> IData<T> getLatestData(String key);

        /** Type-safe variant of {@link #getLatestData(String)}. */
        <T> IData<T> getLatestData(MemoryKey<T> key);

        <T> List<List<IData<T>>> getAllData(String prefix);

        int size();

        IConversationStep get(int index);

        IConversationStep peek();

        <T> List<IData<T>> getAllLatestData(String prefix);
    }

    interface IConversationStep extends Serializable {
        <T> IData<T> getData(String key);

        /** Type-safe variant of {@link #getData(String)}. */
        <T> IData<T> getData(MemoryKey<T> key);

        /**
         * Convenience method: returns the value directly, or {@code null} if not
         * present. Equivalent to
         * {@code getData(key) != null ? getData(key).getResult() : null}.
         */
        <T> T get(MemoryKey<T> key);

        <T> List<IData<T>> getAllData(String prefix);

        Set<String> getAllKeys();

        List<IData<?>> getAllElements();

        int size();

        boolean isEmpty();

        <T> IData<T> getLatestData(String prefix);

        /** Type-safe variant of {@link #getLatestData(String)}. */
        <T> IData<T> getLatestData(MemoryKey<T> key);

        ConversationOutput getConversationOutput();
    }

    interface IWritableConversationStep extends IConversationStep {
        void storeData(IData<?> element);

        /**
         * Type-safe store: creates a {@link IData} wrapper, sets the public flag from
         * the key, and stores it in this step.
         */
        <T> void set(MemoryKey<T> key, T value);

        void removeData(String key);

        void setCurrentWorkflowId(String workflowId);

        void resetConversationOutput(String rootKey);

        /**
         * Removes the first occurrence of {@code value} from the list stored under
         * {@code key} in the conversation output, leaving every other entry intact.
         * No-op when the key is absent, holds a non-list, or the value is not present.
         * Used to drop a single transient entry (e.g. the HITL pending-approval
         * placeholder on resume) without clearing legitimate earlier output.
         */
        void removeConversationOutputListItem(String key, Object value);

        /**
         * Removes an entire conversation-output entry by key (e.g. the transient
         * {@code hitl:status} pause marker on resume).
         */
        void removeConversationOutput(String key);

        void addConversationOutputObject(String key, Object value);

        void replaceConversationOutputObject(String key, Object value, Object replace);

        void addConversationOutputString(String key, String value);

        void addConversationOutputList(String key, List<?> list);

        void addConversationOutputMap(String key, Map<String, Object> map);
    }

    interface IConversationProperties extends Map<String, Property> {
        Map<String, Object> toMap();
    }
}
