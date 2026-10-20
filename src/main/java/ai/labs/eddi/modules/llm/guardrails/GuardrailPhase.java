/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

/**
 * Where a guardrail runs: on the user's message before the model is called, or
 * on the model's final answer before it is stored and delivered.
 */
public enum GuardrailPhase {
    INPUT("input"), OUTPUT("output");

    private final String label;

    GuardrailPhase(String label) {
        this.label = label;
    }

    /** The lower-case name used in audit keys, metric tags and SSE payloads. */
    public String label() {
        return label;
    }
}
