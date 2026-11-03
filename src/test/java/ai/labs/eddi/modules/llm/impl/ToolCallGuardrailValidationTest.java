/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.guardrails.ContentGuardrail;
import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import ai.labs.eddi.modules.llm.guardrails.GuardrailService;
import ai.labs.eddi.modules.llm.guardrails.GuardrailVerdict;
import ai.labs.eddi.modules.llm.guardrails.impl.DisclosureGuardrail;
import ai.labs.eddi.modules.llm.guardrails.impl.PiiRedactionGuardrail;
import ai.labs.eddi.modules.llm.guardrails.impl.RegexFilterGuardrail;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.GuardrailConfig;
import ai.labs.eddi.modules.llm.model.ToolCallGuardrailConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.modules.llm.tools.guardrail.ToolCallGuardrailConfigValidatorTest.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("toolCallGuardrails — deploy-time validation")
class ToolCallGuardrailValidationTest {

    private final GuardrailService service = new GuardrailService(
            List.of(new RegexFilterGuardrail(), new PiiRedactionGuardrail(), new DisclosureGuardrail(), new InputOnly()), new SimpleMeterRegistry());

    /** A type that does not run on tool-call arguments. */
    private static final class InputOnly implements ContentGuardrail {
        @Override
        public String type() {
            return "input-only";
        }

        @Override
        public Set<GuardrailPhase> phases() {
            return Set.of(GuardrailPhase.INPUT);
        }

        @Override
        public GuardrailVerdict evaluate(String text, GuardrailContext context) {
            return GuardrailVerdict.allow();
        }
    }

    private static LlmConfiguration.Task task(ToolCallGuardrailConfig... entries) {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setToolCallGuardrails(List.of(entries));
        return task;
    }

    @Test
    @DisplayName("an unknown type is refused, not skipped")
    void unknownTypeRefused() {
        var problems = ToolCallGuardrailValidation.deployProblems(service, task(entry("regx-filter", "block", List.of("x"), null)), "t");
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).startsWith("task 't' toolCallGuardrails[0]: unknown guardrail type 'regx-filter'"), problems.get(0));
    }

    @Test
    @DisplayName("a type that does not run on tool-call arguments is refused")
    void wrongPhaseRefused() {
        var problems = ToolCallGuardrailValidation.deployProblems(service, task(entry("input-only", "block", List.of("x"), null)), "t");
        assertTrue(problems.get(0).contains("does not run on tool-call arguments"), problems.toString());
    }

    @Test
    @DisplayName("parameters are validated by type")
    void parametersValidated() {
        var regex = entry("regex-filter", "block", List.of("x"), null);
        regex.setParameters(Map.of("patterns", "(unclosed"));
        var disclosure = entry("disclosure", "redact", List.of("x"), null);
        var problems = ToolCallGuardrailValidation.deployProblems(service, task(regex, disclosure), "t");
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("toolCallGuardrails[0]: regex-filter guardrail"), problems.get(0));
        assertTrue(problems.get(1).contains("toolCallGuardrails[1]: disclosure guardrail: parameter 'text'"), problems.get(1));
    }

    @Test
    @DisplayName("a disclosure output guardrail must use redact")
    void outputDisclosureNeedsRedact() {
        var task = new LlmConfiguration.Task();
        var output = new GuardrailConfig();
        output.setType("disclosure");
        output.setAction("block");
        task.setOutputGuardrails(List.of(output));
        var problems = ToolCallGuardrailValidation.deployProblems(service, task, "t");
        assertEquals(List.of("task 't' outputGuardrails[0]: the disclosure guardrail rewrites text; use action 'redact'"), problems);
    }

    @Test
    @DisplayName("a valid list has no problems; no list, no problems")
    void valid() {
        var disclosure = entry("disclosure", "redact", List.of("mcp:add_issue_comment"), List.of("/body"));
        disclosure.setParameters(Map.of("text", "— AI"));
        assertTrue(ToolCallGuardrailValidation.deployProblems(service,
                task(entry("pii-redaction", "redact", List.of("mcp:add_issue_comment"), List.of("/body")), disclosure), "t").isEmpty());
        assertTrue(ToolCallGuardrailValidation.deployProblems(service, new LlmConfiguration.Task(), "t").isEmpty());
    }
}
