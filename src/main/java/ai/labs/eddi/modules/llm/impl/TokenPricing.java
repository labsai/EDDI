/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import java.util.Map;

/**
 * The single home of the configured-price token-cost arithmetic. Both dollar
 * signals that price LLM token spend — cascade steps
 * ({@code CascadingModelExecutor.computeCost}) and plain task-level pricing
 * ({@code LlmTask.accumulateAuditEvidence}) — delegate here, so the formula
 * cannot drift between the two paths.
 * <p>
 * There is deliberately no built-in provider price table: prices are an
 * agent-designer concern and belong in configuration, where they can track
 * provider changes without a code release.
 * <p>
 * Public because the group transcript summarizer (I9,
 * {@code GroupContextBuilder}) prices its own summarization calls with the same
 * formula.
 */
public final class TokenPricing {

    private TokenPricing() {
    }

    /**
     * Dollar cost of one call from its reported token usage and configured
     * per-1M-token prices. A null price means "unpriced" and contributes 0; with
     * both prices null (or no usage reported) the call is free.
     *
     * @param tokenUsage
     *            the {@code tokenUsage} metadata map carrying
     *            {@code inputTokens}/{@code outputTokens} as numbers
     */
    public static double cost(Double inputPricePer1M, Double outputPricePer1M, Map<?, ?> tokenUsage) {
        if (tokenUsage == null || (inputPricePer1M == null && outputPricePer1M == null)) {
            return 0.0;
        }
        long inputTokens = asLong(tokenUsage.get("inputTokens"));
        long outputTokens = asLong(tokenUsage.get("outputTokens"));
        double cost = 0.0;
        if (inputPricePer1M != null) {
            cost += inputTokens / 1_000_000.0 * inputPricePer1M;
        }
        if (outputPricePer1M != null) {
            cost += outputTokens / 1_000_000.0 * outputPricePer1M;
        }
        return cost;
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
