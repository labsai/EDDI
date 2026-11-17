/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle;

import java.util.Map;

/**
 * Callback interface for streaming conversation events via SSE.
 * <p>
 * The events supported:
 * <ul>
 * <li><strong>Workflow step events</strong> —
 * {@link #onTaskStart}/{@link #onTaskComplete} emitted by
 * {@code LifecycleManager} around each task execution</li>
 * <li><strong>LLM token events</strong> — {@link #onToken} emitted by
 * {@code LlmTask} during streaming chat completion</li>
 * <li><strong>Optional events</strong> (no-op by default) — tool calls and
 * results, cascade steps and escalations, LLM retries, guardrail triggers and
 * task failures</li>
 * <li><strong>Terminal events</strong> — {@link #onComplete} or
 * {@link #onError}</li>
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
    void onTaskStart(TaskId taskId, String taskType, int index);

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
    void onTaskComplete(TaskId taskId, String taskType, long durationMs, Map<String, Object> summary);

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
     * Called when the LLM task is about to ask the same model again because its
     * reply was unusable (the {@code retry} action of {@code responseValidation}).
     * Tokens of the earlier attempt were buffered, not streamed, so a UI can show
     * "retrying..." without having to retract text.
     *
     * @param reason
     *            why: {@code empty}, {@code truncated}, {@code content_filter},
     *            {@code invalid_json}, {@code schema_mismatch} or
     *            {@code context_too_long}
     * @param attempt
     *            1-based number of the re-ask
     */
    default void onLlmRetry(String reason, int attempt) {
    }

    /**
     * Called when an input or output guardrail of an LLM task fired (WS4). Sent
     * before the turn's answer is delivered; with a {@code block} the answer that
     * follows is the guardrail's block message.
     *
     * @param type
     *            the guardrail type, e.g. {@code pii-redaction}
     * @param phase
     *            {@code input} or {@code output}
     * @param action
     *            what was done: {@code block}, {@code redact}, {@code reprompt} or
     *            {@code warn}
     * @param taskId
     *            the id of the LLM task whose guardrail fired
     */
    default void onGuardrailTriggered(String type, String phase, String action, String taskId) {
    }

    /**
     * Called when a pipeline task fails during execution. Provides structured error
     * information for admin monitoring.
     */
    default void onTaskFailed(TaskId taskId, String taskType, long durationMs,
                              String errorType, String errorSummary) {
    }

    /**
     * Called by the tool loop as it is about to execute one tool call — the live
     * "Using {tool}…" signal for streaming clients. Only the NAME travels:
     * arguments may hold user data and are already delivered, redacted, in the task
     * summary's toolTrace at turn end. Default no-op so non-streaming sinks are
     * unaffected.
     */
    default void onToolCall(String toolName) {
    }

    /**
     * As {@link #onToolCall(String)}, with the model's call id and the designer's
     * optional {@link ToolDisplay} (the {@code toolDisplay} task setting). The id
     * lets a client pair this call with its {@link #onToolResult}. Defaults to the
     * name-only form, so a sink that only knows that one keeps working.
     *
     * @param display
     *            null unless the task configures a display for this tool
     */
    default void onToolCall(String toolName, String callId, ToolDisplay display) {
        onToolCall(toolName);
    }

    /**
     * One tool call has finished — sent exactly once for every call, and always
     * after that call's {@link #onToolCall} on the same stream (a gated call that a
     * resume settles without running is announced immediately before its result).
     * Never the raw result: results may hold user data and are delivered, redacted,
     * in the task summary's toolTrace; the five-argument form below adds the start
     * of that same redacted text.
     *
     * @param status
     *            {@code ok}, {@code error}, {@code not_executed} (refused or
     *            skipped before the tool ran) or {@code rejected} (a human declined
     *            it)
     */
    default void onToolResult(String toolName, String callId, String status, long durationMs) {
    }

    /**
     * As {@link #onToolResult(String, String, String, long)}, with a short summary
     * of the result (B6) — the form the tool loop calls. The summary is the start
     * of the result, or of the refusal (budget, quota, deadline), redacted exactly
     * like the turn-end {@code toolTrace} ({@code ToolTraceRedaction.summary}), so
     * it shows nothing the task summary does not, and at most
     * {@code ToolTraceRedaction.MAX_SUMMARY_LENGTH} characters. Defaults to the
     * status-only form, so a sink that only knows that one keeps working.
     *
     * @param summary
     *            never null; empty when there is nothing to show (a call a resume
     *            settled without running)
     */
    default void onToolResult(String toolName, String callId, String status, long durationMs, String summary) {
        onToolResult(toolName, callId, status, durationMs);
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
