/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.model;

/**
 * What happens when a HITL approval timeout expires.
 */
public enum HitlTimeoutPolicy {
    /** Automatically reject the paused turn/phase. */
    AUTO_REJECT,

    /** Automatically approve the paused turn/phase. */
    AUTO_APPROVE,

    /** Abort the conversation/discussion entirely. */
    ABORT,

    /** Wait indefinitely for human approval (no timeout schedule created). */
    WAIT_INDEFINITELY
}
