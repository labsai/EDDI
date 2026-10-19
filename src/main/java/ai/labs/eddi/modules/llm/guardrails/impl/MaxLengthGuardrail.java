/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails.impl;

import ai.labs.eddi.modules.llm.guardrails.ContentGuardrail;
import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import ai.labs.eddi.modules.llm.guardrails.GuardrailVerdict;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

/**
 * {@code max-length} — input and output. Flags a text longer than
 * {@code maxChars} characters (parameter; default {@value #DEFAULT_MAX_CHARS}).
 * The finding is reported as a rewrite cut to {@code maxChars}: action
 * {@code redact} truncates, {@code block} refuses, {@code reprompt} (output)
 * asks the model for a shorter answer.
 */
@ApplicationScoped
public class MaxLengthGuardrail implements ContentGuardrail {

    public static final String TYPE = "max-length";
    static final int DEFAULT_MAX_CHARS = 8000;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<GuardrailPhase> phases() {
        return Set.of(GuardrailPhase.INPUT, GuardrailPhase.OUTPUT);
    }

    @Override
    public GuardrailVerdict evaluate(String text, GuardrailContext context) {
        int maxChars = context.intParameter("maxChars", DEFAULT_MAX_CHARS);
        if (maxChars <= 0 || text.length() <= maxChars) {
            return GuardrailVerdict.allow();
        }
        return new GuardrailVerdict(GuardrailVerdict.Kind.REWRITE, text.substring(0, maxChars), "max_length_exceeded",
                "Your previous reply was longer than " + maxChars + " characters. Write a shorter reply.", 0.0);
    }
}
