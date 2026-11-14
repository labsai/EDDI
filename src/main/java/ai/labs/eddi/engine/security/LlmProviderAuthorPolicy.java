/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.modules.llm.impl.builder.JlamaModelDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Decides which LLM provider settings an author who is not an administrator may
 * store. Three kinds of setting reach further than the agent they configure:
 * <ul>
 * <li><b>Restricted providers</b> ({@value #RESTRICTED_PROVIDERS_PROPERTY},
 * default {@code bedrock,gemini-vertex,oracle-genai,jlama}; for embeddings
 * {@value #RESTRICTED_EMBEDDING_PROVIDERS_PROPERTY}, default
 * {@code bedrock,vertex}) — the cloud providers authenticate with the server's
 * own ambient identity (the AWS default credential chain, Google application
 * default credentials, an OCI config profile), so whoever writes the config
 * spends the operator's cloud account; Jlama downloads any Hugging Face model
 * into the shared JVM.</li>
 * <li><b>Jlama paths</b> — {@code modelCachePath} and {@code workingDirectory}
 * decide where the server writes gigabytes of weights. With
 * {@value JlamaModelDirectory#PROPERTY} set they must lie under it; without it
 * only an administrator may set them.</li>
 * <li><b>Content logging</b> — {@code logRequests}, {@code logResponses},
 * {@code logRequestsAndResponses} write prompts and responses, other users'
 * conversations included, to the server log. Editors may turn them on only with
 * {@value #CONTENT_LOGGING_EDITORS_PROPERTY}{@code =true}.</li>
 * </ul>
 * Checked where the author is known — REST create, update, patch and duplicate,
 * import and upgrade — as {@link CallerTokenAuthorPolicy} is. Never at run
 * time: a document an administrator stored keeps working.
 * <p>
 * <b>Carry-over.</b> A setting the version being replaced already had, exactly,
 * passes: an editor may change the prompt of an administrator's Bedrock agent,
 * but not point it at another model, region or profile, and not add one. For a
 * restricted provider "exactly" means the provider together with every
 * parameter that selects the model, its location or its credentials.
 * <p>
 * <b>A text scan of the document tree</b>, deliberately, like
 * {@link CallerTokenAuthorPolicy}: a provider is named in an LLM task, a
 * cascade step, a judge, a guardrail's {@code provider} parameter, a summary's
 * {@code llmProvider}, a RAG's {@code embeddingProvider}, an agent's Dream
 * settings and a group's consolidation settings, and a field-by-field check
 * would have to be kept in step with all of them. Any object whose
 * {@code type}, {@code provider} or {@code llmProvider} names a restricted
 * provider counts, wherever it sits.
 * <p>
 * Inactive when {@code authorization.enabled=false}: then every caller is an
 * administrator anyway.
 */
@ApplicationScoped
public class LlmProviderAuthorPolicy {

    public static final String RESTRICTED_PROVIDERS_PROPERTY = "eddi.llm.restricted-providers";
    public static final String RESTRICTED_EMBEDDING_PROVIDERS_PROPERTY = "eddi.llm.restricted-embedding-providers";
    public static final String CONTENT_LOGGING_EDITORS_PROPERTY = "eddi.llm.content-logging.editors-allowed";

    static final String DEFAULT_RESTRICTED_PROVIDERS = "bedrock,gemini-vertex,oracle-genai,jlama";
    static final String DEFAULT_RESTRICTED_EMBEDDING_PROVIDERS = "bedrock,vertex";

    /** Fields that name an LLM provider. */
    private static final List<String> PROVIDER_FIELDS = List.of("type", "provider", "llmProvider");
    /** The field that names an embedding provider. */
    private static final String EMBEDDING_PROVIDER_FIELD = "embeddingProvider";
    /** Fields whose non-literal value could select any provider at run time. */
    private static final List<String> INDIRECTION_FIELDS = List.of("type", "provider", "llmProvider", "embeddingProvider");

    /**
     * Whether a provider value is a variable or template rather than a provider
     * name.
     */
    static boolean isIndirect(String value) {
        return value != null && (value.contains("${") || value.contains("{"));
    }

    /** Children holding a provider's parameters. */
    private static final List<String> PARAMETER_FIELDS = List.of("parameters", "embeddingParameters");

    private static final Set<String> LOG_FLAGS = Set.of("logRequests", "logResponses", "logRequestsAndResponses");
    private static final Set<String> JLAMA_PATHS = Set.of("modelCachePath", "workingDirectory");
    private static final String JLAMA = "jlama";

    /**
     * Parameter names that point a restricted provider somewhere: its model, its
     * region or location, its credentials. Matched as substrings of the lower-cased
     * name, so {@code modelId}, {@code llmModel}, {@code region},
     * {@code compartmentId} and {@code modelCachePath} all count while
     * {@code systemMessage}, {@code temperature} and {@code maxTokens} do not.
     */
    private static final List<String> POINTING_HINTS = List.of("model", "region", "profile", "compartment", "project", "location",
            "endpoint", "url", "credential", "path", "directory", "authtoken", "apikey", "accesskey", "secretkey", "servingmode");

    private static final ObjectMapper TREE_MAPPER = new ObjectMapper();

    private final SecurityIdentity identity;
    private final IJsonSerialization jsonSerialization;
    private final boolean authorizationEnabled;
    private final Set<String> restrictedProviders;
    private final Set<String> restrictedEmbeddingProviders;
    private final boolean contentLoggingEditorsAllowed;
    private final Optional<Path> jlamaBase;

    @Inject
    public LlmProviderAuthorPolicy(SecurityIdentity identity, IJsonSerialization jsonSerialization,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authorizationEnabled,
            @ConfigProperty(name = RESTRICTED_PROVIDERS_PROPERTY, defaultValue = DEFAULT_RESTRICTED_PROVIDERS) List<String> restrictedProviders,
            @ConfigProperty(name = RESTRICTED_EMBEDDING_PROVIDERS_PROPERTY,
                            defaultValue = DEFAULT_RESTRICTED_EMBEDDING_PROVIDERS) List<String> restrictedEmbeddingProviders,
            @ConfigProperty(name = CONTENT_LOGGING_EDITORS_PROPERTY, defaultValue = "false") boolean contentLoggingEditorsAllowed,
            @ConfigProperty(name = JlamaModelDirectory.PROPERTY) Optional<String> jlamaModelDir) {
        this.identity = identity;
        this.jsonSerialization = jsonSerialization;
        this.authorizationEnabled = authorizationEnabled;
        this.restrictedProviders = normalise(restrictedProviders);
        this.restrictedEmbeddingProviders = normalise(restrictedEmbeddingProviders);
        this.contentLoggingEditorsAllowed = contentLoggingEditorsAllowed;
        this.jlamaBase = JlamaModelDirectory.base(jlamaModelDir == null ? null : jlamaModelDir.orElse(null));
    }

    private static Set<String> normalise(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream().filter(v -> v != null && !v.isBlank()).map(v -> v.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Refuses the write when {@code candidate} carries a setting a
     * non-administrator may not author and {@code stored} — the version it
     * replaces, or null for a new resource — did not already carry exactly that
     * setting.
     *
     * @param kind
     *            what is being stored, for the message
     * @throws ForbiddenException
     *             403 with a JSON {@code error} naming what was refused
     */
    public void requireMayAuthor(Object candidate, Object stored, String kind) {
        if (!authorizationEnabled || candidate == null || callerIsAdmin()) {
            return;
        }
        Facts wanted = facts(candidate);
        if (wanted.isEmpty()) {
            return;
        }
        Facts allowed = stored == null ? new Facts() : facts(stored);
        if (wanted.ambiguous || allowed.ambiguous) {
            // An element id that occurs twice no longer names one element: nothing is
            // carried over by it, so a privileged setting needs an administrator.
            allowed = new Facts();
        }
        List<String> refused = new ArrayList<>();
        for (String fact : wanted.privileged) {
            if (!allowed.privileged.contains(fact)) {
                refused.add(describe(fact));
            }
        }
        if (!refused.isEmpty()) {
            throw forbidden("This " + kind + " configuration sets " + String.join("; ", new TreeSet<>(refused))
                    + ". Only eddi-admin may create or change that — ask an administrator to save it. Other fields of a "
                    + "configuration an administrator stored stay editable.");
        }
    }

    /**
     * What a document asks for that needs an administrator, each bound to the
     * element (task, step) that sets it.
     */
    private static final class Facts {
        private final Set<String> privileged = new TreeSet<>();
        /** Whether some array carries the same element id twice. */
        private boolean ambiguous;

        boolean isEmpty() {
            return privileged.isEmpty();
        }
    }

    Facts facts(Object document) {
        JsonNode tree;
        try {
            String json = document instanceof String text ? text : jsonSerialization.serialize(document);
            tree = TREE_MAPPER.readTree(json);
        } catch (Exception e) {
            // Not inspectable means not provably harmless.
            Facts unknown = new Facts();
            unknown.privileged.add("unreadable|");
            return unknown;
        }
        Facts facts = new Facts();
        walk(tree, "", facts);
        return facts;
    }

    private void walk(JsonNode node, String path, Facts facts) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            Set<String> ids = new HashSet<>();
            for (JsonNode element : node) {
                JsonNode id = element != null && element.isObject() ? element.get("id") : null;
                if (id != null && id.isTextual() && !id.asText().isBlank() && !ids.add(id.asText().trim())) {
                    facts.ambiguous = true;
                }
            }
            int index = 0;
            for (JsonNode element : node) {
                // An element keeps its identity in the path — its "id" where it has one
                // (an LLM task), its position otherwise — so a setting approved on one
                // task cannot be moved to another.
                walk(element, path + "[" + elementKey(element, index++) + "]", facts);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        inspect(node, path, facts);
        inspectCascadeSteps(node, path, facts);
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            walk(field.getValue(), path + "/" + field.getKey(), facts);
        }
    }

    /**
     * An array element's identity in a fact's path: its {@code id} where it has
     * one. Without an id, its position <em>and</em> a digest of its whole content:
     * position alone let an editor remove an approved id-less task and move another
     * into its slot with the approved setting switched on. So an id-less element
     * carries a privileged setting over only while it is unchanged — an unchanged
     * save still passes; any edit to such an element needs an administrator.
     */
    static String elementKey(JsonNode element, int index) {
        JsonNode id = element != null && element.isObject() ? element.get("id") : null;
        if (id != null && id.isTextual() && !id.asText().isBlank()) {
            // Encoded, so an id cannot spell out the rest of another element's path
            // ("a]/modelCascade/steps[0#…") — URL encoding is injective and leaves no
            // '[', ']', '/', '#' or '|' in it.
            return "id=" + URLEncoder.encode(id.asText().trim(), StandardCharsets.UTF_8);
        }
        return index + "#" + digest(element == null ? "" : element.toString());
    }

    private static String digest(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256; without it, an id-less element carries nothing.
            return "undigested-" + System.identityHashCode(content);
        }
    }

    /**
     * A cascade step runs on its own {@code type}, or on the task's when it names
     * none, with the task's parameters overlaid by its own
     * ({@code CascadingModelExecutor.resolveStepModel}). Judged on that effective
     * provider and those effective parameters: otherwise an untyped step under a
     * Bedrock task could re-point its model, or a Bedrock step could inherit a
     * changed model name from its task, unseen.
     */
    private void inspectCascadeSteps(JsonNode task, String path, Facts facts) {
        JsonNode cascade = task.get("modelCascade");
        JsonNode steps = cascade != null && cascade.isObject() ? cascade.get("steps") : null;
        if (steps == null || !steps.isArray()) {
            return;
        }
        JsonNode taskType = task.get("type");
        String taskProvider = taskType != null && taskType.isTextual() ? taskType.asText().trim().toLowerCase(Locale.ROOT) : null;
        Map<String, String> taskSettings = settingsOf(task);
        int index = 0;
        for (JsonNode step : steps) {
            // The same path the walk gives the step itself, so its fact is bound to it.
            String stepPath = path + "/modelCascade/steps[" + elementKey(step, index++) + "]";
            if (step == null || !step.isObject()) {
                continue;
            }
            JsonNode stepType = step.get("type");
            String provider = stepType != null && stepType.isTextual() && !stepType.asText().isBlank()
                    ? stepType.asText().trim().toLowerCase(Locale.ROOT)
                    : taskProvider;
            if (provider == null || !restrictedProviders.contains(provider)) {
                continue;
            }
            Map<String, String> effective = new TreeMap<>(taskSettings);
            JsonNode parameters = step.get("parameters");
            if (parameters != null && parameters.isObject()) {
                scalars(parameters, effective);
            }
            facts.privileged.add("provider|" + provider + "|" + pointing(effective) + AT + stepPath);
        }
    }

    /** Separates a fact's value from the path of the element that sets it. */
    private static final String AT = " @ ";

    private static Map<String, String> pointing(Map<String, String> settings) {
        Map<String, String> pointing = new TreeMap<>();
        settings.forEach((key, value) -> {
            String lower = key.toLowerCase(Locale.ROOT);
            if (POINTING_HINTS.stream().anyMatch(lower::contains)) {
                pointing.put(key, value);
            }
        });
        return pointing;
    }

    /**
     * One object: is it a restricted provider, does it turn on logging, does it set
     * a Jlama path.
     */
    private void inspect(JsonNode node, String path, Facts facts) {
        Map<String, String> settings = settingsOf(node);
        String provider = providerOf(node);

        // A provider field that is not a plain literal: the runtime expands ${vars:...}
        // in an LLM task's, cascade step's and judge's type, so "${vars:p}" could name
        // a restricted provider the scan above never sees. A template ("{...}") is
        // refused the same way — no provider ever needs one.
        for (String field : INDIRECTION_FIELDS) {
            JsonNode value = node.get(field);
            // "type" is a generic field name: it counts only on an object that also
            // carries model parameters (an LLM task, a cascade step, a judge).
            boolean modelSpec = !"type".equals(field) || node.has("parameters");
            if (modelSpec && value != null && value.isTextual() && isIndirect(value.asText())) {
                facts.privileged.add("indirect|" + field + "|" + value.asText().trim() + AT + path);
            }
        }

        if (!contentLoggingEditorsAllowed) {
            for (String flag : LOG_FLAGS) {
                if (isOn(settings.get(flag))) {
                    facts.privileged.add("log|" + path + "|" + flag);
                }
            }
        }
        if (provider == null) {
            return;
        }
        if (JLAMA.equals(provider)) {
            for (String key : JLAMA_PATHS) {
                String value = settings.get(key);
                if (value == null || value.isBlank()) {
                    continue;
                }
                boolean inside = jlamaBase.isPresent() && JlamaModelDirectory.confine(value, jlamaBase.get()).isPresent();
                if (!inside) {
                    facts.privileged.add("jlama-path|" + key + "|" + value.trim() + AT + path);
                }
            }
        }
        if (isRestricted(node, provider)) {
            Map<String, String> pointing = new TreeMap<>();
            settings.forEach((key, value) -> {
                String lower = key.toLowerCase(Locale.ROOT);
                if (POINTING_HINTS.stream().anyMatch(lower::contains)) {
                    pointing.put(key, value);
                }
            });
            facts.privileged.add("provider|" + provider + "|" + pointing + AT + path);
        }
    }

    private boolean isRestricted(JsonNode node, String provider) {
        JsonNode embedding = node.get(EMBEDDING_PROVIDER_FIELD);
        if (embedding != null && embedding.isTextual() && provider.equals(embedding.asText().trim().toLowerCase(Locale.ROOT))) {
            return restrictedEmbeddingProviders.contains(provider);
        }
        return restrictedProviders.contains(provider);
    }

    /** The restricted or Jlama provider this object names, lower-cased, or null. */
    private String providerOf(JsonNode node) {
        JsonNode embedding = node.get(EMBEDDING_PROVIDER_FIELD);
        if (embedding != null && embedding.isTextual()) {
            String value = embedding.asText().trim().toLowerCase(Locale.ROOT);
            if (restrictedEmbeddingProviders.contains(value)) {
                return value;
            }
        }
        for (String field : PROVIDER_FIELDS) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual()) {
                String name = value.asText().trim().toLowerCase(Locale.ROOT);
                if (restrictedProviders.contains(name) || JLAMA.equals(name)) {
                    return name;
                }
            }
        }
        return null;
    }

    /** The object's own scalar fields plus those of its parameter maps. */
    private static Map<String, String> settingsOf(JsonNode node) {
        Map<String, String> settings = new TreeMap<>();
        scalars(node, settings);
        for (String field : PARAMETER_FIELDS) {
            JsonNode parameters = node.get(field);
            if (parameters != null && parameters.isObject()) {
                scalars(parameters, settings);
            }
        }
        return settings;
    }

    private static void scalars(JsonNode node, Map<String, String> into) {
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (field.getValue().isValueNode() && !field.getValue().isNull()) {
                into.put(field.getKey(), field.getValue().asText());
            }
        }
    }

    /**
     * Whether a logging flag is on. Anything but an explicit {@code false} counts —
     * a template such as {@code {properties.debug}} can render {@code true}.
     */
    private static boolean isOn(String value) {
        return value != null && !value.isBlank() && !"false".equalsIgnoreCase(value.trim());
    }

    private String describe(String fact) {
        String[] parts = fact.split("\\|", 3);
        return switch (parts[0]) {
            case "log" -> "content logging ('" + parts[2] + "'; " + CONTENT_LOGGING_EDITORS_PROPERTY + ")";
            case "jlama-path" -> "the Jlama path '" + parts[1] + "'" + (jlamaBase.isPresent()
                    ? " outside " + JlamaModelDirectory.PROPERTY
                    : " (set " + JlamaModelDirectory.PROPERTY + " to let editors choose paths under it)");
            case "indirect" -> "a provider '" + parts[1] + "' given as a variable or template ('" + (parts.length > 2 ? parts[2] : "")
                    + "'), which could resolve to a restricted provider — name the provider literally";
            case "provider" -> "the restricted provider '" + parts[1] + "' or its model, location or credentials ("
                    + RESTRICTED_PROVIDERS_PROPERTY + ", " + RESTRICTED_EMBEDDING_PROVIDERS_PROPERTY + ")";
            default -> "a document that could not be inspected";
        };
    }

    private boolean callerIsAdmin() {
        try {
            return identity != null && !identity.isAnonymous() && identity.hasRole("eddi-admin");
        } catch (RuntimeException outsideARequest) {
            // No request context, so no administrator to vouch for the setting.
            return false;
        }
    }

    private static ForbiddenException forbidden(String message) {
        return new ForbiddenException(message, Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("error", message))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
