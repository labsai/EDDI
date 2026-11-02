/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pricing;

import java.util.List;
import java.util.Map;

/**
 * Merging the token-usage maps the executors pass around. A metered map carries
 * more than the three base counts (cache, long-context and call counts, the
 * pricing model, the per-call catalog price); merging only the base counts
 * would leave the merged map unpriceable from the catalog. One merge rule, used
 * by every merge site through {@code CascadingModelExecutor.mergeTokenUsage}.
 */
public final class UsageMaps {

    /** The metered integer counts, beyond the persisted fields. */
    static final List<String> METERED_COUNTS = List.of(MeteredTokenUsage.REASONING_TOKENS, MeteredTokenUsage.LC_INPUT_TOKENS,
            MeteredTokenUsage.LC_CACHE_READ_TOKENS, MeteredTokenUsage.LC_CACHE_WRITE_TOKENS, MeteredTokenUsage.LC_OUTPUT_TOKENS,
            MeteredTokenUsage.CALLS, MeteredTokenUsage.UNCATALOGUED_CALLS);

    private UsageMaps() {
    }

    /**
     * Adds {@code delta} into {@code total}. A count is touched only when either
     * side carries it, so a provider that omits {@code totalTokens} never
     * materializes a bogus 0. Summing usages of two different pricing models yields
     * {@link MeteredTokenUsage#MIXED}.
     *
     * @param baseFields
     *            the persisted count keys ({@code TOKEN_USAGE_FIELDS})
     */
    public static void merge(Map<String, Object> total, Map<String, Object> delta, List<String> baseFields) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        boolean totalWasEmpty = total.isEmpty();
        for (String field : baseFields) {
            addLong(total, delta, field);
        }
        for (String field : METERED_COUNTS) {
            addLong(total, delta, field);
        }
        if (delta.containsKey(MeteredTokenUsage.CATALOG_USD) || total.containsKey(MeteredTokenUsage.CATALOG_USD)) {
            total.put(MeteredTokenUsage.CATALOG_USD,
                    asDouble(total.get(MeteredTokenUsage.CATALOG_USD)) + asDouble(delta.get(MeteredTokenUsage.CATALOG_USD)));
        }
        Object deltaModel = delta.get(MeteredTokenUsage.PRICING_MODEL);
        Object totalModel = total.get(MeteredTokenUsage.PRICING_MODEL);
        if (totalWasEmpty) {
            if (deltaModel != null) {
                total.put(MeteredTokenUsage.PRICING_MODEL, deltaModel);
            }
        } else if (deltaModel == null ? totalModel != null : !deltaModel.equals(totalModel)) {
            total.put(MeteredTokenUsage.PRICING_MODEL, MeteredTokenUsage.MIXED);
        }
    }

    private static void addLong(Map<String, Object> total, Map<String, Object> delta, String field) {
        if (delta.containsKey(field) || total.containsKey(field)) {
            total.put(field, asLong(total.get(field)) + asLong(delta.get(field)));
        }
    }

    static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    static double asDouble(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) && d > 0 ? d : 0.0;
        }
        return 0.0;
    }
}
