/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.modules.llm.impl.TokenPricing;

import java.util.Map;

/**
 * Prices one SERVICE convergence-judge call.
 * <p>
 * A deliberate one-method seam. Today it applies the configured per-1M prices
 * through {@link TokenPricing}, the formula every other group summarizer uses.
 * When a central price resolver lands (provider/model catalog, explicit
 * overrides winning), only this method body changes — the judge and its tests
 * keep calling the same signature, so the two changes can merge in either
 * order. That is also why {@code provider} and {@code model} are parameters
 * although the current body does not read them: they are reserved for the
 * catalog resolver ({@code ModelPriceResolver}) that the cost-accounting change
 * (https://github.com/labsai/EDDI/pull/1222) introduces, which prices a call by
 * provider and model. Do not remove them as unused.
 */
public final class JudgeCallPricing {

    private JudgeCallPricing() {
    }

    /**
     * Dollar cost of one judge call. Null prices mean unpriced and contribute 0, so
     * an unpriced judge is free on the ledger — the same semantics as the other
     * group summarizers.
     */
    public static double cost(String provider, String model, Double inputPricePer1M, Double outputPricePer1M, int inputTokens,
                              int outputTokens) {
        return TokenPricing.cost(inputPricePer1M, outputPricePer1M, Map.of("inputTokens", inputTokens, "outputTokens", outputTokens));
    }
}
