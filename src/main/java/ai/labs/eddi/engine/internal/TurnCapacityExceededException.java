/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import java.util.concurrent.RejectedExecutionException;

/**
 * This node already runs {@code eddi.conversation.max-in-flight-turns} turns.
 * <p>
 * A {@link RejectedExecutionException} on purpose, so every surface that starts
 * a turn handles it exactly like coordinator saturation and the shutdown drain:
 * {@code RestAgentEngine} and the global
 * {@code RejectedExecutionExceptionMapper} answer the retryable 503
 * {@code capacity_exceeded} with a {@code Retry-After}, and the group and HITL
 * paths already treat the type as backpressure. Its own type only so
 * {@link ConversationService} can tell it apart from an internal fault and skip
 * the ERROR stack trace: under overload there is one of these per rejected
 * turn.
 */
public class TurnCapacityExceededException extends RejectedExecutionException {
    public TurnCapacityExceededException(String message) {
        super(message);
    }
}
