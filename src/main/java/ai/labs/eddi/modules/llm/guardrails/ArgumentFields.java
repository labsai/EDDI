/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The fields of a tool call's arguments that a {@code toolCallGuardrails} entry
 * screens, and the rewrite of those fields in place.
 * <p>
 * A field is addressed by a JSON pointer (RFC 6901) with one extension: a
 * segment that is exactly {@code *} means "every element of an array, every
 * member of an object". The empty pointer {@code ""} is the whole arguments
 * document, serialized — for a guardrail that reads JSON ({@code expression},
 * {@code json-object}). With no fields configured every string leaf of the
 * arguments is screened.
 * <p>
 * Numbers are kept exact and object members keep their order, and the arguments
 * are re-serialized only when a field actually changed, so a call no guardrail
 * rewrote reaches its tool byte for byte as the model wrote it.
 * <p>
 * Bounded: at most {@link #MAX_LEAVES} fields and {@link #MAX_CHARS} characters
 * of text per call. Past either the call is too large to screen
 * ({@link TooLargeException}), which the caller treats as "could not decide" —
 * the guardrail's {@code failMode} applies, so a {@code block} entry refuses
 * the call rather than letting it through unscreened.
 *
 * @since 6.8.0
 */
public final class ArgumentFields {

    /** At most this many fields are screened per call and entry. */
    public static final int MAX_LEAVES = 256;

    /** At most this many characters of text are screened per call and entry. */
    public static final int MAX_CHARS = 256 * 1024;

    /** The wildcard segment. */
    public static final String WILDCARD = "*";

    /** What a field configuration of "every string leaf" reports as its field. */
    public static final String ALL_STRING_FIELDS = "*";

    private static final ObjectMapper MAPPER = JsonMapper.builder().nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private ArgumentFields() {
    }

    /** The arguments could not be parsed as JSON. */
    public static final class UnparseableException extends Exception {
        UnparseableException() {
            super("arguments_unparseable", null, false, false);
        }
    }

    /** The selected fields exceed {@link #MAX_LEAVES} or {@link #MAX_CHARS}. */
    public static final class TooLargeException extends Exception {
        TooLargeException() {
            super("arguments_too_large", null, false, false);
        }
    }

    /**
     * Parses the raw arguments of a tool call. Null or blank arguments are the
     * empty object, as the executors treat them.
     */
    public static Document parse(String rawArguments) throws UnparseableException {
        if (rawArguments == null || rawArguments.isBlank()) {
            return new Document(rawArguments, MAPPER.createObjectNode());
        }
        if (rawArguments.length() > 4 * MAX_CHARS) {
            // Larger than anything that could be screened; not worth parsing.
            return new Document(rawArguments, null);
        }
        try {
            JsonNode root = MAPPER.readTree(rawArguments);
            if (root == null || root.isMissingNode()) {
                throw new UnparseableException();
            }
            return new Document(rawArguments, root);
        } catch (JsonProcessingException e) {
            throw new UnparseableException();
        }
    }

    /**
     * Whether {@code pointer} is a valid field: {@code ""} or a JSON pointer whose
     * {@code ~} escapes are {@code ~0}/{@code ~1} and whose {@code *} appears only
     * as a whole segment. Returns the problem, or null when it is valid.
     */
    public static String pointerProblem(String pointer) {
        if (pointer == null) {
            return "a field must not be null";
        }
        if (pointer.isEmpty()) {
            return null;
        }
        if (pointer.charAt(0) != '/') {
            return "'" + shown(pointer) + "' is not a JSON pointer: it must be \"\" (the whole arguments) or start with '/', e.g. \"/body\"";
        }
        for (String segment : pointer.substring(1).split("/", -1)) {
            for (int i = 0; i < segment.length(); i++) {
                if (segment.charAt(i) == '~' && (i + 1 >= segment.length() || (segment.charAt(i + 1) != '0' && segment.charAt(i + 1) != '1'))) {
                    return "'" + shown(pointer) + "': '~' must be followed by 0 or 1 (~0 is '~', ~1 is '/')";
                }
            }
            if (segment.contains(WILDCARD) && !segment.equals(WILDCARD)) {
                return "'" + shown(pointer) + "': '*' may only stand for a whole segment (every element or member), as in \"/items/*/text\"";
            }
        }
        return null;
    }

    private static String shown(String text) {
        String cut = text.length() > 60 ? text.substring(0, 60) + "..." : text;
        return cut.replaceAll("[\\r\\n\\t]", "_");
    }

    /** One parsed arguments document. Not thread-safe; one per call screening. */
    public static final class Document {
        private final String raw;
        private JsonNode root;
        private boolean changed;

        private Document(String raw, JsonNode root) {
            this.raw = raw;
            this.root = root;
        }

        /** True when a field was rewritten. */
        public boolean changed() {
            return changed;
        }

        /**
         * The arguments to send: the raw text when nothing changed, otherwise the
         * re-serialized document.
         */
        public String arguments() {
            if (!changed) {
                return raw;
            }
            try {
                return MAPPER.writeValueAsString(root);
            } catch (JsonProcessingException e) {
                // A tree Jackson built itself always serializes.
                throw new IllegalStateException("tool-call arguments could not be serialized", e);
            }
        }

        /**
         * The fields one entry screens: every string leaf when {@code fields} is null
         * or empty, otherwise each pointer's targets (a missing path selects nothing).
         *
         * @throws TooLargeException
         *             past {@link #MAX_LEAVES} fields or {@link #MAX_CHARS} characters
         */
        public List<Field> select(List<String> fields) throws TooLargeException {
            if (root == null) {
                throw new TooLargeException();
            }
            List<Field> selected = new ArrayList<>();
            int[] chars = {0};
            if (fields == null || fields.isEmpty()) {
                collectStrings(root, null, null, "", selected, chars);
                return selected;
            }
            for (String pointer : fields) {
                if (pointer == null) {
                    continue;
                }
                if (pointer.isEmpty()) {
                    add(selected, chars, new Field(this, "", null, null, true));
                    continue;
                }
                List<String> segments = new ArrayList<>();
                for (String segment : pointer.substring(1).split("/", -1)) {
                    segments.add(segment);
                }
                walk(root, null, null, segments, 0, pointer, selected, chars);
            }
            return selected;
        }

        private void walk(JsonNode node, JsonNode parent, Object key, List<String> segments, int index, String pointer, List<Field> out, int[] chars)
                throws TooLargeException {
            if (index == segments.size()) {
                add(out, chars, new Field(this, pointer, parent, key, false));
                return;
            }
            String segment = segments.get(index);
            if (WILDCARD.equals(segment)) {
                if (node.isArray()) {
                    for (int i = 0; i < node.size(); i++) {
                        walk(node.get(i), node, i, segments, index + 1, pointer, out, chars);
                    }
                } else if (node.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> members = node.fields();
                    while (members.hasNext()) {
                        Map.Entry<String, JsonNode> member = members.next();
                        walk(member.getValue(), node, member.getKey(), segments, index + 1, pointer, out, chars);
                    }
                }
                return;
            }
            String name = segment.replace("~1", "/").replace("~0", "~");
            if (node.isObject()) {
                JsonNode child = node.get(name);
                if (child != null) {
                    walk(child, node, name, segments, index + 1, pointer, out, chars);
                }
            } else if (node.isArray() && isIndex(name)) {
                int i = Integer.parseInt(name);
                if (i < node.size()) {
                    walk(node.get(i), node, i, segments, index + 1, pointer, out, chars);
                }
            }
        }

        private static boolean isIndex(String name) {
            if (name.isEmpty() || name.length() > 9 || (name.length() > 1 && name.charAt(0) == '0')) {
                return false;
            }
            for (int i = 0; i < name.length(); i++) {
                if (!Character.isDigit(name.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        private void collectStrings(JsonNode node, JsonNode parent, Object key, String pointer, List<Field> out, int[] chars)
                throws TooLargeException {
            if (node.isTextual()) {
                add(out, chars, new Field(this, pointer, parent, key, false));
            } else if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    collectStrings(node.get(i), node, i, pointer + "/" + i, out, chars);
                }
            } else if (node.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> members = node.fields();
                while (members.hasNext()) {
                    Map.Entry<String, JsonNode> member = members.next();
                    String escaped = member.getKey().replace("~", "~0").replace("/", "~1");
                    collectStrings(member.getValue(), node, member.getKey(), pointer + "/" + escaped, out, chars);
                }
            }
        }

        private static void add(List<Field> out, int[] chars, Field field) throws TooLargeException {
            if (out.size() >= MAX_LEAVES) {
                throw new TooLargeException();
            }
            chars[0] += field.text().length();
            if (chars[0] > MAX_CHARS) {
                throw new TooLargeException();
            }
            out.add(field);
        }

        private JsonNode node(JsonNode parent, Object key) {
            if (parent == null) {
                return root;
            }
            return key instanceof Integer i ? parent.get(i) : parent.get((String) key);
        }
    }

    /**
     * One selected field: its text and the means to rewrite it. The pointer is the
     * concrete one (wildcards resolved) — it names model-written keys, so it is for
     * matching and debugging only and is never recorded.
     */
    public static final class Field {
        private final Document document;
        private final String pointer;
        private final JsonNode parent;
        private final Object key;
        private final boolean wholeDocument;
        private String text;

        private Field(Document document, String pointer, JsonNode parent, Object key, boolean wholeDocument) {
            this.document = document;
            this.pointer = pointer;
            this.parent = parent;
            this.key = key;
            this.wholeDocument = wholeDocument;
        }

        /** The concrete pointer of this field. */
        public String pointer() {
            return pointer;
        }

        /**
         * The text a guardrail screens: a string's value, any other value (and the
         * whole document) as JSON.
         */
        public String text() {
            if (text == null) {
                JsonNode node = wholeDocument ? document.root : document.node(parent, key);
                if (node == null) {
                    text = "";
                } else if (node.isTextual()) {
                    text = node.textValue();
                } else {
                    try {
                        text = MAPPER.writeValueAsString(node);
                    } catch (JsonProcessingException e) {
                        text = "";
                    }
                }
            }
            return text;
        }

        /**
         * Replaces this field's value with {@code replacement}. Only a string field can
         * take a text, and the whole document only a text that is itself JSON; any
         * other rewrite cannot be applied and returns false, so the caller fails safe.
         */
        public boolean replace(String replacement) {
            if (replacement == null) {
                return false;
            }
            if (replacement.equals(text())) {
                return true;
            }
            if (wholeDocument) {
                try {
                    JsonNode parsed = MAPPER.readTree(replacement);
                    if (parsed == null || parsed.isMissingNode()) {
                        return false;
                    }
                    document.root = parsed;
                } catch (JsonProcessingException e) {
                    return false;
                }
            } else {
                JsonNode current = document.node(parent, key);
                if (current == null || !current.isTextual()) {
                    return false;
                }
                if (parent instanceof ObjectNode object) {
                    object.set((String) key, TextNode.valueOf(replacement));
                } else if (parent instanceof ArrayNode array) {
                    array.set((Integer) key, TextNode.valueOf(replacement));
                } else {
                    // A bare string as the whole arguments: the document is that string.
                    document.root = TextNode.valueOf(replacement);
                }
            }
            text = replacement;
            document.changed = true;
            return true;
        }
    }
}
