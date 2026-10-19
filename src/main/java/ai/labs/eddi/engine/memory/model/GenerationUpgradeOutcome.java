/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a single conditional compatibility-generation write did, and — when it
 * wrote nothing — why.
 * <p>
 * The write is one atomic compare-and-set in the store: it matches only a
 * conversation of the named agent, in a state a turn can run from, whose
 * generation is absent or lower than the target. Everything else is reported
 * here instead of being overwritten.
 */
public enum GenerationUpgradeOutcome {
    /** The generation was written (and the revision moved). */
    UPGRADED,
    /** The conversation does not exist (any more). */
    NOT_FOUND,
    /** The conversation belongs to another agent. */
    WRONG_AGENT,
    /**
     * The conversation already holds the target generation or a higher one; never
     * lowered.
     */
    ALREADY_CURRENT,
    /** The conversation has ended. */
    ENDED,
    /**
     * The conversation is paused for human approval; a resume must finish on the
     * version that paused it.
     */
    AWAITING_HUMAN,
    /**
     * The conversation is recorded {@code IN_PROGRESS}: a resume is running (or a
     * crashed one has not been recovered yet). A say turn never persists that
     * state, so this is never "a turn is running" — see
     * {@code ConversationUpgradeService} for how turns are kept out.
     */
    IN_PROGRESS,
    /**
     * The conversation matched every precondition when it was last read but the
     * write found it changed in between; nothing was written.
     */
    CHANGED_CONCURRENTLY;

    /**
     * The states a conversation can take its next turn from, and so the only ones
     * an upgrade writes to: {@code READY}, and the two a {@code say} recovers from
     * ({@code ERROR} — a failed turn does not block the next one — and
     * {@code EXECUTION_INTERRUPTED}, the recoverable marker a watchdog expiry or
     * crash recovery leaves).
     */
    public static final Set<ConversationState> UPGRADABLE_STATES = EnumSet.of(ConversationState.READY, ConversationState.ERROR,
            ConversationState.EXECUTION_INTERRUPTED);

    /**
     * The outcome to report when the conditional write matched nothing, derived
     * from what the follow-up read found. Shared by both stores so they give the
     * same reason for the same row.
     */
    public static GenerationUpgradeOutcome whyNotUpgraded(boolean exists, boolean sameAgent, Integer storedGeneration, int targetGeneration,
                                                          ConversationState state) {
        if (!exists) {
            return NOT_FOUND;
        }
        if (!sameAgent) {
            return WRONG_AGENT;
        }
        if (storedGeneration != null && storedGeneration >= targetGeneration) {
            return ALREADY_CURRENT;
        }
        if (state == ConversationState.ENDED) {
            return ENDED;
        }
        if (state == ConversationState.AWAITING_HUMAN) {
            return AWAITING_HUMAN;
        }
        if (state == ConversationState.IN_PROGRESS) {
            return IN_PROGRESS;
        }
        return CHANGED_CONCURRENTLY;
    }
}
