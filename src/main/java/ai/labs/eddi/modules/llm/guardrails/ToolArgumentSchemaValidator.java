/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonNodePath;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.AllowSchemaLoader;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks a tool call's arguments against the JSON Schema its configuration
 * declares, before the call is shown to the approval gate or executed.
 * <p>
 * The pre-execution counterpart of {@link ToolResultGuardrail}: a pure function
 * of (schema, policy, arguments), with no CDI state, so it can later become one
 * type of a configurable tool-call guardrail without changing what it decides.
 * Today its checks run for the httpcall tool ({@code HttpCallToolsProvider},
 * see {@code ApiCall.argumentSchema}); {@code ToolLoopRunner} reuses
 * {@link #refusal} for the refusals it issues itself.
 * <p>
 * <b>The subset.</b> Only keywords that constrain a value a model writes, and
 * that are safe to evaluate on model input, are honoured: {@code type},
 * {@code properties}, {@code required}, {@code items}, {@code enum}, boolean
 * {@code additionalProperties}, {@code nullable} (OpenAPI 3.0 style),
 * {@code minimum}/{@code maximum} (and their exclusive forms),
 * {@code minLength}/{@code maxLength} and {@code minItems}/{@code maxItems}.
 * Everything else is removed before validation: {@code $ref} (the generator
 * resolves references; a hand-written one would otherwise make the validator
 * load something), {@code pattern} (an author's regex run on model text is a
 * backtracking risk the API itself is better placed to judge) and
 * {@code format}. Validation itself is delegated to the networknt validator the
 * build already ships for artifact validators; this class only builds the
 * effective schema and words the violations for the model.
 */
public final class ToolArgumentSchemaValidator {

    /** How undeclared fields are treated. See {@code ApiCall.unknownFields}. */
    public enum UnknownFields {
        REJECT, DROP, ALLOW;

        /** {@code null}, blank or unrecognised is {@link #REJECT}. */
        public static UnknownFields of(String value) {
            if (value == null) {
                return REJECT;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "drop" -> DROP;
                case "allow" -> ALLOW;
                default -> REJECT;
            };
        }
    }

    /**
     * The outcome of one check.
     *
     * @param arguments
     *            the arguments as they should be used — with a JSON-encoded string
     *            given for an object or array parameter decoded, and, under
     *            {@link UnknownFields#DROP}, undeclared fields removed;
     *            {@code null} when the arguments are not a JSON object at all
     * @param violations
     *            one line per violation ({@code $.requestBody.role: must be one of
     *            manager, curator}); empty when the call may proceed
     */
    public record Result(ObjectNode arguments, List<String> violations) {
        public Result {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }

        /** Whether the call may proceed: no violations. */
        public boolean valid() {
            return violations.isEmpty();
        }
    }

    /** The violation reported when the configured schema itself is unusable. */
    static final String SCHEMA_NOT_COMPILED = "$: the argument schema of this tool could not be compiled; the call was refused";

    private static final Logger LOGGER = Logger.getLogger(ToolArgumentSchemaValidator.class);

    /** Violations quoted back to the model; the rest are counted. */
    static final int MAX_VIOLATIONS = 20;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_CACHED = 512;
    private static final Set<String> INTEGER_KEYWORDS = Set.of("minLength", "maxLength", "minItems", "maxItems");
    private static final Set<String> NUMBER_KEYWORDS = Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum");

    /**
     * Strict, like every other parser of model output in the tool path: a value
     * followed by trailing text is not a JSON document.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * Refuses every schema IRI: the effective schema never contains a {@code $ref},
     * and nothing may be fetched on behalf of a tool call.
     */
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaLoaders(loaders -> loaders.values(list -> list.addFirst(new AllowSchemaLoader(iri -> false)))));

    /** Effective schema (text + policy) to its compiled form. */
    private static final Map<String, Compiled> CACHE = new ConcurrentHashMap<>();

    private record Compiled(ObjectNode effective, JsonSchema schema) {
    }

    private ToolArgumentSchemaValidator() {
    }

    /**
     * @param schema
     *            the declared argument schema; {@code null} or empty means "no
     *            schema" and every call is valid
     * @param unknownFields
     *            the configured policy, see {@link UnknownFields#of}
     * @param argumentsJson
     *            the model's arguments as sent ({@code null}/blank is an empty
     *            object)
     */
    public static Result check(Map<String, Object> schema, String unknownFields, String argumentsJson) {
        ObjectNode arguments;
        try {
            JsonNode parsed = argumentsJson == null || argumentsJson.isBlank()
                    ? JsonNodeFactory.instance.objectNode()
                    : MAPPER.readTree(argumentsJson);
            if (!(parsed instanceof ObjectNode object)) {
                return new Result(null, List.of("$: the arguments must be one JSON object"));
            }
            arguments = object;
        } catch (Exception e) {
            return new Result(null, List.of("$: the arguments are not valid JSON"));
        }
        if (schema == null || schema.isEmpty()) {
            return new Result(arguments, List.of());
        }
        UnknownFields mode = UnknownFields.of(unknownFields);
        Compiled compiled;
        try {
            compiled = compile(schema, mode);
        } catch (RuntimeException e) {
            // Fail closed: a schema that cannot be compiled must not let the call
            // through unchecked. The type only — the message may quote the schema.
            LOGGER.warnf("Tool argument schema could not be compiled (%s); the call is refused", e.getClass().getSimpleName());
            return new Result(arguments, List.of(SCHEMA_NOT_COMPILED));
        }
        decodeContainerStrings(arguments, compiled.effective());
        if (mode == UnknownFields.DROP) {
            dropUndeclared(arguments, compiled.effective(), 0);
        }
        Set<ValidationMessage> messages;
        try {
            messages = compiled.schema().validate(arguments);
        } catch (Exception e) {
            return new Result(arguments, List.of("$: the argument schema of this tool could not be evaluated; the call was refused"));
        }
        List<ValidationMessage> sorted = new ArrayList<>(messages);
        sorted.sort(Comparator.comparing((ValidationMessage m) -> String.valueOf(m.getInstanceLocation()))
                .thenComparing(m -> String.valueOf(m.getType())));
        List<String> violations = new ArrayList<>();
        for (ValidationMessage message : sorted) {
            if (violations.size() == MAX_VIOLATIONS) {
                violations.add("(and " + (sorted.size() - MAX_VIOLATIONS) + " more)");
                break;
            }
            violations.add(describe(message, compiled.effective()));
        }
        return new Result(arguments, violations);
    }

    /**
     * The NOT_EXECUTED tool result for a refused call, as one JSON object.
     */
    public static String refusal(List<String> violations) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("status", "NOT_EXECUTED");
        result.put("reason", "The arguments do not match this tool's schema, so the call was not run and no approval was requested. "
                + "Fix every listed violation and call the tool again.");
        ArrayNode list = result.putArray("violations");
        violations.forEach(list::add);
        return result.toString();
    }

    /**
     * The effective schema for {@code mode} and its compiled validator, cached by
     * schema text and mode (the cache is cleared when full). Throws on a schema
     * that cannot be serialised or compiled; {@link #check} turns that into a
     * refusal (fail closed).
     */
    private static Compiled compile(Map<String, Object> schema, UnknownFields mode) {
        JsonNode declared = MAPPER.valueToTree(schema);
        String key = mode + "\u0000" + declared;
        Compiled cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (CACHE.size() >= MAX_CACHED) {
            CACHE.clear();
        }
        ObjectNode effective = effectiveSchema(declared, mode, 0);
        JsonSchema compiledSchema = FACTORY.getSchema(effective);
        compiledSchema.initializeValidators();
        Compiled compiled = new Compiled(effective, compiledSchema);
        CACHE.put(key, compiled);
        return compiled;
    }

    /**
     * The declared schema reduced to the subset, with
     * {@code additionalProperties: false} added to every object that declares
     * properties and does not say otherwise, under {@link UnknownFields#REJECT}.
     */
    static ObjectNode effectiveSchema(JsonNode node, UnknownFields mode, int depth) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (!(node instanceof ObjectNode declared) || depth > MAX_DEPTH) {
            return out;
        }
        boolean nullable = declared.path("nullable").asBoolean(false);
        JsonNode type = declared.get("type");
        if (type != null && type.isTextual()) {
            if (nullable && !"null".equals(type.asText())) {
                out.putArray("type").add(type.asText()).add("null");
            } else {
                out.put("type", type.asText());
            }
        } else if (type != null && type.isArray()) {
            ArrayNode types = out.putArray("type");
            type.forEach(t -> {
                if (t.isTextual()) {
                    types.add(t.asText());
                }
            });
            if (nullable) {
                types.add("null");
            }
        }
        JsonNode enumNode = declared.get("enum");
        if (enumNode != null && enumNode.isArray() && !enumNode.isEmpty()) {
            ArrayNode values = out.putArray("enum");
            enumNode.forEach(values::add);
            if (nullable) {
                values.addNull();
            }
        }
        for (String keyword : INTEGER_KEYWORDS) {
            JsonNode value = declared.get(keyword);
            if (value != null && value.canConvertToInt()) {
                out.put(keyword, value.asInt());
            }
        }
        for (String keyword : NUMBER_KEYWORDS) {
            JsonNode value = declared.get(keyword);
            if (value != null && value.isNumber()) {
                out.set(keyword, value);
            }
        }
        JsonNode properties = declared.get("properties");
        boolean declaresProperties = properties != null && properties.isObject();
        if (declaresProperties) {
            ObjectNode outProperties = out.putObject("properties");
            properties.fields().forEachRemaining(entry -> outProperties.set(entry.getKey(), effectiveSchema(entry.getValue(), mode, depth + 1)));
        }
        JsonNode required = declared.get("required");
        if (required != null && required.isArray()) {
            ArrayNode names = out.putArray("required");
            required.forEach(name -> {
                if (name.isTextual()) {
                    names.add(name.asText());
                }
            });
        }
        JsonNode items = declared.get("items");
        if (items != null && items.isObject()) {
            out.set("items", effectiveSchema(items, mode, depth + 1));
        }
        JsonNode additional = declared.get("additionalProperties");
        if (additional != null && additional.isBoolean()) {
            out.put("additionalProperties", additional.asBoolean());
        } else if (additional != null && additional.isObject()) {
            // A map-like object: any keys. Its value schema is not enforced.
            out.put("additionalProperties", true);
        } else if (declaresProperties && mode == UnknownFields.REJECT) {
            out.put("additionalProperties", false);
        }
        return out;
    }

    /**
     * A parameter declared as an object or array, given as a string holding that
     * JSON, is decoded in place. Earlier generated tools typed every parameter as a
     * string, so a model (and a prompt written for those tools) that still encodes
     * the body as one string keeps working.
     */
    private static void decodeContainerStrings(ObjectNode arguments, ObjectNode schema) {
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }
        properties.fields().forEachRemaining(entry -> {
            JsonNode value = arguments.get(entry.getKey());
            if (value == null || !value.isTextual()) {
                return;
            }
            boolean wantsObject = typeAllows(entry.getValue(), "object");
            boolean wantsArray = typeAllows(entry.getValue(), "array");
            if (!wantsObject && !wantsArray) {
                return;
            }
            try {
                JsonNode decoded = MAPPER.readTree(value.asText());
                if ((wantsObject && decoded.isObject()) || (wantsArray && decoded.isArray())) {
                    arguments.set(entry.getKey(), decoded);
                }
            } catch (Exception e) {
                // left as the string: validation then reports the type mismatch
            }
        });
    }

    /**
     * Whether the schema's {@code type} (a name or a list of names) includes
     * {@code wanted}.
     */
    private static boolean typeAllows(JsonNode schema, String wanted) {
        JsonNode type = schema.get("type");
        if (type == null) {
            return false;
        }
        if (type.isTextual()) {
            return wanted.equals(type.asText());
        }
        for (JsonNode t : type) {
            if (wanted.equals(t.asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes every field an object's schema does not declare, unless that schema
     * explicitly admits additional properties.
     */
    private static void dropUndeclared(JsonNode value, JsonNode schema, int depth) {
        if (schema == null || depth > MAX_DEPTH) {
            return;
        }
        if (value instanceof ObjectNode object) {
            JsonNode properties = schema.get("properties");
            if (properties == null || !properties.isObject()) {
                return;
            }
            boolean admitsAdditional = schema.path("additionalProperties").asBoolean(false);
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode fieldSchema = properties.get(field.getKey());
                if (fieldSchema == null) {
                    if (!admitsAdditional) {
                        fields.remove();
                    }
                    continue;
                }
                dropUndeclared(field.getValue(), fieldSchema, depth + 1);
            }
        } else if (value instanceof ArrayNode array) {
            JsonNode items = schema.get("items");
            for (JsonNode element : array) {
                dropUndeclared(element, items, depth + 1);
            }
        }
    }

    /** One violation, worded for the model that has to fix it. */
    static String describe(ValidationMessage message, ObjectNode effective) {
        String path = String.valueOf(message.getInstanceLocation());
        String type = message.getType() == null ? "" : message.getType();
        JsonNode keywordValue = message.getSchemaNode();
        return switch (type) {
            case "required" -> child(path, message.getProperty()) + ": required";
            case "enum" -> path + ": must be one of " + joinValues(keywordValue);
            case "type" -> path + ": must be " + joinTypes(keywordValue);
            case "additionalProperties" -> {
                String declared = declaredFields(effective, message.getInstanceLocation());
                yield child(path, message.getProperty()) + ": unknown field" + (declared.isEmpty() ? "" : " (declared fields: " + declared + ")");
            }
            default -> message.getMessage();
        };
    }

    /**
     * The path of a property below {@code path}: dotted for a plain identifier,
     * bracketed and quoted otherwise. {@code path} itself when there is no property
     * name.
     */
    private static String child(String path, String property) {
        if (property == null) {
            return path;
        }
        boolean plain = !property.isEmpty() && property.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
        return plain ? path + "." + property : path + "['" + property + "']";
    }

    /** The enum values, comma-separated; strings unquoted, other values as JSON. */
    private static String joinValues(JsonNode values) {
        if (values == null || !values.isArray()) {
            return String.valueOf(values);
        }
        List<String> parts = new ArrayList<>();
        values.forEach(v -> parts.add(v.isTextual() ? v.asText() : v.toString()));
        return String.join(", ", parts);
    }

    /**
     * The expected type(s) as words, e.g. {@code an integer} or
     * {@code a string or null}.
     */
    private static String joinTypes(JsonNode type) {
        if (type == null) {
            return "of another type";
        }
        if (type.isTextual()) {
            return article(type.asText());
        }
        List<String> parts = new ArrayList<>();
        type.forEach(t -> parts.add(t.asText()));
        return article(String.join(" or ", parts));
    }

    /** The type name with its indefinite article ({@code null} stays bare). */
    private static String article(String type) {
        return switch (type) {
            case "object", "array", "integer" -> "an " + type;
            case "null" -> type;
            default -> "a " + type;
        };
    }

    /**
     * The fields the schema declares at {@code location}, so a misspelling can be
     * corrected without a second refusal.
     */
    private static String declaredFields(ObjectNode effective, JsonNodePath location) {
        JsonNode schema = effective;
        for (int i = 0; location != null && i < location.getNameCount() && schema != null; i++) {
            Object element = location.getElement(i);
            schema = element instanceof Integer ? schema.get("items") : schema.path("properties").get(String.valueOf(element));
        }
        JsonNode properties = schema == null ? null : schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        properties.fieldNames().forEachRemaining(names::add);
        return String.join(", ", names);
    }
}
