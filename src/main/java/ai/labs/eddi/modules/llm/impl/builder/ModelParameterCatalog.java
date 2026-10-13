/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What every LLM provider's parameters mean, and which <em>model versions</em>
 * reject or ignore them — loaded once from {@code llm/model-parameters.json}.
 * <p>
 * Two consumers: the Manager renders its "Model settings" panel from the same
 * file (through a mirrored copy guarded by a drift test), and
 * {@code ChatModelRegistry} calls {@link #adapt} on every model build, so a
 * parameter the configured model would answer with HTTP 400 is removed before
 * the request is ever sent instead of failing every turn of that agent.
 * <p>
 * <b>Rule semantics.</b> A parameter's {@code rules} are tried in order and the
 * first one that matches decides; when none matches, the parameter applies. A
 * rule matches when its {@code models} regex is found (case-insensitively) in
 * the configured model id, its optional {@code when} condition holds and its
 * optional {@code unless} condition does not. A condition holds when the named
 * parameter is set to one of {@code values} (case-insensitive; numbers compare
 * numerically, so {@code 1} equals {@code 1.0}) — or, without {@code values},
 * when it is set at all; an unset parameter yields {@code whenUnset} (default
 * {@code false}). Unknown provider types and models no rule names fail open.
 * <p>
 * <b>Inheritance.</b> A provider with {@code "inherits": "openai"} receives
 * every parameter of that provider <em>without its rules</em> — the parent's
 * model regexes are about the parent's models — and its own entries replace
 * inherited ones key by key. The OpenAI-compatible family uses it, because it
 * runs on the OpenAI builder and therefore reads every OpenAI key.
 * <p>
 * Immutable after loading, hence thread-safe.
 */
public final class ModelParameterCatalog {

    static final String RESOURCE = "/llm/model-parameters.json";
    static final int SCHEMA_VERSION = 1;

    /** Every key a builder reads the model from, in lookup order. */
    public static final List<String> MODEL_KEYS = List.of("modelName", "model", "modelId", "modelID", "deploymentName");

    /**
     * Azure's {@code deploymentName} is a name the customer chose, not a model id,
     * so rules use this gating-only key when it is set.
     */
    public static final String KEY_BASE_MODEL = "baseModel";

    static final String TYPE_AZURE_OPENAI = "azure-openai";

    /**
     * Keys every provider form handles on its own (model, credentials, endpoint,
     * timeout and logging); the catalog deliberately does not describe them.
     */
    public static final Set<String> DEDICATED_KEYS = Set.of("modelName", "model", "modelId", "modelID", "deploymentName", "apiKey",
            "nonAzureApiKey", "accessToken", "authToken", "baseUrl", "timeout", "logRequests", "logResponses",
            "logRequestsAndResponses");

    /**
     * {@code region} of an OpenAI-compatible provider picks one of its endpoints
     * and so is dedicated too; Bedrock's and Oracle's {@code region} is an ordinary
     * catalog setting.
     */
    static final String KEY_COMPATIBLE_REGION = OpenAiCompatibleLanguageModelBuilder.KEY_REGION;

    private static final Set<String> TYPES = Set.of("double", "int", "boolean", "string", "enum", "stringList", "json");
    private static final Set<String> GROUPS = Set.of("model", "sampling", "output", "reasoning", "tools", "caching", "safety",
            "connection", "advanced");
    private static final Set<String> PROVIDER_FIELDS = Set.of("displayName", "inherits", "params");
    private static final Set<String> PARAM_FIELDS = Set.of("key", "type", "group", "label", "description", "enum", "min", "max",
            "default", "wire", "body", "header", "jsonShape", "rules", "source");
    private static final Set<String> RULE_FIELDS = Set.of("models", "effect", "note", "when", "unless", "migrateTo");
    private static final Set<String> CONDITION_FIELDS = Set.of("key", "values", "whenUnset");

    /** What a matching rule says about a parameter. */
    public enum Effect {
        /** The API answers with an error; dropped before the build by default. */
        REJECTED,
        /** Harmless but without effect; kept, with a warning. */
        IGNORED;

        static Effect parse(String raw) {
            if ("rejected".equals(raw)) {
                return REJECTED;
            }
            if ("ignored".equals(raw)) {
                return IGNORED;
            }
            return null;
        }
    }

    /**
     * A {@code when}/{@code unless} test against another (or the same) parameter.
     */
    public record Condition(String key, List<String> values, boolean whenUnset) {
        public Condition {
            values = values == null ? null : List.copyOf(values);
        }

        boolean holds(Map<String, String> parameters) {
            String raw = trimmedOrNull(parameters.get(key));
            if (raw == null) {
                return whenUnset;
            }
            if (values == null) {
                return true;
            }
            for (String candidate : values) {
                if (sameValue(candidate, raw)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * One model-version rule of a parameter.
     *
     * @param migrateTo
     *            optional key of another parameter of the same provider: when this
     *            rule removes the parameter and that key is not configured, the
     *            removed value is used for it instead. Keeps an output or cost cap
     *            alive when a model renames the parameter ({@code maxTokens} became
     *            {@code maxCompletionTokens} on reasoning models).
     */
    public record Rule(Pattern models, Effect effect, String note, Condition when, Condition unless, String migrateTo) {
        boolean matches(String modelId, Map<String, String> parameters) {
            return models.matcher(modelId).find() && (when == null || when.holds(parameters))
                    && (unless == null || !unless.holds(parameters));
        }
    }

    /**
     * One parameter of one provider.
     *
     * @param defaultValue
     *            advisory default as shown in the UI, or {@code null}
     * @param wire
     *            dotted request-body path; only for {@code body} parameters
     * @param body
     *            sent as a raw request-body field rather than through a langchain4j
     *            setter
     * @param header
     *            sent as this HTTP header rather than a body field
     */
    public record Param(String key, String type, String group, String label, String description, List<String> enumValues,
            Double min, Double max, String defaultValue, String wire, boolean body, String header, String jsonShape,
            List<Rule> rules, String source) {
        public Param {
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
            rules = rules == null ? List.of() : List.copyOf(rules);
        }

        Param withoutRules() {
            return new Param(key, type, group, label, description, enumValues, min, max, defaultValue, wire, body, header,
                    jsonShape, List.of(), source);
        }
    }

    /**
     * What {@link #adapt} found for one configured parameter. Carries the key, the
     * model and the rule's note — never the parameter's value.
     *
     * @param removed
     *            whether the parameter was taken out of the map handed to the
     *            builder
     * @param migratedTo
     *            the parameter its value was carried over to when the rule renames
     *            it ({@code maxTokens} to {@code maxCompletionTokens}), else
     *            {@code null}
     */
    public record Finding(String key, String model, Effect effect, boolean removed, String note, String migratedTo) {
    }

    /** The map to hand to the builder, and what was found on the way. */
    public record Adapted(Map<String, String> parameters, List<Finding> findings) {
        public Adapted {
            findings = List.copyOf(findings);
        }
    }

    private record Provider(String displayName, Map<String, Param> params) {
    }

    private static final class Holder {
        static final ModelParameterCatalog DEFAULT = load(RESOURCE);
    }

    private final Map<String, Provider> providers;

    private ModelParameterCatalog(Map<String, Provider> providers) {
        this.providers = providers;
    }

    /**
     * The catalog shipped on the classpath; loaded once, fails fast if malformed.
     */
    public static ModelParameterCatalog defaultCatalog() {
        return Holder.DEFAULT;
    }

    /** Load a catalog from a classpath resource (tests pass their own). */
    static ModelParameterCatalog load(String resource) {
        try (InputStream in = ModelParameterCatalog.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + resource);
            }
            return parse(new ObjectMapper().readTree(in), resource);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource + ": " + e.getMessage(), e);
        }
    }

    /** Parse and validate a catalog document; {@code origin} names it in errors. */
    static ModelParameterCatalog parse(String json, String origin) {
        try {
            return parse(new ObjectMapper().readTree(json), origin);
        } catch (IOException e) {
            throw new IllegalStateException(origin + " is not valid JSON: " + e.getMessage(), e);
        }
    }

    private static ModelParameterCatalog parse(JsonNode root, String origin) {
        if (root == null || !root.isObject()) {
            throw new IllegalStateException(origin + " must be a JSON object");
        }
        JsonNode version = root.get("schemaVersion");
        if (version == null || !version.isInt() || version.intValue() != SCHEMA_VERSION) {
            throw new IllegalStateException(origin + " must declare \"schemaVersion\": " + SCHEMA_VERSION);
        }
        JsonNode providersNode = root.get("providers");
        if (providersNode == null || !providersNode.isObject() || providersNode.isEmpty()) {
            throw new IllegalStateException(origin + " declares no providers");
        }

        Map<String, JsonNode> raw = new LinkedHashMap<>();
        providersNode.fields().forEachRemaining(e -> raw.put(e.getKey(), e.getValue()));

        Map<String, Provider> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : raw.entrySet()) {
            String type = entry.getKey();
            String where = origin + ": provider '" + type + "'";
            if (!type.equals(type.trim().toLowerCase(Locale.ROOT)) || type.isBlank()) {
                throw new IllegalStateException(where + ": type must be lower-case");
            }
            JsonNode node = entry.getValue();
            requireObject(node, where);
            rejectUnknownFields(node, PROVIDER_FIELDS, where);
            String displayName = requireText(node, "displayName", where);

            Map<String, Param> params = new LinkedHashMap<>();
            String inherits = optionalText(node, "inherits", where);
            if (inherits != null) {
                JsonNode parent = raw.get(inherits);
                if (parent == null) {
                    throw new IllegalStateException(where + ": inherits unknown provider '" + inherits + "'");
                }
                if (parent.has("inherits")) {
                    throw new IllegalStateException(where + ": inherits '" + inherits + "', which itself inherits");
                }
                for (Param p : parseParams(parent, origin + ": provider '" + inherits + "'")) {
                    params.put(p.key(), p.withoutRules());
                }
            }
            Set<String> ownKeys = new LinkedHashSet<>();
            for (Param p : parseParams(node, where)) {
                if (!ownKeys.add(p.key())) {
                    throw new IllegalStateException(where + ": duplicate parameter '" + p.key() + "'");
                }
                params.put(p.key(), p);
            }
            parsed.put(type, new Provider(displayName, Collections.unmodifiableMap(params)));
        }
        return new ModelParameterCatalog(Collections.unmodifiableMap(parsed));
    }

    private static List<Param> parseParams(JsonNode provider, String where) {
        JsonNode params = provider.get("params");
        if (params == null || !params.isArray() || params.isEmpty()) {
            throw new IllegalStateException(where + ": \"params\" must be a non-empty array");
        }
        List<Param> result = new ArrayList<>();
        for (JsonNode node : params) {
            result.add(parseParam(node, where));
        }
        return result;
    }

    private static Param parseParam(JsonNode node, String providerWhere) {
        requireObject(node, providerWhere + ": parameter");
        String key = requireText(node, "key", providerWhere + ": parameter");
        String where = providerWhere + ": parameter '" + key + "'";
        rejectUnknownFields(node, PARAM_FIELDS, where);
        if (DEDICATED_KEYS.contains(key)) {
            throw new IllegalStateException(where + ": is a dedicated key the provider form handles; do not catalog it");
        }

        String type = requireText(node, "type", where);
        if (!TYPES.contains(type)) {
            throw new IllegalStateException(where + ": unknown type '" + type + "', expected one of " + new TreeSet<>(TYPES));
        }
        String group = requireText(node, "group", where);
        if (!GROUPS.contains(group)) {
            throw new IllegalStateException(where + ": unknown group '" + group + "', expected one of " + new TreeSet<>(GROUPS));
        }
        String label = requireText(node, "label", where);
        String description = requireText(node, "description", where);
        String source = requireText(node, "source", where);
        if (!source.startsWith("https://")) {
            throw new IllegalStateException(where + ": source must be an https URL");
        }

        List<String> enumValues = null;
        if (node.has("enum")) {
            if (!"enum".equals(type)) {
                throw new IllegalStateException(where + ": only type enum may list \"enum\" values");
            }
            enumValues = textArray(node.get("enum"), where + ": enum");
        } else if ("enum".equals(type)) {
            throw new IllegalStateException(where + ": type enum needs a non-empty \"enum\"");
        }

        Double min = optionalNumber(node, "min", type, where);
        Double max = optionalNumber(node, "max", type, where);
        if (min != null && max != null && min > max) {
            throw new IllegalStateException(where + ": min is greater than max");
        }

        String defaultValue = null;
        JsonNode def = node.get("default");
        if (def != null) {
            if (!def.isValueNode() || def.isNull()) {
                throw new IllegalStateException(where + ": default must be a string, number or boolean");
            }
            defaultValue = def.asText();
        }

        boolean body = false;
        JsonNode bodyNode = node.get("body");
        if (bodyNode != null) {
            if (!bodyNode.isBoolean()) {
                throw new IllegalStateException(where + ": body must be a boolean");
            }
            body = bodyNode.booleanValue();
        }
        String wire = optionalText(node, "wire", where);
        if (body && wire == null) {
            throw new IllegalStateException(where + ": a body parameter needs a \"wire\" path");
        }
        if (!body && wire != null) {
            throw new IllegalStateException(where + ": \"wire\" is only meaningful with \"body\": true");
        }
        if (wire != null && !wire.matches("[A-Za-z0-9_\\-]+(\\.[A-Za-z0-9_\\-]+)*")) {
            throw new IllegalStateException(where + ": wire must be a dotted path of field names");
        }
        String header = optionalText(node, "header", where);
        if (header != null && body) {
            throw new IllegalStateException(where + ": a parameter is either a body field or a header, not both");
        }

        List<Rule> rules = new ArrayList<>();
        JsonNode rulesNode = node.get("rules");
        if (rulesNode != null) {
            if (!rulesNode.isArray() || rulesNode.isEmpty()) {
                throw new IllegalStateException(where + ": rules must be a non-empty array when present");
            }
            int index = 0;
            for (JsonNode r : rulesNode) {
                rules.add(parseRule(r, where + ": rule " + index++));
            }
        }
        String jsonShape = optionalText(node, "jsonShape", where);
        if (jsonShape != null && !"json".equals(type)) {
            throw new IllegalStateException(where + ": \"jsonShape\" is only meaningful for type json");
        }
        if (jsonShape != null && !"object".equals(jsonShape) && !"array".equals(jsonShape)) {
            throw new IllegalStateException(where + ": jsonShape must be object or array, got '" + jsonShape + "'");
        }
        if (jsonShape == null && "json".equals(type)) {
            jsonShape = "object";
        }
        return new Param(key, type, group, label, description, enumValues, min, max, defaultValue, wire, body, header, jsonShape,
                rules, source);
    }

    private static Rule parseRule(JsonNode node, String where) {
        requireObject(node, where);
        rejectUnknownFields(node, RULE_FIELDS, where);
        String models = requireText(node, "models", where);
        if (!models.startsWith("^")) {
            throw new IllegalStateException(where + ": the models regex must be anchored with ^");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(models, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException(where + ": invalid models regex: " + e.getDescription(), e);
        }
        String effectRaw = requireText(node, "effect", where);
        Effect effect = Effect.parse(effectRaw);
        if (effect == null) {
            throw new IllegalStateException(where + ": effect must be rejected or ignored, got '" + effectRaw + "'");
        }
        String note = requireText(node, "note", where);
        String migrateTo = node.hasNonNull("migrateTo") ? requireText(node, "migrateTo", where) : null;
        return new Rule(pattern, effect, note, parseCondition(node.get("when"), where + ": when"),
                parseCondition(node.get("unless"), where + ": unless"), migrateTo);
    }

    private static Condition parseCondition(JsonNode node, String where) {
        if (node == null) {
            return null;
        }
        requireObject(node, where);
        rejectUnknownFields(node, CONDITION_FIELDS, where);
        String key = requireText(node, "key", where);
        List<String> values = node.has("values") ? textArray(node.get("values"), where + ": values") : null;
        boolean whenUnset = false;
        JsonNode unset = node.get("whenUnset");
        if (unset != null) {
            if (!unset.isBoolean()) {
                throw new IllegalStateException(where + ": whenUnset must be a boolean");
            }
            whenUnset = unset.booleanValue();
        }
        return new Condition(key, values, whenUnset);
    }

    private static void requireObject(JsonNode node, String where) {
        if (node == null || !node.isObject()) {
            throw new IllegalStateException(where + " must be a JSON object");
        }
    }

    private static void rejectUnknownFields(JsonNode node, Set<String> allowed, String where) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new IllegalStateException(where + ": unknown field '" + name + "'");
            }
        }
    }

    private static String requireText(JsonNode node, String field, String where) {
        String value = optionalText(node, field, where);
        if (value == null) {
            throw new IllegalStateException(where + ": \"" + field + "\" is required");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException(where + ": \"" + field + "\" must be a non-blank string");
        }
        return value.textValue();
    }

    private static Double optionalNumber(JsonNode node, String field, String type, String where) {
        JsonNode value = node.get(field);
        if (value == null) {
            return null;
        }
        if (!"double".equals(type) && !"int".equals(type)) {
            throw new IllegalStateException(where + ": \"" + field + "\" is only meaningful for numeric types");
        }
        if (!value.isNumber()) {
            throw new IllegalStateException(where + ": \"" + field + "\" must be a number");
        }
        return value.doubleValue();
    }

    private static List<String> textArray(JsonNode node, String where) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalStateException(where + " must be a non-empty array of strings");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalStateException(where + " must contain only non-blank strings");
            }
            values.add(item.textValue());
        }
        return values;
    }

    // ---- lookups ---------------------------------------------------------

    /** The provider types the catalog describes. */
    public Set<String> types() {
        return providers.keySet();
    }

    /** One parameter of a provider type; case- and whitespace-insensitive type. */
    public Optional<Param> find(String type, String key) {
        Provider provider = provider(type);
        return provider == null || key == null ? Optional.empty() : Optional.ofNullable(provider.params().get(key));
    }

    /** Every catalogued key of a provider type; empty for an unknown type. */
    public Set<String> keys(String type) {
        Provider provider = provider(type);
        return provider == null ? Set.of() : provider.params().keySet();
    }

    /** Every parameter of a provider type, in catalog order; empty when unknown. */
    public List<Param> params(String type) {
        Provider provider = provider(type);
        return provider == null ? List.of() : List.copyOf(provider.params().values());
    }

    /** The display name of a provider type, if catalogued. */
    public Optional<String> displayName(String type) {
        Provider provider = provider(type);
        return provider == null ? Optional.empty() : Optional.of(provider.displayName());
    }

    /**
     * The keys of {@code type} that its provider form handles itself and the
     * catalog therefore does not describe.
     */
    public static Set<String> dedicatedKeys(String type) {
        if (OpenAiCompatibleProviders.find(type).isEmpty()) {
            return DEDICATED_KEYS;
        }
        Set<String> keys = new LinkedHashSet<>(DEDICATED_KEYS);
        keys.add(KEY_COMPATIBLE_REGION);
        return Collections.unmodifiableSet(keys);
    }

    private Provider provider(String type) {
        return type == null ? null : providers.get(type.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * The model id rules are evaluated against: {@code baseModel} on Azure when
     * set, else the provider's own model key (the one its builder reads), else the
     * first non-blank of {@link #MODEL_KEYS}, else an OpenAI-compatible provider's
     * default model; {@code null} when none is known.
     * <p>
     * The provider's own key goes first because a model cascade merges a step's
     * parameters over the task's: the task's {@code modelName} stays in the map
     * next to the step's {@code deploymentName}, and only the latter is what the
     * step's builder reads.
     */
    public static String modelId(String type, Map<String, String> parameters) {
        if (parameters == null) {
            return null;
        }
        String normalisedType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (TYPE_AZURE_OPENAI.equals(normalisedType)) {
            String base = trimmedOrNull(parameters.get(KEY_BASE_MODEL));
            if (base != null) {
                return base;
            }
        }
        String own = trimmedOrNull(parameters.get(ownModelKey(normalisedType)));
        if (own != null) {
            return own;
        }
        for (String key : MODEL_KEYS) {
            String value = trimmedOrNull(parameters.get(key));
            if (value != null) {
                return value;
            }
        }
        return OpenAiCompatibleProviders.find(normalisedType).map(OpenAiCompatibleProvider::defaultModel).orElse(null);
    }

    /** The parameter this provider's builder reads its model from. */
    static String ownModelKey(String normalisedType) {
        return switch (normalisedType) {
            case "ollama" -> "model";
            case "bedrock", "huggingface", "gemini-vertex" -> "modelId";
            case TYPE_AZURE_OPENAI -> "deploymentName";
            default -> "modelName";
        };
    }

    /**
     * Apply the model rules to a resolved parameter map.
     * <p>
     * Every configured (non-blank) parameter with rules is checked once against the
     * configured model; the first matching rule yields one {@link Finding}.
     * {@link Effect#REJECTED} parameters are removed when {@code dropRejected} is
     * set; {@link Effect#IGNORED} ones are always kept. Conditions are read from
     * the map as configured, so removing one parameter never changes the verdict on
     * another. An unknown type or model leaves the map untouched.
     *
     * @return the map to hand to the builder (the input itself when nothing was
     *         removed) and the findings, ordered by key
     */
    public Adapted adapt(String type, Map<String, String> parameters, boolean dropRejected) {
        if (parameters == null || parameters.isEmpty()) {
            return new Adapted(parameters == null ? Map.of() : parameters, List.of());
        }
        Provider provider = provider(type);
        String model = provider == null ? null : modelId(type, parameters);
        if (model == null) {
            return new Adapted(parameters, List.of());
        }

        List<Finding> findings = new ArrayList<>();
        Map<String, String> reduced = null;
        TreeSet<String> keys = new TreeSet<>();
        for (String key : parameters.keySet()) {
            if (key != null) {
                keys.add(key);
            }
        }
        for (String key : keys) {
            Param param = provider.params().get(key);
            if (param == null || param.rules().isEmpty() || trimmedOrNull(parameters.get(key)) == null) {
                continue;
            }
            for (Rule rule : param.rules()) {
                if (rule.matches(model, parameters)) {
                    boolean remove = rule.effect() == Effect.REJECTED && dropRejected;
                    String migratedTo = null;
                    if (remove) {
                        if (reduced == null) {
                            reduced = new HashMap<>(parameters);
                        }
                        String removed = reduced.remove(key);
                        if (rule.migrateTo() != null && trimmedOrNull(parameters.get(rule.migrateTo())) == null) {
                            reduced.put(rule.migrateTo(), removed);
                            migratedTo = rule.migrateTo();
                        }
                    }
                    findings.add(new Finding(key, model, rule.effect(), remove, rule.note(), migratedTo));
                    break;
                }
            }
        }
        return new Adapted(reduced == null ? parameters : Collections.unmodifiableMap(reduced), findings);
    }

    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean sameValue(String expected, String actual) {
        if (expected.equalsIgnoreCase(actual)) {
            return true;
        }
        try {
            return Double.parseDouble(expected) == Double.parseDouble(actual);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
