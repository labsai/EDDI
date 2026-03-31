package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the appropriate {@link TokenCountEstimator} based on model type.
 * <p>
 * Used by {@link ConversationHistoryBuilder} for token-aware conversation
 * window management (Strategy 1).
 *
 * <ul>
 * <li>{@code openai} / {@code azure-openai} → {@link OpenAiTokenCountEstimator}
 * (tiktoken-based, accurate)</li>
 * <li>All other providers → {@link ApproximateTokenCountEstimator} (chars / 4
 * heuristic)</li>
 * </ul>
 */
@ApplicationScoped
public class TokenCounterFactory {

    private final Map<String, TokenCountEstimator> estimatorCache = new ConcurrentHashMap<>();

    /**
     * Get a token count estimator for the given model type.
     *
     * @param modelType
     *            LLM provider type (e.g. "openai", "anthropic", "gemini")
     * @param modelName
     *            optional model name for provider-specific resolution (e.g.
     *            "gpt-4o")
     * @return a TokenCountEstimator instance (cached per model type)
     */
    public TokenCountEstimator getEstimator(String modelType, String modelName) {
        if (modelType == null) {
            return estimatorCache.computeIfAbsent("__approximate__", k -> new ApproximateTokenCountEstimator());
        }

        return switch (modelType.toLowerCase()) {
            case "openai", "azure-openai" -> estimatorCache.computeIfAbsent("openai:" + (modelName != null ? modelName : "gpt-4o"),
                    k -> new OpenAiTokenCountEstimator(modelName != null ? modelName : "gpt-4o"));
            default -> estimatorCache.computeIfAbsent("__approximate__", k -> new ApproximateTokenCountEstimator());
        };
    }

    /**
     * Extract the text content from a ChatMessage for token counting.
     */
    static String extractText(ChatMessage message) {
        return switch (message) {
            case SystemMessage sm -> sm.text();
            case AiMessage am -> am.text() != null ? am.text() : "";
            case UserMessage um -> um.hasSingleText()
                    ? um.singleText()
                    : um.contents().stream().filter(c -> c instanceof TextContent).map(c -> ((TextContent) c).text())
                            .reduce("", (a, b) -> a + " " + b).trim();
            default -> "";
        };
    }

    /**
     * Fallback token count estimator using characters / 4 approximation. Suitable
     * for providers without native tokenizers (Anthropic, Gemini, Ollama, etc.).
     */
    static class ApproximateTokenCountEstimator implements TokenCountEstimator {

        @Override
        public int estimateTokenCountInText(String text) {
            if (text == null || text.isEmpty()) {
                return 0;
            }
            return Math.max(1, text.length() / 4);
        }

        @Override
        public int estimateTokenCountInMessage(ChatMessage message) {
            return estimateTokenCountInText(extractText(message));
        }

        @Override
        public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
            int total = 0;
            for (ChatMessage message : messages) {
                total += estimateTokenCountInMessage(message);
            }
            return total;
        }
    }
}
