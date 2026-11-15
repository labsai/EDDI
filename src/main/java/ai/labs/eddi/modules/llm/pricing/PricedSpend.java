/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pricing;

/**
 * The outcome of pricing one usage map: dollars, how many calls had no price at
 * all, and where the prices came from.
 *
 * @param usd
 *            the cost; never negative or NaN
 * @param unpricedCalls
 *            calls priced at $0 because neither the config nor the catalog
 *            named an input or output price (an explicit {@code 0} is priced)
 * @param source
 *            where the prices came from
 * @param cacheReadTokens
 *            prompt tokens served from the provider cache
 * @param cacheWriteTokens
 *            prompt tokens written to the provider cache
 */
public record PricedSpend(double usd, int unpricedCalls, Source source, long cacheReadTokens, long cacheWriteTokens) {

    /** No usage. */
    public static final PricedSpend NONE = new PricedSpend(0.0, 0, Source.NONE, 0, 0);

    /** Where a cost's prices came from. */
    public enum Source {
        /** No usage was reported. */
        NONE,
        /** Only the configuration's own prices. */
        TASK,
        /** Only catalog prices. */
        CATALOG,
        /** Configuration prices completed by the catalog, or partly unpriced. */
        MIXED,
        /** Nothing priced. */
        UNPRICED
    }

    public PricedSpend {
        usd = Double.isFinite(usd) && usd > 0 ? usd : 0.0;
        unpricedCalls = Math.max(0, unpricedCalls);
        source = source == null ? Source.NONE : source;
    }
}
