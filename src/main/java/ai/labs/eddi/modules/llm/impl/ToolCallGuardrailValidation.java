/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.guardrails.GuardrailAction;
import ai.labs.eddi.modules.llm.guardrails.GuardrailService;
import ai.labs.eddi.modules.llm.guardrails.ToolCallGuardrailScreener;
import ai.labs.eddi.modules.llm.guardrails.impl.DisclosureGuardrail;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.GuardrailConfig;
import ai.labs.eddi.modules.llm.model.ToolCallGuardrailConfig;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deploy-time checks of a task's {@code toolCallGuardrails}, where the
 * guardrail beans are known (the save-time checks are
 * {@code ToolCallGuardrailConfigValidator}).
 * <ul>
 * <li>Each entry's parameters, by its type ({@code ContentGuardrail.validate}),
 * as for input and output guardrails.</li>
 * <li>An unknown type, or one that does not run on tool-call arguments, is
 * <em>refused</em> — unlike input and output, which skip it with a warning. The
 * field is new, so no config written for another EDDI version can carry it, and
 * a skipped hard block is a disabled one.</li>
 * <li>A {@code disclosure} entry among the task's output guardrails must use
 * {@code redact} as well: any other action would block or reprompt every
 * unlabelled answer.</li>
 * </ul>
 * Warnings (logged, never refused): {@code expression} and {@code json-object}
 * without {@code fields} (they read JSON, and every string is rarely that), a
 * model-backed type without {@code fields} (one model call per string in the
 * arguments), and a rewriting entry after a {@code disclosure} entry on the
 * same tools (it may rewrite the label away).
 *
 * @since 6.7.0
 */
final class ToolCallGuardrailValidation {

    private static final Logger LOGGER = Logger.getLogger(ToolCallGuardrailValidation.class);

    static final String LIST_NAME = "toolCallGuardrails";

    private static final Set<String> JSON_TYPES = Set.of("expression", "json-object");

    private ToolCallGuardrailValidation() {
    }

    /**
     * The problems that refuse the deploy, each prefixed with the task; warnings
     * are logged.
     */
    static List<String> deployProblems(GuardrailService guardrailService, LlmConfiguration.Task task, String taskId) {
        List<String> problems = new ArrayList<>();
        if (guardrailService == null || task == null) {
            return problems;
        }
        String prefix = "task '" + taskId + "' ";
        checkOutputDisclosure(task.getOutputGuardrails(), prefix, problems);
        List<ToolCallGuardrailConfig> entries = task.getToolCallGuardrails();
        if (entries == null || entries.isEmpty()) {
            return problems;
        }
        List<GuardrailConfig> asConfigs = new ArrayList<>(entries);
        for (String problem : guardrailService.validate(LIST_NAME, asConfigs)) {
            problems.add(prefix + problem);
        }
        var screener = new ToolCallGuardrailScreener(guardrailService);
        Set<String> disclosureTools = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            ToolCallGuardrailConfig entry = entries.get(i);
            String where = prefix + LIST_NAME + "[" + i + "]: ";
            if (entry == null) {
                continue;
            }
            if (!screener.supports(entry)) {
                problems.add(where + (guardrailService.types().contains(entry.getType())
                        ? "guardrail type '" + entry.getType() + "' does not run on tool-call arguments"
                        : "unknown guardrail type '" + entry.getType() + "'; known types: " + guardrailService.types()));
                continue;
            }
            boolean noFields = entry.getFields() == null || entry.getFields().isEmpty();
            if (noFields && JSON_TYPES.contains(entry.getType())) {
                LOGGER.warnf("%s%s screens every string of the arguments; set fields (\"\" for the whole arguments) so it sees JSON", where,
                        entry.getType());
            } else if (noFields && screener.isModelBacked(entry)) {
                LOGGER.warnf("%s%s without fields makes one model call per string in the arguments; set fields, e.g. [\"/body\"]", where,
                        entry.getType());
            }
            List<String> tools = entry.getTools() != null ? entry.getTools() : List.of();
            if (DisclosureGuardrail.TYPE.equals(entry.getType())) {
                disclosureTools.addAll(tools);
            } else if (GuardrailAction.parse(entry.getAction()) == GuardrailAction.REDACT && overlaps(disclosureTools, tools)) {
                LOGGER.warnf("%sa rewriting entry after a disclosure entry on the same tools may rewrite the label away; put disclosure last",
                        where);
            }
        }
        for (String warning : guardrailService.warnings(LIST_NAME, asConfigs)) {
            LOGGER.warnf("LLM task '%s' %s", taskId, warning);
        }
        return problems;
    }

    private static boolean overlaps(Set<String> disclosureTools, List<String> tools) {
        if (disclosureTools.isEmpty()) {
            return false;
        }
        for (String tool : tools) {
            if (disclosureTools.contains(tool) || tool.contains("*")) {
                return true;
            }
        }
        return disclosureTools.stream().anyMatch(t -> t.contains("*"));
    }

    private static void checkOutputDisclosure(List<GuardrailConfig> outputGuardrails, String prefix, List<String> problems) {
        if (outputGuardrails == null) {
            return;
        }
        for (int i = 0; i < outputGuardrails.size(); i++) {
            GuardrailConfig config = outputGuardrails.get(i);
            if (config != null && DisclosureGuardrail.TYPE.equals(config.getType())
                    && GuardrailAction.parse(config.getAction()) != GuardrailAction.REDACT) {
                problems.add(prefix + "outputGuardrails[" + i + "]: the disclosure guardrail rewrites text; use action 'redact'");
            }
        }
    }
}
