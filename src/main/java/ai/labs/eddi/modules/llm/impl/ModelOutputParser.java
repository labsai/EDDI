/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <li>inside JSON strings, drop the backslash of {@code \'}. JSON has no such
 * escape, and models trained on JavaScript and Python emit it
 * ({@code Japan\'s}), so one apostrophe failed an otherwise complete reply. It
 * can only mean {@code '}. The pass is string-aware: {@code \\'} (an escaped
 * backslash, then a quote) and anything outside a string are left alone;</li>
 * <li>if that still does not parse, take the outermost balanced {@code {...}}
 * or {@code [...]} out of the text (string- and escape-aware scan) and parse
 * that — this handles a prose prefix and/or suffix. An extracted fragment is
 * accepted only if it is a JSON object with at least one key; a top-level array
 * is accepted only as the whole (unfenced) reply, so prose like
 * {@code Pick option [1] or [2].} is never turned into a List.</li>
 * </ol>
 * Apart from that one escape, invalid JSON is never "fixed" (no trailing-comma
 * repair, no quote swapping): it either parses or it is {@link Kind#INVALID}.
 * <p>
 * Three more local rescues are opt-in through {@link RescueOptions} (absent =
 * none of them runs): a one-level unescape of a double-escaped object
 * ({@code {\"a\": \"b\"}}, accepted only if it then parses to an object and
 * only for a reply that did not parse as it was); {@code fieldAliases}, which
 * rename a top-level key of a parsed object when the target key is absent; and
 * {@code wrap}, which turns a still-unparseable prose reply into
 * <code>{ wrapField: text }</code> (never text that starts with {@code {} or
 * {@code [}, and never text over the configured length).
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
     * @param wrapped
     *            true when the value is a plain-text reply wrapped into an object
     *            ({@link RescueOptions#wrapField()}); also true on a wrapped reply
     *            that then broke the shape
     */
    public record JsonOutcome(Kind kind, Object value, boolean repaired, String reason, String raw, boolean wrapped) {

        public JsonOutcome(Kind kind, Object value, boolean repaired, String reason, String raw) {
            this(kind, value, repaired, reason, raw, false);
        }

        /**
         * The label recorded on the step and used as the metric tag:
         * {@code valid|repaired|wrapped|invalid|empty|schema_mismatch}.
         */
        public String label() {
            return switch (kind) {
                case VALID -> wrapped ? "wrapped" : repaired ? "repaired" : "valid";
                case INVALID -> "invalid";
                case EMPTY -> "empty";
                case SCHEMA_MISMATCH -> "schema_mismatch";
            };
        }
    }

    /** A Markdown code fence: three backticks. */
    private static final String FENCE = "```";

    /** The info string a JSON fence may carry, matched ASCII case-insensitively. */
    private static final String JSON_TAG = "json";

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
        return parse(raw, convertToObject, responseSchema, nonBlankFields, taskId, RescueOptions.NONE);
    }

    /**
     * As above with the local rescue options of the task: the double-escape repair,
     * {@code fieldAliases} and the plain-text {@code wrap}. This is the one path
     * every caller shares, so a reply that one caller (live turn, resume, the
     * format-retry runner's detection, the circuit breaker) sees as usable is
     * usable for all of them. The order is: fence strip / object extraction,
     * {@code \'} repair, double-escape repair, parse, aliases, shape validation,
     * and only for a reply that is still not JSON, {@code wrap}.
     */
    public JsonOutcome parse(String raw, boolean convertToObject, String responseSchema, List<String> nonBlankFields, String taskId,
                             RescueOptions rescue) {
        JsonOutcome outcome = parse(raw, convertToObject, rescue);
        if (!convertToObject || outcome.kind() != Kind.VALID) {
            return outcome;
        }
        return shapeValidator.validate(outcome.value(), responseSchema, nonBlankFields, taskId)
                .map(reason -> new JsonOutcome(Kind.SCHEMA_MISMATCH, outcome.value(), outcome.repaired(), reason, outcome.raw(),
                        outcome.wrapped()))
                .orElse(outcome);
    }

    /**
     * @param raw
     *            the model's reply, may be null
     * @param convertToObject
     *            when false the reply is passed through untouched as a VALID
     *            outcome — callers do not record an outcome in that mode
     */
    public JsonOutcome parse(String raw, boolean convertToObject) {
        return parse(raw, convertToObject, RescueOptions.NONE);
    }

    /**
     * As {@link #parse(String, boolean)} with rescue options;
     * {@link RescueOptions#NONE} is exactly the two-argument form.
     */
    public JsonOutcome parse(String raw, boolean convertToObject, RescueOptions rescue) {
        if (!convertToObject) {
            return new JsonOutcome(Kind.VALID, raw, false, null, raw);
        }
        RescueOptions options = rescue != null ? rescue : RescueOptions.NONE;
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return new JsonOutcome(Kind.EMPTY, raw, false, null, raw);
        }

        String unfenced = stripFence(trimmed);
        String whole = dropApostropheEscapes(unfenced);
        // A stripped fence or a dropped \' escape: either way the reply was repaired.
        boolean normalised = !whole.equals(trimmed);

        Object parsed = tryParseWhole(whole);
        if (parsed != null) {
            return valid(parsed, normalised, options, raw);
        }

        // Double-escaped object ({\"a\": \"b\"}): only when asked for, only for a reply
        // that did not parse, and only accepted as a JSON object.
        if (options.doubleEscape() && looksDoubleEscaped(unfenced)) {
            Object unescaped = tryParseWhole(dropApostropheEscapes(unescapeOneLevel(unfenced)));
            if (unescaped instanceof Map<?, ?>) {
                return valid(unescaped, true, options, raw);
            }
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
            // The whole text was already tried above; do not parse it a second time. A
            // candidate is repaired on its own: a stray quote in the prose before it must
            // not decide what counts as "inside a string" within the object.
            Object value = candidate.equals(unfenced) ? null : tryParse(dropApostropheEscapes(candidate));
            // An extracted fragment (there was text around it) is only trusted when it is
            // a non-empty object: prose such as "Pick option [1] or [2]." or "The empty
            // set {} is..." must stay the user's answer, not become a List or empty Map.
            // A top-level array is accepted only as the whole reply, handled above.
            if (value instanceof Map<?, ?> map && !map.isEmpty()) {
                return valid(value, true, options, raw);
            }
            reason = value == null ? REASON_INVALID_SYNTAX : REASON_NO_OBJECT;
            from = scan.end() + 1;
        }
        JsonOutcome wrapped = wrap(trimmed, unfenced, options, raw);
        return wrapped != null ? wrapped : new JsonOutcome(Kind.INVALID, raw, false, reason, raw);
    }

    /** A parsed reply, with the task's field aliases applied. */
    private static JsonOutcome valid(Object parsed, boolean repaired, RescueOptions options, String raw) {
        if (options.fieldAliases().isEmpty() || !(parsed instanceof Map<?, ?> map)) {
            return new JsonOutcome(Kind.VALID, parsed, repaired, null, raw);
        }
        Map<String, Object> renamed = null;
        for (Map.Entry<String, String> alias : options.fieldAliases().entrySet()) {
            Map<?, ?> current = renamed != null ? renamed : map;
            // Rename only into an absent target: when the model sent both keys, the
            // target is the one the template reads and it wins.
            if (current.containsKey(alias.getKey()) && !current.containsKey(alias.getValue())) {
                if (renamed == null) {
                    renamed = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        renamed.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
                renamed.put(alias.getValue(), renamed.remove(alias.getKey()));
            }
        }
        return renamed == null ? new JsonOutcome(Kind.VALID, parsed, repaired, null, raw) : new JsonOutcome(Kind.VALID, renamed, true, null, raw);
    }

    /**
     * {@code { wrapField: text }} (plus {@code wrapDefaults}) for a reply that is
     * prose, or null when wrapping is off or the reply is not wrappable: it starts
     * with {@code {} or {@code [} (broken JSON, not prose, with or without a
     * fence), or it is longer than the cap.
     */
    private static JsonOutcome wrap(String trimmed, String unfenced, RescueOptions options, String raw) {
        if (!options.wraps() || trimmed.length() > options.wrapMaxChars() || startsLikeJson(trimmed) || startsLikeJson(unfenced)) {
            return null;
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : options.wrapDefaults().entrySet()) {
            wrapped.put(e.getKey(), deepCopy(e.getValue()));
        }
        wrapped.put(options.wrapField(), trimmed);
        return new JsonOutcome(Kind.VALID, wrapped, true, null, raw, true);
    }

    private static boolean startsLikeJson(String text) {
        return text.startsWith("{") || text.startsWith("[");
    }

    /**
     * A copy of a configured default, so no reply shares a list with the config.
     */
    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), deepCopy(v)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(v -> copy.add(deepCopy(v)));
            return copy;
        }
        return value;
    }

    /**
     * Whether an unparseable text has the shape of an object that was JSON-escaped
     * once too often: it opens with {@code {\"}, or it opens with a brace and holds
     * an escaped value after a key ({@code "k": \"v}).
     */
    private static boolean looksDoubleEscaped(String text) {
        return text.startsWith("{\\\"") || text.startsWith("{") && (text.contains("\": \\\"") || text.contains("\":\\\""));
    }

    /**
     * One level of JSON string-unescaping applied to the whole text: {@code \"}
     * becomes {@code "} and {@code \\} becomes {@code \} (so {@code \\n} becomes
     * the JSON escape {@code \n} and {@code \\\"} becomes {@code \"}). Every other
     * backslash is copied as it is. Left to right and pairwise, so an escaped
     * backslash is never read as the start of another escape.
     */
    static String unescapeOneLevel(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && (text.charAt(i + 1) == '"' || text.charAt(i + 1) == '\\')) {
                out.append(text.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The body of a reply wrapped in one Markdown code fence, optionally tagged
     * {@code json}; anything else unchanged.
     * <p>
     * String operations, not a regex. The pattern this replaces,
     * {@code ^```(?:json)?[ \t]*\r?\n?(.*?)\s*```$}, had three quantifiers
     * competing for one whitespace run, so a reply of three backticks, N spaces and
     * a letter cost O(N³): 3.7 s at 2,000 spaces, about an hour at 16,000, on the
     * turn thread of any {@code convertToObject} agent whose model can be talked
     * into emitting it (security review 2026-10-17, H3). The result is the same for
     * every input: the pattern's leading {@code [ \t]*\r?\n?} and trailing
     * {@code \s*} only moved whitespace that {@code trim()} removed anyway, and its
     * optional {@code json} was taken whenever it was there.
     */
    static String stripFence(String trimmed) {
        int length = trimmed.length();
        if (length < 2 * FENCE.length() || !trimmed.startsWith(FENCE) || !trimmed.endsWith(FENCE)) {
            return trimmed;
        }
        int start = FENCE.length();
        if (hasJsonTag(trimmed, start)) {
            start += JSON_TAG.length();
        }
        return trimmed.substring(start, length - FENCE.length()).trim();
    }

    /**
     * {@code text} with the backslash of every {@code \'} inside a JSON string
     * removed; {@code text} itself when it holds no {@code \'}.
     * <p>
     * One linear pass that tracks whether it is inside a string. Every other escape
     * is copied as the pair it is, so {@code \\'} stays an escaped backslash
     * followed by a quote and {@code \"} does not end the string. Outside strings
     * nothing changes: valid JSON cannot hold {@code \'} there, and prose around
     * the object is the extraction step's business.
     */
    static String dropApostropheEscapes(String text) {
        if (text.indexOf("\\'") < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString && c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                if (next != '\'') {
                    out.append(c);
                }
                out.append(next);
                continue;
            }
            if (c == '"') {
                inString = !inString;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * {@code json} at {@code at}, ASCII letters in either case: what the old
     * pattern's {@code CASE_INSENSITIVE} (without {@code UNICODE_CASE}) admitted.
     * {@code c | 0x20} folds exactly {@code J S O N} onto {@code j s o n}; no other
     * character maps there.
     */
    private static boolean hasJsonTag(String text, int at) {
        if (text.length() - FENCE.length() < at + JSON_TAG.length()) {
            return false;
        }
        for (int i = 0; i < JSON_TAG.length(); i++) {
            if ((text.charAt(at + i) | 0x20) != JSON_TAG.charAt(i)) {
                return false;
            }
        }
        return true;
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

    /**
     * The first balanced {@code {...}} object in {@code text} — from its first
     * {@code {} to the matching closer, honouring string literals, escapes and
     * nested brackets — or {@code null} when there is none, or it is unbalanced or
     * cut short.
     * <p>
     * The one scanner for pulling a JSON object out of model prose within the LLM
     * module (the confidence judge uses it too), so a reply that one reader accepts
     * is not misread by another: the greedy first-{@code {}-to-last-{@code }} slice
     * breaks as soon as prose after the object contains a brace.
     */
    public static String extractFirstObject(String text) {
        if (text == null) {
            return null;
        }
        int open = text.indexOf('{');
        if (open < 0) {
            return null;
        }
        int end = scan(text, open).end();
        return end < 0 ? null : text.substring(open, end + 1);
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
