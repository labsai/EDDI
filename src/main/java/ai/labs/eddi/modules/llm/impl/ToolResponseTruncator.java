/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.ToolResponseLimits;
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
 * When a response is truncated, a note is appended indicating the original
 * length so the LLM knows information was lost and can request a more targeted
 * follow-up query.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class ToolResponseTruncator {

    private static final Logger LOGGER = Logger.getLogger(ToolResponseTruncator.class);
    private static final String TRUNCATION_SUFFIX = "\n\n[TRUNCATED: Response was %d characters, limit is %d. " +
            "Request more specific data or use pagination if available.]";

    private final MeterRegistry meterRegistry;

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

        int originalLength = result.length();
        String truncated = result.substring(0, maxChars) + TRUNCATION_SUFFIX.formatted(originalLength, maxChars);

        LOGGER.debugf("Truncated tool '%s' response: %d -> %d chars", toolName, originalLength, maxChars);
        incrementCounter(toolName);

        return truncated;
    }

    private int resolveLimit(String toolName, ToolResponseLimits limits) {
        if (limits.getPerToolLimits() != null && limits.getPerToolLimits().containsKey(toolName)) {
            return limits.getPerToolLimits().get(toolName);
        }
        return limits.getDefaultMaxChars();
    }

    private void incrementCounter(String toolName) {
        Counter.builder("eddi.mcp.response.truncation.count")
                .tag("tool", toolName)
                .register(meterRegistry)
                .increment();
    }
}
