/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import ai.labs.eddi.secrets.VaultReferencePlacement;
import ai.labs.eddi.utils.EndpointParameters;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@code embeddingParameters} each embedding provider actually honours.
 *
 * <h2>Why a parameter the provider ignores is an error</h2>
 * <p>
 * The {@code openai} provider used to be built from {@code model} and
 * {@code apiKey} alone. An operator who pointed it at a private
 * OpenAI-compatible endpoint with {@code baseUrl} — which the RAG guide listed
 * — had every document sent to {@code api.openai.com} instead, with nothing to
 * say so but an "Incorrect API key" in the log. A parameter that names
 * <em>where</em> data goes and is not honoured is therefore refused, at save
 * time by {@link RagConfiguration#validate()} and again when the model is built
 * (for a configuration stored before this check existed). Other unrecognised
 * parameters are only logged: they cannot redirect data, and refusing them
 * would break every stored configuration that carries a harmless leftover.
 * <p>
 * {@code model} and {@code modelName} are aliases — the LLM task configuration
 * calls the field {@code modelName}, and authors copy from it. {@code timeout}
 * is in milliseconds, as it is for LLM tasks.
 */
public final class EmbeddingParameters {

    public static final String MODEL = "model";
    public static final String MODEL_NAME = "modelName";

    private static final Map<String, Set<String>> HONOURED = Map.of(
            "openai", Set.of(MODEL, MODEL_NAME, "apiKey", "baseUrl", "timeout", "organizationId", "projectId", "dimensions",
                    "maxRetries"),
            "azure-openai", Set.of("deploymentName", "apiKey", "endpoint", "timeout", "dimensions", "maxRetries"),
            "ollama", Set.of(MODEL, MODEL_NAME, "baseUrl", "timeout", "maxRetries"),
            "mistral", Set.of(MODEL, MODEL_NAME, "apiKey", "baseUrl", "timeout", "maxRetries"),
            "bedrock", Set.of(MODEL, MODEL_NAME, "region", "dimensions"),
            "cohere", Set.of(MODEL, MODEL_NAME, "apiKey", "baseUrl", "timeout"),
            "gemini", Set.of(MODEL, MODEL_NAME, "apiKey", "baseUrl", "timeout", "maxRetries", "taskType", "outputDimensionality"),
            "vertex", Set.of(MODEL, MODEL_NAME, "project", "location", "endpoint", "maxRetries"));

    /**
     * The parameters of each provider that carry a credential — the only ones a
     * {@code ${vault:…}} reference may sit in (see
     * {@link VaultReferencePlacement}). Ollama, Bedrock and Vertex take none:
     * Ollama is unauthenticated, Bedrock and Vertex authenticate through the cloud
     * credential chain.
     */
    private static final Map<String, Set<String>> CREDENTIALS = Map.of(
            "openai", Set.of("apiKey"),
            "azure-openai", Set.of("apiKey"),
            "ollama", Set.of(),
            "mistral", Set.of("apiKey"),
            "bedrock", Set.of(),
            "cohere", Set.of("apiKey"),
            "gemini", Set.of("apiKey"),
            "vertex", Set.of());

    private EmbeddingParameters() {
    }

    /** The parameters {@code provider} honours; empty for an unknown provider. */
    public static Set<String> honoured(String provider) {
        return provider == null ? Set.of() : HONOURED.getOrDefault(provider.trim(), Set.of());
    }

    /**
     * The parameters of {@code provider} that carry a credential; empty for an
     * unknown provider, which therefore accepts no vault reference at all.
     */
    public static Set<String> credentialParameters(String provider) {
        return provider == null ? Set.of() : CREDENTIALS.getOrDefault(provider.trim(), Set.of());
    }

    /**
     * Describes the first problem with these parameters that would make the
     * provider send data somewhere other than configured, or {@code null} — a vault
     * reference outside the credential parameters first, since every other
     * description may quote a value.
     */
    public static String findProblem(String provider, Map<String, String> params) {
        String misplaced = findMisplacedVaultReference(provider, params);
        if (misplaced != null) {
            return misplaced;
        }
        String endpoint = findIgnoredEndpointParameter(provider, params);
        return endpoint != null ? endpoint : findModelNameConflict(params);
    }

    /**
     * A vault reference in a parameter that does not carry a credential, described
     * by its key (never its value), or {@code null}. Checked at save time by
     * {@link RagConfiguration#validate()} and again, after global variables are
     * expanded, before the model is built: a secret resolved into {@code model}
     * comes back in the provider's error, which RAG search and the ingestion status
     * return to the caller.
     */
    public static String findMisplacedVaultReference(String provider, Map<String, String> params) {
        Set<String> credentials = credentialParameters(provider);
        String key = VaultReferencePlacement.findMisplaced(params, credentials);
        if (key == null) {
            return null;
        }
        String name = provider == null ? "" : provider.trim();
        return VaultReferencePlacement.describe("Embedding provider '" + name + "' (embeddingParameters)", key, credentials);
    }

    /**
     * An endpoint parameter ({@link EndpointParameters#isEndpointKey}, so
     * {@code baseURL} as surely as {@code baseUrl}) the provider would ignore,
     * described, or {@code null}. An unknown provider is reported elsewhere.
     */
    public static String findIgnoredEndpointParameter(String provider, Map<String, String> params) {
        if (params == null || provider == null || !HONOURED.containsKey(provider.trim())) {
            return null;
        }
        Set<String> honoured = honoured(provider);
        for (var entry : new TreeSet<>(params.keySet())) {
            if (entry == null || honoured.contains(entry)) {
                continue;
            }
            if (EndpointParameters.isEndpointKey(entry)) {
                return "embeddingParameters." + entry + " is not supported by embedding provider '" + provider.trim()
                        + "', so documents and queries would go to the provider's default endpoint instead. Supported parameters: "
                        + new TreeSet<>(honoured);
            }
        }
        return null;
    }

    /**
     * Parameters the provider does not honour and that are not endpoints — worth a
     * log line, not a refusal.
     */
    public static Set<String> unrecognised(String provider, Map<String, String> params) {
        Set<String> unknown = new TreeSet<>();
        if (params == null || provider == null || !HONOURED.containsKey(provider.trim())) {
            return unknown;
        }
        Set<String> honoured = honoured(provider);
        for (String key : params.keySet()) {
            if (key != null && !honoured.contains(key)) {
                unknown.add(key);
            }
        }
        return unknown;
    }

    /** {@code model} and {@code modelName} both set, to different values. */
    public static String findModelNameConflict(Map<String, String> params) {
        if (params == null) {
            return null;
        }
        String model = params.get(MODEL);
        String modelName = params.get(MODEL_NAME);
        if (model != null && !model.isBlank() && modelName != null && !modelName.isBlank() && !model.equals(modelName)) {
            return "embeddingParameters sets both 'model' ('" + model + "') and 'modelName' ('" + modelName
                    + "'); they are the same setting — keep one";
        }
        return null;
    }

    /**
     * The configured model name ({@code model} or {@code modelName}), or the
     * default.
     */
    public static String modelName(Map<String, String> params, String defaultValue) {
        if (params != null) {
            String model = params.get(MODEL);
            if (model != null && !model.isBlank()) {
                return model;
            }
            String modelName = params.get(MODEL_NAME);
            if (modelName != null && !modelName.isBlank()) {
                return modelName;
            }
        }
        return defaultValue;
    }
}
