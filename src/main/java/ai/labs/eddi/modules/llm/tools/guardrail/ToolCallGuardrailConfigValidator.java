/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.guardrail;

import ai.labs.eddi.engine.hitl.tools.ToolApprovalPatterns;
import ai.labs.eddi.modules.llm.guardrails.ArgumentFields;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.GuardrailConfig;
import ai.labs.eddi.modules.llm.model.ToolCallGuardrailConfig;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Save-time validation of {@code toolCallGuardrails}. A violation is reported
 * as an {@link IllegalArgumentException}, which the store layer answers with a
 * 400 like the other LLM configuration checks.
 * <p>
 * Stricter than the input and output lists on purpose. Those parse an unknown
 * {@code action} as {@code block} and skip an unknown type, so a config written
 * for a newer EDDI loads; this field is new, so there is no such config, and a
 * typo in a hard block must be an error rather than a silently different rule.
 * Whether the {@code type} exists and runs on tool-call arguments is checked at
 * deploy time, where the guardrail beans are known.
 *
 * @since 6.7.0
 */
public final class ToolCallGuardrailConfigValidator {

    static final int MAX_ENTRIES = 32;
    static final int MAX_TOOL_PATTERNS = 32;
    static final int MAX_FIELDS = 16;
    static final int MAX_BLOCK_MESSAGE_CHARS = 1000;
    static final int MAX_TYPE_CHARS = 64;

    static final String DISCLOSURE_TYPE = "disclosure";

    private static final Set<String> ACTIONS = Set.of("block", "redact", "reprompt", "warn");

    private ToolCallGuardrailConfigValidator() {
    }

    /**
     * @param entries
     *            the task's {@code toolCallGuardrails}; null or empty is valid
     * @param fieldPath
     *            where it sits, for the message, e.g.
     *            {@code langchain.task[0].toolCallGuardrails}
     */
    public static void validate(List<ToolCallGuardrailConfig> entries, String fieldPath) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException(fieldPath + " has " + entries.size() + " entries; at most " + MAX_ENTRIES + " are allowed");
        }
        for (int i = 0; i < entries.size(); i++) {
            validateEntry(entries.get(i), fieldPath + "[" + i + "]");
        }
    }

    private static void validateEntry(ToolCallGuardrailConfig entry, String path) {
        if (entry == null) {
            throw new IllegalArgumentException(path + " must not be null");
        }
        String type = entry.getType();
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException(path + ".type is required (e.g. regex-filter, pii-redaction, judge, disclosure)");
        }
        if (type.length() > MAX_TYPE_CHARS) {
            throw new IllegalArgumentException(path + ".type is longer than " + MAX_TYPE_CHARS + " characters");
        }
        validateTools(entry.getTools(), path + ".tools");
        validateFields(entry.getFields(), path + ".fields");

        String action = entry.getAction() == null ? "block" : entry.getAction().trim().toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            throw new IllegalArgumentException(path + ".action must be one of block, redact, reprompt, warn");
        }
        if (DISCLOSURE_TYPE.equals(type.trim()) && !"redact".equals(action)) {
            throw new IllegalArgumentException(path + ": the disclosure guardrail rewrites text; use action 'redact'");
        }
        if (entry.getMaxRetries() != null && (entry.getMaxRetries() < 0 || entry.getMaxRetries() > GuardrailConfig.MAX_RETRIES_CEILING)) {
            throw new IllegalArgumentException(path + ".maxRetries must be between 0 and " + GuardrailConfig.MAX_RETRIES_CEILING);
        }
        String onExhausted = entry.getOnExhausted();
        if (onExhausted != null && !onExhausted.isBlank() && !GuardrailConfig.ON_EXHAUSTED_BLOCK.equalsIgnoreCase(onExhausted.trim())) {
            throw new IllegalArgumentException(path + ".onExhausted may only be 'block' on a tool-call guardrail — there is no best call to deliver");
        }
        if (entry.getBlockMessage() != null && entry.getBlockMessage().length() > MAX_BLOCK_MESSAGE_CHARS) {
            throw new IllegalArgumentException(path + ".blockMessage is longer than " + MAX_BLOCK_MESSAGE_CHARS + " characters");
        }
    }

    private static void validateTools(List<String> tools, String path) {
        if (tools == null || tools.isEmpty()) {
            throw new IllegalArgumentException(path + " is required: name the tool calls this guardrail screens, e.g. [\"mcp:add_issue_comment\"]");
        }
        if (tools.size() > MAX_TOOL_PATTERNS) {
            throw new IllegalArgumentException(path + " has " + tools.size() + " patterns; at most " + MAX_TOOL_PATTERNS + " are allowed");
        }
        for (int i = 0; i < tools.size(); i++) {
            String pattern = tools.get(i);
            if (pattern == null) {
                throw new IllegalArgumentException(path + "[" + i + "] must not be null");
            }
            Optional<String> problem = ToolApprovalPatterns.validate(pattern);
            if (problem.isPresent()) {
                throw new IllegalArgumentException(path + "[" + i + "]: " + problem.get());
            }
        }
    }

    private static void validateFields(List<String> fields, String path) {
        if (fields == null) {
            return;
        }
        if (fields.size() > MAX_FIELDS) {
            throw new IllegalArgumentException(path + " has " + fields.size() + " pointers; at most " + MAX_FIELDS + " are allowed");
        }
        for (int i = 0; i < fields.size(); i++) {
            String problem = ArgumentFields.pointerProblem(fields.get(i));
            if (problem != null) {
                throw new IllegalArgumentException(path + "[" + i + "]: " + problem);
            }
        }
    }
}
