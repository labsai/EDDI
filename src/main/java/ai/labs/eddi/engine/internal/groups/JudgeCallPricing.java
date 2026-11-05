/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.modules.llm.pricing.MeteredTokenUsage;
import ai.labs.eddi.modules.llm.pricing.ModelPriceResolver;
import ai.labs.eddi.modules.llm.pricing.PriceOverride;

import java.util.HashMap;
import java.util.Map;

/**
 * Prices one SERVICE convergence-judge call through the one price resolution
 * every model call uses ({@link ModelPriceResolver}): the judge's own
 * {@code inputPricePer1M}/{@code outputPricePer1M} win, and a price the
 * configuration leaves out comes from the deployment's price catalog entry for
 * {@code provider}/{@code model}. A judge priced by neither is unpriced and
 * costs $0, as every other unpriced call does.
 * <p>
 * A deliberate one-method seam: the judge and its tests call this signature
 * only, so a change to how prices resolve stays in this body.
 */
public final class JudgeCallPricing {

    private JudgeCallPricing() {
    }

    /**
     * Dollar cost of one judge call.
     *
     * @param provider
     *            the judge's model type ({@code llmProvider}), used for the catalog
     *            lookup; {@code null} or blank skips the catalog
     * @param model
     *            the judge's model name ({@code llmModel}); {@code null} or blank
     *            skips the catalog
     */
    public static double cost(String provider, String model, Double inputPricePer1M, Double outputPricePer1M, int inputTokens,
                              int outputTokens) {
        Map<String, Object> usage = new HashMap<>();
        usage.put("inputTokens", inputTokens);
        usage.put("outputTokens", outputTokens);
        if (provider != null && !provider.isBlank() && model != null && !model.isBlank()) {
            usage.put(MeteredTokenUsage.PRICING_MODEL, provider.trim() + "/" + model.trim());
        }
        return ModelPriceResolver.price(PriceOverride.of(inputPricePer1M, outputPricePer1M), usage).usd();
    }
}
