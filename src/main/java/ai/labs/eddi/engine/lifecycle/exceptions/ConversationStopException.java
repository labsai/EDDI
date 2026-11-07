/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.exceptions;

/**
 * Thrown when a STOP_CONVERSATION action is detected in the lifecycle pipeline.
 * Checked exception (mirrored by {@link ConversationPauseException}).
 */
public class ConversationStopException extends Exception {
    public ConversationStopException() {
        super("Conversation stopped by user action");
    }
}
