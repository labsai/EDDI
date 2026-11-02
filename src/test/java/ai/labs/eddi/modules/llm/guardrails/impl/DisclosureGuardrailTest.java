/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails.impl;

import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import ai.labs.eddi.modules.llm.guardrails.GuardrailVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("disclosure guardrail")
class DisclosureGuardrailTest {

    private final DisclosureGuardrail guardrail = new DisclosureGuardrail();

    private static GuardrailContext context(String... keyValues) {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put(keyValues[i], keyValues[i + 1]);
        }
        return new GuardrailContext(GuardrailPhase.TOOL_CALL, "t", "c", "a", params, List.of());
    }

    @Test
    @DisplayName("appends the label after a blank line, as a rewrite with reason disclosure_added")
    void appends() {
        var verdict = guardrail.evaluate("Thanks for the report.  \n", context("text", "— AI-assisted reply"));
        assertEquals(GuardrailVerdict.Kind.REWRITE, verdict.kind());
        assertEquals("Thanks for the report.\n\n— AI-assisted reply", verdict.text());
        assertEquals(DisclosureGuardrail.REASON_ADDED, verdict.reason());
    }

    @Test
    @DisplayName("prepends with a custom separator")
    void prepends() {
        var verdict = guardrail.evaluate("  Body", context("text", "[AI]", "position", "prepend", "separator", " "));
        assertEquals("[AI] Body", verdict.text());
    }

    @Test
    @DisplayName("idempotent: a text that already carries the label (trailing whitespace tolerated) is allowed")
    void idempotent() {
        var ctx = context("text", "— AI-assisted reply");
        var once = guardrail.evaluate("Hello", ctx);
        assertFalse(guardrail.evaluate(once.text(), ctx).triggered());
        assertFalse(guardrail.evaluate(once.text() + "\n  ", ctx).triggered());
        var prepend = context("text", "[AI]", "position", "prepend");
        assertFalse(guardrail.evaluate("  [AI] body", prepend).triggered());
    }

    @Test
    @DisplayName("an empty text becomes the label alone")
    void emptyText() {
        assertEquals("[AI]", guardrail.evaluate("", context("text", "[AI]")).text());
    }

    @Test
    @DisplayName("parameters: text required and bounded, position append or prepend")
    void validation() {
        assertNotNull(guardrail.validate(Map.of()));
        assertNotNull(guardrail.validate(Map.of("text", "x".repeat(DisclosureGuardrail.MAX_TEXT_CHARS + 1))));
        assertNotNull(guardrail.validate(Map.of("text", "x", "position", "middle")));
        assertNull(guardrail.validate(Map.of("text", "x", "position", "Prepend")));
        assertNull(guardrail.validate(Map.of("text", "x".repeat(DisclosureGuardrail.MAX_TEXT_CHARS))));
    }

    @Test
    @DisplayName("runs on tool-call arguments and on output")
    void phases() {
        assertEquals(Set.of(GuardrailPhase.OUTPUT, GuardrailPhase.TOOL_CALL), guardrail.phases());
    }
}
