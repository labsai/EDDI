/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns OpenAPI schemas into the JSON Schema subset an httpcall's
 * {@code argumentSchema} holds (see {@code ApiCall.argumentSchema} and
 * {@code ToolArgumentSchemaValidator}): {@code type}, {@code properties},
 * {@code required}, {@code items}, {@code enum}, boolean
 * {@code additionalProperties}, {@code description}, {@code nullable} and the
 * numeric, length and size bounds.
 * <p>
 * Component {@code $ref}s are resolved, at every depth, so the stored schema is
 * self-contained. A recursive reference, a composition the subset cannot
 * express ({@code anyOf}, {@code oneOf}, an {@code allOf} of non-objects) or a
 * schema without a type becomes an untyped node, which accepts any value: the
 * check stays exact where the spec is exact and never refuses what it cannot
 * read. {@code readOnly} properties are left out of request bodies, as OpenAPI
 * says they are not sent.
 */
final class OpenApiArgumentSchemas {

    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";
    private static final int MAX_DEPTH = 24;
    private static final Set<String> SCALAR_TYPES = Set.of("string", "integer", "number", "boolean");

    private OpenApiArgumentSchemas() {
    }

    /**
     * A path or query parameter. Only scalar types are kept: a path or query value
     * is substituted into the URL as text, so an array or object parameter stays a
     * string, as every parameter was before typed schemas.
     */
    static Map<String, Object> parameterSchema(Schema<?> schema, OpenAPI openAPI) {
        Map<String, Object> converted = convert(schema, openAPI, new HashSet<>(), 0);
        Object type = converted.get("type");
        if (type instanceof String name && SCALAR_TYPES.contains(name)) {
            return converted;
        }
        Map<String, Object> string = new LinkedHashMap<>();
        string.put("type", "string");
        return string;
    }

    /** A JSON request body, fully resolved. */
    static Map<String, Object> bodySchema(Schema<?> schema, OpenAPI openAPI) {
        return convert(schema, openAPI, new HashSet<>(), 0);
    }

    static Map<String, Object> convert(Schema<?> schema, OpenAPI openAPI, Set<String> resolving, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (schema == null || depth > MAX_DEPTH) {
            return out;
        }
        String ref = schema.get$ref();
        if (ref != null) {
            if (!ref.startsWith(SCHEMA_REF_PREFIX) || openAPI == null || openAPI.getComponents() == null
                    || openAPI.getComponents().getSchemas() == null) {
                return out;
            }
            String name = ref.substring(SCHEMA_REF_PREFIX.length());
            Schema<?> target = openAPI.getComponents().getSchemas().get(name);
            if (target == null || !resolving.add(name)) {
                // unknown, or a recursive structure: accept anything below this point
                return out;
            }
            try {
                return convert(target, openAPI, resolving, depth + 1);
            } finally {
                resolving.remove(name);
            }
        }
        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            return mergeAllOf(schema, openAPI, resolving, depth);
        }
        if (notEmpty(schema.getAnyOf()) || notEmpty(schema.getOneOf())) {
            putDescription(out, schema);
            return out;
        }
        String type = typeOf(schema);
        if (type == null) {
            putDescription(out, schema);
            return out;
        }
        out.put("type", type);
        putDescription(out, schema);
        if (Boolean.TRUE.equals(schema.getNullable()) || (schema.getTypes() != null && schema.getTypes().contains("null"))) {
            out.put("nullable", true);
        }
        if (notEmpty(schema.getEnum())) {
            List<Object> values = new ArrayList<>();
            for (Object value : schema.getEnum()) {
                if (value != null) {
                    values.add(plain(value));
                }
            }
            if (!values.isEmpty()) {
                out.put("enum", values);
            }
        }
        putBound(out, "minimum", schema.getMinimum(), schema.getExclusiveMinimum(), "exclusiveMinimum");
        putBound(out, "maximum", schema.getMaximum(), schema.getExclusiveMaximum(), "exclusiveMaximum");
        // OpenAPI 3.1 states an exclusive bound as its own number
        if (schema.getExclusiveMinimumValue() != null) {
            out.put("exclusiveMinimum", plain(schema.getExclusiveMinimumValue()));
        }
        if (schema.getExclusiveMaximumValue() != null) {
            out.put("exclusiveMaximum", plain(schema.getExclusiveMaximumValue()));
        }
        putInt(out, "minLength", schema.getMinLength());
        putInt(out, "maxLength", schema.getMaxLength());
        putInt(out, "minItems", schema.getMinItems());
        putInt(out, "maxItems", schema.getMaxItems());
        if ("array".equals(type)) {
            out.put("items", convert(schema.getItems(), openAPI, resolving, depth + 1));
        } else if ("object".equals(type)) {
            putObjectShape(out, schema, openAPI, resolving, depth);
        }
        return out;
    }

    private static void putObjectShape(Map<String, Object> out, Schema<?> schema, OpenAPI openAPI, Set<String> resolving, int depth) {
        @SuppressWarnings("rawtypes")
        Map<String, Schema> properties = schema.getProperties();
        if (properties != null && !properties.isEmpty()) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (var entry : properties.entrySet()) {
                if (entry.getValue() != null && Boolean.TRUE.equals(entry.getValue().getReadOnly())) {
                    continue;
                }
                converted.put(entry.getKey(), convert(entry.getValue(), openAPI, resolving, depth + 1));
            }
            out.put("properties", converted);
            if (schema.getRequired() != null) {
                List<String> required = schema.getRequired().stream().filter(converted::containsKey).toList();
                if (!required.isEmpty()) {
                    out.put("required", new ArrayList<>(required));
                }
            }
        }
        Object additional = schema.getAdditionalProperties();
        if (additional instanceof Boolean allowed) {
            out.put("additionalProperties", allowed);
        } else if (additional instanceof Schema<?>) {
            out.put("additionalProperties", true);
        }
    }

    /**
     * An {@code allOf} of objects (the usual "base type plus fields" idiom) is one
     * object with every part's properties and required names. Anything else is
     * untyped.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mergeAllOf(Schema<?> schema, OpenAPI openAPI, Set<String> resolving, int depth) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        Boolean additional = null;
        List<Schema> parts = new ArrayList<>(schema.getAllOf());
        if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            Schema<Object> own = new Schema<>();
            own.setType("object");
            own.setProperties(schema.getProperties());
            own.setRequired(schema.getRequired());
            parts.add(own);
        }
        for (Schema<?> part : parts) {
            Map<String, Object> converted = convert(part, openAPI, resolving, depth + 1);
            if (!"object".equals(converted.get("type"))) {
                Map<String, Object> untyped = new LinkedHashMap<>();
                putDescription(untyped, schema);
                return untyped;
            }
            if (converted.get("properties") instanceof Map<?, ?> partProperties) {
                properties.putAll((Map<String, Object>) partProperties);
            }
            if (converted.get("required") instanceof List<?> partRequired) {
                partRequired.forEach(name -> {
                    if (!required.contains(name)) {
                        required.add((String) name);
                    }
                });
            }
            if (Boolean.TRUE.equals(converted.get("additionalProperties"))) {
                additional = true;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        putDescription(out, schema);
        out.put("properties", properties);
        if (!required.isEmpty()) {
            out.put("required", required);
        }
        if (additional != null) {
            out.put("additionalProperties", additional);
        }
        return out;
    }

    /**
     * The declared type, OpenAPI 3.0 or 3.1 style, or the one implied by the shape.
     */
    static String typeOf(Schema<?> schema) {
        if (schema.getType() != null) {
            return schema.getType();
        }
        if (schema.getTypes() != null) {
            List<String> concrete = schema.getTypes().stream().filter(t -> !"null".equals(t)).toList();
            if (concrete.size() == 1) {
                return concrete.getFirst();
            }
            if (!concrete.isEmpty()) {
                return null;
            }
        }
        if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            return "object";
        }
        if (schema.getItems() != null) {
            return "array";
        }
        return null;
    }

    private static void putDescription(Map<String, Object> out, Schema<?> schema) {
        if (schema.getDescription() != null && !schema.getDescription().isBlank()) {
            out.put("description", schema.getDescription().trim());
        }
    }

    private static void putBound(Map<String, Object> out, String keyword, BigDecimal value, Boolean exclusive, String exclusiveKeyword) {
        if (value == null) {
            return;
        }
        out.put(Boolean.TRUE.equals(exclusive) ? exclusiveKeyword : keyword, plain(value));
    }

    private static void putInt(Map<String, Object> out, String keyword, Integer value) {
        if (value != null) {
            out.put(keyword, value);
        }
    }

    /**
     * A JSON-friendly value: a {@link BigDecimal} (how the parser reads every
     * number) becomes a long or a double, so the stored config holds plain numbers.
     */
    private static Object plain(Object value) {
        if (value instanceof BigDecimal decimal) {
            try {
                return decimal.longValueExact();
            } catch (ArithmeticException notIntegral) {
                return decimal.doubleValue();
            }
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        return String.valueOf(value);
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }
}
