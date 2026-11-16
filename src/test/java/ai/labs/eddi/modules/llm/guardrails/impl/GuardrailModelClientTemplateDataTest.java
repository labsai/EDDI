/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails.impl;

import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("GuardrailModelClient.templateData")
class GuardrailModelClientTemplateDataTest {

    private static GuardrailContext context(GuardrailPhase phase) {
        return new GuardrailContext(phase, "t", "c", "a", Map.of(), List.of(UserMessage.from("the user's question")));
    }

    @Test
    @DisplayName("tool-call phase: {output} is the screened argument, {input} the user's last message")
    void toolCallPhase() {
        var data = GuardrailModelClient.templateData("comment body", context(GuardrailPhase.TOOL_CALL));
        assertEquals("comment body", data.get("output"));
        assertEquals("the user's question", data.get("input"));
    }

    @Test
    @DisplayName("input phase: {input} is the screened text, {output} empty")
    void inputPhase() {
        var data = GuardrailModelClient.templateData("typed", context(GuardrailPhase.INPUT));
        assertEquals("typed", data.get("input"));
        assertEquals("", data.get("output"));
    }
}
