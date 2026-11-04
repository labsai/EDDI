/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing.model;

/**
 * What EDDI does with a model call it cannot price — neither the task nor the
 * deployment price catalog names a price for the model. Ordered from the most
 * lenient to the strictest: {@link #stricter} relies on that order.
 */
public enum UnpricedPolicy {

    /**
     * The default and the behaviour before the catalog existed: the call runs,
     * costs $0, and is counted in {@code eddi.llm.calls.unpriced} and
     * {@code audit:pricing}.
     */
    ALLOW,

    /**
     * Refuse an unpriced call when a dollar ceiling applies to it — a tenant
     * monthly budget, the agent's {@code maxCostPerConversationUsd}, a cascade's or
     * Dream's {@code maxCostPerRun}. A budget that cannot see a call cannot hold
     * it.
     */
    REFUSE_WHEN_BUDGETED,

    /** Refuse every unpriced call. An explicit {@code 0} price is priced. */
    REFUSE;

    /** The stricter of the two; a null side is ignored. */
    public static UnpricedPolicy stricter(UnpricedPolicy a, UnpricedPolicy b) {
        if (a == null) {
            return b == null ? ALLOW : b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
