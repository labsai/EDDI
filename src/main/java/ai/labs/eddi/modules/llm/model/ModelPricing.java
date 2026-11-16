/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import ai.labs.eddi.engine.pricing.model.LongContextTier;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The optional {@code pricing} object on an LLM task or cascade step: the
 * prices beyond {@code inputPricePer1M}/{@code outputPricePer1M}, for this one
 * config. Every field is optional; anything left null comes from the deployment
 * price catalog entry the model matches, and failing that a cache price falls
 * back to the input price and a long-context price to the base price
 * (docs/model-pricing.md).
 *
 * <pre>
 * "pricing": {
 *   "cacheReadPricePer1M": 0.30,
 *   "cacheWritePricePer1M": 3.75,
 *   "longContext": { "inputPricePer1M": 6.0, "outputPricePer1M": 22.5 }
 * }
 * </pre>
 *
 * @param longContext
 *            the long-context tier's prices; its {@code thresholdTokens} is
 *            ignored here — the threshold is a property of the model and comes
 *            from the catalog
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelPricing(Double cacheReadPricePer1M, Double cacheWritePricePer1M, LongContextTier longContext) {
}
