/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The decoding the self-ungating heuristics share. {@link SelfUngatingGuard}
 * and {@link ApprovedCallScope} test whether a request names an id or a key
 * <em>after</em> the receiver has decoded it, and such a rule holds only while
 * its decoding matches the receiver's. There is therefore one decoding, here: a
 * hardening applied to it reaches both guards, and the two cannot disagree
 * about the same request.
 */
final class GuardText {

    /**
     * Parses arguments for the {@link #haystack}. Every non-standard syntax Jackson
     * knows is accepted (single quotes, unquoted names, comments, any escaped
     * character): a parse only ever adds text to the haystack, so the most lenient
     * parse is the safe one. Arguments a lenient receiver would read must not slip
     * past the test by failing a strict parse.
     */
    private static final ObjectMapper LENIENT = JsonMapper.builder().enable(JsonReadFeature.values()).build();
    /**
     * How deep {@link #haystack} follows a string value that is itself JSON (an MCP
     * {@code update_resource} carries the document as a JSON string).
     */
    private static final int MAX_NESTED_JSON_DEPTH = 4;
    /**
     * The characters a document {@link #LENIENT} accepts can start with: a bracket,
     * a quote of either kind, or a comment ({@code /*}, {@code //}, {@code #}).
     * Text starting with anything else is not parsed; one that starts with a
     * comment must be, or a lenient receiver reads an escaped id the test never
     * decodes.
     */
    private static final String DOCUMENT_STARTS = "{[\"'/#";
    private static final char SEPARATOR = ' ';
    private static final char BACKSLASH = 92;
    /*
     * The three patterns langchain4j's ToolExecutionRequestUtil applies, in this
     * order, when its first parse of the arguments fails (langchain4j 1.23.0,
     * normalizeJsonString then removeTrailingComma). Copied, not approximated: the
     * guard has to read what the executor reads.
     */
    private static final Pattern LEADING_TRAILING_QUOTE = Pattern.compile("^\"|\"$");
    private static final Pattern ESCAPED_QUOTE = Pattern.compile("\\\\\"");
    private static final Pattern TRAILING_COMMA = Pattern.compile(",(\\s*[}\\]])");

    private GuardText() {
    }

    /**
     * {@code value} percent-decoded, or unchanged when an escape is malformed — a
     * malformed escape is not a reason to stop checking.
     */
    static String percentDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    /** {@code value} percent-decoded and lower-cased; empty for null. */
    static String decodeLower(String value) {
        return value == null ? "" : percentDecode(value).toLowerCase(Locale.ROOT);
    }

    /**
     * Whether {@code haystack} (already {@linkplain #decodeLower lower-cased})
     * names {@code id}, case-insensitively. A blank id names nothing.
     */
    static boolean containsId(String haystack, String id) {
        return id != null && !id.isBlank() && haystack.contains(id.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * What an argument or URI test searches: the raw text percent-decoded, followed
     * by every reading of it a receiver might make.
     * <p>
     * The raw text alone was not enough. A JSON escape is invisible to a substring
     * test: the key {@code toolApprovals} with its first letter written as a JSON
     * unicode escape (backslash, {@code u0074}) does not contain
     * {@code toolapprovals}, yet every JSON parser downstream (the MCP server,
     * EDDI's own store) reads it as exactly that key, and an id can be spelt the
     * same way. So the haystack also holds, for the raw text and for its
     * percent-decoded form:
     * <ul>
     * <li>every key and string value of the text <em>parsed as lenient JSON</em>; a
     * string value that is itself JSON is parsed again, a few levels deep, because
     * a document carried as a string is unescaped once per level;</li>
     * <li>the same for the text as langchain4j <em>normalises</em> it when its
     * first parse fails ({@link #langchain4jNormalised}): built-in tools take their
     * arguments through that fallback, so text no JSON parser accepts can still
     * execute;</li>
     * <li>the text with its JSON escapes resolved in one pass
     * ({@link #jsonUnescaped}), whether or not it parses at all.</li>
     * </ul>
     * Each reading only ever adds text, so the coverage is a union: a request one
     * reading misses, another can still catch, and none can hide what the raw test
     * sees. Each parsed text is percent-decoded too, so neither encoding can hide
     * the other.
     */
    static String haystack(String raw) {
        StringBuilder haystack = new StringBuilder(decodeLower(raw));
        if (raw == null) {
            return haystack.toString();
        }
        appendReadings(raw, haystack);
        String percentDecoded = percentDecode(raw);
        if (!percentDecoded.equals(raw)) {
            appendReadings(percentDecoded, haystack);
        }
        return haystack.toString();
    }

    private static void appendReadings(String text, StringBuilder haystack) {
        appendParsedText(text, haystack, 0);
        String normalised = langchain4jNormalised(text);
        if (!normalised.equals(text)) {
            appendParsedText(normalised, haystack, 0);
        }
        String unescaped = jsonUnescaped(text);
        if (!unescaped.equals(text)) {
            haystack.append(SEPARATOR).append(decodeLower(unescaped));
        }
    }

    /**
     * {@code text} as langchain4j's {@code ToolExecutionRequestUtil.argumentsAsMap}
     * rewrites it before its second parse: one leading and one trailing double
     * quote removed, every backslash-quote turned into a quote, and a comma before
     * a closing brace or bracket dropped. langchain4j does this only after a strict
     * parse fails; the haystack does it always, since a reading only adds text.
     */
    static String langchain4jNormalised(String text) {
        String unquoted = LEADING_TRAILING_QUOTE.matcher(text).replaceAll("");
        String unescapedQuotes = ESCAPED_QUOTE.matcher(unquoted).replaceAll("\"");
        return TRAILING_COMMA.matcher(unescapedQuotes).replaceAll("$1");
    }

    /**
     * Resolves JSON string escapes the way a JSON parser would, in one pass over
     * the whole text and without parsing it: a {@code u} escape to its UTF-16 unit,
     * any other escaped character to itself (control-character escapes cannot be
     * part of an id or a key, so their exact value does not matter here). A
     * malformed {@code u} escape is kept as written.
     */
    static String jsonUnescaped(String text) {
        if (text.indexOf(BACKSLASH) < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != BACKSLASH || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char next = text.charAt(i + 1);
            if (next == 'u') {
                if (i + 6 <= text.length()) {
                    try {
                        out.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                        i += 5;
                        continue;
                    } catch (NumberFormatException e) {
                        // kept as written below
                    }
                }
                out.append(c);
                continue;
            }
            out.append(next);
            i++;
        }
        return out.toString();
    }

    private static void appendParsedText(String text, StringBuilder haystack, int depth) {
        if (text == null || depth > MAX_NESTED_JSON_DEPTH) {
            return;
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty() || DOCUMENT_STARTS.indexOf(trimmed.charAt(0)) < 0) {
            return;
        }
        JsonNode tree;
        try {
            tree = LENIENT.readTree(trimmed);
        } catch (JsonProcessingException e) {
            return;
        }
        appendNode(tree, haystack, depth);
    }

    private static void appendNode(JsonNode node, StringBuilder haystack, int depth) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                haystack.append(SEPARATOR).append(decodeLower(field.getKey()));
                appendNode(field.getValue(), haystack, depth);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                appendNode(element, haystack, depth);
            }
        } else if (node.isTextual()) {
            String value = node.asText();
            haystack.append(SEPARATOR).append(decodeLower(value));
            appendParsedText(value, haystack, depth + 1);
        }
    }
}
