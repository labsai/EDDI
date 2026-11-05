/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pricing;

import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Summing, serialising and comparing the metered token usage the cost
 * accounting records per call.
 */
class MeteredTokenUsageTest {

    private static MeteredTokenUsage usage(String model, double usd) {
        return new MeteredTokenUsage(100, 20, 120, 10, 5, 3, 1, 2, 3, 4, model, 1, 0, usd);
    }

    @Test
    void sumWithANullSideIsTheOtherSide() {
        var a = usage("openai/gpt", 0.5);
        assertSame(a, MeteredTokenUsage.sum(a, null));
        assertSame(a, MeteredTokenUsage.sum(null, a));
        assertNull(MeteredTokenUsage.sum(null, null));
    }

    @Test
    void twoPlainUsagesStayPlain() {
        TokenUsage sum = MeteredTokenUsage.sum(new TokenUsage(1, 2, 3), new TokenUsage(null, 4, null));
        assertFalse(sum instanceof MeteredTokenUsage);
        assertEquals(1, sum.inputTokenCount());
        assertEquals(6, sum.outputTokenCount());
        assertEquals(3, sum.totalTokenCount());

        TokenUsage empty = MeteredTokenUsage.sum(new TokenUsage(null, null, null), new TokenUsage(null, null, null));
        assertNull(empty.inputTokenCount(), "unknown plus unknown stays unknown");
    }

    @Test
    void sumAddsEveryFieldAndKeepsASharedModel() {
        var sum = assertInstanceOf(MeteredTokenUsage.class, usage("openai/gpt", 0.5).add(usage("openai/gpt", 0.25)));
        assertEquals(200, sum.inputTokenCount());
        assertEquals(40, sum.outputTokenCount());
        assertEquals(240, sum.totalTokenCount());
        assertEquals(20, sum.cacheReadTokens());
        assertEquals(10, sum.cacheWriteTokens());
        assertEquals(6, sum.reasoningTokens());
        assertEquals(2, sum.lcInputTokens());
        assertEquals(4, sum.lcCacheReadTokens());
        assertEquals(6, sum.lcCacheWriteTokens());
        assertEquals(8, sum.lcOutputTokens());
        assertEquals("openai/gpt", sum.pricingModel());
        assertEquals(2, sum.calls());
        assertEquals(0, sum.uncataloguedCalls());
        assertEquals(0.75, sum.catalogUsd(), 1e-12);
    }

    @Test
    void differentModelsSumToMixed() {
        var sum = (MeteredTokenUsage) MeteredTokenUsage.sum(usage("openai/gpt", 0), usage("anthropic/claude", 0));
        assertEquals(MeteredTokenUsage.MIXED, sum.pricingModel());
    }

    @Test
    void aPlainUsageCountsAsOneUncataloguedCallOfUnknownModel() {
        var sum = (MeteredTokenUsage) MeteredTokenUsage.sum(new TokenUsage(7, 3, 10), usage(null, 0.1));
        assertEquals(107, sum.inputTokenCount());
        assertEquals(2, sum.calls());
        assertEquals(1, sum.uncataloguedCalls());
        assertNull(sum.pricingModel(), "a plain usage has no model, like the metered side here");

        var other = (MeteredTokenUsage) MeteredTokenUsage.sum(usage("openai/gpt", 0.1), new TokenUsage(7, 3, 10));
        assertEquals(MeteredTokenUsage.MIXED, other.pricingModel());
    }

    @Test
    void anUnusableCostIsZero() {
        assertEquals(0.0, usage("m", Double.NaN).catalogUsd());
        assertEquals(0.0, usage("m", Double.POSITIVE_INFINITY).catalogUsd());
        assertEquals(0.0, usage("m", -1).catalogUsd());
    }

    @Test
    void putIntoWritesTheExtraFieldsAndOmitsAMissingModel() {
        Map<String, Object> map = new HashMap<>();
        usage("openai/gpt", 0.5).putInto(map);
        assertEquals(10, map.get(MeteredTokenUsage.CACHE_READ_TOKENS));
        assertEquals(5, map.get(MeteredTokenUsage.CACHE_WRITE_TOKENS));
        assertEquals(3, map.get(MeteredTokenUsage.REASONING_TOKENS));
        assertEquals(1, map.get(MeteredTokenUsage.LC_INPUT_TOKENS));
        assertEquals(4, map.get(MeteredTokenUsage.LC_OUTPUT_TOKENS));
        assertEquals("openai/gpt", map.get(MeteredTokenUsage.PRICING_MODEL));
        assertEquals(1, map.get(MeteredTokenUsage.CALLS));
        assertEquals(0, map.get(MeteredTokenUsage.UNCATALOGUED_CALLS));
        assertEquals(0.5, map.get(MeteredTokenUsage.CATALOG_USD));

        Map<String, Object> noModel = new HashMap<>();
        usage(null, 0).putInto(noModel);
        assertFalse(noModel.containsKey(MeteredTokenUsage.PRICING_MODEL));
    }

    @Test
    void equalityComparesEveryField() {
        var base = usage("m", 0.5);
        assertEquals(base, usage("m", 0.5));
        assertEquals(base.hashCode(), usage("m", 0.5).hashCode());
        assertNotEquals(base, new TokenUsage(100, 20, 120), "a plain usage is never equal");
        assertNotEquals(base, new MeteredTokenUsage(101, 20, 120, 10, 5, 3, 1, 2, 3, 4, "m", 1, 0, 0.5), "base counts");

        MeteredTokenUsage[] variants = {
                new MeteredTokenUsage(100, 20, 120, 11, 5, 3, 1, 2, 3, 4, "m", 1, 0, 0.5),
                new MeteredTokenUsage(100, 20, 120, 10, 6, 3, 1, 2, 3, 4, "m", 1, 0, 0.5),
                new MeteredTokenUsage(100, 20, 120, 10, 5, 4, 1, 2, 3, 4, "m", 1, 0, 0.5),
                new MeteredTokenUsage(100, 20, 120, 10, 5, 3, 2, 2, 3, 4, "m", 1, 0, 0.5),
                new MeteredTokenUsage(100, 20, 120, 10, 5, 3, 1, 3, 3, 4, "m", 1, 0, 0.5),
                new MeteredTokenUsage(100, 20, 120, 10, 5, 3, 1, 2, 4, 4, "m", 1, 0, 0.5),
                new MeteredTokenUsage(100, 20, 120, 10, 5, 3, 1, 2, 3, 5, "m", 1, 0, 0.5),
                new MeteredTokenUsage(100, 20, 120, 10, 5, 3, 1, 2, 3, 4, "m", 2, 0, 0.5),
                new MeteredTokenUsage(100, 20, 120, 10, 5, 3, 1, 2, 3, 4, "m", 1, 1, 0.5),
                new MeteredTokenUsage(100, 20, 120, 10, 5, 3, 1, 2, 3, 4, "m", 1, 0, 0.6),
                new MeteredTokenUsage(100, 20, 120, 10, 5, 3, 1, 2, 3, 4, "n", 1, 0, 0.5)};
        for (MeteredTokenUsage variant : variants) {
            assertNotEquals(base, variant, variant.toString());
        }
    }

    @Test
    void toStringNamesTheModelAndCost() {
        String text = usage("openai/gpt", 0.5).toString();
        assertTrue(text.contains("model=openai/gpt"), text);
        assertTrue(text.contains("catalogUsd=0.5"), text);
    }
}
