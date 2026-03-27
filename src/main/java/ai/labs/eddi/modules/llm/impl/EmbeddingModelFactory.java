package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates and caches {@link EmbeddingModel} instances based on
 * {@link RagConfiguration}. Follows the same pattern as
 * {@link ChatModelRegistry} for LLM models.
 */
@ApplicationScoped
public class EmbeddingModelFactory {

    private static final Logger LOGGER = Logger.getLogger(EmbeddingModelFactory.class);

    private final Map<String, EmbeddingModel> cache = new ConcurrentHashMap<>();
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
        return cache.computeIfAbsent(cacheKey, k -> build(config));
    }

    private EmbeddingModel build(RagConfiguration config) {
        Map<String, String> rawParams = config.getEmbeddingParameters() != null ? config.getEmbeddingParameters() : Map.of();
        Map<String, String> params = secretResolver.resolveSecrets(rawParams);
        String provider = config.getEmbeddingProvider();
        LOGGER.infof("Building embedding model for provider: %s", provider);

        return switch (provider) {
            case "openai" ->
                OpenAiEmbeddingModel.builder().modelName(params.getOrDefault("model", "text-embedding-3-small")).apiKey(params.get("apiKey")).build();
            case "ollama" -> OllamaEmbeddingModel.builder().modelName(params.getOrDefault("model", "nomic-embed-text"))
                    .baseUrl(params.getOrDefault("baseUrl", "http://localhost:11434")).build();
            default -> throw new IllegalArgumentException("Unsupported embedding provider: " + provider);
        };
    }

    /**
     * Clears the model cache. Useful for testing or config hot-reload.
     */
    public void clearCache() {
        cache.clear();
    }
}
