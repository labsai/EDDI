/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.ToolResponseLimits;
import ai.labs.eddi.modules.llm.tools.PaginatedResponseStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Truncates tool response strings that exceed configured character limits.
 * <p>
 * This prevents context window bloat from verbose tool outputs (e.g., large web
 * scrapes, full PDF dumps, or raw API responses). Truncation happens after the
 * tool executes but before the result is injected into the LLM context.
 * <p>
 * <strong>Wave 2 strategies:</strong>
 * <ul>
 * <li>{@code truncate} — hard cut at limit with truncation note (default)</li>
 * <li>{@code paginate} — split into pages, return first page + responseId for
 * fetch_tool_response_page</li>
 * <li>{@code summarize} — route through summarizer model, fallback to truncate
 * on failure</li>
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

    private final MeterRegistry meterRegistry;

    @Inject
    PaginatedResponseStore paginatedResponseStore;

    @Inject
    public ToolResponseTruncator(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Truncate the tool result if it exceeds the configured limit.
     *
     * @param toolName
     *            the tool that produced the result
     * @param result
     *            the raw tool result string
     * @param limits
     *            the response limit configuration (may be null)
     * @return the (possibly truncated) result string
     */
    public String truncateIfNeeded(String toolName, String result, ToolResponseLimits limits) {
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
            case "summarize" -> summarizeResponse(toolName, result, maxChars, limits);
            default -> truncateResponse(toolName, result, maxChars);
        };
    }

    /**
     * Default truncation: hard cut with note about original length.
     */
    private String truncateResponse(String toolName, String result, int maxChars) {
        int originalLength = result.length();
        String truncated = result.substring(0, maxChars) + TRUNCATION_SUFFIX.formatted(originalLength, maxChars);

        LOGGER.debugf("Truncated tool '%s' response: %d -> %d chars", toolName, originalLength, maxChars);
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
            LOGGER.warnf("PaginatedResponseStore not available, falling back to truncation for tool '%s'", toolName);
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
                toolName, originalLength, pageCount, responseId);
        incrementCounter(toolName, "paginate");

        return paginated;
    }

    /**
     * Summarize: falls back to truncation. Actual model-based summarization is not
     * yet implemented — this method provides the cost-ceiling guard and fallback
     * chain so that the "summarize" strategy degrades safely.
     */
    private String summarizeResponse(String toolName, String result, int maxChars, ToolResponseLimits limits) {
        String summarizerModel = limits.getSummarizerModel();

        if (summarizerModel == null || summarizerModel.isBlank()) {
            LOGGER.debugf("No summarizer model configured for tool '%s', falling back to truncation", toolName);
            return truncateResponse(toolName, result, maxChars);
        }

        // Cost ceiling check: if result is too large for summarization
        // (> 200k chars ≈ 50k tokens), skip to truncation
        if (result.length() > 200_000) {
            LOGGER.debugf("Response too large for summarization (%d chars), falling back to truncation for tool '%s'",
                    result.length(), toolName);
            incrementCounter(toolName, "summarize_fallback");
            return truncateResponse(toolName, result, maxChars);
        }

        // Model-based summarization not yet wired — degrade to truncation
        LOGGER.warnf("Summarize strategy requested for tool '%s' (model='%s') but summarization "
                + "is not yet implemented. Falling back to truncation.", toolName, summarizerModel);
        incrementCounter(toolName, "summarize_fallback");
        return truncateResponse(toolName, result, maxChars);
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
