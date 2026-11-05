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
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.bedrock.BedrockTokenUsage;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class UsageNormalizerTest {

    private static final PriceCatalogSnapshot NONE = PriceCatalogSnapshot.EMPTY;

    private static MeteredTokenUsage norm(TokenUsage raw, String type) {
        return (MeteredTokenUsage) UsageNormalizer.normalize(raw, type, null, "m", NONE, true);
    }

    @Nested
    class PerProvider {

        @Test
        void anthropicAddsCacheReadAndWriteToInput() {
            var raw = AnthropicTokenUsage.builder().inputTokenCount(100).outputTokenCount(50).cacheReadInputTokens(1000)
                    .cacheCreationInputTokens(200).build();
            var m = norm(raw, "anthropic");
            assertEquals(1300, m.inputTokenCount());
            assertEquals(50, m.outputTokenCount());
            assertEquals(1000, m.cacheReadTokens());
            assertEquals(200, m.cacheWriteTokens());
            assertEquals(1350, m.totalTokenCount());
        }

        @Test
        void bedrockAddsCacheReadAndWriteToInput() {
            var raw = BedrockTokenUsage.builder().inputTokenCount(10).outputTokenCount(5).cacheReadInputTokens(30).cacheWriteInputTokens(7).build();
            var m = norm(raw, "bedrock");
            assertEquals(47, m.inputTokenCount());
            assertEquals(30, m.cacheReadTokens());
            assertEquals(7, m.cacheWriteTokens());
        }

        @Test
        void openAiKeepsInputAndTakesCachedSubsetFromDetails() {
            var raw = OpenAiTokenUsage.builder().inputTokenCount(1000).outputTokenCount(300).totalTokenCount(1300)
                    .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(800).build())
                    .outputTokensDetails(OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(120).build()).build();
            var m = norm(raw, "openai");
            assertEquals(1000, m.inputTokenCount());
            assertEquals(300, m.outputTokenCount(), "reasoning is already inside completion tokens");
            assertEquals(800, m.cacheReadTokens());
            assertEquals(120, m.reasoningTokens());
        }

        @Test
        void geminiAddsThoughtsToOutput() {
            var raw = GoogleAiGeminiTokenUsage.builder().inputTokenCount(500).outputTokenCount(40).totalTokenCount(640).cachedContentTokenCount(100)
                    .thoughtsTokenCount(100).build();
            var m = norm(raw, "gemini");
            assertEquals(500, m.inputTokenCount());
            assertEquals(140, m.outputTokenCount());
            assertEquals(100, m.cacheReadTokens());
            assertEquals(100, m.reasoningTokens());
        }

        @Test
        void vertexAddsTheResidualAsThinking() {
            var m = norm(new TokenUsage(500, 40, 600), "gemini-vertex");
            assertEquals(100, m.outputTokenCount());
            assertEquals(60, m.reasoningTokens());
            var noResidual = norm(new TokenUsage(500, 40, 540), "gemini-vertex");
            assertEquals(40, noResidual.outputTokenCount());
        }

        @Test
        void plainUsagePassesThrough() {
            var m = norm(new TokenUsage(10, 20, 30), "mistral");
            assertEquals(10, m.inputTokenCount());
            assertEquals(20, m.outputTokenCount());
            assertEquals(0, m.cacheReadTokens());
            assertEquals(1, m.calls());
            assertEquals(1, m.uncataloguedCalls());
            assertEquals("mistral/m", m.pricingModel());
        }

        @Test
        void nullFieldsAreZeroAndNullUsageStaysNull() {
            var m = norm(AnthropicTokenUsage.builder().inputTokenCount(10).build(), "anthropic");
            assertEquals(10, m.inputTokenCount());
            assertEquals(0, m.cacheReadTokens());
            assertNull(UsageNormalizer.normalize(null, "openai", null, null, NONE, true));
        }

        @Test
        void alreadyMeteredIsReturnedAsIs() {
            var m = norm(new TokenUsage(1, 2, 3), "openai");
            assertSame(m, UsageNormalizer.normalize(m, "openai", null, null, NONE, true));
        }

        @Test
        void escapeHatchKeepsTheOldCounts() {
            var raw = AnthropicTokenUsage.builder().inputTokenCount(100).outputTokenCount(50).cacheReadInputTokens(1000).build();
            var m = (MeteredTokenUsage) UsageNormalizer.normalize(raw, "anthropic", null, "m", NONE, false);
            assertEquals(100, m.inputTokenCount());
            assertEquals(0, m.cacheReadTokens());
        }
    }

    @Nested
    class Catalog {

        private final PriceCatalogSnapshot catalog = PriceCatalogSnapshot.of(new ModelPriceCatalog(1, UnpricedPolicy.ALLOW, null, null,
                List.of(new ModelPriceEntry("anthropic", "claude*", 3.0, 15.0, 0.3, 3.75, new LongContextTier(200_000L, 6.0, 22.5, null, null),
                        null, null, null))),
                UnpricedPolicy.ALLOW);

        private MeteredTokenUsage call(int input) {
            return (MeteredTokenUsage) UsageNormalizer.normalize(new TokenUsage(input, 1000), "anthropic", "claude-x", "claude-x", catalog, true);
        }

        @Test
        void longContextIsStrictlyGreaterThanTheThreshold() {
            assertEquals(0, call(199_999).lcInputTokens());
            assertEquals(0, call(200_000).lcInputTokens());
            assertEquals(200_001, call(200_001).lcInputTokens());
            assertEquals(1000, call(200_001).lcOutputTokens());
        }

        @Test
        void catalogPriceIsComputedPerCallAtTheTier() {
            assertEquals(100_000 * 3.0 / 1e6 + 1000 * 15.0 / 1e6, call(100_000).catalogUsd(), 1e-12);
            assertEquals(300_000 * 6.0 / 1e6 + 1000 * 22.5 / 1e6, call(300_000).catalogUsd(), 1e-12);
            assertEquals(0, call(100_000).uncataloguedCalls());
        }

        @Test
        void summingRoundsKeepsPerCallClassification() {
            var sum = (MeteredTokenUsage) MeteredTokenUsage.sum(call(100_000), call(300_000));
            assertEquals(400_000, sum.inputTokenCount());
            assertEquals(300_000, sum.lcInputTokens(), "only the long call is in the tier");
            assertEquals(2, sum.calls());
            assertEquals(call(100_000).catalogUsd() + call(300_000).catalogUsd(), sum.catalogUsd(), 1e-12);
        }

        @Test
        void responseModelIsMatchedBeforeRequestModel() {
            var m = (MeteredTokenUsage) UsageNormalizer.normalize(new TokenUsage(10, 10), "anthropic", "claude-real", "alias", catalog, true);
            assertEquals("anthropic/claude-real", m.pricingModel());
            var byRequest = (MeteredTokenUsage) UsageNormalizer.normalize(new TokenUsage(10, 10), "anthropic", "zzz", "claude-req", catalog, true);
            assertEquals("anthropic/claude-req", byRequest.pricingModel());
        }
    }

    @Nested
    class Sums {

        @Test
        void mixedModelsSumToMixed() {
            var a = UsageNormalizer.normalize(new TokenUsage(1, 1), "openai", null, "a", NONE, true);
            var b = UsageNormalizer.normalize(new TokenUsage(1, 1), "openai", null, "b", NONE, true);
            assertEquals(MeteredTokenUsage.MIXED, ((MeteredTokenUsage) MeteredTokenUsage.sum(a, b)).pricingModel());
            assertEquals("openai/a", ((MeteredTokenUsage) MeteredTokenUsage.sum(a, a)).pricingModel());
        }

        @Test
        void summingWithAPlainUsageKeepsTheMeteredFields() {
            var a = AnthropicTokenUsage.builder().inputTokenCount(1).outputTokenCount(1).cacheReadInputTokens(5).build();
            var m = UsageNormalizer.normalize(a, "anthropic", null, "c", NONE, true);
            var sum = assertInstanceOf(MeteredTokenUsage.class, MeteredTokenUsage.sum(new TokenUsage(10, 10, 20), m));
            assertEquals(16, sum.inputTokenCount());
            assertEquals(5, sum.cacheReadTokens());
            assertEquals(2, sum.calls());
            assertInstanceOf(MeteredTokenUsage.class, m.add(new TokenUsage(1, 1)));
        }
    }
}
