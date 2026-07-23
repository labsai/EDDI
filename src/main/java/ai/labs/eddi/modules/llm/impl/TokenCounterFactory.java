/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
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
     * <p>
     * Tool traffic counts too. An {@link AiMessage} that carries tool-execution
     * requests contributes the requested tool names and their serialized arguments,
     * and a {@link ToolExecutionResultMessage} contributes the tool name plus the
     * whole result payload. Both used to fall through to {@code ""} — an
     * {@code AiMessage} announcing five tool calls has a {@code null}
     * {@code text()} and every result message hit the {@code default} arm — which
     * made the entire in-turn tool context weigh zero tokens to every caller of
     * this class.
     */
    static String extractText(ChatMessage message) {
        return switch (message) {
            case SystemMessage sm -> sm.text();
            case AiMessage am -> aiMessageText(am);
            case ToolExecutionResultMessage trm -> toolResultText(trm);
            case UserMessage um -> um.hasSingleText()
                    ? um.singleText()
                    : um.contents().stream().filter(c -> c instanceof TextContent).map(c -> ((TextContent) c).text())
                            .reduce("", (a, b) -> a + " " + b).trim();
            default -> "";
        };
    }

    /**
     * An assistant turn's billable text: its own prose plus, when it announces tool
     * calls, each requested tool name and its serialized argument JSON — the bytes
     * the provider actually receives back in the next request.
     */
    private static String aiMessageText(AiMessage message) {
        String text = message.text() != null ? message.text() : "";
        if (!message.hasToolExecutionRequests()) {
            return text;
        }
        StringBuilder builder = new StringBuilder(text);
        for (ToolExecutionRequest request : message.toolExecutionRequests()) {
            if (request.name() != null) {
                builder.append(' ').append(request.name());
            }
            if (request.arguments() != null) {
                builder.append(' ').append(request.arguments());
            }
        }
        return builder.toString().trim();
    }

    /**
     * A tool result's billable text: the tool name plus the full result payload.
     */
    private static String toolResultText(ToolExecutionResultMessage message) {
        String text = message.text() != null ? message.text() : "";
        String toolName = message.toolName();
        return toolName != null ? (toolName + " " + text).trim() : text;
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
