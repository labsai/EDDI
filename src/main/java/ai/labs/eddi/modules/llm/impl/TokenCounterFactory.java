/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.TokenCountEstimator;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Resolves the appropriate {@link TokenCountEstimator} based on model type.
 * <p>
 * Used by {@link ConversationHistoryBuilder} for token-aware conversation
 * window management (Strategy 1).
 *
 * <ul>
 * <li>{@code openai} / {@code azure-openai} → {@link OpenAiTokenCountEstimator}
 * (tiktoken-based, accurate) when jtokkit knows the model name, otherwise the
 * approximate estimator</li>
 * <li>All other providers → {@link ApproximateTokenCountEstimator} (chars / 4
 * heuristic)</li>
 * </ul>
 */
@ApplicationScoped
public class TokenCounterFactory {

    private static final Logger LOGGER = Logger.getLogger(TokenCounterFactory.class);

    /** Model used when an OpenAI-family task names none. */
    static final String DEFAULT_OPENAI_MODEL = "gpt-4o";

    /**
     * Upper bound on distinct model names remembered. Model names come from agent
     * configuration (and, through global variables, can change at runtime), so the
     * set is not fixed; a map keyed on them must not grow for the JVM lifetime.
     */
    static final int MAX_CACHED_ESTIMATORS = 256;

    private static final TokenCountEstimator APPROXIMATE = new ApproximateTokenCountEstimator();

    private final Cache<String, TokenCountEstimator> estimatorCache = Caffeine.newBuilder().maximumSize(MAX_CACHED_ESTIMATORS).build();

    /**
     * Get a token count estimator for the given model type.
     *
     * <p>
     * Never throws. {@link OpenAiTokenCountEstimator} refuses to construct for a
     * model name jtokkit does not know — every Azure deployment name
     * ({@code my-gpt4-prod}), every OpenAI-compatible endpoint serving a non-OpenAI
     * model, every model released after the jtokkit version on the classpath — and
     * that exception used to escape from here straight through
     * {@code LlmTask.executeTask}, failing every turn of an agent that set
     * {@code maxContextTokens}. Such a model is counted approximately instead,
     * which is what every non-OpenAI provider already gets.
     * </p>
     *
     * @param modelType
     *            LLM provider type (e.g. "openai", "anthropic", "gemini")
     * @param modelName
     *            optional model name for provider-specific resolution (e.g.
     *            "gpt-4o")
     * @return a TokenCountEstimator instance (cached per model type and name)
     */
    public TokenCountEstimator getEstimator(String modelType, String modelName) {
        if (modelType == null) {
            return APPROXIMATE;
        }

        return switch (modelType.toLowerCase()) {
            case "openai", "azure-openai" -> {
                String model = modelName != null && !modelName.isBlank() ? modelName : DEFAULT_OPENAI_MODEL;
                yield estimatorCache.get("openai:" + model, key -> openAiOrApproximate(model));
            }
            default -> APPROXIMATE;
        };
    }

    /**
     * The tiktoken estimator for {@code modelName}, or the approximate one when
     * jtokkit cannot build it. Probed once, so a model that constructs but cannot
     * count is caught here rather than in the middle of a turn.
     */
    static TokenCountEstimator openAiOrApproximate(String modelName) {
        try {
            var estimator = new OpenAiTokenCountEstimator(modelName);
            estimator.estimateTokenCountInText("probe");
            return estimator;
        } catch (RuntimeException unknownModel) {
            LOGGER.infof("No tiktoken encoding for model '%s' (%s); counting its tokens approximately (chars / 4)",
                    sanitize(modelName), sanitize(unknownModel.getMessage()));
            return APPROXIMATE;
        }
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
     * <p>
     * Public rather than package-private so {@code ToolContextBudget}
     * ({@code ai.labs.eddi.modules.llm.impl.orchestration}, extracted from
     * {@code AgentOrchestrator} in R2 step 3) can meter the same text this class
     * counts.
     */
    public static String extractText(ChatMessage message) {
        String text = switch (message) {
            case SystemMessage sm -> sm.text();
            case AiMessage am -> aiMessageText(am);
            case ToolExecutionResultMessage trm -> toolResultText(trm);
            case UserMessage um -> um.hasSingleText()
                    ? um.singleText()
                    : um.contents().stream().filter(c -> c instanceof TextContent).map(c -> ((TextContent) c).text())
                            .reduce("", (a, b) -> a + " " + b).trim();
            default -> "";
        };
        // Never null. `sm.text()` and `um.singleText()` can both be null, and the
        // `default` arm already established "" as this method's empty value — so
        // callers reasonably treat the result as a String they can measure.
        // ToolContextBudget#tokensOf does exactly that (`text.length() / 4` in its
        // tokenizer-failure fallback), which would have thrown NPE on a null-text
        // message inside the very branch that exists to keep a turn alive.
        return text != null ? text : "";
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
