/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The prices a model charges once a single call's prompt is longer than
 * {@code thresholdTokens} — "strictly greater than", the way providers phrase
 * it ("prompts &gt; 200K tokens"). A call is classified on its own prompt, so
 * summing the rounds of a tool loop never moves a short call into the tier.
 * <p>
 * Any price left null falls back to the base price of the same kind.
 *
 * @param thresholdTokens
 *            the prompt size above which this tier applies; required in a
 *            catalog entry, ignored on a task's own {@code pricing} (the
 *            threshold is a property of the model, so it lives in the catalog)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LongContextTier(Long thresholdTokens, Double inputPricePer1M, Double outputPricePer1M, Double cacheReadPricePer1M,
        Double cacheWritePricePer1M) {
}
