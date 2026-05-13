/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.ToolResponseLimits;
import ai.labs.eddi.modules.llm.tools.PaginatedResponseStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Truncates tool response strings that exceed configured character limits.
 * <p>
 * This prevents context window bloat from verbose tool outputs (e.g., large web
 * scrapes, full PDF dumps, or raw API responses). Truncation happens after the
 * tool executes but before the result is injected into the LLM context.
 * <p>
 * <strong>Strategies:</strong>
 * <ul>
 * <li>{@code truncate} — hard cut at limit with truncation note (default)</li>
 * <li>{@code paginate} — split into pages, return first page + responseId for
 * fetch_tool_response_page</li>
 * <li>{@code summarize} — route through a cheap summarizer model, inheriting
 * the parent task's provider and API key. Falls back to truncate on
 * failure.</li>
 * </ul>
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class ToolResponseTruncator {

    private static final Logger LOGGER = Logger.getLogger(ToolResponseTruncator.class);
    private static final String TRUNCATION_SUFFIX = "\n\n[TRUNCATED: Response was %d characters, limit is %d. " +
            "Request more specific data or use pagination if available.]";
    private static final String PAGINATION_SUFFIX = "\n\n[PAGINATED: Response was %d characters. " +
            "This is page 1 of %d. Use fetch_tool_response_page(responseId=\"%s\", pageNumber=N) to retrieve more pages.]";
    private static final String SUMMARY_HEADER = "[SUMMARY — original: %d chars, tool: %s]\n";

    /**
     * Cost ceiling: responses larger than this (≈ 50k tokens) are too expensive to
     * summarize — skip straight to truncation.
     */
    static final int SUMMARIZE_COST_CEILING_CHARS = 200_000;

    /**
     * System prompt for the summarizer model. Purpose-built and not configurable —
     * this is engine behavior, not agent logic.
     */
    static final String SUMMARIZER_SYSTEM_PROMPT = "Summarize the following tool response concisely. Preserve all key data points, " +
            "numbers, identifiers, and actionable information. Remove formatting noise, " +
            "repeated content, and boilerplate. The summary MUST be under %d characters.";

    private final MeterRegistry meterRegistry;
    private final ChatModelRegistry chatModelRegistry;

    @Inject
    PaginatedResponseStore paginatedResponseStore;

    @Inject
    public ToolResponseTruncator(MeterRegistry meterRegistry, ChatModelRegistry chatModelRegistry) {
        this.meterRegistry = meterRegistry;
        this.chatModelRegistry = chatModelRegistry;
    }

    /**
     * Truncate the tool result if it exceeds the configured limit.
     * <p>
     * When the {@code summarize} strategy is active, the parent task's provider
     * type and parameters (including API key) are used to build the summarizer
     * model. Only the {@code modelName} is overridden with {@code summarizerModel}.
     *
     * @param toolName
     *            the tool that produced the result
     * @param result
     *            the raw tool result string
     * @param limits
     *            the response limit configuration (may be null)
     * @param taskType
     *            the parent task's LLM provider type (e.g., "openai"), used by
     *            summarize strategy
     * @param taskParameters
     *            the parent task's parameters (includes apiKey, baseUrl, etc.),
     *            used by summarize strategy
     * @return the (possibly truncated) result string
     */
    public String truncateIfNeeded(String toolName, String result, ToolResponseLimits limits,
                                   String taskType, Map<String, String> taskParameters) {
        if (limits == null || result == null) {
            return result;
        }

        int maxChars = resolveLimit(toolName, limits);
        if (maxChars <= 0 || result.length() <= maxChars) {
            return result;
        }

        // Determine strategy
        String strategy = limits.getTruncationStrategy() != null ? limits.getTruncationStrategy() : "truncate";

        return switch (strategy.toLowerCase()) {
            case "paginate" -> paginateResponse(toolName, result, maxChars);
            case "summarize" -> summarizeResponse(toolName, result, maxChars, limits, taskType, taskParameters);
            default -> truncateResponse(toolName, result, maxChars);
        };
    }

    /**
     * Default truncation: hard cut with note about original length.
     */
    private String truncateResponse(String toolName, String result, int maxChars) {
        int originalLength = result.length();
        String truncated = result.substring(0, maxChars) + TRUNCATION_SUFFIX.formatted(originalLength, maxChars);

        LOGGER.debugf("Truncated tool '%s' response: %d -> %d chars", sanitize(toolName), originalLength, maxChars);
        incrementCounter(toolName, "truncate");

        return truncated;
    }

    /**
     * Paginate: split into pages, store in PaginatedResponseStore, return first
     * page with responseId.
     */
    private String paginateResponse(String toolName, String result, int maxChars) {
        int originalLength = result.length();

        if (paginatedResponseStore == null) {
            LOGGER.warnf("PaginatedResponseStore not available, falling back to truncation for tool '%s'", sanitize(toolName));
            return truncateResponse(toolName, result, maxChars);
        }

        String responseId = paginatedResponseStore.store(toolName, result, maxChars);
        if (responseId == null) {
            return truncateResponse(toolName, result, maxChars);
        }

        int pageCount = paginatedResponseStore.getPageCount(responseId);
        String firstPage = result.substring(0, Math.min(maxChars, originalLength));
        String paginated = firstPage + PAGINATION_SUFFIX.formatted(originalLength, pageCount, responseId);

        LOGGER.debugf("Paginated tool '%s' response: %d chars into %d pages (responseId=%s)",
                sanitize(toolName), originalLength, pageCount, responseId);
        incrementCounter(toolName, "paginate");

        return paginated;
    }

    /**
     * Summarize: route tool response through a cheap LLM model for summarization,
     * inheriting the parent task's provider and API key.
     * <p>
     * Fallback chain:
     * <ol>
     * <li>No {@code summarizerModel} configured → truncate</li>
     * <li>No {@code taskType} or {@code taskParameters} → truncate</li>
     * <li>Response exceeds cost ceiling (200K chars) → truncate</li>
     * <li>Model creation or LLM call fails → truncate</li>
     * <li>Summary is longer than {@code maxChars} → truncate</li>
     * <li>Summary is empty → truncate</li>
     * </ol>
     */
    private String summarizeResponse(String toolName, String result, int maxChars,
                                     ToolResponseLimits limits, String taskType,
                                     Map<String, String> taskParameters) {
        String summarizerModel = limits.getSummarizerModel();

        // Guard 1: no summarizer model configured
        if (summarizerModel == null || summarizerModel.isBlank()) {
            LOGGER.debugf("No summarizer model configured for tool '%s', falling back to truncation", sanitize(toolName));
            incrementCounter(toolName, "summarize_fallback");
            return truncateResponse(toolName, result, maxChars);
        }

        // Guard 2: no parent task context (can't build model without provider + API
        // key)
        if (taskType == null || taskType.isBlank() || taskParameters == null) {
            LOGGER.warnf("No task context available for summarization of tool '%s', falling back to truncation", sanitize(toolName));
            incrementCounter(toolName, "summarize_fallback");
            return truncateResponse(toolName, result, maxChars);
        }

        // Guard 3: cost ceiling — too expensive to summarize
        if (result.length() > SUMMARIZE_COST_CEILING_CHARS) {
            LOGGER.debugf("Response too large for summarization (%d chars), falling back to truncation for tool '%s'",
                    result.length(), sanitize(toolName));
            incrementCounter(toolName, "summarize_fallback");
            return truncateResponse(toolName, result, maxChars);
        }

        try {
            // Build summarizer params: inherit parent task's params, override modelName
            Map<String, String> summarizerParams = new HashMap<>(taskParameters);
            summarizerParams.put("modelName", summarizerModel);
            // Strip responseFormat — summarizer must return plain text, not JSON
            summarizerParams.remove("responseFormat");

            // Use the parent task's provider type — inherits API key, baseUrl, etc.
            ChatModel model = chatModelRegistry.getOrCreate(taskType, summarizerParams);

            String systemPrompt = SUMMARIZER_SYSTEM_PROMPT.formatted(maxChars);
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(result));

            var response = model.chat(ChatRequest.builder().messages(messages).build());
            String summary = response.aiMessage().text();

            // Guard 4: empty summary
            if (summary == null || summary.isBlank()) {
                LOGGER.warnf("Summarizer returned empty result for tool '%s', falling back to truncation", sanitize(toolName));
                incrementCounter(toolName, "summarize_fallback");
                return truncateResponse(toolName, result, maxChars);
            }

            // Guard 5: summary + header combined must fit within maxChars
            String header = SUMMARY_HEADER.formatted(result.length(), toolName);
            if (summary.length() + header.length() > maxChars) {
                LOGGER.debugf("Summary for tool '%s' exceeded limit (%d + %d header > %d), falling back to truncation",
                        sanitize(toolName), summary.length(), header.length(), maxChars);
                incrementCounter(toolName, "summarize_fallback");
                return truncateResponse(toolName, result, maxChars);
            }

            LOGGER.debugf("Summarized tool '%s' response: %d -> %d chars (model=%s)",
                    sanitize(toolName), result.length(), summary.length(), sanitize(summarizerModel));
            incrementCounter(toolName, "summarize");

            return header + summary;

        } catch (Exception e) {
            LOGGER.warnf("Summarization failed for tool '%s': %s. Falling back to truncation.",
                    sanitize(toolName), sanitize(e.getMessage()));
            incrementCounter(toolName, "summarize_fallback");
            return truncateResponse(toolName, result, maxChars);
        }
    }

    private int resolveLimit(String toolName, ToolResponseLimits limits) {
        if (limits.getPerToolLimits() != null && limits.getPerToolLimits().containsKey(toolName)) {
            return limits.getPerToolLimits().get(toolName);
        }
        return limits.getDefaultMaxChars();
    }

    private void incrementCounter(String toolName, String strategy) {
        Counter.builder("eddi.mcp.response.truncation.count")
                .tag("tool", toolName)
                .tag("strategy", strategy)
                .register(meterRegistry)
                .increment();
    }
}
