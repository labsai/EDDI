/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.pricing.ModelPriceResolver;
import ai.labs.eddi.modules.llm.pricing.PriceOverride;
import ai.labs.eddi.modules.llm.pricing.PricedSpend;

import java.util.Map;

/**
 * The configured-price token-cost entry point for callers that hold a pair of
 * prices rather than a task: guardrails, the cascade's confidence judge, group
 * and stance summaries, Dream, format re-asks. A thin wrapper over
 * {@link ModelPriceResolver}, the one price resolution every model call goes
 * through.
 * <p>
 * This class used to say there was deliberately no built-in provider price
 * table. That stance is reversed (docs/model-pricing.md): the deployment price
 * catalog completes whatever a configuration leaves unpriced. It still ships no
 * <em>active</em> prices — an empty catalog prices exactly as before, and an
 * admin adopts the bundled reference prices explicitly.
 */
public final class TokenPricing {

    private TokenPricing() {
    }

    /**
     * Dollar cost of one call (or a sum of calls) from its reported token usage and
     * configured per-1M-token prices. A null price is completed from the catalog
     * entry the usage's model matches; with no price anywhere the call is unpriced
     * and contributes 0.
     *
     * @param tokenUsage
     *            the {@code tokenUsage} metadata map ({@code inputTokens},
     *            {@code outputTokens}, and the metered keys when present)
     */
    public static double cost(Double inputPricePer1M, Double outputPricePer1M, Map<?, ?> tokenUsage) {
        return price(inputPricePer1M, outputPricePer1M, tokenUsage).usd();
    }

    /** As {@link #cost}, with the unpriced count and the price source. */
    public static PricedSpend price(Double inputPricePer1M, Double outputPricePer1M, Map<?, ?> tokenUsage) {
        return ModelPriceResolver.price(PriceOverride.of(inputPricePer1M, outputPricePer1M), tokenUsage);
    }
}
