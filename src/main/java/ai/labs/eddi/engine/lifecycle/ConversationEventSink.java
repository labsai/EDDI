/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle;

import java.util.Map;

/**
 * Callback interface for streaming conversation events via SSE.
 * <p>
 * Two layers of events are supported:
 * <ul>
 * <li><strong>Workflow step events</strong> —
 * {@link #onTaskStart}/{@link #onTaskComplete} emitted by
 * {@code LifecycleManager} around each task execution</li>
 * <li><strong>LLM token events</strong> — {@link #onToken} emitted by
 * {@code LlmTask} during streaming chat completion</li>
 * </ul>
 * <p>
 * When no streaming is requested (standard {@code say} endpoint), no sink is
 * created and the workflow operates exactly as before.
 */
public interface ConversationEventSink {

    /**
     * Called before a lifecycle task begins execution.
     *
     * @param taskId
     *            the task identifier (e.g. "ai.labs.parser")
     * @param taskType
     *            the task type (e.g. "expressions")
     * @param index
     *            0-based position in the workflow
     */
    void onTaskStart(String taskId, String taskType, int index);

    /**
     * Called after a lifecycle task completes execution.
     *
     * @param taskId
     *            the task identifier
     * @param taskType
     *            the task type
     * @param durationMs
     *            execution time in milliseconds
     * @param summary
     *            optional task-specific summary data (e.g. emitted actions). May be
     *            empty, never null.
     */
    void onTaskComplete(String taskId, String taskType, long durationMs, Map<String, Object> summary);

    /**
     * Called for each LLM token during streaming chat completion.
     *
     * @param token
     *            the partial response token
     */
    void onToken(String token);

    /**
     * Called when a model cascade step starts execution.
     *
     * @param stepIndex
     *            0-based step position in the cascade
     * @param modelType
     *            model provider (e.g. "openai", "anthropic")
     * @param modelName
     *            specific model name (e.g. "gpt-4o-mini")
     * @param totalSteps
     *            total number of cascade steps
     */
    default void onCascadeStepStart(int stepIndex, String modelType, String modelName, int totalSteps) {
    }

    /**
     * Called when a cascade step completes and escalation is triggered.
     *
     * @param fromStep
     *            step index that was just evaluated
     * @param toStep
     *            step index being escalated to
     * @param confidence
     *            evaluated confidence score (0.0–1.0)
     * @param threshold
     *            threshold that was not met
     * @param reason
     *            reason for escalation: "low_confidence", "timeout", "error",
     *            "retryable_error"
     * @param durationMs
     *            time spent on the completed step
     */
    default void onCascadeEscalation(int fromStep, int toStep, double confidence, double threshold, String reason, long durationMs) {
    }

    /**
     * Called when the entire conversation step has completed successfully. The SSE
     * endpoint should send the final snapshot and close the stream.
     */
    void onComplete();

    /**
     * Called when an error occurs during conversation processing.
     *
     * @param error
     *            the error that occurred
     */
    void onError(Throwable error);
}
