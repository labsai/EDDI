/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

/**
 * Where a guardrail runs: on the user's message before the model is called, on
 * the model's final answer before it is stored and delivered, or on the
 * arguments of a tool call the model asked for, before the call is put in front
 * of an approver or executed ({@code toolCallGuardrails}, since 6.7.0).
 */
public enum GuardrailPhase {
    INPUT("input"), OUTPUT("output"), TOOL_CALL("tool_call");

    private final String label;

    GuardrailPhase(String label) {
        this.label = label;
    }

    /** The lower-case name used in audit keys, metric tags and SSE payloads. */
    public String label() {
        return label;
    }
}
