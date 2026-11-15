/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pricing;

import ai.labs.eddi.engine.pricing.model.LongContextTier;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;

/**
 * Effective per-1M-token prices for the four token buckets, and the one formula
 * that turns buckets into dollars:
 *
 * <pre>
 * cost = max(0, input − cacheRead − cacheWrite) × in
 *      + cacheRead  × (cacheRead  ?? in)
 *      + cacheWrite × (cacheWrite ?? in)
 *      + output     × out
 * </pre>
 *
 * A cache price that is unknown is charged at the input price — conservative,
 * because over-counting is safe under a hard budget and under-counting is not.
 * A null input or output price contributes nothing for its bucket. Every price
 * that is not finite or is negative is treated as null.
 */
public record BucketPrices(Double input, Double output, Double cacheRead, Double cacheWrite) {

    public static final BucketPrices NONE = new BucketPrices(null, null, null, null);

    public BucketPrices {
        input = clean(input);
        output = clean(output);
        cacheRead = clean(cacheRead);
        cacheWrite = clean(cacheWrite);
    }

    /** The base prices of a catalog entry. */
    public static BucketPrices of(ModelPriceEntry entry) {
        return entry == null
                ? NONE
                : new BucketPrices(entry.inputPricePer1M(), entry.outputPricePer1M(), entry.cacheReadPricePer1M(), entry.cacheWritePricePer1M());
    }

    /** True when an input or an output price is known. */
    public boolean priced() {
        return input != null || output != null;
    }

    /** Each price of this, else {@code fallback}'s. */
    public BucketPrices orElse(BucketPrices fallback) {
        if (fallback == null) {
            return this;
        }
        return new BucketPrices(input != null ? input : fallback.input, output != null ? output : fallback.output,
                cacheRead != null ? cacheRead : fallback.cacheRead, cacheWrite != null ? cacheWrite : fallback.cacheWrite);
    }

    /**
     * The long-context tier's prices over these base prices: a tier price that is
     * not set falls back to the base price of the same kind.
     */
    public BucketPrices tier(LongContextTier tier) {
        if (tier == null) {
            return this;
        }
        return new BucketPrices(tier.inputPricePer1M(), tier.outputPricePer1M(), tier.cacheReadPricePer1M(), tier.cacheWritePricePer1M())
                .orElse(this);
    }

    /** USD for these token buckets at these prices. Never negative, never NaN. */
    public double cost(long inputTokens, long cacheReadTokens, long cacheWriteTokens, long outputTokens) {
        long in = Math.max(0, inputTokens);
        long cr = Math.max(0, cacheReadTokens);
        long cw = Math.max(0, cacheWriteTokens);
        long out = Math.max(0, outputTokens);
        long uncached = Math.max(0, in - cr - cw);
        double usd = 0.0;
        if (input != null) {
            usd += uncached * input;
        }
        Double crPrice = cacheRead != null ? cacheRead : input;
        if (crPrice != null) {
            usd += cr * crPrice;
        }
        Double cwPrice = cacheWrite != null ? cacheWrite : input;
        if (cwPrice != null) {
            usd += cw * cwPrice;
        }
        if (output != null) {
            usd += out * output;
        }
        usd = usd / 1_000_000.0;
        return Double.isFinite(usd) && usd > 0 ? usd : 0.0;
    }

    private static Double clean(Double price) {
        return price != null && Double.isFinite(price) && price >= 0 ? price : null;
    }
}
