/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.modules.llm.model.ToolDisplayConfig;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Save-time validation of {@code toolDisplay}. A violation is reported as an
 * {@link IllegalArgumentException}, which the store layer answers with a 400
 * like the other LLM configuration checks.
 */
public final class ToolDisplayConfigValidator {

    /** Most tools one task may configure a display for. */
    static final int MAX_ENTRIES = 100;

    private ToolDisplayConfigValidator() {
    }

    /**
     * @param toolDisplay
     *            the task's {@code toolDisplay}; {@code null} is valid (no display)
     * @param fieldPath
     *            where it sits, for the message, e.g.
     *            {@code langchain.task[0].toolDisplay}
     */
    public static void validate(Map<String, ToolDisplayConfig> toolDisplay, String fieldPath) {
        if (toolDisplay == null) {
            return;
        }
        if (toolDisplay.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException(fieldPath + " has " + toolDisplay.size() + " entries; at most " + MAX_ENTRIES + " are allowed");
        }
        for (Map.Entry<String, ToolDisplayConfig> entry : toolDisplay.entrySet()) {
            String tool = entry.getKey();
            if (tool == null || tool.isBlank()) {
                throw new IllegalArgumentException(fieldPath + " has a blank tool name");
            }
            String path = fieldPath + "['" + tool + "']";
            if (tool.length() > ToolDisplayConfig.MAX_NAME_LENGTH) {
                throw new IllegalArgumentException(path + " is longer than " + ToolDisplayConfig.MAX_NAME_LENGTH + " characters");
            }
            ToolDisplayConfig config = entry.getValue();
            if (config == null) {
                throw new IllegalArgumentException(path + " must not be null");
            }
            boolean hasLabel = config.getLabel() != null;
            if (hasLabel && config.getLabel().isBlank()) {
                throw new IllegalArgumentException(path + ".label is blank: give it text or remove it");
            }
            if (hasLabel && config.getLabel().length() > ToolDisplayConfig.MAX_LABEL_LENGTH) {
                throw new IllegalArgumentException(path + ".label is longer than " + ToolDisplayConfig.MAX_LABEL_LENGTH + " characters");
            }
            boolean hasFields = config.getFields() != null && !config.getFields().isEmpty();
            if (hasFields) {
                if (config.getFields().size() > ToolDisplayConfig.MAX_FIELDS) {
                    throw new IllegalArgumentException(path + ".fields names " + config.getFields().size() + " arguments; at most "
                            + ToolDisplayConfig.MAX_FIELDS + " may be shown");
                }
                Set<String> seen = new HashSet<>();
                for (String field : config.getFields()) {
                    if (field == null || field.isBlank()) {
                        throw new IllegalArgumentException(path + ".fields has a blank argument name");
                    }
                    if (field.length() > ToolDisplayConfig.MAX_NAME_LENGTH) {
                        throw new IllegalArgumentException(
                                path + ".fields has an argument name longer than " + ToolDisplayConfig.MAX_NAME_LENGTH + " characters");
                    }
                    if (SecretRedactionFilter.isCredentialFieldName(field)) {
                        throw new IllegalArgumentException(path + ".fields names '" + field + "', which names a credential field; it is never shown");
                    }
                    if (!seen.add(field)) {
                        throw new IllegalArgumentException(path + ".fields names '" + field + "' twice");
                    }
                }
            }
            if (!hasLabel && !hasFields) {
                throw new IllegalArgumentException(path + " shows nothing: set a label or name fields, or remove the entry");
            }
        }
    }
}
