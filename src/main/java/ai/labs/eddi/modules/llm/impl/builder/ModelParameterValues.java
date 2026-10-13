/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
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

    private static final ObjectMapper JSON = new ObjectMapper();

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
     * Filesystem-path variant of {@link #applyInt}, for provider options that take
     * a {@link Path}.
     * <p>
     * {@code Path.of} throws {@link InvalidPathException} on a string the platform
     * cannot express as a path — a Windows drive letter typed into a Linux
     * deployment, say. That is the same class of mistake as a mistyped
     * {@code topP}, so it is treated the same way: log which key was rejected and
     * leave the provider default in place, rather than failing every conversation
     * the agent serves.
     */
    static void applyPath(Map<String, String> parameters, String key, Consumer<Path> setter) {
        String raw = rawValue(parameters, key);
        if (raw == null) {
            return;
        }
        try {
            setter.accept(Path.of(raw));
        } catch (InvalidPathException e) {
            // The configured value itself is never logged: an invalid path can still
            // carry a real filesystem path (a username's home directory, an internal
            // project name) even though it fails Path.of, so echoing it back would leak
            // that into the log for no diagnostic gain the key name doesn't already give.
            LOGGER.warnv("LLM parameter ''{0}'' is not a usable filesystem path — "
                    + "falling back to the model default.", sanitize(key));
        }
    }

    /**
     * A boolean read with an EDDI-chosen default, for options whose provider
     * default is wrong for EDDI. Unlike {@link #applyBoolean}, where unset means
     * "let the provider decide", an absent, blank or unparseable value here lands
     * on {@code defaultValue}; an explicit {@code "true"}/{@code "false"} is
     * honoured.
     */
    static boolean booleanValue(Map<String, String> parameters, String key, boolean defaultValue) {
        String raw = rawValue(parameters, key);
        if (raw == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        LOGGER.warnf("LLM parameter '%s' is not a boolean ('%s') — falling back to %b.",
                sanitize(key), sanitize(raw), defaultValue);
        return defaultValue;
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

    /**
     * {@code overlay} laid over {@code base}, nested objects merged key by key
     * rather than replaced: a user's {@code {"provider":{"zdr":true}}} must not
     * wipe the {@code provider.order} a catalog field put there. Scalars and lists
     * in the overlay win; neither argument is modified.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        overlay.forEach((key, value) -> {
            Object existing = merged.get(key);
            if (existing instanceof Map<?, ?> existingMap && value instanceof Map<?, ?> valueMap) {
                merged.put(key, deepMerge((Map<String, Object>) existingMap, (Map<String, Object>) valueMap));
            } else {
                merged.put(key, value);
            }
        });
        return merged;
    }

    /** {@code true}/{@code false}, or {@code null} when absent or not a boolean. */
    static Boolean booleanOrNull(Map<String, String> parameters, String key) {
        String raw = rawValue(parameters, key);
        if (raw == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return Boolean.FALSE;
        }
        return rejected(key, raw, "boolean");
    }

    /** The trimmed value, or {@code null} when absent or blank. */
    static String stringValue(Map<String, String> parameters, String key) {
        return rawValue(parameters, key);
    }

    static void applyString(Map<String, String> parameters, String key, Consumer<String> setter) {
        String value = rawValue(parameters, key);
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * A string restricted to {@code allowed} (case-insensitive, returned in the
     * canonical spelling from {@code allowed}); anything else is rejected with a
     * warning naming the accepted values, and the provider default stays in place.
     */
    static String enumValue(Map<String, String> parameters, String key, Collection<String> allowed) {
        String raw = rawValue(parameters, key);
        if (raw == null) {
            return null;
        }
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(raw)) {
                return candidate;
            }
        }
        LOGGER.warnv("LLM parameter ''{0}'' is not one of {1} (''{2}'') — falling back to the model default.",
                sanitize(key), allowed, sanitize(raw));
        return null;
    }

    /**
     * A list of strings: either a JSON array ({@code ["END","

    "]}, needed when an item contains a comma or a newline) or a comma-separated
     * list. Blank items are dropped; {@code null} when nothing usable remains.
     */
    static List<String> stringList(Map<String, String> parameters, String key) {
        String raw = rawValue(parameters, key);
        if (raw == null) {
            return null;
        }
        List<String> items = new ArrayList<>();
        if (raw.startsWith("[")) {
            try {
                for (String item : JSON.readValue(raw, new TypeReference<List<String>>() {
                })) {
                    if (item != null && !item.isEmpty()) {
                        items.add(item);
                    }
                }
            } catch (JsonProcessingException e) {
                return rejected(key, "(unparseable)", "JSON array of strings");
            }
        } else {
            for (String item : raw.split(",")) {
                if (!item.trim().isEmpty()) {
                    items.add(item.trim());
                }
            }
        }
        return items.isEmpty() ? null : List.copyOf(items);
    }

    static void applyStringList(Map<String, String> parameters, String key, Consumer<List<String>> setter) {
        List<String> value = stringList(parameters, key);
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * A JSON object. Only the key is ever logged on failure: the value of a header
     * or body parameter can carry a credential.
     */
    static Map<String, Object> objectMap(Map<String, String> parameters, String key) {
        String raw = rawValue(parameters, key);
        if (raw == null) {
            return null;
        }
        try {
            Map<String, Object> parsed = JSON.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return parsed == null || parsed.isEmpty() ? null : parsed;
        } catch (JsonProcessingException e) {
            return rejected(key, "(unparseable)", "JSON object");
        }
    }

    /**
     * A JSON object whose values are all coerced to strings (headers, labels,
     * metadata).
     */
    static Map<String, String> stringMap(Map<String, String> parameters, String key) {
        Map<String, Object> raw = objectMap(parameters, key);
        if (raw == null) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null && !k.isBlank() && v != null) {
                result.put(k, String.valueOf(v));
            }
        });
        return result.isEmpty() ? null : result;
    }

    static void applyStringMap(Map<String, String> parameters, String key, Consumer<Map<String, String>> setter) {
        Map<String, String> value = stringMap(parameters, key);
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * Any JSON value — object, array, string, number or boolean — as Jackson's
     * plain Java form, for body fields whose shape the catalog only knows as
     * "json". Only the key is logged on failure.
     */
    static Object jsonValue(Map<String, String> parameters, String key) {
        String raw = rawValue(parameters, key);
        if (raw == null) {
            return null;
        }
        try {
            return JSON.readValue(raw, Object.class);
        } catch (JsonProcessingException e) {
            return rejected(key, "(unparseable)", "JSON value");
        }
    }

    /** A JSON object whose values are all integers (logit bias). */
    static Map<String, Integer> intMap(Map<String, String> parameters, String key) {
        Map<String, Object> raw = objectMap(parameters, key);
        if (raw == null) {
            return null;
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof Number number)) {
                return rejected(key, "(non-numeric value)", "JSON object of integers");
            }
            result.put(entry.getKey(), number.intValue());
        }
        return result;
    }

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
