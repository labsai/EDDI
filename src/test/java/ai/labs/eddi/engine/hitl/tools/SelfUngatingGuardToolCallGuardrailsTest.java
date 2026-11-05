/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * An agent may not write {@code toolCallGuardrails} — the hard blocks on its
 * own tool-call arguments — into an LLM configuration, by any route an approved
 * call can take.
 */
@DisplayName("SelfUngatingGuard — toolCallGuardrails")
class SelfUngatingGuardToolCallGuardrailsTest {

    private static final String URI = "https://eddi.example/llmstore/llms/5f1c0f3a9d8e4b2a7c6d1e0f?version=3";
    private static final String AGENT = "aaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    @DisplayName("a full document carrying toolCallGuardrails is refused, an empty list included")
    void documentCarryingTheField() {
        assertEquals(SelfUngatingGuard.GUARDRAIL_WRITE_REASON,
                SelfUngatingGuard.refusal("PUT", URI, "{\"tasks\":[{\"id\":\"t\",\"toolCallGuardrails\":[]}]}", AGENT));
        assertNull(SelfUngatingGuard.refusal("PUT", URI, "{\"tasks\":[{\"id\":\"t\"}]}", AGENT));
    }

    @Test
    @DisplayName("a patch naming it in a path, or carrying it in a value, is refused — even next to an unsupported operation")
    void patchNamingTheField() {
        assertEquals(SelfUngatingGuard.GUARDRAIL_WRITE_REASON,
                SelfUngatingGuard.refusal("PATCH", URI, "[{\"op\":\"remove\",\"path\":\"/tasks/0/toolCallGuardrails/0\"}]", AGENT));
        assertEquals(SelfUngatingGuard.GUARDRAIL_WRITE_REASON,
                SelfUngatingGuard.refusal("PATCH", URI, "[{\"op\":\"replace\",\"path\":\"/tasks/0\",\"value\":{\"toolCallGuardrails\":[]}}]", AGENT));
        assertEquals(SelfUngatingGuard.GUARDRAIL_WRITE_REASON, SelfUngatingGuard.refusal("PATCH", URI,
                "[{\"op\":\"copy\",\"from\":\"/a\",\"path\":\"/b\"},{\"op\":\"remove\",\"path\":\"/tasks/0/toolCallGuardrails\"}]", AGENT));
    }

    @Test
    @DisplayName("an unresolved call (an MCP update_resource) whose arguments carry it is refused")
    void rawArguments() {
        assertEquals(SelfUngatingGuard.GUARDRAIL_WRITE_REASON, SelfUngatingGuard.refusalFromArguments(
                "{\"resourceType\":\"llm\",\"content\":\"{\\\"tasks\\\":[{\\\"toolCallGuardrails\\\":[]}]}\"}", AGENT));
    }

    @Test
    @DisplayName("toolApprovals still reports the gate reason first")
    void gateReasonFirst() {
        assertEquals(SelfUngatingGuard.GATE_WRITE_REASON,
                SelfUngatingGuard.refusal("PUT", URI, "{\"tasks\":[{\"toolApprovals\":{},\"toolCallGuardrails\":[]}]}", AGENT));
    }
}
