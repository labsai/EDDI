/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pricing;

import ai.labs.eddi.engine.pricing.PriceCatalogSnapshot;
import ai.labs.eddi.engine.pricing.model.LongContextTier;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.bedrock.BedrockTokenUsage;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;

import java.util.Optional;

/**
 * Turns the token usage one provider call reported into a
 * {@link MeteredTokenUsage}. This is where the providers' differing conventions
 * are reconciled (langchain4j 1.22):
 * <table>
 * <caption>What each provider's input and output counts mean</caption>
 * <tr>
 * <th>usage class</th>
 * <th>input</th>
 * <th>output</th>
 * </tr>
 * <tr>
 * <td>Anthropic, Bedrock</td>
 * <td>excludes cache reads and writes, which are reported separately — added
 * in</td>
 * <td>as reported</td>
 * </tr>
 * <tr>
 * <td>OpenAI and every OpenAI-client type</td>
 * <td>{@code prompt_tokens}, cached included; the cached subset from
 * {@code prompt_tokens_details}</td>
 * <td>reasoning already included</td>
 * </tr>
 * <tr>
 * <td>Gemini (Google AI)</td>
 * <td>cached content included</td>
 * <td>{@code candidatesTokenCount} excludes thoughts — added in</td>
 * </tr>
 * <tr>
 * <td>Gemini on Vertex</td>
 * <td>as reported</td>
 * <td>thoughts are only visible as {@code total − input − output} — added
 * in</td>
 * </tr>
 * <tr>
 * <td>everything else</td>
 * <td>as reported</td>
 * <td>as reported</td>
 * </tr>
 * </table>
 * With normalization switched off
 * ({@code eddi.llm.pricing.provider-normalization=false}) the base counts pass
 * through unchanged and no cache or thinking detail is kept — the numbers EDDI
 * recorded before — while the catalog is still consulted.
 */
public final class UsageNormalizer {

    /** EDDI's model type for Gemini on Vertex AI. */
    static final String GEMINI_VERTEX = "gemini-vertex";

    private UsageNormalizer() {
    }

    /**
     * @param raw
     *            the usage the provider reported; null stays null
     * @param eddiType
     *            the EDDI model type the call ran on ({@code anthropic},
     *            {@code openai}, ...)
     * @param responseModel
     *            the model the provider says answered, or null
     * @param requestModel
     *            the model that was requested, or null
     * @param catalog
     *            the snapshot to match against (read once for this call)
     * @param normalize
     *            false: pass the base counts through (the escape hatch)
     */
    public static TokenUsage normalize(TokenUsage raw, String eddiType, String responseModel, String requestModel, PriceCatalogSnapshot catalog,
                                       boolean normalize) {
        if (raw == null || raw instanceof MeteredTokenUsage) {
            return raw;
        }
        int in = nz(raw.inputTokenCount());
        int out = nz(raw.outputTokenCount());
        int cacheRead = 0;
        int cacheWrite = 0;
        int reasoning = 0;

        if (normalize) {
            if (raw instanceof AnthropicTokenUsage a) {
                cacheRead = nz(a.cacheReadInputTokens());
                cacheWrite = nz(a.cacheCreationInputTokens());
                in += cacheRead + cacheWrite;
            } else if (raw instanceof BedrockTokenUsage b) {
                cacheRead = nz(b.cacheReadInputTokens());
                cacheWrite = nz(b.cacheWriteInputTokens());
                in += cacheRead + cacheWrite;
            } else if (raw instanceof OpenAiTokenUsage o) {
                if (o.inputTokensDetails() != null) {
                    cacheRead = nz(o.inputTokensDetails().cachedTokens());
                    cacheWrite = nz(o.inputTokensDetails().cacheWriteTokens());
                }
                if (o.outputTokensDetails() != null) {
                    reasoning = nz(o.outputTokensDetails().reasoningTokens());
                }
            } else if (raw instanceof GoogleAiGeminiTokenUsage g) {
                cacheRead = nz(g.cachedContentTokenCount());
                reasoning = nz(g.thoughtsTokenCount());
                out += reasoning;
            } else if (GEMINI_VERTEX.equalsIgnoreCase(eddiType) && raw.totalTokenCount() != null) {
                reasoning = Math.max(0, raw.totalTokenCount() - in - out);
                out += reasoning;
            }
            // the cached subsets can never exceed the prompt they are part of
            cacheRead = Math.min(cacheRead, in);
            cacheWrite = Math.min(cacheWrite, in - cacheRead);
        }
        Integer total = raw.totalTokenCount() == null && raw.inputTokenCount() == null && raw.outputTokenCount() == null
                ? null
                : Math.max(nz(raw.totalTokenCount()), in + out);

        PriceCatalogSnapshot snapshot = catalog != null ? catalog : PriceCatalogSnapshot.EMPTY;
        String matchedName = null;
        ModelPriceEntry entry = null;
        Optional<ModelPriceEntry> byResponse = snapshot.match(eddiType, responseModel);
        if (byResponse.isPresent()) {
            entry = byResponse.get();
            matchedName = responseModel;
        } else {
            Optional<ModelPriceEntry> byRequest = snapshot.match(eddiType, requestModel);
            if (byRequest.isPresent()) {
                entry = byRequest.get();
                matchedName = requestModel;
            }
        }
        String name = matchedName != null ? matchedName : responseModel != null && !responseModel.isBlank() ? responseModel : requestModel;
        String pricingModel = name == null || name.isBlank() ? null : (eddiType == null ? "" : eddiType) + "/" + name;

        boolean longContext = isLongContext(entry, in);
        double catalogUsd = 0.0;
        if (entry != null) {
            BucketPrices base = BucketPrices.of(entry);
            BucketPrices prices = longContext ? base.tier(entry.longContext()) : base;
            catalogUsd = prices.cost(in, cacheRead, cacheWrite, out);
        }
        return new MeteredTokenUsage(raw.inputTokenCount() == null && in == 0 ? null : in, raw.outputTokenCount() == null && out == 0 ? null : out,
                total, cacheRead, cacheWrite, reasoning, longContext ? in : 0, longContext ? cacheRead : 0, longContext ? cacheWrite : 0,
                longContext ? out : 0, pricingModel, 1, entry == null ? 1 : 0, catalogUsd);
    }

    /**
     * Whether a call with {@code promptTokens} falls in {@code entry}'s
     * long-context tier: strictly more prompt tokens than the threshold.
     */
    static boolean isLongContext(ModelPriceEntry entry, long promptTokens) {
        if (entry == null) {
            return false;
        }
        LongContextTier tier = entry.longContext();
        return tier != null && tier.thresholdTokens() != null && promptTokens > tier.thresholdTokens();
    }

    private static int nz(Integer value) {
        return value != null && value > 0 ? value : 0;
    }
}
