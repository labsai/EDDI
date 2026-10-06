/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns the task's {@code responseSchema} text into a langchain4j
 * {@link JsonSchema} for provider-native enforcement.
 * <p>
 * Conversion is <strong>all or nothing</strong>: a schema this class cannot
 * express faithfully yields {@link Optional#empty()}, and the request then
 * falls back to schemaless JSON mode plus the prompt block. Sending a
 * half-converted schema would make the provider enforce something the designer
 * did not write.
 * <p>
 * Convertible: a root {@code object} with at least one property, whose
 * properties are {@code string} (optionally with an all-string {@code enum}),
 * {@code integer}, {@code number}, {@code boolean}, nested {@code object}, or
 * {@code array} with an {@code items} schema; plus {@code required},
 * {@code description} and a boolean {@code additionalProperties}. Not
 * convertible (the whole schema falls back): type arrays, a missing
 * {@code type}, {@code null}, {@code $ref}, {@code anyOf}/{@code oneOf}, an
 * array without {@code items}, a non-object root. {@code minLength},
 * {@code pattern} and the like have no counterpart in langchain4j's element
 * model and are dropped here — EDDI's own validation still enforces
 * {@code minLength} after the reply arrives.
 */
public final class ResponseSchemaConverter {

    /**
     * Name sent to providers that require one (OpenAI {@code json_schema.name}).
     */
    static final String SCHEMA_NAME = "response";

    private static final int MAX_DEPTH = 16;
    private static final int MAX_CACHED = 256;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** schema text to its conversion; empty values are cached too. */
    private static final Map<String, Optional<JsonSchema>> CACHE = new ConcurrentHashMap<>();

    private ResponseSchemaConverter() {
    }

    /**
     * @param responseSchemaJson
     *            the task's {@code responseSchema}, may be null/blank
     * @return the native schema, or empty when absent or not convertible
     */
    public static Optional<JsonSchema> convert(String responseSchemaJson) {
        if (responseSchemaJson == null || responseSchemaJson.isBlank()) {
            return Optional.empty();
        }
        Optional<JsonSchema> cached = CACHE.get(responseSchemaJson);
        if (cached != null) {
            return cached;
        }
        if (CACHE.size() >= MAX_CACHED) {
            CACHE.clear();
        }
        Optional<JsonSchema> converted = doConvert(responseSchemaJson);
        CACHE.put(responseSchemaJson, converted);
        return converted;
    }

    /**
     * Like {@link #convert}, but the root is the author's schema text as a
     * {@link JsonRawSchema}. Needed where a binding's typed mapper drops a keyword
     * the designer relies on: langchain4j 1.20.2's Gemini {@code SchemaMapper}
     * never carries {@code additionalProperties}, so a closed object would be sent
     * open. Still empty when the schema is not convertible at all, so the request
     * falls back to schemaless JSON exactly as {@link #convert} does.
     */
    public static Optional<JsonSchema> convertRaw(String responseSchemaJson) {
        return convert(responseSchemaJson)
                .map(typed -> JsonSchema.builder().name(SCHEMA_NAME).rootElement(JsonRawSchema.from(responseSchemaJson)).build());
    }

    /**
     * Whether any object in the schema says {@code additionalProperties: false}.
     */
    public static boolean hasClosedObject(String responseSchemaJson) {
        try {
            return responseSchemaJson != null && containsClosed(MAPPER.readTree(responseSchemaJson), 0);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean containsClosed(JsonNode node, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return false;
        }
        JsonNode additional = node.get("additionalProperties");
        if (additional != null && additional.isBoolean() && !additional.asBoolean()) {
            return true;
        }
        JsonNode properties = node.get("properties");
        if (properties != null && properties.isObject()) {
            for (JsonNode child : properties) {
                if (containsClosed(child, depth + 1)) {
                    return true;
                }
            }
        }
        return containsClosed(node.get("items"), depth + 1);
    }

    private static Optional<JsonSchema> doConvert(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject() || !"object".equals(text(root, "type")) || !hasProperties(root)) {
                return Optional.empty();
            }
            JsonSchemaElement element = element(root, 0);
            return element == null ? Optional.empty() : Optional.of(JsonSchema.builder().name(SCHEMA_NAME).rootElement(element).build());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static JsonSchemaElement element(JsonNode node, int depth) {
        if (depth > MAX_DEPTH || node == null || !node.isObject()) {
            return null;
        }
        String description = text(node, "description");
        return switch (String.valueOf(text(node, "type"))) {
            case "string" -> stringElement(node, description);
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            case "array" -> arrayElement(node, description, depth);
            case "object" -> objectElement(node, description, depth);
            default -> null;
        };
    }

    private static JsonSchemaElement stringElement(JsonNode node, String description) {
        JsonNode enumNode = node.get("enum");
        if (enumNode != null) {
            // An enum we cannot carry (not an array, empty) must not silently become a
            // plain string: all or nothing.
            if (!enumNode.isArray() || enumNode.isEmpty()) {
                return null;
            }
            List<String> values = new ArrayList<>();
            for (JsonNode member : enumNode) {
                if (!member.isTextual()) {
                    return null;
                }
                values.add(member.asText());
            }
            return JsonEnumSchema.builder().description(description).enumValues(values).build();
        }
        return JsonStringSchema.builder().description(description).build();
    }

    private static JsonSchemaElement arrayElement(JsonNode node, String description, int depth) {
        JsonSchemaElement items = element(node.get("items"), depth + 1);
        return items == null ? null : JsonArraySchema.builder().description(description).items(items).build();
    }

    private static JsonSchemaElement objectElement(JsonNode node, String description, int depth) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder().description(description);
        JsonNode properties = node.get("properties");
        List<String> declared = new ArrayList<>();
        if (properties != null && properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = properties.fields();
            while (it.hasNext()) {
                var entry = it.next();
                JsonSchemaElement child = element(entry.getValue(), depth + 1);
                if (child == null) {
                    return null;
                }
                builder.addProperty(entry.getKey(), child);
                declared.add(entry.getKey());
            }
        }
        JsonNode required = node.get("required");
        if (required != null && required.isArray()) {
            List<String> names = new ArrayList<>();
            for (JsonNode name : required) {
                if (name.isTextual() && declared.contains(name.asText())) {
                    names.add(name.asText());
                }
            }
            if (!names.isEmpty()) {
                builder.required(names);
            }
        }
        JsonNode additional = node.get("additionalProperties");
        if (additional != null && additional.isBoolean()) {
            builder.additionalProperties(additional.asBoolean());
        }
        return builder.build();
    }

    private static boolean hasProperties(JsonNode node) {
        JsonNode properties = node.get("properties");
        return properties != null && properties.isObject() && properties.size() > 0;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
