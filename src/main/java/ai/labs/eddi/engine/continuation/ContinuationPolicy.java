/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.continuation;

/**
 * An agent's self-continuation settings, carried from the deployed agent onto
 * the conversation memory for every turn (transient, never persisted) — the
 * same carrier the per-conversation cost ceiling uses.
 *
 * @param maxContinuations
 *            how many continuation turns one conversation may run in total;
 *            {@code 0} (the default) switches the {@code CONTINUE_CONVERSATION}
 *            action off
 * @param input
 *            the literal user input of every continuation turn, written by the
 *            agent designer; never a template and never runtime data. Empty
 *            when unset
 */
public record ContinuationPolicy(int maxContinuations, String input) {

    /** The policy of an agent that sets nothing: continuation is off. */
    public static final ContinuationPolicy OFF = new ContinuationPolicy(0, "");

    public ContinuationPolicy {
        maxContinuations = Math.max(0, maxContinuations);
        input = input == null ? "" : input;
    }

    /**
     * The policy for an agent's stored settings; {@link #OFF} when neither is set.
     */
    public static ContinuationPolicy of(Integer maxContinuations, String input) {
        if ((maxContinuations == null || maxContinuations <= 0) && (input == null || input.isEmpty())) {
            return OFF;
        }
        return new ContinuationPolicy(maxContinuations == null ? 0 : maxContinuations, input);
    }

    /** Whether the agent allows any continuation at all. */
    public boolean enabled() {
        return maxContinuations > 0;
    }
}
