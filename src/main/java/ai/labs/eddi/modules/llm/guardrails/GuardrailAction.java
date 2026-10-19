/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import org.jboss.logging.Logger;

import java.util.Locale;

/**
 * What a guardrail finding does — EDDI's own vocabulary, the only form that is
 * ever stored or emitted.
 * <ul>
 * <li>{@code BLOCK} — the turn's answer becomes the guardrail's block message
 * (input: the model is never called);</li>
 * <li>{@code REDACT} — the text is replaced by the guardrail's rewrite (or by
 * the block message when the guardrail offers none) and processing
 * continues;</li>
 * <li>{@code REPROMPT} — output only: the same model is asked again with a
 * corrective message, at most {@code maxRetries} times, then the answer is
 * blocked. On the input side it behaves as {@code BLOCK};</li>
 * <li>{@code WARN} — recorded (audit, metric, SSE) and nothing else.</li>
 * </ul>
 */
public enum GuardrailAction {
    BLOCK, REDACT, REPROMPT, WARN;

    private static final Logger LOGGER = Logger.getLogger(GuardrailAction.class);
    private static final int MAX_LOGGED_CHARS = 40;

    /**
     * Case-insensitive parse; {@code BLOCK} for null or blank, and {@code BLOCK}
     * with a WARN for an unknown value — an unknown action must fail safe, neither
     * open nor by throwing from configuration.
     */
    public static GuardrailAction parse(String value) {
        if (value == null || value.isBlank()) {
            return BLOCK;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            String shown = value.length() > MAX_LOGGED_CHARS ? value.substring(0, MAX_LOGGED_CHARS) : value;
            LOGGER.warnf("Unknown guardrail action '%s' — treating it as 'block'", shown.replaceAll("[\\r\\n]", "_"));
            return BLOCK;
        }
    }

    /** The lower-case name used in audit records, metric tags and SSE payloads. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether this action can change the delivered text — and therefore forces the
     * task to buffer its answer instead of streaming it.
     */
    public boolean mayChangeText() {
        return this != WARN;
    }
}
