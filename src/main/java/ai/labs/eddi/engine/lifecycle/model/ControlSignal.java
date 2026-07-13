/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.model;

/**
 * Lifecycle control signals for conversation and group discussion execution.
 * Checked at safe points during pipeline/phase execution.
 */
public enum ControlSignal {
    /** Normal execution — continue to next task/speaker/phase. */
    CONTINUE,

    /** Graceful stop — finish current work, then stop before the next unit. */
    CANCEL_GRACEFUL,

    /** Immediate stop — best-effort interrupt of the current in-flight call. */
    CANCEL_IMMEDIATE
}
