/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pricing;

import ai.labs.eddi.engine.pricing.ModelPriceCatalogs;
import ai.labs.eddi.engine.pricing.PriceCatalogSnapshot;
import ai.labs.eddi.engine.pricing.model.LongContextTier;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.modules.llm.model.ModelPricing;

import java.util.Map;
import java.util.Optional;

/**
 * The one price resolution every model call in EDDI goes through. Every pricing
 * site — {@code ModelPrices} (tasks, tool loops, cascade steps, resumes,
 * re-asks) and {@code TokenPricing} (guardrails, the cascade judge, group and
 * stance summaries, Dream) — delegates here, so the precedence below cannot
 * drift between them.
 * <p>
 * For one usage map, each price is chosen in this order:
 * <ol>
 * <li>the configuration's explicit {@code inputPricePer1M} /
 * {@code outputPricePer1M};</li>
 * <li>its optional {@code pricing} object (cache and long-context prices);</li>
 * <li>the catalog entry matched by the usage's {@code pricingModel};</li>
 * <li>fallbacks: an unknown cache price is the input price, an unknown
 * long-context price the base price, and no input or output price at all is
 * <em>unpriced</em> ($0, counted). An explicit {@code 0} is priced.</li>
 * </ol>
 * When the configuration sets no price at all, each call is priced at the
 * catalog entry it matched <em>when it returned</em> (the usage's
 * {@code catalogUsd}), so a catalog edit mid-turn cannot tear a call's price.
 *
 * <h2>For other features</h2> A feature that calls a model outside these paths
 * prices it with {@link #price(PriceOverride, Map)} on the
 * {@code ToolContextBudget.tokenUsageMap} of the response, and reads a model's
 * effective prices with {@link #resolve(String, String)}. Both are stable,
 * public and pure apart from reading {@link ModelPriceCatalogs#current()}.
 */
public final class ModelPriceResolver {

    private ModelPriceResolver() {
    }

    /** Prices {@code usage} against the current catalog. */
    public static PricedSpend price(PriceOverride override, Map<?, ?> usage) {
        return price(override, usage, ModelPriceCatalogs.current());
    }

    /** Prices {@code usage} against {@code catalog}. */
    public static PricedSpend price(PriceOverride override, Map<?, ?> usage, PriceCatalogSnapshot catalog) {
        if (usage == null || usage.isEmpty()) {
            return PricedSpend.NONE;
        }
        PriceCatalogSnapshot snapshot = catalog != null ? catalog : PriceCatalogSnapshot.EMPTY;
        long in = UsageMaps.asLong(usage.get("inputTokens"));
        long out = UsageMaps.asLong(usage.get("outputTokens"));
        long cr = UsageMaps.asLong(usage.get(MeteredTokenUsage.CACHE_READ_TOKENS));
        long cw = UsageMaps.asLong(usage.get(MeteredTokenUsage.CACHE_WRITE_TOKENS));
        long lcIn = UsageMaps.asLong(usage.get(MeteredTokenUsage.LC_INPUT_TOKENS));
        long lcCr = UsageMaps.asLong(usage.get(MeteredTokenUsage.LC_CACHE_READ_TOKENS));
        long lcCw = UsageMaps.asLong(usage.get(MeteredTokenUsage.LC_CACHE_WRITE_TOKENS));
        long lcOut = UsageMaps.asLong(usage.get(MeteredTokenUsage.LC_OUTPUT_TOKENS));
        boolean hasTokens = in > 0 || out > 0;
        int calls = usage.containsKey(MeteredTokenUsage.CALLS) ? (int) UsageMaps.asLong(usage.get(MeteredTokenUsage.CALLS)) : hasTokens ? 1 : 0;
        int uncatalogued = usage.containsKey(MeteredTokenUsage.UNCATALOGUED_CALLS)
                ? (int) UsageMaps.asLong(usage.get(MeteredTokenUsage.UNCATALOGUED_CALLS))
                : calls;
        if (!hasTokens) {
            return new PricedSpend(0.0, 0, PricedSpend.Source.NONE, cr, cw);
        }

        ModelPriceEntry entry = catalogEntry(usage.get(MeteredTokenUsage.PRICING_MODEL), snapshot);

        if (override != null && override.isExplicit()) {
            ModelPricing pricing = override.pricing();
            BucketPrices own = new BucketPrices(override.inputPer1M(), override.outputPer1M(),
                    pricing != null ? pricing.cacheReadPricePer1M() : null, pricing != null ? pricing.cacheWritePricePer1M() : null);
            BucketPrices fromCatalog = BucketPrices.of(entry);
            BucketPrices base = own.orElse(fromCatalog);
            BucketPrices tier = tierPrices(pricing != null ? pricing.longContext() : null, entry, base);
            double usd = base.cost(in - lcIn, cr - lcCr, cw - lcCw, out - lcOut) + tier.cost(lcIn, lcCr, lcCw, lcOut);
            if (!base.priced()) {
                return new PricedSpend(0.0, calls, PricedSpend.Source.UNPRICED, cr, cw);
            }
            boolean catalogFilled = !own.equals(base);
            return new PricedSpend(usd, 0, catalogFilled ? PricedSpend.Source.MIXED : PricedSpend.Source.TASK, cr, cw);
        }

        if (usage.containsKey(MeteredTokenUsage.CATALOG_USD)) {
            double usd = UsageMaps.asDouble(usage.get(MeteredTokenUsage.CATALOG_USD));
            int unpriced = Math.min(uncatalogued, Math.max(calls, 1));
            PricedSpend.Source source = unpriced == 0
                    ? PricedSpend.Source.CATALOG
                    : unpriced < calls ? PricedSpend.Source.MIXED : PricedSpend.Source.UNPRICED;
            return new PricedSpend(usd, unpriced, source, cr, cw);
        }

        if (entry != null) {
            BucketPrices base = BucketPrices.of(entry);
            BucketPrices tier = tierPrices(null, entry, base);
            double usd = base.cost(in - lcIn, cr - lcCr, cw - lcCw, out - lcOut) + tier.cost(lcIn, lcCr, lcCw, lcOut);
            return new PricedSpend(usd, 0, PricedSpend.Source.CATALOG, cr, cw);
        }
        return new PricedSpend(0.0, Math.max(calls, 1), PricedSpend.Source.UNPRICED, cr, cw);
    }

    /**
     * The catalog entry pricing {@code model} on {@code provider} in the current
     * catalog — what a deployment warning, the Manager's price hint or a cost
     * estimate needs.
     */
    public static Optional<ModelPriceEntry> resolve(String provider, String model) {
        return ModelPriceCatalogs.current().match(provider, model);
    }

    /**
     * Whether a model call configured with {@code override} on {@code provider} /
     * {@code model} would be priced: the configuration names an input or output
     * price, or the catalog matches the model.
     */
    public static boolean isPriced(PriceOverride override, String provider, String model, PriceCatalogSnapshot catalog) {
        if (override != null && (override.inputPer1M() != null || override.outputPer1M() != null)) {
            return true;
        }
        PriceCatalogSnapshot snapshot = catalog != null ? catalog : ModelPriceCatalogs.current();
        return snapshot.match(provider, model).map(e -> BucketPrices.of(e).priced()).orElse(false);
    }

    private static BucketPrices tierPrices(LongContextTier own, ModelPriceEntry entry, BucketPrices base) {
        BucketPrices tier = base.tier(entry != null ? entry.longContext() : null);
        if (own == null) {
            return tier;
        }
        return new BucketPrices(own.inputPricePer1M(), own.outputPricePer1M(), own.cacheReadPricePer1M(), own.cacheWritePricePer1M()).orElse(tier);
    }

    /**
     * The entry for a {@code "<type>/<model>"} key; none for a mixed or absent key.
     */
    static ModelPriceEntry catalogEntry(Object pricingModel, PriceCatalogSnapshot snapshot) {
        if (!(pricingModel instanceof String key) || key.isBlank() || MeteredTokenUsage.MIXED.equals(key)) {
            return null;
        }
        int slash = key.indexOf('/');
        if (slash < 0) {
            return null;
        }
        return snapshot.match(key.substring(0, slash), key.substring(slash + 1)).orElse(null);
    }
}
