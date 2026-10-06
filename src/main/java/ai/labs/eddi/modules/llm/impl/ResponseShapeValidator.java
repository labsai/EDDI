/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks that a <em>successfully parsed</em> model reply has the shape the
 * agent designer asked for. It answers one question — "is this the object the
 * templates expect?" — and never throws: a malformed schema disables the check
 * (one {@code WARN}) instead of failing the turn.
 *
 * <h2>Schema subset</h2> Deliberately small and in-house, so a model reply can
 * never reach a regex engine or a remote {@code $ref} fetch:
 * <ul>
 * <li>{@code type} — {@code object, array, string, number, integer, boolean,
 * null}, or an array of those;</li>
 * <li>{@code required}, {@code properties} (recursive);</li>
 * <li>{@code items} (a single schema applied to every element);</li>
 * <li>{@code enum} (scalar members);</li>
 * <li>{@code minLength} (strings, counted in characters);</li>
 * <li>{@code additionalProperties: false} (optional; any other value is
 * ignored).</li>
 * </ul>
 * Every other keyword ({@code pattern}, {@code $ref}, {@code oneOf},
 * {@code description}, ...) is ignored, so a schema written for the prompt
 * block or for a provider's native enforcement can be reused unchanged.
 *
 * <h2>{@code nonBlankFields}</h2> Top-level or dotted ({@code a.b}) names that
 * must hold a non-blank string. This is the "{@code ""} is not an answer" rule
 * that needs no schema.
 *
 * <h2>Reasons</h2> The returned reason is built only from <em>EDDI</em> text
 * (the keyword that failed and the path of the field, e.g.
 * {@code schema: $.htmlResponseText required}). It never contains a model
 * value, nor a property name taken from the model's reply, because it is meant
 * to be fed back to the model in a corrective message and model output echoed
 * into a prompt is an injection channel.
 * <p>
 * Thread-safe: compiled schemas are immutable and cached by schema text.
 */
public final class ResponseShapeValidator {

    private static final Logger LOGGER = Logger.getLogger(ResponseShapeValidator.class);

    /** Most violations listed in one reason. */
    private static final int MAX_VIOLATIONS = 3;
    /** Longest path quoted in a reason. */
    private static final int MAX_PATH_CHARS = 120;
    /** Compiled schemas kept; the cache is cleared when exceeded. */
    private static final int MAX_CACHED_SCHEMAS = 256;
    /** Deepest nesting walked; deeper replies are not checked further. */
    private static final int MAX_DEPTH = 32;

    private static final Set<String> TYPES = Set.of("object", "array", "string", "number", "integer", "boolean", "null");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** schema text to its compiled form; {@link Compiled#UNUSABLE} when invalid. */
    private final Map<String, Compiled> cache = new ConcurrentHashMap<>();

    /**
     * @param parsed
     *            the parsed reply (Map, List or scalar)
     * @param responseSchemaJson
     *            the task's {@code responseSchema}, may be null/blank
     * @param nonBlankFields
     *            fields that must be non-blank strings, may be null/empty
     * @return the EDDI-generated reason of the violation, or empty when the shape
     *         is acceptable (or nothing is configured, or the schema is unusable)
     */
    public Optional<String> validate(Object parsed, String responseSchemaJson, List<String> nonBlankFields) {
        return validate(parsed, responseSchemaJson, nonBlankFields, null);
    }

    /**
     * As {@link #validate(Object, String, List)}; {@code taskId} only labels the
     * one-time warning for an unusable schema.
     */
    public Optional<String> validate(Object parsed, String responseSchemaJson, List<String> nonBlankFields, String taskId) {
        List<String> violations = new ArrayList<>();
        Compiled compiled = compile(responseSchemaJson, taskId);
        if (compiled.schema() != null) {
            check(parsed, compiled.schema(), "$", violations, 0);
        }
        if (nonBlankFields != null) {
            for (String field : nonBlankFields) {
                if (violations.size() >= MAX_VIOLATIONS) {
                    break;
                }
                if (field != null && !field.isBlank() && !isNonBlankString(parsed, field.trim())) {
                    violations.add("nonBlank: " + path("$", field.trim()));
                }
            }
        }
        if (violations.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(String.join("; ", violations));
    }

    /** Whether {@code responseSchemaJson} is a usable schema (or absent). */
    public boolean isUsable(String responseSchemaJson) {
        return responseSchemaJson == null || responseSchemaJson.isBlank() || compile(responseSchemaJson, null).schema() != null;
    }

    // ---- walking ----------------------------------------------------------------

    private void check(Object value, JsonNode schema, String path, List<String> violations, int depth) {
        if (violations.size() >= MAX_VIOLATIONS || depth > MAX_DEPTH || !schema.isObject()) {
            return;
        }
        JsonNode type = schema.get("type");
        if (type != null && !matchesType(value, type)) {
            violations.add("schema: " + path + " type");
            return; // the other keywords assume the right type
        }
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray() && !inEnum(value, enumNode)) {
            violations.add("schema: " + path + " enum");
            return;
        }
        if (value instanceof String text) {
            JsonNode min = schema.get("minLength");
            if (min != null && min.canConvertToInt() && text.codePointCount(0, text.length()) < min.asInt()) {
                violations.add("schema: " + path + " minLength");
            }
        } else if (value instanceof Map<?, ?> map) {
            checkObject(map, schema, path, violations, depth);
        } else if (value instanceof List<?> list) {
            JsonNode items = schema.get("items");
            if (items != null && items.isObject()) {
                for (int i = 0; i < list.size(); i++) {
                    check(list.get(i), items, path + "[" + i + "]", violations, depth + 1);
                }
            }
        }
    }

    private void checkObject(Map<?, ?> map, JsonNode schema, String path, List<String> violations, int depth) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode name : required) {
                if (violations.size() < MAX_VIOLATIONS && name.isTextual() && !map.containsKey(name.asText())) {
                    violations.add("schema: " + path(path, name.asText()) + " required");
                }
            }
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = properties.fields();
            while (it.hasNext()) {
                var entry = it.next();
                if (map.containsKey(entry.getKey())) {
                    check(map.get(entry.getKey()), entry.getValue(), path(path, entry.getKey()), violations, depth + 1);
                }
            }
        }
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && additional.isBoolean() && !additional.asBoolean()) {
            Set<String> declared = new HashSet<>();
            if (properties != null && properties.isObject()) {
                properties.fieldNames().forEachRemaining(declared::add);
            }
            for (Object key : map.keySet()) {
                if (!declared.contains(String.valueOf(key))) {
                    // The offending key is model output: name the object, not the key.
                    violations.add("schema: " + path + " additionalProperties");
                    break;
                }
            }
        }
    }

    private static boolean matchesType(Object value, JsonNode type) {
        if (type.isTextual()) {
            return matchesType(value, type.asText());
        }
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (t.isTextual() && matchesType(value, t.asText())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static boolean matchesType(Object value, String type) {
        return switch (type) {
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            case "number" -> value instanceof Number;
            case "integer" -> isInteger(value);
            default -> true;
        };
    }

    private static boolean isInteger(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof BigInteger) {
            return true;
        }
        if (value instanceof Double d) {
            return !d.isInfinite() && !d.isNaN() && d == Math.rint(d);
        }
        if (value instanceof Float f) {
            return !f.isInfinite() && !f.isNaN() && f == Math.rint(f);
        }
        if (value instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().scale() <= 0;
        }
        return false;
    }

    private static boolean inEnum(Object value, JsonNode enumNode) {
        for (JsonNode member : enumNode) {
            if (member.isNull()
                    ? value == null
                    : member.isTextual()
                            ? member.asText().equals(value)
                            : member.isBoolean()
                                    ? Boolean.valueOf(member.asBoolean()).equals(value)
                                    : member.isNumber() && numberEquals(member, value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * NaN / Infinity never equal an enum member (and would not parse as
     * BigDecimal).
     */
    private static boolean numberEquals(JsonNode member, Object value) {
        if (!(value instanceof Number n) || (n instanceof Double d && !Double.isFinite(d)) || (n instanceof Float f && !Float.isFinite(f))) {
            return false;
        }
        return member.decimalValue().compareTo(new BigDecimal(n.toString())) == 0;
    }

    private static boolean isNonBlankString(Object parsed, String field) {
        Object current = parsed;
        for (String segment : field.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return false;
            }
            current = map.get(segment);
        }
        return current instanceof String s && !s.isBlank();
    }

    private static String path(String base, String field) {
        String joined = base + "." + field;
        StringBuilder safe = new StringBuilder(joined.length());
        for (int i = 0; i < joined.length() && safe.length() < MAX_PATH_CHARS; i++) {
            char c = joined.charAt(i);
            safe.append(Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.' || c == '[' || c == ']' || c == '-' ? c : '_');
        }
        return safe.toString();
    }

    // ---- schema compilation
    // -------------------------------------------------------

    private record Compiled(JsonNode schema) {
        static final Compiled UNUSABLE = new Compiled(null);
    }

    private Compiled compile(String schemaJson, String taskId) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return Compiled.UNUSABLE;
        }
        Compiled cached = cache.get(schemaJson);
        if (cached != null) {
            return cached;
        }
        if (cache.size() >= MAX_CACHED_SCHEMAS) {
            cache.clear();
        }
        String problem = null;
        JsonNode node = null;
        try {
            node = MAPPER.readTree(schemaJson);
            problem = schemaProblem(node, "schema", 0);
        } catch (Exception e) {
            problem = "not valid JSON";
        }
        Compiled compiled = problem == null ? new Compiled(node) : Compiled.UNUSABLE;
        if (problem != null) {
            // Logged once: the cache holds the verdict, so later turns stay quiet.
            LOGGER.warnf("responseSchema%s is not usable (%s); shape validation is skipped", taskId != null ? " of task '" + taskId + "'" : "",
                    problem);
        }
        cache.put(schemaJson, compiled);
        return compiled;
    }

    /**
     * First problem found in a schema, or null. Checks only what the walker reads.
     */
    private static String schemaProblem(JsonNode schema, String where, int depth) {
        if (!schema.isObject()) {
            return where + " is not an object";
        }
        if (depth > MAX_DEPTH) {
            return "nested deeper than " + MAX_DEPTH;
        }
        JsonNode type = schema.get("type");
        if (type != null) {
            if (type.isTextual() ? !TYPES.contains(type.asText()) : !type.isArray() || !allKnownTypes(type)) {
                return where + ".type is not a known type";
            }
        }
        JsonNode required = schema.get("required");
        if (required != null && (!required.isArray() || !allText(required))) {
            return where + ".required is not an array of names";
        }
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && !enumNode.isArray()) {
            return where + ".enum is not an array";
        }
        JsonNode min = schema.get("minLength");
        if (min != null && (!min.canConvertToInt() || min.asInt() < 0)) {
            return where + ".minLength is not a non-negative integer";
        }
        JsonNode properties = schema.get("properties");
        if (properties != null) {
            if (!properties.isObject()) {
                return where + ".properties is not an object";
            }
            Iterator<Map.Entry<String, JsonNode>> it = properties.fields();
            while (it.hasNext()) {
                var entry = it.next();
                // A non-object property schema (e.g. `true`) is ignored, not an error.
                String nested = entry.getValue().isObject() ? schemaProblem(entry.getValue(), where + ".properties", depth + 1) : null;
                if (nested != null) {
                    return nested;
                }
            }
        }
        JsonNode items = schema.get("items");
        // Tuple-form (array) items are ignored by the walker, so they are not an error.
        return items != null && items.isObject() ? schemaProblem(items, where + ".items", depth + 1) : null;
    }

    private static boolean allKnownTypes(JsonNode array) {
        for (JsonNode t : array) {
            if (!t.isTextual() || !TYPES.contains(t.asText())) {
                return false;
            }
        }
        return array.size() > 0;
    }

    private static boolean allText(JsonNode array) {
        for (JsonNode t : array) {
            if (!t.isTextual()) {
                return false;
            }
        }
        return true;
    }
}
