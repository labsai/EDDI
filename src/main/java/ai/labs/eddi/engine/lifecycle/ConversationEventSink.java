package ai.labs.eddi.engine.lifecycle;

import java.util.Map;

/**
 * Callback interface for streaming conversation events via SSE.
 * <p>
 * Two layers of events are supported:
 * <ul>
 * <li><strong>Workflow step events</strong> —
 * {@link #onTaskStart}/{@link #onTaskComplete}
 * emitted by {@code LifecycleManager} around each task execution</li>
 * <li><strong>LLM token events</strong> — {@link #onToken} emitted by
 * {@code LangchainTask} during streaming chat completion</li>
 * </ul>
 * <p>
 * When no streaming is requested (standard {@code say} endpoint), no sink is
 * created and the workflow operates exactly as before.
 */
public interface ConversationEventSink {

    /**
     * Called before a lifecycle task begins execution.
     *
     * @param taskId   the task identifier (e.g. "ai.labs.parser")
     * @param taskType the task type (e.g. "expressions")
     * @param index    0-based position in the workflow
     */
    void onTaskStart(String taskId, String taskType, int index);

    /**
     * Called after a lifecycle task completes execution.
     *
     * @param taskId     the task identifier
     * @param taskType   the task type
     * @param durationMs execution time in milliseconds
     * @param summary    optional task-specific summary data (e.g. emitted
     *                   actions). May be empty, never null.
     */
    void onTaskComplete(String taskId, String taskType, long durationMs, Map<String, Object> summary);

    /**
     * Called for each LLM token during streaming chat completion.
     *
     * @param token the partial response token
     */
    void onToken(String token);

    /**
     * Called when the entire conversation step has completed successfully.
     * The SSE endpoint should send the final snapshot and close the stream.
     */
    void onComplete();

    /**
     * Called when an error occurs during conversation processing.
     *
     * @param error the error that occurred
     */
    void onError(Throwable error);
}
