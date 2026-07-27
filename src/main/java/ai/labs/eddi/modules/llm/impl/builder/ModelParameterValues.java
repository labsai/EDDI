/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import org.jboss.logging.Logger;

import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

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
            return Integer.valueOf(raw);
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
            return Long.valueOf(raw);
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
            return Double.valueOf(raw);
        } catch (NumberFormatException e) {
            return rejected(key, raw, "decimal");
        }
    }

    /**
     * Hand a parsed integer to {@code setter}, or do nothing when the key is absent
     * or unusable — leaving the model's own default in place.
     * <p>
     * The apply-form exists because the read-then-null-check form is three lines at
     * every one of the ~36 call sites across the builders, and that is where a
     * transcription slip hides.
     */
    static void applyInt(Map<String, String> parameters, String key, IntConsumer setter) {
        Integer value = intValue(parameters, key);
        if (value != null) {
            setter.accept(value);
        }
    }

    /** Long variant of {@link #applyInt}. */
    static void applyLong(Map<String, String> parameters, String key, LongConsumer setter) {
        Long value = longValue(parameters, key);
        if (value != null) {
            setter.accept(value);
        }
    }

    /** Double variant of {@link #applyInt}. */
    static void applyDouble(Map<String, String> parameters, String key, DoubleConsumer setter) {
        Double value = doubleValue(parameters, key);
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * The trimmed value, or {@code null} when the key is absent, empty or nothing
     * but whitespace.
     * <p>
     * Blank is absent, not invalid: {@code isNullOrEmpty} only tests
     * {@code isEmpty()}, so a key left as " " used to reach the parser and be
     * reported as "not a valid integer" — a warning about a value the user never
     * really set. Trimming here also spares each parser its own trim.
     */
    private static String rawValue(Map<String, String> parameters, String key) {
        if (parameters == null) {
            return null;
        }
        String raw = parameters.get(key);
        if (raw == null) {
            return null;
        }
        raw = raw.trim();
        return raw.isEmpty() ? null : raw;
    }

    private static <T> T rejected(String key, String raw, String expected) {
        LOGGER.warnv("LLM parameter ''{0}'' is not a valid {1} (''{2}'') — falling back to the model default.",
                key, expected, raw);
        return null;
    }
}
