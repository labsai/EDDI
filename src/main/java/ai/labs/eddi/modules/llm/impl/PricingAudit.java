/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.pricing.UnpricedSightings;
import ai.labs.eddi.modules.llm.pricing.MeteredTokenUsage;
import ai.labs.eddi.modules.llm.pricing.ModelPriceResolver;
import ai.labs.eddi.modules.llm.pricing.PriceOverride;
import ai.labs.eddi.modules.llm.pricing.PricedSpend;
import io.micrometer.core.instrument.Metrics;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records how a model phase's spend was priced, next to the spend itself: the
 * step's {@code audit:pricing} ({@link MemoryKeys#AUDIT_PRICING}), the
 * {@code eddi.llm.cost.usd} counter and, for an unpriced call, the
 * {@code eddi.llm.calls.unpriced} counter and the node's unpriced sightings.
 * Called from {@code LlmTask.recordSpend} — the one place a phase's spend
 * reaches the step — so each phase is counted exactly once.
 * <p>
 * When the phase's own prices are known ({@code prices} non-null) the resolver
 * decides what was unpriced. On the pause and failure paths they are not, and a
 * phase is counted unpriced when it cost nothing and its calls had no catalog
 * entry — so a task priced at an explicit {@code 0} with no catalog entry reads
 * as unpriced there; a catalog {@code 0} entry is the precise way to mark a
 * free model.
 */
final class PricingAudit {

    private static final Logger LOGGER = Logger.getLogger(PricingAudit.class);
    static final String SITE = "llm_task";

    private PricingAudit() {
    }

    static void record(IWritableConversationStep step, Map<?, ?> usage, double costUsd, ModelPrices prices) {
        if (step == null || usage == null || usage.isEmpty()) {
            return;
        }
        try {
            PricedSpend priced = prices != null ? prices.price(usage) : ModelPriceResolver.price(PriceOverride.NONE, usage);
            int unpriced = prices == null && costUsd > 0.0 ? 0 : priced.unpricedCalls();
            long calls = usage.get(MeteredTokenUsage.CALLS) instanceof Number n ? n.longValue() : 1L;

            Map<String, Object> total = new LinkedHashMap<>();
            IData<Map<String, Object>> existing = step.getLatestData(MemoryKeys.AUDIT_PRICING);
            if (existing != null && existing.getResult() != null) {
                total.putAll(existing.getResult());
            }
            total.put("calls", asLong(total.get("calls")) + calls);
            total.put("unpricedCalls", asLong(total.get("unpricedCalls")) + unpriced);
            total.put("cacheReadTokens", asLong(total.get("cacheReadTokens")) + priced.cacheReadTokens());
            total.put("cacheWriteTokens", asLong(total.get("cacheWriteTokens")) + priced.cacheWriteTokens());
            List<Object> sources = total.get("sources") instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
            String source = unpriced > 0 && priced.source() != PricedSpend.Source.MIXED ? PricedSpend.Source.UNPRICED.name() : priced.source().name();
            if (!sources.contains(source)) {
                sources.add(source);
            }
            total.put("sources", sources);
            step.storeData(new Data<>(MemoryKeys.AUDIT_PRICING, total));

            String pricingModel = usage.get(MeteredTokenUsage.PRICING_MODEL) instanceof String m ? m : null;
            if (unpriced > 0) {
                UnpricedSightings.record(pricingModel, SITE, unpriced);
            }
            if (Double.isFinite(costUsd) && costUsd > 0.0) {
                int slash = pricingModel != null ? pricingModel.indexOf('/') : -1;
                String provider = slash > 0 ? pricingModel.substring(0, slash) : "unknown";
                String model = slash >= 0 ? pricingModel.substring(slash + 1) : pricingModel;
                Metrics.globalRegistry.counter("eddi.llm.cost.usd", "provider", provider, "model", UnpricedSightings.tag(model), "source",
                        source.toLowerCase()).increment(costUsd);
            }
        } catch (RuntimeException e) {
            LOGGER.debugf("Pricing audit not recorded: %s", e.getMessage());
        }
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
