/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import org.jboss.logging.Logger;

import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Lenient numeric reads for the free-form {@code parameters} map an LLM
 * configuration carries.
 * <p>
 * Those values arrive as strings typed by a user in the Manager, so a stray
 * character is a configuration mistake, not a programming error. Parsing them
 * directly means one bad {@code topP} takes down every conversation that agent
 * serves with an uncaught {@link NumberFormatException} — and the stack trace
 * names the parse site, not the parameter. These helpers return {@code null}
 * for "absent or unusable" and log which key was rejected, letting each builder
 * fall back to the model's own default.
 */
final class ModelParameterValues {

    private static final Logger LOGGER = Logger.getLogger(ModelParameterValues.class);

    private ModelParameterValues() {
        // non-instantiable utility
    }

    /** Parsed integer, or {@code null} when the key is absent or not a number. */
    static Integer intValue(Map<String, String> parameters, String key) {
        String raw = rawValue(parameters, key);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return rejected(key, raw, "integer");
        }
    }

    /** Parsed long, or {@code null} when the key is absent or not a number. */
    static Long longValue(Map<String, String> parameters, String key) {
        String raw = rawValue(parameters, key);
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return rejected(key, raw, "long");
        }
    }

    /** Parsed double, or {@code null} when the key is absent or not a number. */
    static Double doubleValue(Map<String, String> parameters, String key) {
        String raw = rawValue(parameters, key);
        if (raw == null) {
            return null;
        }
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return rejected(key, raw, "decimal");
        }
    }

    private static String rawValue(Map<String, String> parameters, String key) {
        if (parameters == null) {
            return null;
        }
        String raw = parameters.get(key);
        return isNullOrEmpty(raw) ? null : raw;
    }

    private static <T> T rejected(String key, String raw, String expected) {
        LOGGER.warnv("LLM parameter ''{0}'' is not a valid {1} (''{2}'') — falling back to the model default.",
                key, expected, raw);
        return null;
    }
}
