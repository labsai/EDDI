/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One row of the deployment price catalog: the per-1M-token prices of the
 * models a {@code provider}/{@code model} pattern matches.
 *
 * @param provider
 *            the EDDI model type ({@code anthropic}, {@code openai},
 *            {@code xai}, {@code openai-compatible}, ...) or {@code *}
 * @param model
 *            the model name, case-insensitive: exact, or with {@code *} as a
 *            leading and/or trailing wildcard only ({@code gpt-4o*},
 *            {@code *anthropic.claude*}, {@code *})
 * @param inputPricePer1M
 *            USD per 1M uncached prompt tokens; {@code 0} is a real price
 *            (self-hosted models), null is "not priced"
 * @param cacheReadPricePer1M
 *            USD per 1M prompt tokens served from the provider's cache; null
 *            charges them at {@code inputPricePer1M}
 * @param cacheWritePricePer1M
 *            USD per 1M prompt tokens written to the cache; null charges them
 *            at {@code inputPricePer1M}
 * @param longContext
 *            the tier for prompts above a threshold, or null
 * @param asOf
 *            ISO date the price was taken from the provider (display and the
 *            staleness gauge only)
 * @param source
 *            http(s) link to where the price was taken from — display only,
 *            never fetched
 * @param note
 *            free text for the admin
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelPriceEntry(String provider, String model, Double inputPricePer1M, Double outputPricePer1M, Double cacheReadPricePer1M,
        Double cacheWritePricePer1M, LongContextTier longContext, String asOf, String source, String note) {

    /** A plain input/output entry. */
    public static ModelPriceEntry of(String provider, String model, Double inputPricePer1M, Double outputPricePer1M) {
        return new ModelPriceEntry(provider, model, inputPricePer1M, outputPricePer1M, null, null, null, null, null, null);
    }
}
