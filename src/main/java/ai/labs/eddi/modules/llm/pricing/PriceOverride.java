/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pricing;

import ai.labs.eddi.modules.llm.model.ModelPricing;

/**
 * The prices a configuration sets explicitly for the model it calls — a task's,
 * a cascade step's, a guardrail's, a summary's, Dream's. Whatever it leaves
 * null is resolved from the deployment price catalog by
 * {@link ModelPriceResolver}.
 *
 * @param inputPer1M
 *            USD per 1M input tokens, or null
 * @param outputPer1M
 *            USD per 1M output tokens, or null
 * @param pricing
 *            cache and long-context prices, or null
 */
public record PriceOverride(Double inputPer1M, Double outputPer1M, ModelPricing pricing) {

    /** Nothing set: the catalog decides. */
    public static final PriceOverride NONE = new PriceOverride(null, null, null);

    public static PriceOverride of(Double inputPer1M, Double outputPer1M) {
        return new PriceOverride(inputPer1M, outputPer1M, null);
    }

    /** True when the configuration sets any price of its own. */
    public boolean isExplicit() {
        return inputPer1M != null || outputPer1M != null || pricing != null;
    }
}
