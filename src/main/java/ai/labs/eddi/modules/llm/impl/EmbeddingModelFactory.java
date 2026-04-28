/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.secrets.SecretResolver;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.model.bedrock.BedrockTitanEmbeddingModel;
import dev.langchain4j.model.cohere.CohereEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel.TaskType;
import dev.langchain4j.model.mistralai.MistralAiEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.vertexai.VertexAiEmbeddingModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import software.amazon.awssdk.regions.Region;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

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
    private final SecretResolver secretResolver;

    @Inject
    public EmbeddingModelFactory(SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
    }

    /**
     * Returns a cached or newly created embedding model for the given
     * configuration.
     */
    public EmbeddingModel getOrCreate(RagConfiguration config) {
        String paramKey = config.getEmbeddingParameters() != null ? new TreeMap<>(config.getEmbeddingParameters()).toString() : "";
        String cacheKey = config.getEmbeddingProvider() + ":" + paramKey;
        return cache.get(cacheKey, k -> build(config));
    }

    private EmbeddingModel build(RagConfiguration config) {
        Map<String, String> rawParams = config.getEmbeddingParameters() != null ? config.getEmbeddingParameters() : Map.of();
        Map<String, String> params = secretResolver.resolveSecrets(rawParams);
        String provider = config.getEmbeddingProvider();
        LOGGER.infof("Building embedding model for provider: %s", provider);

        return switch (provider) {
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
    }

    private EmbeddingModel buildOpenAi(Map<String, String> params) {
        return OpenAiEmbeddingModel.builder().modelName(params.getOrDefault("model", "text-embedding-3-small")).apiKey(params.get("apiKey")).build();
    }

    private EmbeddingModel buildAzureOpenAi(Map<String, String> params) {
        var builder = dev.langchain4j.model.azure.AzureOpenAiEmbeddingModel.builder()
                .deploymentName(params.getOrDefault("deploymentName", "text-embedding-3-small")).apiKey(params.get("apiKey"));

        if (params.containsKey("endpoint")) {
            builder.endpoint(params.get("endpoint"));
        }
        return builder.build();
    }

    private EmbeddingModel buildOllama(Map<String, String> params) {
        return OllamaEmbeddingModel.builder().modelName(params.getOrDefault("model", "nomic-embed-text"))
                .baseUrl(params.getOrDefault("baseUrl", "http://localhost:11434")).build();
    }

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

    private EmbeddingModel buildMistral(Map<String, String> params) {
        return MistralAiEmbeddingModel.builder().modelName(params.getOrDefault("model", "mistral-embed")).apiKey(params.get("apiKey")).build();
    }

    private EmbeddingModel buildBedrock(Map<String, String> params) {
        String model = params.getOrDefault("model", "amazon.titan-embed-text-v2:0");
        String region = params.getOrDefault("region", "us-east-1");
        return BedrockTitanEmbeddingModel.builder().model(model).region(Region.of(region)).build();
    }

    private EmbeddingModel buildCohere(Map<String, String> params) {
        return CohereEmbeddingModel.builder().modelName(params.getOrDefault("model", "embed-english-v3.0")).apiKey(params.get("apiKey")).build();
    }

    private EmbeddingModel buildVertex(Map<String, String> params) {
        String project = params.get("project");
        String location = params.getOrDefault("location", "us-central1");
        String model = params.getOrDefault("model", "text-embedding-005");
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("Vertex AI embedding requires 'project' parameter");
        }
        return VertexAiEmbeddingModel.builder().project(project).location(location).modelName(model).build();
    }

    private TaskType parseTaskType(String taskTypeStr) {
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
     * Clears the model cache. Useful for testing or config hot-reload.
     */
    public void clearCache() {
        cache.invalidateAll();
    }
}
