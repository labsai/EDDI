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
     * by every key and string value of the text <em>parsed as JSON</em>.
     * <p>
     * The raw text alone was not enough. A JSON escape is invisible to a substring
     * test: the key {@code toolApprovals} with its first letter written as a JSON
     * unicode escape (backslash, {@code u0074}) does not contain
     * {@code toolapprovals}, yet every JSON parser downstream (the MCP server,
     * EDDI's own store) reads it as exactly that key, and an id can be spelt the
     * same way. Parsing undoes the escapes; a string value that is itself JSON is
     * parsed again, a few levels deep, because a document carried as a string is
     * unescaped once per level. Each parsed text is percent-decoded too, and the
     * percent-decoded raw text is parsed as well, so neither encoding can hide the
     * other. Text that is not JSON keeps the raw test, unchanged.
     */
    static String haystack(String raw) {
        StringBuilder haystack = new StringBuilder(decodeLower(raw));
        appendParsedText(raw, haystack, 0);
        if (raw != null) {
            String percentDecoded = percentDecode(raw);
            if (!percentDecoded.equals(raw)) {
                appendParsedText(percentDecoded, haystack, 0);
            }
        }
        return haystack.toString();
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
