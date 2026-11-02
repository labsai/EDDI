/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

/**
 * How a phase repeat ended and what the discussion loop should do next (I2).
 * <p>
 * Introduced for convergence detection, but deliberately shaped as the general
 * phase-exit channel the plan calls for: I11 (negotiated agreement reached) and
 * I12 (a facilitator's END_PHASE move) both need to end a phase's repeats early
 * for their own reasons, and each additional feature inventing its own
 * out-of-band flag on {@code GroupConversation} is how a phase loop becomes
 * unreadable.
 *
 * @param signal
 *            what the loop should do
 * @param reason
 *            human-readable justification, already suitable for a transcript
 *            entry or an SSE payload; {@code null} for
 *            {@link PhaseExitSignal#CONTINUE}
 * @author ginccc
 */
public record PhaseOutcome(PhaseExitSignal signal, String reason) {

    private static final PhaseOutcome CONTINUE = new PhaseOutcome(PhaseExitSignal.CONTINUE, null);

    /** The overwhelmingly common case — nothing special happened. */
    public static PhaseOutcome cont() {
        return CONTINUE;
    }

    public static PhaseOutcome endRepeats(String reason) {
        return new PhaseOutcome(PhaseExitSignal.END_REPEATS, reason);
    }

    public boolean isContinue() {
        return signal == PhaseExitSignal.CONTINUE;
    }

    /**
     * What a {@link PhaseOutcome} asks the discussion loop to do.
     */
    public enum PhaseExitSignal {
        /** Run the next repeat (or move on to the next phase) as normal. */
        CONTINUE,
        /**
         * Stop repeating this phase; carry on with the phases that follow. What
         * convergence produces — the phase is done, the discussion is not.
         */
        END_REPEATS
    }
}
