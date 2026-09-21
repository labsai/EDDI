/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import java.io.Serial;

/**
 * Signals that a submitted callable RAN TO COMPLETION but its result was thrown
 * away because the submission had already been abandoned — the agent-timeout
 * watchdog cancelled the {@code Future} while the body was still running (see
 * {@code BaseRuntime}).
 *
 * <p>
 * This is emphatically NOT a failure of the work: the watchdog has already
 * persisted the accurate outcome ({@code EXECUTION_INTERRUPTED}, or it
 * deliberately left an {@code AWAITING_HUMAN} pause alone), and a late
 * {@code ERROR} write from the zombie turn is exactly the stale overwrite the
 * abandonment token exists to prevent. Completion callbacks therefore log it
 * and discard the result rather than flipping the conversation to
 * {@code ERROR}.
 * </p>
 *
 * <p>
 * It is a distinct type precisely so it can be told apart from a GENUINE
 * {@link InterruptedException} raised by the callable's own body — which means
 * the work did NOT finish, no watchdog ran, and the conversation must be
 * recorded as failed. Matching on the bare {@code InterruptedException} type
 * conflated the two and silently swallowed real failures. It still extends
 * {@code InterruptedException} so any caller that only distinguishes
 * "interrupted" from "failed" keeps working.
 * </p>
 *
 * @author ginccc
 */
public class ExecutionAbandonedException extends InterruptedException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ExecutionAbandonedException(String message) {
        super(message);
    }
}
