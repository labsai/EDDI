/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolContextBudget;
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

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Truncates tool response strings that exceed configured character limits.
 * <p>
 * This prevents context window bloat from verbose tool outputs (e.g., large web
 * scrapes, full PDF dumps, or raw API responses). Truncation happens after the
 * tool executes but before the result is injected into the LLM context.
 * <p>
 * It receives a result the tool-result guardrail has already screened in full,
 * so the pages it stores and the text it hands a summarizer are governed text —
 * see {@code ToolLoopRunner}.
 * <p>
 * <strong>Strategies:</strong>
 * <ul>
 * <li>{@code headTail} — keep the start and the end, drop the middle, with a
 * note in its place (default when no strategy is set). The end of a tool result
 * is where exit codes, error messages and totals usually sit.</li>
 * <li>{@code truncate} — hard cut at limit with truncation note: keeps the
 * start only</li>
 * <li>{@code paginate} — split into pages, return first page + responseId for
 * fetch_tool_response_page</li>
 * <li>{@code summarize} — route through a cheap summarizer model, inheriting
 * the parent task's provider and API key. Falls back to truncate on failure.
 * The provider type is resolved through {@link GlobalVariableResolver}, as
 * {@code LlmTask} resolves it; the parameters are passed as configured, where
 * the model registry resolves {@code ${vars:...}} and vault references but a
 * Qute expression stays unrendered.</li>
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
    private static final String HEAD_TAIL_MARKER = "\n\n[TRUNCATED: Response was %d characters, limit is %d. " +
            "The middle %d characters were omitted; the first %d and the last %d are shown. " +
            "Request more specific data or use pagination if available.]\n\n";

    /** The strategy used when {@code truncationStrategy} is not set. */
    static final String DEFAULT_STRATEGY = "headtail";

    /**
     * Share of the kept characters taken from the start under {@code headTail}; the
     * rest comes from the end. The start usually says what the result is, the end
     * how it finished, and the start is the larger part of both.
     */
    static final double HEAD_SHARE = 0.7;

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
    private final GlobalVariableResolver globalVariableResolver;
    private final PaginatedResponseStore paginatedResponseStore;

    @Inject
    public ToolResponseTruncator(MeterRegistry meterRegistry, ChatModelRegistry chatModelRegistry, GlobalVariableResolver globalVariableResolver,
            PaginatedResponseStore paginatedResponseStore) {
        this.meterRegistry = meterRegistry;
        this.chatModelRegistry = chatModelRegistry;
        this.globalVariableResolver = globalVariableResolver;
        this.paginatedResponseStore = paginatedResponseStore;
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
     *            the parent task's LLM provider type (e.g., "openai", or a
     *            {@code ${vars:...}} reference to one), used by summarize strategy
     * @param taskParameters
     *            the parent task's parameters (includes apiKey, baseUrl, etc.),
     *            used by summarize strategy
     * @return the (possibly truncated) result string
     */
    public String truncateIfNeeded(String toolName, String result, ToolResponseLimits limits,
                                   String taskType, Map<String, String> taskParameters) {
        return truncateIfNeeded(null, toolName, result, limits, taskType, taskParameters);
    }

    /**
     * As
     * {@link #truncateIfNeeded(String, String, ToolResponseLimits, String, Map)},
     * for a result produced inside {@code conversationId}. The {@code paginate}
     * strategy stores its pages under that conversation and the pages can be
     * fetched from it alone (L13); without a conversation it cannot scope the pages
     * and falls back to truncation.
     */
    public String truncateIfNeeded(String conversationId, String toolName, String result, ToolResponseLimits limits,
                                   String taskType, Map<String, String> taskParameters) {
        return truncateIfNeeded(conversationId, toolName, result, limits, taskType, taskParameters, SummarizerSpend.UNMETERED);
    }

    /**
     * The spend side of the {@code summarize} strategy: its summarizer call is a
     * paid model call like any other of the turn.
     */
    interface SummarizerSpend {

        /** Neither checked nor charged — a caller outside a conversation turn. */
        SummarizerSpend UNMETERED = new SummarizerSpend() {
            @Override
            public boolean admit() {
                return true;
            }

            @Override
            public void charge(Map<String, Object> tokenUsage) {
                // nothing to charge
            }
        };

        /**
         * Whether the summarizer may be called: false when the conversation's cost
         * ceiling or the tenant budget is reached — the result is then truncated
         * instead.
         */
        boolean admit();

        /** Charges the summarizer call's token usage to the conversation. */
        void charge(Map<String, Object> tokenUsage);
    }

    /**
     * As
     * {@link #truncateIfNeeded(String, String, String, ToolResponseLimits, String, Map)},
     * with the {@code summarize} strategy's call checked and charged through
     * {@code spend}.
     */
    public String truncateIfNeeded(String conversationId, String toolName, String result, ToolResponseLimits limits,
                                   String taskType, Map<String, String> taskParameters, SummarizerSpend spend) {
        if (limits == null || result == null) {
            return result;
        }

        int maxChars = resolveLimit(toolName, limits);
        if (maxChars <= 0 || result.length() <= maxChars) {
            return result;
        }

        // Determine strategy
        String strategy = limits.getTruncationStrategy() != null ? limits.getTruncationStrategy() : DEFAULT_STRATEGY;

        return switch (strategy.toLowerCase(Locale.ROOT)) {
            case "headtail" -> headTailResponse(toolName, result, maxChars);
            case "paginate" -> paginateResponse(conversationId, toolName, result, maxChars);
            case "summarize" -> summarizeResponse(toolName, result, maxChars, limits, taskType, taskParameters,
                    spend != null ? spend : SummarizerSpend.UNMETERED);
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
     * Head-and-tail truncation: keeps {@link #HEAD_SHARE} of {@code maxChars} from
     * the start and the rest from the end, with a note in place of the middle. The
     * kept content is exactly {@code maxChars} characters, as with
     * {@code truncate}; only the note is extra.
     * <p>
     * Neither cut splits a surrogate pair, so an emoji or other supplementary
     * character at a boundary is dropped whole rather than leaving half of it.
     */
    private String headTailResponse(String toolName, String result, int maxChars) {
        int originalLength = result.length();
        int headEnd = (int) (maxChars * HEAD_SHARE);
        int tailStart = originalLength - (maxChars - headEnd);
        if (headEnd > 0 && Character.isHighSurrogate(result.charAt(headEnd - 1))) {
            headEnd--;
        }
        if (tailStart < originalLength && Character.isLowSurrogate(result.charAt(tailStart))) {
            tailStart++;
        }
        int tailLength = originalLength - tailStart;
        String truncated = result.substring(0, headEnd)
                + HEAD_TAIL_MARKER.formatted(originalLength, maxChars, tailStart - headEnd, headEnd, tailLength)
                + result.substring(tailStart);

        LOGGER.debugf("Truncated tool '%s' response (head and tail): %d -> %d chars", sanitize(toolName), originalLength,
                headEnd + tailLength);
        incrementCounter(toolName, "headtail");

        return truncated;
    }

    /**
     * Paginate: split into pages, store in PaginatedResponseStore, return first
     * page with responseId.
     * <p>
     * Responses exceeding {@link #PAGINATE_MAX_STORABLE_CHARS} fall back to
     * truncation to prevent excessive memory usage from materializing all page
     * substrings in the Caffeine cache.
     */
    static final int PAGINATE_MAX_STORABLE_CHARS = 500_000;

    private String paginateResponse(String conversationId, String toolName, String result, int maxChars) {
        int originalLength = result.length();

        if (conversationId == null || conversationId.isBlank()) {
            LOGGER.warnf("No conversation to scope paginated pages to, falling back to truncation for tool '%s'", sanitize(toolName));
            return truncateResponse(toolName, result, maxChars);
        }

        // Memory guard — refuse to materialize very large responses as pages
        if (originalLength > PAGINATE_MAX_STORABLE_CHARS) {
            LOGGER.warnf("Response too large for pagination (%d chars > %d ceiling), " +
                    "falling back to truncation for tool '%s'",
                    originalLength, PAGINATE_MAX_STORABLE_CHARS, sanitize(toolName));
            return truncateResponse(toolName, result, maxChars);
        }

        String responseId = paginatedResponseStore.store(conversationId, toolName, result, maxChars);
        if (responseId == null) {
            return truncateResponse(toolName, result, maxChars);
        }

        int pageCount = paginatedResponseStore.getPageCount(conversationId, responseId);
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
     * <li>The spend check refuses the call (conversation cost ceiling, tenant
     * budget) → truncate; an admitted call is charged whatever happens next</li>
     * <li>Model creation or LLM call fails → truncate</li>
     * <li>Summary is longer than {@code maxChars} → truncate</li>
     * <li>Summary is empty → truncate</li>
     * </ol>
     */
    private String summarizeResponse(String toolName, String result, int maxChars,
                                     ToolResponseLimits limits, String taskType,
                                     Map<String, String> taskParameters, SummarizerSpend spend) {
        String summarizerModel = limits.getSummarizerModel();

        // Guard 1: no summarizer model configured
        if (summarizerModel == null || summarizerModel.isBlank()) {
            LOGGER.debugf("No summarizer model configured for tool '%s', falling back to truncation", sanitize(toolName));
            incrementCounter(toolName, "summarize_fallback");
            return truncateResponse(toolName, result, maxChars);
        }

        // The task's type may be a ${vars:...} reference, as LlmTask allows; the
        // registry can build no model for the unresolved string, and the summary
        // then silently became a truncation. The parameters need no such step: the
        // registry resolves variable and vault references in them itself.
        String type = globalVariableResolver.resolveValue(taskType);

        // Guard 2: no parent task context (can't build model without provider + API
        // key)
        if (type == null || type.isBlank() || taskParameters == null) {
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

        // Spend guard: a summarizer call is paid like every other model
        // call of the turn and must not start once the conversation's ceiling or the
        // tenant budget is reached.
        if (!spend.admit()) {
            LOGGER.debugf("Spend check refused summarizing tool '%s'; falling back to truncation", sanitize(toolName));
            incrementCounter(toolName, "summarize_fallback");
            return truncateResponse(toolName, result, maxChars);
        }

        try {
            // Build summarizer params: inherit parent task's params, override modelName
            // (every provider's model key — see ModelParameterKeys#withModel)
            Map<String, String> summarizerParams = ModelParameterKeys.withModel(taskParameters, type, summarizerModel);
            // Strip responseFormat — summarizer must return plain text, not JSON
            summarizerParams.remove("responseFormat");

            // Use the parent task's provider type — inherits API key, baseUrl, etc.
            ChatModel model = chatModelRegistry.getOrCreate(type, summarizerParams);

            String systemPrompt = SUMMARIZER_SYSTEM_PROMPT.formatted(maxChars);
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(result));

            var response = RetryConfiguration.executeWithDefaultRetry(() -> model.chat(ChatRequest.builder().messages(messages).build()),
                    "Tool response summarization");
            // Paid whatever happens to the summary below.
            if (response.tokenUsage() != null) {
                spend.charge(ToolContextBudget.tokenUsageMap(response.tokenUsage()));
            }
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

    /**
     * A per-tool entry present but null falls back to the default rather than
     * unboxing. {@code perToolLimits} is deserialized from agent JSON, where
     * {@code {"fetch_page": null}} is a legal document, and the
     * containsKey-then-get form turned that one character into an NPE that failed
     * the whole turn.
     */
    private int resolveLimit(String toolName, ToolResponseLimits limits) {
        Map<String, Integer> perToolLimits = limits.getPerToolLimits();
        Integer perTool = perToolLimits == null ? null : perToolLimits.get(toolName);
        return perTool != null ? perTool : limits.getDefaultMaxChars();
    }

    private void incrementCounter(String toolName, String strategy) {
        Counter.builder("eddi.mcp.response.truncation.count")
                .tag("tool", toolName)
                .tag("strategy", strategy)
                .register(meterRegistry)
                .increment();
    }
}
