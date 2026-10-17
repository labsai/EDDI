/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Governs the <em>parameter schema</em> of a remote tool the way
 * {@link RemoteTextGovernor} governs its description.
 * <p>
 * A tool's {@code inputSchema} is prompt content too: every property
 * description, every title and every enum value is rendered into the model's
 * tool definitions, verbatim, by whoever runs the server. Governing the
 * description alone and rebuilding the spec with the parameters untouched left
 * that half of the surface unredacted and unbounded (security review
 * 2026-10-15, finding M7). This walks the schema and
 * <ul>
 * <li>redacts directive-shaped spans and caps the length of every description,
 * title and enum value it finds, at any depth — objects, arrays, {@code anyOf}
 * branches, {@code $defs} and a raw JSON-Schema fragment alike;</li>
 * <li>bounds the serialised size of one tool's schema: a schema over the cap
 * first loses its descriptions (the untrusted prose, usually the bulk) and is
 * dropped if the bare structure is still over it — a schema the model cannot be
 * handed whole cannot be handed at all, since a partial schema makes the model
 * call the tool with the wrong arguments;</li>
 * <li>through {@link ServerBudget}, bounds the total a single server may put
 * into the prompt across all of its tools.</li>
 * </ul>
 * Pure functions over langchain4j's schema model; the managers own the config
 * values, the logging and the metrics.
 */
public final class ToolSchemaGovernor {

    /**
     * Nesting deeper than this is refused: no real tool needs it, a hostile schema
     * would.
     */
    public static final int MAX_DEPTH = 32;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolSchemaGovernor() {
    }

    /**
     * What happened to one schema.
     *
     * @param schema
     *            the governed schema, or {@code null} when the tool must be dropped
     * @param redacted
     *            whether a directive-shaped span was redacted somewhere in it
     * @param truncated
     *            whether a text was cut to the character cap, or every description
     *            was stripped to fit the byte cap
     * @param bytes
     *            serialised size of the governed schema (0 when dropped)
     * @param dropReason
     *            why the tool must be dropped, or {@code null}
     */
    public record Result(JsonObjectSchema schema, boolean redacted, boolean truncated, int bytes, String dropReason) {
        public boolean dropped() {
            return dropReason != null;
        }

        public boolean changed() {
            return redacted || truncated || dropped();
        }
    }

    /**
     * Per-server running total of schema bytes admitted into the prompt. Not
     * thread-safe: one instance per discovery pass.
     */
    public static final class ServerBudget {
        private final long maxBytes;
        private long used;

        public ServerBudget(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        /** Whether {@code bytes} more fit; charged when they do. */
        public boolean admit(int bytes) {
            if (used + bytes > maxBytes) {
                return false;
            }
            used += bytes;
            return true;
        }

        public long used() {
            return used;
        }

        public long maxBytes() {
            return maxBytes;
        }
    }

    /**
     * Governs one tool's parameter schema.
     *
     * @param schema
     *            the remote schema; {@code null} is returned as-is
     * @param maxTextChars
     *            cap for every description, title and enum value
     * @param maxBytes
     *            cap on the serialised schema
     */
    public static Result govern(JsonObjectSchema schema, int maxTextChars, int maxBytes) {
        if (schema == null) {
            return new Result(null, false, false, 0, null);
        }
        Walker walker = new Walker(maxTextChars, false);
        JsonObjectSchema governed;
        try {
            governed = (JsonObjectSchema) walker.walk(schema, 0);
        } catch (TooDeepException e) {
            return new Result(null, walker.redacted, walker.truncated, 0, "schema nests deeper than " + MAX_DEPTH + " levels");
        } catch (UnverifiableException e) {
            return new Result(null, walker.redacted, walker.truncated, 0, e.getMessage());
        }
        int bytes = serializedBytes(governed);
        if (bytes <= maxBytes) {
            return new Result(governed, walker.redacted, walker.truncated, bytes, null);
        }
        // Over the cap: the prose goes first, the structure stays.
        Walker stripper = new Walker(maxTextChars, true);
        JsonObjectSchema bare;
        try {
            bare = (JsonObjectSchema) stripper.walk(schema, 0);
        } catch (TooDeepException | UnverifiableException e) {
            return new Result(null, walker.redacted, true, 0, e.getMessage());
        }
        int bareBytes = serializedBytes(bare);
        if (bareBytes <= maxBytes) {
            return new Result(bare, walker.redacted, true, bareBytes, null);
        }
        return new Result(null, walker.redacted, true, 0,
                "schema is " + bytes + " bytes (" + bareBytes + " without descriptions), over the cap of " + maxBytes);
    }

    /** UTF-8 size of the schema as a provider would serialise it. */
    public static int serializedBytes(JsonSchemaElement schema) {
        if (schema == null) {
            return 0;
        }
        try {
            return MAPPER.writeValueAsString(JsonSchemaElementUtils.toMap(schema)).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException | RuntimeException e) {
            // A schema that cannot be serialised cannot be prompted with either —
            // count it as infinitely large so the byte cap drops it.
            return Integer.MAX_VALUE;
        }
    }

    private static final class TooDeepException extends RuntimeException {
        TooDeepException() {
            super("schema nests deeper than " + MAX_DEPTH + " levels", null, false, false);
        }
    }

    private static final class UnverifiableException extends RuntimeException {
        UnverifiableException(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * One pass over a schema; records whether it redacted or truncated anything.
     */
    private static final class Walker {
        private final int maxTextChars;
        private final boolean stripDescriptions;
        boolean redacted;
        boolean truncated;

        Walker(int maxTextChars, boolean stripDescriptions) {
            this.maxTextChars = maxTextChars;
            this.stripDescriptions = stripDescriptions;
        }

        private String text(String value) {
            if (value == null) {
                return null;
            }
            if (stripDescriptions) {
                truncated = true;
                return null;
            }
            if (RemoteTextGovernor.containsDirective(value)) {
                redacted = true;
            }
            if (value.length() > maxTextChars) {
                truncated = true;
            }
            String governed = RemoteTextGovernor.govern(value, maxTextChars);
            return governed.isEmpty() ? null : governed;
        }

        /**
         * An enum value is never stripped — it is structure — only redacted and capped.
         */
        private String enumValue(String value) {
            if (value == null) {
                return null;
            }
            if (RemoteTextGovernor.containsDirective(value)) {
                redacted = true;
            }
            if (value.length() > maxTextChars) {
                truncated = true;
            }
            return RemoteTextGovernor.govern(value, maxTextChars);
        }

        JsonSchemaElement walk(JsonSchemaElement element, int depth) {
            if (element == null) {
                return null;
            }
            if (depth > MAX_DEPTH) {
                throw new TooDeepException();
            }
            if (element instanceof JsonObjectSchema object) {
                return walkObject(object, depth);
            }
            if (element instanceof JsonArraySchema array) {
                return JsonArraySchema.builder().description(text(array.description())).items(walk(array.items(), depth + 1)).build();
            }
            if (element instanceof JsonEnumSchema enumeration) {
                List<String> values = new ArrayList<>();
                if (enumeration.enumValues() != null) {
                    for (String value : enumeration.enumValues()) {
                        values.add(enumValue(value));
                    }
                }
                return JsonEnumSchema.builder().description(text(enumeration.description())).enumValues(values).build();
            }
            if (element instanceof JsonAnyOfSchema anyOf) {
                List<JsonSchemaElement> branches = new ArrayList<>();
                if (anyOf.anyOf() != null) {
                    for (JsonSchemaElement branch : anyOf.anyOf()) {
                        branches.add(walk(branch, depth + 1));
                    }
                }
                return JsonAnyOfSchema.builder().description(text(anyOf.description())).anyOf(branches).build();
            }
            if (element instanceof JsonStringSchema string) {
                return JsonStringSchema.builder().description(text(string.description())).build();
            }
            if (element instanceof JsonIntegerSchema integer) {
                return JsonIntegerSchema.builder().description(text(integer.description())).build();
            }
            if (element instanceof JsonNumberSchema number) {
                return JsonNumberSchema.builder().description(text(number.description())).build();
            }
            if (element instanceof JsonBooleanSchema bool) {
                return JsonBooleanSchema.builder().description(text(bool.description())).build();
            }
            if (element instanceof JsonRawSchema raw) {
                return walkRaw(raw, depth);
            }
            // JsonNullSchema and JsonReferenceSchema carry no remote text that can be
            // rebuilt (a reference's description has no setter); anything newer that
            // this walker does not know is left as it is rather than dropped, because
            // only the known element kinds carry a description.
            return element;
        }

        private JsonObjectSchema walkObject(JsonObjectSchema object, int depth) {
            JsonObjectSchema.Builder builder = JsonObjectSchema.builder().description(text(object.description()));
            if (object.properties() != null) {
                Map<String, JsonSchemaElement> properties = new LinkedHashMap<>();
                for (Map.Entry<String, JsonSchemaElement> property : object.properties().entrySet()) {
                    properties.put(property.getKey(), walk(property.getValue(), depth + 1));
                }
                builder.addProperties(properties);
            }
            if (object.required() != null) {
                builder.required(object.required());
            }
            builder.additionalProperties(object.additionalProperties());
            if (object.definitions() != null) {
                Map<String, JsonSchemaElement> definitions = new LinkedHashMap<>();
                for (Map.Entry<String, JsonSchemaElement> definition : object.definitions().entrySet()) {
                    definitions.put(definition.getKey(), walk(definition.getValue(), depth + 1));
                }
                builder.definitions(definitions);
            }
            return builder.build();
        }

        /**
         * A raw JSON-Schema fragment is governed as a document: every
         * {@code description} and {@code title} string and every {@code enum} entry, at
         * any depth. One that does not parse cannot be verified and the tool is dropped
         * — "could not see it" is not "it is not there".
         */
        private JsonRawSchema walkRaw(JsonRawSchema raw, int depth) {
            if (raw.schema() == null || raw.schema().isBlank()) {
                return raw;
            }
            JsonNode tree;
            try {
                tree = MAPPER.readTree(raw.schema());
            } catch (JsonProcessingException e) {
                throw new UnverifiableException("raw schema fragment is not valid JSON");
            }
            walkRawNode(tree, depth);
            try {
                return JsonRawSchema.from(MAPPER.writeValueAsString(tree));
            } catch (JsonProcessingException e) {
                throw new UnverifiableException("raw schema fragment could not be re-serialised");
            }
        }

        private void walkRawNode(JsonNode node, int depth) {
            if (depth > MAX_DEPTH) {
                throw new TooDeepException();
            }
            if (node instanceof ObjectNode object) {
                for (String key : List.of("description", "title")) {
                    JsonNode value = object.get(key);
                    if (value != null && value.isTextual()) {
                        String governed = text(value.asText());
                        if (governed == null) {
                            object.remove(key);
                        } else {
                            object.put(key, governed);
                        }
                    }
                }
                JsonNode enumeration = object.get("enum");
                if (enumeration instanceof ArrayNode values) {
                    for (int i = 0; i < values.size(); i++) {
                        if (values.get(i).isTextual()) {
                            values.set(i, MAPPER.getNodeFactory().textNode(enumValue(values.get(i).asText())));
                        }
                    }
                }
                Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (!"enum".equals(field.getKey())) {
                        walkRawNode(field.getValue(), depth + 1);
                    }
                }
            } else if (node instanceof ArrayNode array) {
                for (JsonNode element : array) {
                    walkRawNode(element, depth + 1);
                }
            }
        }
    }
}
