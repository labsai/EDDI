/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
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

    /**
     * Keys that live in an LLM task's {@code parameters} map but are consumed
     * <em>outside</em> the provider builders, so they must never be reported as
     * unrecognised.
     * <p>
     * {@code timeout} is on the list even though most builders do read it: the two
     * that do not (jlama, oracle-genai) still honour it, because
     * {@code ObservableChatModel} applies it as a wall-clock bound around
     * {@code chat(...)}. Everything else here is read by {@code LlmTask} or
     * {@code ChatModelRegistry} before a builder is ever reached.
     */
    private static final Set<String> PIPELINE_KEYS = Set.of(
            "systemMessage", "prompt", "logSizeLimit", "includeFirstAgentMessage",
            "convertToObject", "addToOutput", "responseSchema", "logRequests", "logResponses", "timeout");

    private ModelParameterValues() {
        // non-instantiable utility
    }

    /**
     * Warn about configured parameters the selected provider builder does not read.
     * <p>
     * A dropped parameter is silent otherwise: the model is built, the turn
     * succeeds, and the agent designer simply never gets the {@code temperature}
     * they set. Emitted from the build path only (a cache miss), so it does not
     * repeat on every turn.
     *
     * @param provider
     *            the model type, used only as log context
     * @param parameters
     *            the map handed to the builder
     * @param recognised
     *            the keys the builder reads; an <em>empty</em> set means the
     *            builder does not declare its parameters and the check is skipped
     *            entirely
     */
    static void warnAboutUnrecognisedKeys(String provider, Map<String, String> parameters, Set<String> recognised) {
        if (parameters == null || parameters.isEmpty() || recognised == null || recognised.isEmpty()) {
            return;
        }
        var unrecognised = new TreeSet<String>();
        for (String key : parameters.keySet()) {
            if (key != null && !recognised.contains(key) && !PIPELINE_KEYS.contains(key)) {
                unrecognised.add(sanitize(key));
            }
        }
        if (!unrecognised.isEmpty()) {
            LOGGER.warnv("LLM parameter(s) {0} are not read by the ''{1}'' model builder and have no effect. "
                    + "Recognised parameters: {2}.", unrecognised, sanitize(provider), new TreeSet<>(recognised));
        }
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
     * Boolean variant of {@link #applyInt}, for tri-state provider options where
     * <em>unset</em> is a third, meaningful value.
     * <p>
     * Deliberately not {@code Boolean.parseBoolean(parameters.get(key))}: that maps
     * an absent key, a typo and an explicit {@code "false"} all to {@code false},
     * which would silently pin the option instead of leaving the provider default —
     * the exact difference between "do not think" and "let the model decide" on
     * Ollama.
     */
    static void applyBoolean(Map<String, String> parameters, String key, Consumer<Boolean> setter) {
        String raw = rawValue(parameters, key);
        if (raw == null) {
            return;
        }
        if ("true".equalsIgnoreCase(raw)) {
            setter.accept(Boolean.TRUE);
        } else if ("false".equalsIgnoreCase(raw)) {
            setter.accept(Boolean.FALSE);
        } else {
            LOGGER.warnf("LLM parameter '%s' is not a boolean ('%s') — leaving the provider default in place.",
                    sanitize(key), sanitize(raw));
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
                sanitize(key), expected, sanitize(raw));
        return null;
    }
}
