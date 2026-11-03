/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pricing;

import ai.labs.eddi.engine.pricing.PriceCatalogSnapshot;
import ai.labs.eddi.engine.pricing.model.LongContextTier;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolContextBudget;
import ai.labs.eddi.modules.llm.model.ModelPricing;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelPriceResolverTest {

    private static final double EPS = 1e-12;

    private static final PriceCatalogSnapshot CATALOG = PriceCatalogSnapshot.of(new ModelPriceCatalog(1, UnpricedPolicy.ALLOW, null, null,
            List.of(new ModelPriceEntry("anthropic", "claude*", 3.0, 15.0, 0.3, 3.75, new LongContextTier(200_000L, 6.0, 22.5, null, null), null,
                    null, null), ModelPriceEntry.of("ollama", "*", 0.0, 0.0))),
            UnpricedPolicy.ALLOW);

    /**
     * An Anthropic call: 1000 uncached + 2000 cache read + 500 cache write in, 100
     * out.
     */
    private static Map<String, Object> anthropicUsage(PriceCatalogSnapshot catalog) {
        var raw = AnthropicTokenUsage.builder().inputTokenCount(1000).outputTokenCount(100).cacheReadInputTokens(2000).cacheCreationInputTokens(500)
                .build();
        return ToolContextBudget.tokenUsageMap(UsageNormalizer.normalize(raw, "anthropic", null, "claude-x", catalog, true));
    }

    @Test
    void taskPricesOnlyChargeCacheAtTheInputPrice() {
        PricedSpend spend = ModelPriceResolver.price(PriceOverride.of(2.0, 10.0), anthropicUsage(PriceCatalogSnapshot.EMPTY),
                PriceCatalogSnapshot.EMPTY);
        assertEquals((3500 * 2.0 + 100 * 10.0) / 1e6, spend.usd(), EPS);
        assertEquals(PricedSpend.Source.TASK, spend.source());
        assertEquals(0, spend.unpricedCalls());
        assertEquals(2000, spend.cacheReadTokens());
    }

    @Test
    void ownPricingBeatsCatalogWhichBeatsFallback() {
        var withOwn = new PriceOverride(2.0, 10.0, new ModelPricing(0.1, null, null));
        PricedSpend spend = ModelPriceResolver.price(withOwn, anthropicUsage(CATALOG), CATALOG);
        // uncached at 2.0, cache read at own 0.1, cache write from the catalog 3.75,
        // output 10.0
        assertEquals((1000 * 2.0 + 2000 * 0.1 + 500 * 3.75 + 100 * 10.0) / 1e6, spend.usd(), EPS);
        assertEquals(PricedSpend.Source.MIXED, spend.source());
    }

    @Test
    void noConfigPricesUsesThePerCallCatalogPrice() {
        PricedSpend spend = ModelPriceResolver.price(PriceOverride.NONE, anthropicUsage(CATALOG), CATALOG);
        assertEquals((1000 * 3.0 + 2000 * 0.3 + 500 * 3.75 + 100 * 15.0) / 1e6, spend.usd(), EPS);
        assertEquals(PricedSpend.Source.CATALOG, spend.source());
    }

    @Test
    void catalogPriceIsTheOneSeenAtCallTimeNotLater() {
        Map<String, Object> usage = anthropicUsage(CATALOG);
        PricedSpend later = ModelPriceResolver.price(PriceOverride.NONE, usage, PriceCatalogSnapshot.EMPTY);
        assertEquals(ModelPriceResolver.price(PriceOverride.NONE, usage, CATALOG).usd(), later.usd(), EPS);
    }

    @Test
    void unpricedIsCountedAndExplicitZeroIsNot() {
        var usage = ToolContextBudget.tokenUsageMap(UsageNormalizer.normalize(new TokenUsage(10, 10), "mistral", null, "x", CATALOG, true));
        PricedSpend unpriced = ModelPriceResolver.price(PriceOverride.NONE, usage, CATALOG);
        assertEquals(0.0, unpriced.usd());
        assertEquals(1, unpriced.unpricedCalls());
        assertEquals(PricedSpend.Source.UNPRICED, unpriced.source());

        PricedSpend explicitZero = ModelPriceResolver.price(PriceOverride.of(0.0, 0.0), usage, CATALOG);
        assertEquals(0, explicitZero.unpricedCalls());
        assertEquals(PricedSpend.Source.TASK, explicitZero.source());

        var ollama = ToolContextBudget.tokenUsageMap(UsageNormalizer.normalize(new TokenUsage(10, 10), "ollama", null, "llama3", CATALOG, true));
        assertEquals(0, ModelPriceResolver.price(PriceOverride.NONE, ollama, CATALOG).unpricedCalls(), "a catalog 0 is priced");
    }

    @Test
    void longContextBucketsUseTierPricesAndMissingTierPricesFallBackToBase() {
        var raw = new TokenUsage(300_000, 1000);
        var usage = ToolContextBudget.tokenUsageMap(UsageNormalizer.normalize(raw, "anthropic", null, "claude-x", CATALOG, true));
        // explicit base prices; the tier comes from the catalog (6.0 / 22.5)
        PricedSpend spend = ModelPriceResolver.price(PriceOverride.of(3.0, 15.0), usage, CATALOG);
        assertEquals((300_000 * 6.0 + 1000 * 22.5) / 1e6, spend.usd(), EPS);
        // own tier input price, tier output falls back to the catalog tier
        var own = new PriceOverride(3.0, 15.0, new ModelPricing(null, null, new LongContextTier(null, 5.0, null, null, null)));
        assertEquals((300_000 * 5.0 + 1000 * 22.5) / 1e6, ModelPriceResolver.price(own, usage, CATALOG).usd(), EPS);
    }

    @Test
    void uncachedNeverGoesNegativeAndNanPricesAreIgnored() {
        Map<String, Object> usage = new HashMap<>(Map.of("inputTokens", 10, "outputTokens", 0, MeteredTokenUsage.CACHE_READ_TOKENS, 50));
        // cache read 50 > input 10: uncached clamps at 0, the 50 cache tokens are still
        // charged
        assertEquals(50 * 1.0 / 1e6, ModelPriceResolver.price(PriceOverride.of(1.0, 1.0), usage, CATALOG).usd(), EPS);
        assertEquals(0.0, ModelPriceResolver.price(PriceOverride.of(Double.NaN, Double.NaN), Map.of("inputTokens", 10), CATALOG).usd());
        assertEquals(0.0, ModelPriceResolver.price(PriceOverride.of(-5.0, null), Map.of("inputTokens", 10), CATALOG).usd());
    }

    @Test
    void legacyMapsWithoutMeteredKeysPriceAsBefore() {
        Map<String, Object> usage = Map.of("inputTokens", 1000, "outputTokens", 500);
        assertEquals((1000 * 2.0 + 500 * 4.0) / 1e6, ModelPriceResolver.price(PriceOverride.of(2.0, 4.0), usage, CATALOG).usd(), EPS);
        assertEquals(1, ModelPriceResolver.price(PriceOverride.NONE, usage, CATALOG).unpricedCalls());
        assertEquals(PricedSpend.NONE, ModelPriceResolver.price(PriceOverride.of(1.0, 1.0), null, CATALOG));
    }

    @Test
    void handBuiltMapWithAPricingModelIsLookedUp() {
        Map<String, Object> usage = Map.of("inputTokens", 1000, "outputTokens", 0, MeteredTokenUsage.PRICING_MODEL, "anthropic/claude-3");
        assertEquals(1000 * 3.0 / 1e6, ModelPriceResolver.price(PriceOverride.NONE, usage, CATALOG).usd(), EPS);
    }

    @Test
    void mergedRoundsCountUnpricedCalls() {
        Map<String, Object> total = new HashMap<>();
        var a = ToolContextBudget.tokenUsageMap(UsageNormalizer.normalize(new TokenUsage(10, 10), "anthropic", null, "claude-x", CATALOG, true));
        var b = ToolContextBudget.tokenUsageMap(UsageNormalizer.normalize(new TokenUsage(10, 10), "mistral", null, "x", CATALOG, true));
        UsageMaps.merge(total, a, ToolContextBudget.TOKEN_USAGE_FIELDS);
        UsageMaps.merge(total, b, ToolContextBudget.TOKEN_USAGE_FIELDS);
        assertEquals(MeteredTokenUsage.MIXED, total.get(MeteredTokenUsage.PRICING_MODEL));
        PricedSpend spend = ModelPriceResolver.price(PriceOverride.NONE, total, CATALOG);
        assertEquals(1, spend.unpricedCalls());
        assertEquals(PricedSpend.Source.MIXED, spend.source());
        assertEquals((10 * 3.0 + 10 * 15.0) / 1e6, spend.usd(), EPS);
    }

    @Test
    void aLegacyMapMergedWithAMeteredOneStaysAnUncataloguedCall() {
        var metered = ToolContextBudget
                .tokenUsageMap(UsageNormalizer.normalize(new TokenUsage(10, 10), "anthropic", null, "claude-x", CATALOG, true));
        Map<String, Object> legacy = Map.of("inputTokens", 5, "outputTokens", 5);

        Map<String, Object> meteredFirst = new HashMap<>();
        UsageMaps.merge(meteredFirst, metered, ToolContextBudget.TOKEN_USAGE_FIELDS);
        UsageMaps.merge(meteredFirst, legacy, ToolContextBudget.TOKEN_USAGE_FIELDS);
        assertEquals(1, ModelPriceResolver.price(PriceOverride.NONE, meteredFirst, CATALOG).unpricedCalls(), "legacy delta counted");

        Map<String, Object> legacyFirst = new HashMap<>();
        UsageMaps.merge(legacyFirst, legacy, ToolContextBudget.TOKEN_USAGE_FIELDS);
        UsageMaps.merge(legacyFirst, metered, ToolContextBudget.TOKEN_USAGE_FIELDS);
        assertEquals(1, ModelPriceResolver.price(PriceOverride.NONE, legacyFirst, CATALOG).unpricedCalls(), "legacy total counted");
    }
}
