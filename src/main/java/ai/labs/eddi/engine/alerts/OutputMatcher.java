/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertCondition;
import ai.labs.eddi.engine.alerts.model.MatchOp;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@code OUTPUT_MATCH} matcher: a dot path into one of three places, a
 * fixed comparison, and the rule author's constant. Deliberately small — no
 * expression language, no template evaluation of runtime data, no wildcards.
 *
 * <h3>Paths</h3>
 * <ul>
 * <li>{@code properties.<name>[.<a.b>]} — the conversation properties after the
 * turn;</li>
 * <li>{@code output.json.<a.b.c>} — the agent's output parsed as JSON, only
 * when it is a JSON object or array of at most
 * {@link TurnSnapshot#MAX_OUTPUT_CHARS} characters (and nesting at most
 * {@link #MAX_JSON_NESTING});</li>
 * <li>{@code actions} — the turn's action list.</li>
 * </ul>
 * Segments are letters, digits, {@code _} and {@code -}; at most
 * {@link #MAX_DEPTH} after the source.
 */
public final class OutputMatcher {

    public static final int MAX_DEPTH = 8;
    public static final int MAX_IN_VALUES = 50;
    public static final int MAX_VALUE_LENGTH = 1000;
    static final int MAX_JSON_NESTING = 64;

    public static final String ACTIONS = "actions";
    public static final String PROPERTIES_PREFIX = "properties.";
    public static final String OUTPUT_JSON_PREFIX = "output.json.";

    private static final Pattern PATH = Pattern
            .compile("actions|properties(\\.[A-Za-z0-9_-]{1,100}){1,8}|output\\.json(\\.[A-Za-z0-9_-]{1,100}){1,8}");

    private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_JSON_NESTING)
                    .maxStringLength(TurnSnapshot.MAX_OUTPUT_CHARS).build()).build());

    private OutputMatcher() {
    }

    // ------------------------------------------------------------------ validation

    /**
     * Problems of an OUTPUT_MATCH condition, each naming its field; empty when
     * valid.
     */
    public static List<String> problems(AlertCondition c) {
        List<String> problems = new ArrayList<>();
        String path = c.path();
        if (path == null || !PATH.matcher(path).matches()) {
            problems.add("condition.path must be actions, properties.<name> or output.json.<a.b.c> — dot-separated names "
                    + "(letters, digits, _ and -), at most " + MAX_DEPTH + " deep, no wildcards");
        }
        MatchOp op = c.op();
        if (op == null) {
            problems.add("condition.op is required: exists, eq, neq, in, gt, gte, lt, lte or contains");
            return problems;
        }
        if (ACTIONS.equals(path) && op != MatchOp.CONTAINS && op != MatchOp.EXISTS) {
            problems.add("condition.op on actions is contains or exists");
        }
        Object value = c.value();
        if (!op.needsValue()) {
            if (value != null) {
                problems.add("condition.value is not used with exists");
            }
            return problems;
        }
        if (value == null) {
            problems.add("condition.value is required with " + op.value());
            return problems;
        }
        switch (op) {
            case GT, GTE, LT, LTE -> {
                if (!(value instanceof Number)) {
                    problems.add("condition.value must be a number with " + op.value());
                }
            }
            case IN -> {
                if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > MAX_IN_VALUES) {
                    problems.add("condition.value must be a list of 1 to " + MAX_IN_VALUES + " values with in");
                } else if (!list.stream().allMatch(OutputMatcher::isScalar)) {
                    problems.add("condition.value: in compares with strings, numbers or booleans only");
                } else if (list.stream().anyMatch(v -> v instanceof String s && s.length() > MAX_VALUE_LENGTH)) {
                    problems.add("condition.value: each value is at most " + MAX_VALUE_LENGTH + " characters");
                }
            }
            case CONTAINS -> {
                if (!(value instanceof String)) {
                    problems.add("condition.value must be a string with contains");
                }
            }
            default -> {
                if (!isScalar(value)) {
                    problems.add("condition.value must be a string, number or boolean with " + op.value());
                }
            }
        }
        if (value instanceof String s && s.length() > MAX_VALUE_LENGTH) {
            problems.add("condition.value is at most " + MAX_VALUE_LENGTH + " characters");
        }
        return problems;
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    // ------------------------------------------------------------------ evaluation

    /** Whether the condition holds for the snapshot. */
    public static boolean matches(AlertCondition c, TurnSnapshot snapshot) {
        Optional<Object> actual = resolve(c.path(), snapshot);
        return compare(c.op(), actual.orElse(null), c.value());
    }

    /** The value at the path, or empty when it is absent or null. */
    public static Optional<Object> resolve(String path, TurnSnapshot snapshot) {
        if (path == null) {
            return Optional.empty();
        }
        if (ACTIONS.equals(path)) {
            // A turn with no actions has none to exist: an empty list is absent, so
            // "actions exists" does not fire on every clean turn.
            List<String> actions = snapshot.actions();
            return actions == null || actions.isEmpty() ? Optional.empty() : Optional.of(actions);
        }
        if (path.startsWith(PROPERTIES_PREFIX)) {
            String[] segments = path.substring(PROPERTIES_PREFIX.length()).split("\\.");
            Object node = snapshot.properties().get(segments[0]);
            for (int i = 1; i < segments.length && node != null; i++) {
                node = node instanceof Map<?, ?> map ? map.get(segments[i]) : null;
            }
            return Optional.ofNullable(node);
        }
        if (path.startsWith(OUTPUT_JSON_PREFIX)) {
            JsonNode node = parse(snapshot.outputText());
            for (String segment : path.substring(OUTPUT_JSON_PREFIX.length()).split("\\.")) {
                node = node == null ? null : node.get(segment);
            }
            return Optional.ofNullable(plain(node));
        }
        return Optional.empty();
    }

    /**
     * The output as JSON when it is a JSON object or array within the caps;
     * {@code null} otherwise.
     */
    static JsonNode parse(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.length() > TurnSnapshot.MAX_OUTPUT_CHARS || trimmed.isEmpty()
                || !(trimmed.charAt(0) == '{' || trimmed.charAt(0) == '[')) {
            return null;
        }
        try {
            return JSON.readTree(trimmed);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object plain(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(element -> list.add(plain(element)));
            return list;
        }
        return JSON.convertValue(node, Map.class);
    }

    static boolean compare(MatchOp op, Object actual, Object constant) {
        if (op == null || actual == null) {
            return false;
        }
        return switch (op) {
            case EXISTS -> true;
            case EQ -> scalarEquals(actual, constant);
            case NEQ -> !scalarEquals(actual, constant);
            case IN -> constant instanceof List<?> list && list.stream().anyMatch(v -> scalarEquals(actual, v));
            case GT -> numbers(actual, constant) && toDouble(actual) > toDouble(constant);
            case GTE -> numbers(actual, constant) && toDouble(actual) >= toDouble(constant);
            case LT -> numbers(actual, constant) && toDouble(actual) < toDouble(constant);
            case LTE -> numbers(actual, constant) && toDouble(actual) <= toDouble(constant);
            case CONTAINS -> actual instanceof String s
                    ? constant instanceof String c && s.contains(c)
                    : actual instanceof List<?> list && list.stream().anyMatch(v -> scalarEquals(v, constant));
        };
    }

    private static boolean scalarEquals(Object actual, Object constant) {
        if (actual == null || constant == null) {
            return false;
        }
        if (actual instanceof Number && constant instanceof Number) {
            return Double.compare(toDouble(actual), toDouble(constant)) == 0;
        }
        if (actual instanceof Map || actual instanceof List) {
            return false;
        }
        return actual.getClass() == constant.getClass() ? actual.equals(constant) : false;
    }

    private static boolean numbers(Object a, Object b) {
        return a instanceof Number && b instanceof Number;
    }

    private static double toDouble(Object number) {
        return ((Number) number).doubleValue();
    }

    /**
     * {@code properties.escalate eq true} — the rule's own words, never the runtime
     * value.
     */
    public static String describe(AlertCondition c) {
        String constant = c.value() == null ? "" : " " + (c.value() instanceof String s ? "\"" + s + "\"" : String.valueOf(c.value()));
        return c.path() + " " + (c.op() == null ? "?" : c.op().value()) + constant;
    }

    /** The runtime value as text, cut to {@code max} characters. */
    public static String render(Object value, int max) {
        String text;
        try {
            text = value instanceof String s ? s : JSON.writeValueAsString(value);
        } catch (Exception e) {
            text = String.valueOf(value);
        }
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max - 1)) + "…";
    }
}
