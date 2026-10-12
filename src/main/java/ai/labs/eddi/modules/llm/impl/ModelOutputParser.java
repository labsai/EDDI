/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a model's raw reply into the object a {@code convertToObject} task
 * stores, and <b>never throws</b>. One instance serves both the live path and
 * the HITL resume path of {@link LlmTask}, so the two cannot drift apart.
 *
 * <h2>Normalisation, in this order, and nothing else</h2>
 * <ol>
 * <li>trim;</li>
 * <li>strip <em>one</em> surrounding markdown fence ({@code ```} or
 * {@code ```json}, tag case-insensitive);</li>
 * <li>if that still does not parse, take the outermost balanced {@code {...}}
 * or {@code [...]} out of the text (string- and escape-aware scan) and parse
 * that — this handles a prose prefix and/or suffix. An extracted fragment is
 * accepted only if it is a JSON object with at least one key; a top-level array
 * is accepted only as the whole (unfenced) reply, so prose like
 * {@code Pick option [1] or [2].} is never turned into a List.</li>
 * </ol>
 * Invalid JSON itself is never "fixed" (no trailing-comma repair, no quote
 * swapping): it either parses or it is {@link Kind#INVALID}.
 *
 * <h2>Reasons</h2> {@link JsonOutcome#reason()} is always one of this class'
 * own constants. It never contains model output, because it is meant to be fed
 * back to the model in a corrective message, and model output echoed into a
 * prompt is an injection channel.
 */
public final class ModelOutputParser {

    /** The reply does not contain a JSON object or array. */
    static final String REASON_NOT_JSON = "not JSON";
    /** A JSON value was opened and the reply ended before it was closed. */
    static final String REASON_TRUNCATED = "truncated JSON";
    /** Only a fragment of the reply parsed, and it is not a non-empty object. */
    static final String REASON_NO_OBJECT = "no JSON object";
    /** A closing bracket does not match the one that was opened. */
    static final String REASON_UNBALANCED = "unbalanced braces";
    /** Braces balance, but the content between them is not valid JSON. */
    static final String REASON_INVALID_SYNTAX = "invalid JSON syntax";

    /** What happened to a reply. */
    public enum Kind {
        /**
         * Parsed (possibly after repair) and, if a shape was configured, matches it.
         */
        VALID,
        /** Not parseable JSON; the raw reply is the value. */
        INVALID,
        /** Null or blank reply. */
        EMPTY,
        /**
         * Parsed, but violates the configured {@code responseSchema} /
         * {@code nonBlankFields}. Unlike {@link #INVALID} the value <em>is</em> the
         * parsed object — templates that worked on a partially valid object keep
         * working — and {@link JsonOutcome#reason()} says what is wrong.
         */
        SCHEMA_MISMATCH
    }

    /**
     * Result of {@link #parse}.
     *
     * @param kind
     *            VALID, INVALID or EMPTY
     * @param value
     *            the parsed Map/List when VALID or SCHEMA_MISMATCH; the raw reply
     *            otherwise (today's observable behaviour: the raw string is what
     *            gets stored)
     * @param repaired
     *            true when VALID only after fence stripping or extraction
     * @param reason
     *            EDDI-generated reason when INVALID or SCHEMA_MISMATCH, else null.
     *            Never model output
     * @param raw
     *            the reply exactly as received
     */
    public record JsonOutcome(Kind kind, Object value, boolean repaired, String reason, String raw) {

        /**
         * The label recorded on the step and used as the metric tag:
         * {@code valid|repaired|invalid|empty|schema_mismatch}.
         */
        public String label() {
            return switch (kind) {
                case VALID -> repaired ? "repaired" : "valid";
                case INVALID -> "invalid";
                case EMPTY -> "empty";
                case SCHEMA_MISMATCH -> "schema_mismatch";
            };
        }
    }

    private static final Pattern FENCE = Pattern.compile("^```(?:json)?[ \\t]*\\r?\\n?(.*?)\\s*```$", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final IJsonSerialization jsonSerialization;
    private final ResponseShapeValidator shapeValidator = new ResponseShapeValidator();

    public ModelOutputParser(IJsonSerialization jsonSerialization) {
        this.jsonSerialization = jsonSerialization;
    }

    /**
     * {@link #parse(String, boolean)} followed by shape validation: a reply that
     * parsed but violates {@code responseSchema} / {@code nonBlankFields} becomes
     * {@link Kind#SCHEMA_MISMATCH}, carrying the <em>parsed</em> value and the
     * violation as the reason. Invalid and empty replies are returned as they are
     * (they have no shape to check), and with neither a schema nor non-blank fields
     * this is exactly {@code parse}.
     *
     * @param taskId
     *            only labels the one-time warning for an unusable schema
     */
    public JsonOutcome parse(String raw, boolean convertToObject, String responseSchema, List<String> nonBlankFields, String taskId) {
        JsonOutcome outcome = parse(raw, convertToObject);
        if (!convertToObject || outcome.kind() != Kind.VALID) {
            return outcome;
        }
        return shapeValidator.validate(outcome.value(), responseSchema, nonBlankFields, taskId)
                .map(reason -> new JsonOutcome(Kind.SCHEMA_MISMATCH, outcome.value(), outcome.repaired(), reason, outcome.raw())).orElse(outcome);
    }

    /**
     * @param raw
     *            the model's reply, may be null
     * @param convertToObject
     *            when false the reply is passed through untouched as a VALID
     *            outcome — callers do not record an outcome in that mode
     */
    public JsonOutcome parse(String raw, boolean convertToObject) {
        if (!convertToObject) {
            return new JsonOutcome(Kind.VALID, raw, false, null, raw);
        }
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return new JsonOutcome(Kind.EMPTY, raw, false, null, raw);
        }

        String unfenced = stripFence(trimmed);
        boolean fenceStripped = !unfenced.equals(trimmed);

        Object parsed = tryParseWhole(unfenced);
        if (parsed != null) {
            return new JsonOutcome(Kind.VALID, parsed, fenceStripped, null, raw);
        }

        // Extraction: successive top-level balanced candidates, first one that parses.
        String reason = REASON_NOT_JSON;
        int from = 0;
        while (from < unfenced.length()) {
            int open = nextOpener(unfenced, from);
            if (open < 0) {
                break;
            }
            Scan scan = scan(unfenced, open);
            if (scan.end() < 0) {
                reason = scan.mismatch() ? REASON_UNBALANCED : REASON_TRUNCATED;
                break;
            }
            String candidate = unfenced.substring(open, scan.end() + 1);
            // The whole text was already tried above; do not parse it a second time.
            Object value = candidate.equals(unfenced) ? null : tryParse(candidate);
            // An extracted fragment (there was text around it) is only trusted when it is
            // a non-empty object: prose such as "Pick option [1] or [2]." or "The empty
            // set {} is..." must stay the user's answer, not become a List or empty Map.
            // A top-level array is accepted only as the whole reply, handled above.
            if (value instanceof Map<?, ?> map && !map.isEmpty()) {
                return new JsonOutcome(Kind.VALID, value, true, null, raw);
            }
            reason = value == null ? REASON_INVALID_SYNTAX : REASON_NO_OBJECT;
            from = scan.end() + 1;
        }
        return new JsonOutcome(Kind.INVALID, raw, false, reason, raw);
    }

    static String stripFence(String trimmed) {
        Matcher m = FENCE.matcher(trimmed);
        return m.matches() ? m.group(1).trim() : trimmed;
    }

    /**
     * Whole-reply parse. The injected mapper tolerates trailing tokens, so
     * {@code [1,2] and more prose} would deserialize to the List; require that the
     * balanced root starting at index 0 ends at the last character before parsing.
     */
    private Object tryParseWhole(String text) {
        if (!text.startsWith("{") && !text.startsWith("[")) {
            return null;
        }
        if (scan(text, 0).end() != text.length() - 1) {
            return null;
        }
        return tryParse(text);
    }

    private Object tryParse(String text) {
        Class<?> target = text.startsWith("{") ? Map.class : text.startsWith("[") ? List.class : null;
        if (target == null) {
            return null;
        }
        try {
            return jsonSerialization.deserialize(text, target);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static int nextOpener(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{' || c == '[') {
                return i;
            }
        }
        return -1;
    }

    private record Scan(int end, boolean mismatch) {
    }

    /**
     * String- and escape-aware bracket scan from an opener. {@code end} is the
     * index of the matching closer, or -1 with {@code mismatch} telling a wrong
     * closer (unbalanced) from running out of text (truncated).
     */
    private static Scan scan(String s, int open) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> stack.push(c);
                case '}', ']' -> {
                    char expected = c == '}' ? '{' : '[';
                    if (stack.isEmpty() || stack.pop() != expected) {
                        return new Scan(-1, true);
                    }
                    if (stack.isEmpty()) {
                        return new Scan(i, false);
                    }
                }
                default -> {
                }
            }
        }
        return new Scan(-1, false);
    }
}
