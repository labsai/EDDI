/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.ToolDisplay;
import ai.labs.eddi.modules.llm.model.ToolDisplayConfig;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the {@link ToolDisplay} of one tool call from the task's
 * {@code toolDisplay} setting. The only place a call's arguments are read for
 * the live event stream, and it reads nothing but the allow-listed top-level
 * fields.
 */
final class ToolDisplayResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ELLIPSIS = String.valueOf((char) 0x2026);

    private ToolDisplayResolver() {
    }

    /**
     * @param toolDisplay
     *            the task's setting; null for none
     * @param dispatchName
     *            the name the model invoked
     * @param canonicalName
     *            the built-in slug of the tool, or the dispatch name again
     * @param arguments
     *            the call's raw JSON arguments
     * @return the display, or null when no entry names this tool
     */
    static ToolDisplay resolve(Map<String, ToolDisplayConfig> toolDisplay, String dispatchName, String canonicalName, String arguments) {
        if (toolDisplay == null || toolDisplay.isEmpty()) {
            return null;
        }
        // Same lookup as toolQuotas / toolRateLimits: the dispatch name wins over the
        // built-in slug.
        ToolDisplayConfig config = dispatchName == null ? null : toolDisplay.get(dispatchName);
        if (config == null && canonicalName != null) {
            config = toolDisplay.get(canonicalName);
        }
        if (config == null) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        if (config.getFields() != null && !config.getFields().isEmpty()) {
            JsonNode root = parse(arguments);
            if (root != null && root.isObject()) {
                for (String name : config.getFields()) {
                    // Redaction below works on the value's shape; a credential-named field
                    // is never shown at all (also refused at save, this covers stored configs).
                    if (SecretRedactionFilter.isCredentialFieldName(name)) {
                        continue;
                    }
                    JsonNode value = root.get(name);
                    // Scalars only: an object or array argument is exactly the kind of
                    // payload that must not be shown.
                    if (value == null || !(value.isTextual() || value.isNumber() || value.isBoolean())) {
                        continue;
                    }
                    fields.put(name, shorten(SecretRedactionFilter.redact(value.asText())));
                }
            }
        }
        return new ToolDisplay(config.getLabel(), fields);
    }

    private static JsonNode parse(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(arguments);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Cut to {@link ToolDisplayConfig#MAX_VALUE_LENGTH} characters, ellipsis
     * included.
     */
    static String shorten(String value) {
        if (value == null) {
            return "";
        }
        int max = ToolDisplayConfig.MAX_VALUE_LENGTH;
        if (value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        int end = value.offsetByCodePoints(0, max - 1);
        return value.substring(0, end) + ELLIPSIS;
    }
}
