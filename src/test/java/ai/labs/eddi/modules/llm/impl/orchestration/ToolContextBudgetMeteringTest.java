/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.orchestration;

import ai.labs.eddi.engine.pricing.PriceCatalogSnapshot;
import ai.labs.eddi.modules.llm.pricing.MeteredTokenUsage;
import ai.labs.eddi.modules.llm.pricing.UsageNormalizer;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The tool loop's one sum point keeps the metered fields — before this, it
 * rebuilt a plain {@code TokenUsage} and lost every cache count.
 */
class ToolContextBudgetMeteringTest {

    private static TokenUsage anthropicRound(int cacheRead) {
        var raw = AnthropicTokenUsage.builder().inputTokenCount(100).outputTokenCount(10).cacheReadInputTokens(cacheRead).build();
        return UsageNormalizer.normalize(raw, "anthropic", null, "claude-x", PriceCatalogSnapshot.EMPTY, true);
    }

    @Test
    void summingRoundsKeepsTheCacheCounts() {
        TokenUsage total = ToolContextBudget.sumTokens(ToolContextBudget.sumTokens(null, anthropicRound(900)), anthropicRound(400));
        var metered = assertInstanceOf(MeteredTokenUsage.class, total);
        assertEquals(1300, metered.cacheReadTokens());
        assertEquals(1500, metered.inputTokenCount());
        assertEquals(2, metered.calls());
    }

    @Test
    void theUsageMapCarriesTheMeteredKeysOnlyForAMeteredUsage() {
        Map<String, Object> metered = ToolContextBudget.tokenUsageMap(anthropicRound(900));
        assertEquals(900, metered.get(MeteredTokenUsage.CACHE_READ_TOKENS));
        assertEquals("anthropic/claude-x", metered.get(MeteredTokenUsage.PRICING_MODEL));
        Map<String, Object> plain = ToolContextBudget.tokenUsageMap(new TokenUsage(1, 2, 3));
        assertEquals(3, plain.size());
        assertFalse(plain.containsKey(MeteredTokenUsage.CACHE_READ_TOKENS));
    }
}
