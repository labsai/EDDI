/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionParameterGuard;
import ai.labs.eddi.secrets.SecretResolver;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.model.bedrock.BedrockTitanEmbeddingModel;
import dev.langchain4j.model.cohere.CohereEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel.TaskType;
import dev.langchain4j.model.mistralai.MistralAiEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.vertexai.VertexAiEmbeddingModel;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import software.amazon.awssdk.regions.Region;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import dev.langchain4j.model.azure.AzureOpenAiEmbeddingModel;

/**
 * Creates and caches {@link EmbeddingModel} instances based on
 * {@link RagConfiguration}. Follows the same pattern as
 * {@link ChatModelRegistry} for LLM models.
 * <p>
 * Supported providers: {@code openai}, {@code azure-openai}, {@code ollama},
 * {@code mistral}, {@code bedrock}, {@code cohere}, {@code gemini},
 * {@code vertex}.
 * <p>
 * Cache is bounded (max 50 entries, 30-minute idle TTL) to prevent memory leaks
 * in multi-tenant or dynamic-config environments.
 */
@ApplicationScoped
public class EmbeddingModelFactory {

    private static final Logger LOGGER = Logger.getLogger(EmbeddingModelFactory.class);

    private final Cache<String, EmbeddingModel> cache = Caffeine.newBuilder().maximumSize(50).expireAfterAccess(Duration.ofMinutes(30)).build();
    private final GlobalVariableResolver globalVariableResolver;
    private final SecretResolver secretResolver;

    @Inject
    public EmbeddingModelFactory(GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver) {
        this.globalVariableResolver = globalVariableResolver;
        this.secretResolver = secretResolver;
    }

    /**
     * Evict cached models when a vault secret or a global variable changes.
     * <p>
     * Without this the cache was effectively permanent for anything in active use:
     * {@code expireAfterAccess} resets on every read, so a model serving traffic
     * never reached its TTL, and {@link #clearCache()} had no production caller at
     * all. Rotating an embedding provider's API key therefore kept authenticating
     * with the old one for the lifetime of the process.
     * <p>
     * The invalidation is deliberately total rather than scanning cache keys for
     * the changed reference the way {@link ChatModelRegistry} does: at most 50
     * entries are involved and rebuilding one is a constructor call, so the extra
     * bookkeeping buys nothing.
     */
    @PostConstruct
    void registerInvalidation() {
        secretResolver.registerInvalidationListener(reference -> clearCache());
        globalVariableResolver.registerInvalidationListener(this::clearCache);
        LOGGER.info("EmbeddingModelFactory registered for secret and global variable invalidation events");
    }

    /**
     * Returns a cached or newly created embedding model for the given
     * configuration, tagged for the role it will be used in.
     *
     * <h4>Why the role is a required argument</h4>
     *
     * Asymmetric embedding models produce a different vector for the same text
     * depending on whether it is being stored or searched with, and they have to be
     * told which. Ingestion and retrieval previously called a single-argument
     * {@code getOrCreate} with the same configuration, so they shared one cache
     * entry and therefore one instance — and Gemini bakes its {@code taskType} in
     * at construction, defaulting to {@code RETRIEVAL_DOCUMENT}. Every Gemini
     * knowledge base was embedding its queries as documents.
     * <p>
     * Making the role a required parameter rather than adding an optional overload
     * is the point: a caller cannot forget it, and the compiler names every site
     * that has to choose. There are two.
     *
     * @param config
     *            the knowledge base's embedding configuration
     * @param inputType
     *            {@link EmbeddingInputType#DOCUMENT} when ingesting,
     *            {@link EmbeddingInputType#QUERY} when retrieving
     *
     * @return a model for that role; for the providers that do not accept an input
     *         type this is the provider's model unchanged
     *
     * @see InputTypedEmbeddingModel
     */
    public EmbeddingModel getOrCreate(RagConfiguration config, EmbeddingInputType inputType) {
        String paramKey = config.getEmbeddingParameters() != null ? new TreeMap<>(config.getEmbeddingParameters()).toString() : "";
        // The role is part of the key: to an asymmetric provider the two roles are
        // two different models, and sharing one entry is precisely the defect.
        String cacheKey = config.getEmbeddingProvider() + ":" + paramKey + ":" + inputType;
        return cache.get(cacheKey, k -> build(config, inputType));
    }

    private EmbeddingModel build(RagConfiguration config, EmbeddingInputType inputType) {
        Map<String, String> rawParams = config.getEmbeddingParameters() != null ? config.getEmbeddingParameters() : Map.of();
        Map<String, String> params = globalVariableResolver.resolveAll(rawParams);
        ConnectionParameterGuard.rejectConnectionReferences(params);
        // Trimmed, as RagConfiguration validates it: " openai" must not save and then
        // fail here as an unsupported provider.
        String provider = config.getEmbeddingProvider() != null ? config.getEmbeddingProvider().trim() : null;
        params = SecretResolver.requireResolved(secretResolver.resolveSecrets(params), "embedding model '" + provider + "'");
        LOGGER.infof("Building embedding model for provider: %s", provider);

        EmbeddingModel model = switch (provider) {
            case "openai" -> buildOpenAi(params);
            case "azure-openai" -> buildAzureOpenAi(params);
            case "ollama" -> buildOllama(params);
            case "mistral" -> buildMistral(params);
            case "bedrock" -> buildBedrock(params);
            case "cohere" -> buildCohere(params);
            case "gemini" -> buildGemini(params);
            case "vertex" -> buildVertex(params);
            default -> throw new IllegalArgumentException(
                    "Unsupported embedding provider: " + provider
                            + ". Supported: openai, azure-openai, ollama, mistral, bedrock, cohere, gemini, vertex");
        };

        if (pinsNonRetrievalTaskType(provider, params)) {
            LOGGER.infof("Embedding provider %s pins taskType=%s; leaving the %s role unset so the configured task type stands",
                    provider, params.get("taskType"), inputType);
            return model;
        }
        return InputTypedEmbeddingModel.wrapIfSupported(model, inputType);
    }

    /**
     * True when the configuration deliberately pins a Gemini {@code taskType} that
     * is not about retrieval, and that choice would be overridden by the
     * per-request role.
     *
     * <h4>Why the role does not simply always win</h4>
     *
     * {@code GoogleAiEmbeddingModel.toTaskType} falls back to the build-time
     * {@code taskType} <em>only when no input type is given</em>; a
     * {@link EmbeddingInputType#QUERY} or {@link EmbeddingInputType#DOCUMENT} maps
     * unconditionally onto {@code RETRIEVAL_QUERY} / {@code RETRIEVAL_DOCUMENT}. So
     * tagging every RAG call with its role — the fix for embedding queries as
     * documents — would also silently retire a {@code taskType} of
     * {@code SEMANTIC_SIMILARITY}, {@code CLASSIFICATION} or {@code CLUSTERING}
     * that an operator had set on purpose. Vectors already in the store would keep
     * that task type while everything ingested afterwards used
     * {@code RETRIEVAL_DOCUMENT}: two incompatible geometries in one index, with
     * nothing failing to say so.
     * <p>
     * {@code RETRIEVAL_DOCUMENT} and {@code RETRIEVAL_QUERY} are not treated as
     * pinned. {@code RETRIEVAL_DOCUMENT} is the default this factory applies when
     * nothing is configured, and it is exactly the value that produced the defect;
     * honouring it would leave the bug in place for anyone who had written the
     * default out explicitly. Both values say "this knowledge base is for
     * retrieval", which is the statement the role refines rather than contradicts.
     *
     * <h4>Why the model name matters</h4>
     *
     * Gemini Embedding 2 does not accept {@code task_type} at all — langchain4j
     * sends {@code null} for any model whose name contains {@code embedding-2} and
     * applies a role-specific <em>prompt instruction</em> instead. A pinned task
     * type is already inert there, so skipping the role would cost the instruction
     * and buy nothing. The name test mirrors that library rule; if a dependency
     * bump changes it, the symptom is this method going quiet for a model that does
     * honour the task type, which {@code EmbeddingModelFactoryTest} pins by name.
     * <p>
     * Package-private, and tested directly, because every path through
     * {@link #build} constructs a live provider client — which needs a socket, so
     * the model-level tests only run where one is available. This predicate is the
     * whole decision and it can be graded anywhere.
     */
    static boolean pinsNonRetrievalTaskType(String provider, Map<String, String> params) {
        if (!"gemini".equals(provider)) {
            return false;
        }
        String configured = params.get("taskType");
        if (configured == null || configured.isBlank()) {
            return false;
        }
        String modelName = params.getOrDefault("model", "gemini-embedding-2");
        if (modelName.contains("embedding-2")) {
            return false;
        }
        TaskType taskType = parseTaskType(configured);
        return taskType != TaskType.RETRIEVAL_DOCUMENT && taskType != TaskType.RETRIEVAL_QUERY;
    }

    // ──────────────────────────────────────────────────
    // OpenAI
    // ──────────────────────────────────────────────────

    /**
     * Builds an OpenAI-backed {@link EmbeddingModel} from configuration parameters.
     * <p>
     * Supported embeddingParameters:
     * <ul>
     * <li>{@code apiKey} — OpenAI API key, supports {@code ${eddivault:...}}
     * (required)</li>
     * <li>{@code model} — model name (default: "text-embedding-3-small")</li>
     * </ul>
     */
    private EmbeddingModel buildOpenAi(Map<String, String> params) {
        return OpenAiEmbeddingModel.builder().modelName(params.getOrDefault("model", "text-embedding-3-small")).apiKey(params.get("apiKey")).build();
    }

    // ──────────────────────────────────────────────────
    // Azure OpenAI
    // ──────────────────────────────────────────────────

    /**
     * Builds an Azure OpenAI-backed {@link EmbeddingModel} from configuration
     * parameters.
     * <p>
     * Supported embeddingParameters:
     * <ul>
     * <li>{@code apiKey} — Azure API key, supports {@code ${eddivault:...}}
     * (required)</li>
     * <li>{@code deploymentName} — deployment name (default:
     * "text-embedding-3-small")</li>
     * <li>{@code endpoint} — Azure endpoint (optional)</li>
     * </ul>
     */
    private EmbeddingModel buildAzureOpenAi(Map<String, String> params) {
        var builder = AzureOpenAiEmbeddingModel.builder()
                .deploymentName(params.getOrDefault("deploymentName", "text-embedding-3-small")).apiKey(params.get("apiKey"));

        if (params.containsKey("endpoint")) {
            builder.endpoint(params.get("endpoint"));
        }
        return builder.build();
    }

    // ──────────────────────────────────────────────────
    // Ollama
    // ──────────────────────────────────────────────────

    /**
     * Builds an Ollama-backed {@link EmbeddingModel} from configuration parameters.
     * <p>
     * Supported embeddingParameters:
     * <ul>
     * <li>{@code model} — model name (default: "nomic-embed-text")</li>
     * <li>{@code baseUrl} — Ollama server URL (default:
     * "http://localhost:11434")</li>
     * </ul>
     */
    private EmbeddingModel buildOllama(Map<String, String> params) {
        return OllamaEmbeddingModel.builder().modelName(params.getOrDefault("model", "nomic-embed-text"))
                .baseUrl(params.getOrDefault("baseUrl", "http://localhost:11434")).build();
    }

    // ──────────────────────────────────────────────────
    // Gemini
    // ──────────────────────────────────────────────────

    /**
     * Builds a Google Gemini-backed {@link EmbeddingModel} from configuration
     * parameters.
     * <p>
     * Supported embeddingParameters:
     * <ul>
     * <li>{@code apiKey} — Google AI API key, supports {@code ${eddivault:...}}
     * (required)</li>
     * <li>{@code model} — model name (default: "gemini-embedding-2")</li>
     * <li>{@code taskType} — task type (default: "RETRIEVAL_DOCUMENT")</li>
     * <li>{@code outputDimensionality} — output dimension (default: 3072)</li>
     * </ul>
     */
    private EmbeddingModel buildGemini(Map<String, String> params) {
        TaskType taskType = parseTaskType(params.getOrDefault("taskType", "RETRIEVAL_DOCUMENT"));
        Integer outputDimensionality = parseIntParam(params, "outputDimensionality", 3072);

        return GoogleAiEmbeddingModel.builder()
                .modelName(params.getOrDefault("model", "gemini-embedding-2"))
                .apiKey(params.get("apiKey"))
                .outputDimensionality(outputDimensionality)
                .taskType(taskType)
                .build();
    }

    // ──────────────────────────────────────────────────
    // Mistral
    // ──────────────────────────────────────────────────

    /**
     * Builds a Mistral AI-backed {@link EmbeddingModel} from configuration
     * parameters.
     * <p>
     * Supported embeddingParameters:
     * <ul>
     * <li>{@code apiKey} — Mistral API key, supports {@code ${eddivault:...}}
     * (required)</li>
     * <li>{@code model} — model name (default: "mistral-embed")</li>
     * </ul>
     */
    private EmbeddingModel buildMistral(Map<String, String> params) {
        return MistralAiEmbeddingModel.builder().modelName(params.getOrDefault("model", "mistral-embed")).apiKey(params.get("apiKey")).build();
    }

    // ──────────────────────────────────────────────────
    // Bedrock
    // ──────────────────────────────────────────────────

    /**
     * Builds an AWS Bedrock-backed {@link EmbeddingModel} from configuration
     * parameters.
     * <p>
     * Supported embeddingParameters:
     * <ul>
     * <li>{@code model} — model name (default: "amazon.titan-embed-text-v2:0")</li>
     * <li>{@code region} — AWS region (default: "us-east-1")</li>
     * </ul>
     */
    private EmbeddingModel buildBedrock(Map<String, String> params) {
        String model = params.getOrDefault("model", "amazon.titan-embed-text-v2:0");
        String region = params.getOrDefault("region", "us-east-1");
        return BedrockTitanEmbeddingModel.builder().model(model).region(Region.of(region)).build();
    }

    // ──────────────────────────────────────────────────
    // Cohere
    // ──────────────────────────────────────────────────

    /**
     * Builds a Cohere-backed {@link EmbeddingModel} from configuration parameters.
     * <p>
     * Supported embeddingParameters:
     * <ul>
     * <li>{@code apiKey} — Cohere API key, supports {@code ${eddivault:...}}
     * (required)</li>
     * <li>{@code model} — model name (default: "embed-english-v3.0")</li>
     * </ul>
     */
    private EmbeddingModel buildCohere(Map<String, String> params) {
        return CohereEmbeddingModel.builder().modelName(params.getOrDefault("model", "embed-english-v3.0")).apiKey(params.get("apiKey")).build();
    }

    // ──────────────────────────────────────────────────
    // Vertex AI
    // ──────────────────────────────────────────────────

    /**
     * Builds a Google Vertex AI-backed {@link EmbeddingModel} from configuration
     * parameters.
     * <p>
     * Supported embeddingParameters:
     * <ul>
     * <li>{@code project} — GCP project ID (required)</li>
     * <li>{@code location} — GCP location (default: "us-central1")</li>
     * <li>{@code model} — model name (default: "text-embedding-005")</li>
     * </ul>
     */
    private EmbeddingModel buildVertex(Map<String, String> params) {
        String project = params.get("project");
        String location = params.getOrDefault("location", "us-central1");
        String model = params.getOrDefault("model", "text-embedding-005");
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("Vertex AI embedding requires 'project' parameter");
        }
        return VertexAiEmbeddingModel.builder().project(project).location(location).modelName(model).build();
    }

    private static TaskType parseTaskType(String taskTypeStr) {
        try {
            TaskType taskType = (taskTypeStr == null || taskTypeStr.isBlank())
                    ? TaskType.RETRIEVAL_DOCUMENT
                    : TaskType.valueOf(taskTypeStr);
            return taskType;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format("Invalid '%s' TaskType. Valid TaskTypes '%s'", taskTypeStr,
                    Arrays.toString(TaskType.values())), e);
        }
    }

    /**
     * Parses an integer parameter with a default, providing a clear error on
     * invalid values.
     */
    private int parseIntParam(Map<String, String> params, String key, int defaultValue) {
        String raw = params.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer value for '" + key + "': " + raw, e);
        }
    }

    /**
     * Clears the model cache. Called on secret rotation and global-variable edits
     * (see {@link #registerInvalidation()}), and by tests.
     */
    public void clearCache() {
        cache.invalidateAll();
    }
}
