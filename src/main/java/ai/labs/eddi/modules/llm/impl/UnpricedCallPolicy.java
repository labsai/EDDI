/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.pricing.ModelPriceCatalogs;
import ai.labs.eddi.engine.pricing.PriceCatalogSnapshot;
import ai.labs.eddi.engine.pricing.SystemSpendMeter;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import ai.labs.eddi.modules.llm.pricing.ModelPriceResolver;
import ai.labs.eddi.modules.llm.pricing.PriceOverride;
import io.micrometer.core.instrument.Metrics;
import org.jboss.logging.Logger;

import java.util.Locale;

/**
 * The opt-in strict mode for unpriced model calls (docs/model-pricing.md).
 * Decided <em>before</em> the call, against the requested model — the model
 * that actually answers is not known yet, which is why a router such as
 * {@code openrouter/auto} needs a catalog cap entry under strict mode.
 * <ul>
 * <li>{@code ALLOW} (default): nothing is refused.</li>
 * <li>{@code REFUSE_WHEN_BUDGETED}: refused when a dollar ceiling applies — the
 * tenant's monthly budget, the agent's {@code maxCostPerConversationUsd}, or
 * the caller's own ceiling (a cascade's or Dream's {@code maxCostPerRun}).</li>
 * <li>{@code REFUSE}: every unpriced call is refused.</li>
 * </ul>
 * A call is priced when its config names an input or output price (an explicit
 * {@code 0} counts) or the catalog matches its model. The effective policy is
 * the stricter of the catalog document's and the operator's floor.
 * <p>
 * What a refusal leads to is the caller's existing failure path: the LLM task
 * serves its fallback ({@code llm:spend_refused:<taskId>}, reason
 * {@code unpriced_model}), a cascade step counts as failed and escalates, a
 * model guardrail is "could not decide" and its {@code failMode} applies.
 */
public final class UnpricedCallPolicy {

    private static final Logger LOGGER = Logger.getLogger(UnpricedCallPolicy.class);

    /** The refusal reason recorded under {@code llm:spend_refused:<taskId>}. */
    public static final String REASON = "unpriced_model";

    private UnpricedCallPolicy() {
    }

    /** Thrown where a refusal must abort a call that is already inside a guard. */
    public static final class UnpricedModelRefusedException extends RuntimeException {
        public UnpricedModelRefusedException(String message) {
            super(message, null, true, false);
        }
    }

    /**
     * The refusal for a call of {@code provider}/{@code model} priced by
     * {@code own}, or null when it may run.
     *
     * @param memory
     *            the conversation, for the agent's ceiling; may be null
     * @param callerCeiling
     *            whether the caller has a dollar ceiling of its own
     * @param site
     *            where the call is made (metric tag)
     */
    public static LlmSpendGate.Refusal check(PriceOverride own, String provider, String model, IConversationMemory memory, boolean callerCeiling,
                                             String site) {
        return check(own, provider, model, memory, callerCeiling, site, ModelPriceCatalogs.current());
    }

    static LlmSpendGate.Refusal check(PriceOverride own, String provider, String model, IConversationMemory memory, boolean callerCeiling,
                                      String site, PriceCatalogSnapshot catalog) {
        try {
            UnpricedPolicy policy = catalog.effectivePolicy();
            if (policy == UnpricedPolicy.ALLOW) {
                return null;
            }
            if (ModelPriceResolver.isPriced(own, provider, model, catalog)) {
                return null;
            }
            if (policy == UnpricedPolicy.REFUSE_WHEN_BUDGETED && !budgeted(memory, callerCeiling)) {
                return null;
            }
            String detail = "The model '" + provider + "/" + model + "' has no price and unpricedPolicy=" + policy
                    + " refuses unpriced calls; add a price to the config or the Model Prices catalog.";
            Metrics.globalRegistry.counter("eddi.llm.pricing.refused", "site", site, "reason", policy.name().toLowerCase(Locale.ROOT)).increment();
            LOGGER.warnf("Refused an unpriced model call at %s: %s/%s (policy %s)", site, provider, model, policy);
            return new LlmSpendGate.Refusal(REASON, detail);
        } catch (RuntimeException e) {
            LOGGER.debugf("Unpriced-call policy check failed: %s", e.getMessage());
            return null;
        }
    }

    /** Throws {@link UnpricedModelRefusedException} when {@link #check} refuses. */
    public static void require(PriceOverride own, String provider, String model, IConversationMemory memory, boolean callerCeiling,
                               String site) {
        LlmSpendGate.Refusal refusal = check(own, provider, model, memory, callerCeiling, site);
        if (refusal != null) {
            throw new UnpricedModelRefusedException(refusal.detail());
        }
    }

    static boolean budgeted(IConversationMemory memory, boolean callerCeiling) {
        if (callerCeiling) {
            return true;
        }
        if (memory != null && LlmSpendGate.ceilingApplies(LlmSpendGate.effectiveCeiling(memory))) {
            return true;
        }
        return SystemSpendMeter.tenantHasBudget();
    }
}
