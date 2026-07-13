/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.exceptions;

/**
 * Thrown when a PAUSE_CONVERSATION action is detected in the lifecycle
 * pipeline. Checked exception (mirrors ConversationStopException) — callers
 * must handle the pause-commit logic explicitly.
 */
public class ConversationPauseException extends Exception {

    /**
     * What triggered the pause. {@code RULE} = a behavior rule emitted the reserved
     * PAUSE_CONVERSATION action (turn-level gate). {@code TOOL_CALL} = the LLM
     * invoked a gated tool (tool-level HITL) — resume re-enters the SAME task index
     * and replays the persisted tool-loop transcript.
     */
    public enum PauseOrigin {
        RULE, TOOL_CALL
    }

    private final String pausedWorkflowId;
    private final int pausedAbsoluteTaskIndex;
    private final String pauseReason;
    private final PauseOrigin pauseOrigin;

    public ConversationPauseException(String pausedWorkflowId, int pausedAbsoluteTaskIndex, String pauseReason) {
        this(pausedWorkflowId, pausedAbsoluteTaskIndex, pauseReason, PauseOrigin.RULE);
    }

    public ConversationPauseException(String pausedWorkflowId, int pausedAbsoluteTaskIndex, String pauseReason, PauseOrigin pauseOrigin) {
        super("Conversation paused: " + (pauseReason == null || pauseReason.isBlank() ? "human approval required" : pauseReason));
        this.pausedWorkflowId = pausedWorkflowId;
        this.pausedAbsoluteTaskIndex = pausedAbsoluteTaskIndex;
        this.pauseReason = pauseReason;
        this.pauseOrigin = pauseOrigin != null ? pauseOrigin : PauseOrigin.RULE;
    }

    public String getPausedWorkflowId() {
        return pausedWorkflowId;
    }

    public int getPausedAbsoluteTaskIndex() {
        return pausedAbsoluteTaskIndex;
    }

    public String getPauseReason() {
        return pauseReason;
    }

    public PauseOrigin getPauseOrigin() {
        return pauseOrigin;
    }
}
