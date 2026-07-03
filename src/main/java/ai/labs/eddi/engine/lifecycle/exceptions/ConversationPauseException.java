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

    private final String pausedWorkflowId;
    private final int pausedAbsoluteTaskIndex;
    private final String pauseReason;

    public ConversationPauseException(String pausedWorkflowId, int pausedAbsoluteTaskIndex, String pauseReason) {
        super("Conversation paused: " + (pauseReason == null || pauseReason.isBlank() ? "human approval required" : pauseReason));
        this.pausedWorkflowId = pausedWorkflowId;
        this.pausedAbsoluteTaskIndex = pausedAbsoluteTaskIndex;
        this.pauseReason = pauseReason;
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
}
