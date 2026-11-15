/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pricing;

import dev.langchain4j.model.output.TokenUsage;

import java.util.Map;
import java.util.Objects;

/**
 * Provider token usage normalized to one convention, so it can be priced the
 * same way whichever provider reported it. Built per call by
 * {@link UsageNormalizer} in the decorator around every chat model, and summed
 * across the rounds of a tool loop with {@link #sum}, which — unlike
 * langchain4j's own {@code TokenUsage.sum} — keeps every field.
 * <ul>
 * <li>{@link #inputTokenCount()}: <b>all</b> prompt tokens, cached ones
 * included (OpenAI's convention; Anthropic and Bedrock report cache reads and
 * writes outside {@code input_tokens}, so they are added in).</li>
 * <li>{@link #cacheReadTokens()}, {@link #cacheWriteTokens()}: the subsets of
 * the input served from, and written to, the provider's prompt cache.</li>
 * <li>{@link #outputTokenCount()}: output <b>including</b> thinking (Gemini
 * reports thoughts outside {@code candidatesTokenCount}, so they are added
 * in).</li>
 * <li>{@link #reasoningTokens()}: informational; already inside the
 * output.</li>
 * <li>the {@code lc*} counts: the part of each of the above that came from
 * calls whose own prompt exceeded the matched catalog entry's long-context
 * threshold. Classified per call, so summing rounds stays exact.</li>
 * <li>{@link #catalogUsd()}: the calls priced at the catalog entry each call
 * matched when it returned — tear-free, because the entry is read once per
 * call.</li>
 * <li>{@link #uncataloguedCalls()}: calls no catalog entry matched.</li>
 * </ul>
 */
public class MeteredTokenUsage extends TokenUsage {

    /** {@link #pricingModel()} after summing calls on different models. */
    public static final String MIXED = "*mixed*";

    public static final String CACHE_READ_TOKENS = "cacheReadTokens";
    public static final String CACHE_WRITE_TOKENS = "cacheWriteTokens";
    public static final String REASONING_TOKENS = "reasoningTokens";
    public static final String LC_INPUT_TOKENS = "lcInputTokens";
    public static final String LC_CACHE_READ_TOKENS = "lcCacheReadTokens";
    public static final String LC_CACHE_WRITE_TOKENS = "lcCacheWriteTokens";
    public static final String LC_OUTPUT_TOKENS = "lcOutputTokens";
    public static final String PRICING_MODEL = "pricingModel";
    public static final String CALLS = "calls";
    public static final String UNCATALOGUED_CALLS = "uncataloguedCalls";
    public static final String CATALOG_USD = "catalogUsd";

    private final int cacheReadTokens;
    private final int cacheWriteTokens;
    private final int reasoningTokens;
    private final int lcInputTokens;
    private final int lcCacheReadTokens;
    private final int lcCacheWriteTokens;
    private final int lcOutputTokens;
    private final String pricingModel;
    private final int calls;
    private final int uncataloguedCalls;
    private final double catalogUsd;

    public MeteredTokenUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens, int cacheReadTokens, int cacheWriteTokens,
            int reasoningTokens, int lcInputTokens, int lcCacheReadTokens, int lcCacheWriteTokens, int lcOutputTokens, String pricingModel,
            int calls, int uncataloguedCalls, double catalogUsd) {
        super(inputTokens, outputTokens, totalTokens);
        this.cacheReadTokens = cacheReadTokens;
        this.cacheWriteTokens = cacheWriteTokens;
        this.reasoningTokens = reasoningTokens;
        this.lcInputTokens = lcInputTokens;
        this.lcCacheReadTokens = lcCacheReadTokens;
        this.lcCacheWriteTokens = lcCacheWriteTokens;
        this.lcOutputTokens = lcOutputTokens;
        this.pricingModel = pricingModel;
        this.calls = calls;
        this.uncataloguedCalls = uncataloguedCalls;
        this.catalogUsd = Double.isFinite(catalogUsd) && catalogUsd > 0 ? catalogUsd : 0.0;
    }

    public int cacheReadTokens() {
        return cacheReadTokens;
    }

    public int cacheWriteTokens() {
        return cacheWriteTokens;
    }

    public int reasoningTokens() {
        return reasoningTokens;
    }

    public int lcInputTokens() {
        return lcInputTokens;
    }

    public int lcCacheReadTokens() {
        return lcCacheReadTokens;
    }

    public int lcCacheWriteTokens() {
        return lcCacheWriteTokens;
    }

    public int lcOutputTokens() {
        return lcOutputTokens;
    }

    /**
     * {@code "<eddi type>/<model name>"} — the name a catalog entry matched, else
     * the response model, else the request model — or {@link #MIXED}.
     */
    public String pricingModel() {
        return pricingModel;
    }

    public int calls() {
        return calls;
    }

    public int uncataloguedCalls() {
        return uncataloguedCalls;
    }

    public double catalogUsd() {
        return catalogUsd;
    }

    /**
     * Sums two usages field by field. Either side may be null or a plain
     * {@link TokenUsage} (which contributes its base counts and one uncatalogued
     * call of unknown model); the result is metered whenever either side is.
     */
    public static TokenUsage sum(TokenUsage a, TokenUsage b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (!(a instanceof MeteredTokenUsage) && !(b instanceof MeteredTokenUsage)) {
            return new TokenUsage(add(a.inputTokenCount(), b.inputTokenCount()), add(a.outputTokenCount(), b.outputTokenCount()),
                    add(a.totalTokenCount(), b.totalTokenCount()));
        }
        MeteredTokenUsage x = metered(a);
        MeteredTokenUsage y = metered(b);
        String model = Objects.equals(x.pricingModel, y.pricingModel) ? x.pricingModel : MIXED;
        return new MeteredTokenUsage(add(x.inputTokenCount(), y.inputTokenCount()), add(x.outputTokenCount(), y.outputTokenCount()),
                add(x.totalTokenCount(), y.totalTokenCount()), x.cacheReadTokens + y.cacheReadTokens, x.cacheWriteTokens + y.cacheWriteTokens,
                x.reasoningTokens + y.reasoningTokens, x.lcInputTokens + y.lcInputTokens, x.lcCacheReadTokens + y.lcCacheReadTokens,
                x.lcCacheWriteTokens + y.lcCacheWriteTokens, x.lcOutputTokens + y.lcOutputTokens, model, x.calls + y.calls,
                x.uncataloguedCalls + y.uncataloguedCalls, x.catalogUsd + y.catalogUsd);
    }

    @Override
    public TokenUsage add(TokenUsage that) {
        return sum(this, that);
    }

    /** A plain usage seen as one uncatalogued call of unknown model. */
    static MeteredTokenUsage metered(TokenUsage usage) {
        if (usage instanceof MeteredTokenUsage m) {
            return m;
        }
        return new MeteredTokenUsage(usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount(), 0, 0, 0, 0, 0, 0, 0, null, 1,
                1, 0.0);
    }

    /**
     * Writes the fields beyond the base three into {@code map} — every value a
     * primitive or a string, so the map stays JSON-safe.
     */
    public void putInto(Map<String, Object> map) {
        map.put(CACHE_READ_TOKENS, cacheReadTokens);
        map.put(CACHE_WRITE_TOKENS, cacheWriteTokens);
        map.put(REASONING_TOKENS, reasoningTokens);
        map.put(LC_INPUT_TOKENS, lcInputTokens);
        map.put(LC_CACHE_READ_TOKENS, lcCacheReadTokens);
        map.put(LC_CACHE_WRITE_TOKENS, lcCacheWriteTokens);
        map.put(LC_OUTPUT_TOKENS, lcOutputTokens);
        if (pricingModel != null) {
            map.put(PRICING_MODEL, pricingModel);
        }
        map.put(CALLS, calls);
        map.put(UNCATALOGUED_CALLS, uncataloguedCalls);
        map.put(CATALOG_USD, catalogUsd);
    }

    private static Integer add(Integer a, Integer b) {
        if (a == null && b == null) {
            return null;
        }
        return (a != null ? a : 0) + (b != null ? b : 0);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MeteredTokenUsage that) || !super.equals(o)) {
            return false;
        }
        return cacheReadTokens == that.cacheReadTokens && cacheWriteTokens == that.cacheWriteTokens && reasoningTokens == that.reasoningTokens
                && lcInputTokens == that.lcInputTokens && lcCacheReadTokens == that.lcCacheReadTokens
                && lcCacheWriteTokens == that.lcCacheWriteTokens && lcOutputTokens == that.lcOutputTokens && calls == that.calls
                && uncataloguedCalls == that.uncataloguedCalls && Double.compare(catalogUsd, that.catalogUsd) == 0
                && Objects.equals(pricingModel, that.pricingModel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), cacheReadTokens, cacheWriteTokens, pricingModel, calls);
    }

    @Override
    public String toString() {
        return "MeteredTokenUsage{in=" + inputTokenCount() + ", out=" + outputTokenCount() + ", total=" + totalTokenCount() + ", cacheRead="
                + cacheReadTokens + ", cacheWrite=" + cacheWriteTokens + ", reasoning=" + reasoningTokens + ", lcIn=" + lcInputTokens
                + ", model=" + pricingModel + ", calls=" + calls + ", uncatalogued=" + uncataloguedCalls + ", catalogUsd=" + catalogUsd + "}";
    }
}
