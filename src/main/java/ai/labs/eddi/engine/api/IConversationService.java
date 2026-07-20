/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Core conversation lifecycle service — extracted from RestAgentEngine. All
 * conversation operations are available here without JAX-RS dependencies.
 *
 * @author ginccc
 */
public interface IConversationService {

    record ConversationResult(String conversationId, URI conversationUri) {
    }

    /**
     * Start a new conversation with the latest ready Agent version.
     *
     * @throws AgentNotReadyException
     *             if no version of the Agent is deployed
     * @throws ResourceStoreException
     *             on persistence failures
     * @throws ResourceNotFoundException
     *             if referenced resources are not found
     */
    ConversationResult startConversation(Environment environment, String agentId, String userId, Map<String, Context> context)
            throws AgentNotReadyException, ResourceStoreException, ResourceNotFoundException;

    /**
     * End a conversation by setting its state to ENDED. Delegates to
     * {@link #endConversation(String, String)} with a {@code system:end} actor —
     * use the two-arg overload from an authenticated context so a pause-terminating
     * end is attributable in the audit trail.
     */
    void endConversation(String conversationId);

    /**
     * End a conversation with actor attribution. When the conversation was
     * {@code AWAITING_HUMAN}, ending it terminally resolves the pending approval:
     * the timeout schedule is disarmed, the bookmark cleared, an
     * {@code hitl.approval} cancellation is audited with {@code endedBy}, and a
     * {@link ai.labs.eddi.engine.events.HitlResumeCompletedEvent} (null verdict,
     * terminal snapshot) is fired for channel observers — so every
     * pause-terminating path is attributed and rendered. {@code endedBy} is a
     * principal name or a {@code system:*} identifier for automated ends.
     */
    void endConversation(String conversationId, String endedBy);

    /**
     * Get the current state of a conversation (from cache or DB).
     *
     * @throws ConversationNotFoundException
     *             if no conversation exists with the given ID
     */
    ConversationState getConversationState(Environment environment, String conversationId);

    /**
     * Read a conversation memory snapshot.
     *
     * @throws AgentMismatchException
     *             if the conversationId does not belong to the given agentId
     * @throws ResourceStoreException
     *             on persistence failures
     * @throws ResourceNotFoundException
     *             if the conversation is not found
     */
    SimpleConversationMemorySnapshot readConversation(Environment environment, String agentId, String conversationId, Boolean returnDetailed,
                                                      Boolean returnCurrentStepOnly, List<String> returningFields)
            throws AgentMismatchException, ResourceStoreException, ResourceNotFoundException;

    /**
     * Read conversation log in text or structured format.
     *
     * @throws ResourceStoreException
     *             on persistence failures
     * @throws ResourceNotFoundException
     *             if the conversation is not found
     */
    ConversationLogResult readConversationLog(String conversationId, String outputType, Integer logSize)
            throws ResourceStoreException, ResourceNotFoundException;

    record ConversationLogResult(Object content, String mediaType) {
    }

    /**
     * Process a user input (say) or rerun the last step. Results are delivered via
     * the responseHandler callback.
     * <p>
     * <b>Handler contract:</b> exactly one handler method is invoked on every
     * accepted call — {@code onComplete} when the turn executed (the snapshot may
     * carry state {@code AWAITING_HUMAN} if THIS turn paused the conversation), or
     * {@code onSkipped} when the turn was dropped without consuming the input
     * (conversation paused or busy at execution time). Calls rejected up-front
     * throw instead and never invoke the handler.
     *
     * @throws AgentMismatchException
     *             if agentId doesn't match the conversation's agent
     * @throws ConversationEndedException
     *             if the conversation has already ended
     * @throws ConversationAwaitingApprovalException
     *             if the conversation is awaiting a human decision — the input is
     *             NOT consumed; a reviewer must resolve the pause via
     *             {@link #resumeConversation} (or cancel) first
     * @throws ResourceNotFoundException
     *             if the conversation is not found
     */
    void say(Environment environment, String agentId, String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
             List<String> returningFields, InputData inputData, boolean rerunOnly, ConversationResponseHandler responseHandler)
            throws Exception;

    @FunctionalInterface
    interface ConversationResponseHandler {
        void onComplete(SimpleConversationMemorySnapshot snapshot);

        /**
         * The turn was dropped WITHOUT consuming the input — the conversation was
         * paused ({@code AWAITING_HUMAN}) or busy ({@code IN_PROGRESS}) by the time the
         * queued turn executed. The snapshot reflects the persisted state; the user's
         * message was not processed. Defaults to {@link #onComplete} so callback
         * consumers that only inspect the snapshot state keep working.
         */
        default void onSkipped(SimpleConversationMemorySnapshot snapshot) {
            onComplete(snapshot);
        }
    }

    /**
     * Callback for streaming conversation responses via SSE.
     */
    interface StreamingResponseHandler {
        void onTaskStart(TaskId taskId, String taskType, int index);

        void onTaskComplete(TaskId taskId, String taskType, long durationMs, Map<String, Object> summary);

        void onToken(String token);

        /**
         * Called when a multi-model cascade step starts. Default no-op so handlers that
         * do not care about cascade events are unaffected.
         */
        default void onCascadeStepStart(int stepIndex, String modelType, String modelName, int totalSteps) {
        }

        /**
         * Called when a cascade step is evaluated and escalation is triggered. Default
         * no-op.
         */
        default void onCascadeEscalation(int fromStep, int toStep, double confidence, double threshold, String reason, long durationMs) {
        }

        void onComplete(SimpleConversationMemorySnapshot snapshot);

        void onError(Throwable error);

        /**
         * The turn was dropped without consuming the input (see
         * {@link ConversationResponseHandler#onSkipped}). Defaults to
         * {@link #onComplete} so the stream always terminates.
         */
        default void onSkipped(SimpleConversationMemorySnapshot snapshot) {
            onComplete(snapshot);
        }
    }

    /**
     * Process user input with SSE streaming. Token and step events are delivered
     * via the streamingHandler callback.
     */
    void sayStreaming(Environment environment, String agentId, String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                      List<String> returningFields, InputData inputData, StreamingResponseHandler streamingHandler)
            throws Exception;

    Boolean isUndoAvailable(Environment environment, String agentId, String conversationId) throws ResourceStoreException, ResourceNotFoundException;

    /**
     * @return true if undo was performed, false if not available
     */
    boolean undo(Environment environment, String agentId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException, AgentMismatchException;

    Boolean isRedoAvailable(Environment environment, String agentId, String conversationId) throws ResourceStoreException, ResourceNotFoundException;

    /**
     * @return true if redo was performed, false if not available
     */
    boolean redo(Environment environment, String agentId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException, AgentMismatchException;

    // --- Conversation-only overloads (resolve agentId + env from stored
    // conversation) ---

    /**
     * Read a conversation memory snapshot — resolves agentId + environment from the
     * stored record.
     */
    SimpleConversationMemorySnapshot readConversation(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                                                      List<String> returningFields)
            throws ResourceStoreException, ResourceNotFoundException;

    /**
     * Get the conversation state — no environment needed.
     */
    ConversationState getConversationState(String conversationId);

    /**
     * Process user input — resolves agentId + environment from the stored record.
     */
    void say(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly, List<String> returningFields, InputData inputData,
             boolean rerunOnly, ConversationResponseHandler responseHandler)
            throws Exception;

    /**
     * Process user input with SSE streaming — resolves agentId + environment from
     * the stored record.
     */
    void sayStreaming(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly, List<String> returningFields, InputData inputData,
                      StreamingResponseHandler streamingHandler)
            throws Exception;

    Boolean isUndoAvailable(String conversationId) throws ResourceStoreException, ResourceNotFoundException;

    boolean undo(String conversationId) throws ResourceStoreException, ResourceNotFoundException;

    Boolean isRedoAvailable(String conversationId) throws ResourceStoreException, ResourceNotFoundException;

    boolean redo(String conversationId) throws ResourceStoreException, ResourceNotFoundException;

    // --- HITL lifecycle ---

    /**
     * Outcome of a cancel request — lets the REST layer report honestly instead of
     * returning 200 unconditionally.
     */
    enum CancelOutcome {
        /** A paused/running conversation was cancelled (or signalled to stop). */
        CANCELLED,
        /**
         * Conversation exists but is neither paused nor executing (READY/ENDED/...).
         */
        NOTHING_TO_CANCEL,
        /** No conversation with that id exists. */
        NOT_FOUND
    }

    /**
     * Cancel a conversation with the given control signal. Cancels a paused
     * (AWAITING_HUMAN) conversation, or signals a turn executing on this pod to
     * stop at the next task boundary.
     *
     * @param conversationId
     *            the conversation to cancel
     * @param mode
     *            the cancellation mode (CANCEL_GRACEFUL or CANCEL_IMMEDIATE —
     *            IMMEDIATE currently degrades to graceful on this surface)
     * @return what actually happened — see {@link CancelOutcome}
     * @throws ResourceStoreException
     *             on persistence failures
     */
    default CancelOutcome cancelConversation(String conversationId,
                                             ai.labs.eddi.engine.lifecycle.model.ControlSignal mode)
            throws ResourceStoreException {
        return cancelConversation(conversationId, mode, null);
    }

    /**
     * Cancel with actor attribution: {@code cancelledBy} identifies who terminated
     * the pending approval (a principal name, or a {@code system:*} identifier for
     * automated cancellations) and is recorded in the audit trail. {@code null} is
     * recorded as {@code unknown}.
     */
    CancelOutcome cancelConversation(String conversationId,
                                     ai.labs.eddi.engine.lifecycle.model.ControlSignal mode,
                                     String cancelledBy)
            throws ResourceStoreException;

    /**
     * Resume a paused (HITL) conversation with the given human decision.
     *
     * @param conversationId
     *            the conversation to resume
     * @param decision
     *            the human approval/rejection decision
     * @param responseHandler
     *            optional callback — may be null for fire-and-forget
     * @throws IllegalStateException
     *             wrong-state conflict (not AWAITING_HUMAN, or agent not deployed)
     *             — maps to HTTP 409; the pause is preserved/restored
     * @throws ResourceStoreException
     *             on infrastructure failures (store errors, coordinator saturation)
     *             — maps to HTTP 500; the pause is restored
     * @throws ResourceNotFoundException
     *             if the conversation is not found
     */
    void resumeConversation(String conversationId,
                            ai.labs.eddi.engine.lifecycle.model.HitlDecision decision,
                            ConversationResponseHandler responseHandler)
            throws ResourceStoreException, ResourceNotFoundException;

    /**
     * Load the full conversation memory snapshot for a given conversationId.
     *
     * @throws ResourceStoreException
     *             on persistence failures
     * @throws ResourceNotFoundException
     *             if the conversation is not found
     */
    ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot getConversationMemorySnapshot(String conversationId)
            throws ResourceStoreException, ResourceNotFoundException;

    /**
     * List conversations currently in AWAITING_HUMAN state (bounded).
     *
     * @param limit
     *            maximum number of summaries to return (clamped to [1, 1000])
     * @return list of pending approval summaries (never null)
     * @throws ResourceStoreException
     *             on persistence failures
     */
    java.util.List<ai.labs.eddi.engine.model.PendingApprovalSummary> listPendingApprovals(int limit)
            throws ResourceStoreException;

    /**
     * Owner-scoped variant of {@link #listPendingApprovals(int)}: only summaries
     * owned by {@code ownerUserId} are returned, and the limit applies AFTER that
     * restriction — a non-admin caller's approval inbox cannot be starved by other
     * users' backlog.
     */
    java.util.List<ai.labs.eddi.engine.model.PendingApprovalSummary> listPendingApprovals(String ownerUserId, int limit)
            throws ResourceStoreException;

    // --- Domain exceptions (no JAX-RS dependency) ---

    class AgentNotReadyException extends Exception {
        public AgentNotReadyException(String message) {
            super(message);
        }
    }

    class ConversationNotFoundException extends RuntimeException {
        public ConversationNotFoundException(String message) {
            super(message);
        }
    }

    class AgentMismatchException extends Exception {
        public AgentMismatchException(String message) {
            super(message);
        }
    }

    class ConversationEndedException extends Exception {
        public ConversationEndedException(String message) {
            super(message);
        }
    }

    /**
     * The conversation is paused awaiting a human decision (HITL) — user input is
     * rejected without being consumed until a reviewer resolves the pause via
     * {@code resumeConversation} or a cancel. Maps to HTTP 409 at the REST layer
     * (mirrors {@link ConversationEndedException} → 410).
     */
    class ConversationAwaitingApprovalException extends Exception {
        public ConversationAwaitingApprovalException(String message) {
            super(message);
        }
    }
}
