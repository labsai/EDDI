/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.engine.pricing.ModelPriceCatalogs;
import ai.labs.eddi.engine.pricing.PriceCatalogSnapshot;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The SERVICE judge prices through the one resolver: its own prices win, a
 * price it leaves out comes from the catalog entry of its provider and model.
 */
class JudgeCallPricingTest {

    @BeforeEach
    void publishCatalog() {
        ModelPriceCatalogs.publish(PriceCatalogSnapshot.of(
                new ModelPriceCatalog(1, UnpricedPolicy.ALLOW, null, null, List.of(ModelPriceEntry.of("openai", "gpt-4o-mini", 2.0, 8.0))),
                UnpricedPolicy.ALLOW));
    }

    @AfterEach
    void clearCatalog() {
        ModelPriceCatalogs.publish(null);
    }

    @Test
    @DisplayName("a judge without prices of its own is priced from the catalog entry of its model")
    void unpricedJudgeUsesTheCatalog() {
        assertEquals(2.0 + 8.0 / 4, JudgeCallPricing.cost("openai", "gpt-4o-mini", null, null, 1_000_000, 250_000), 1e-9);
    }

    @Test
    @DisplayName("the judge's own prices win over the catalog; a missing one is completed from it")
    void configuredPricesWin() {
        assertEquals(0.5 + 1.0 / 4, JudgeCallPricing.cost("openai", "gpt-4o-mini", 0.5, 1.0, 1_000_000, 250_000), 1e-9);
        assertEquals(0.5 + 8.0 / 4, JudgeCallPricing.cost("openai", "gpt-4o-mini", 0.5, null, 1_000_000, 250_000), 1e-9);
    }

    @Test
    @DisplayName("a model the catalog does not know, or no provider/model at all, stays unpriced: $0")
    void unknownModelIsUnpriced() {
        assertEquals(0.0, JudgeCallPricing.cost("openai", "not-in-catalog", null, null, 1_000_000, 1_000_000), 1e-12);
        assertEquals(0.0, JudgeCallPricing.cost(null, null, null, null, 1_000_000, 1_000_000), 1e-12);
        assertEquals(0.0, JudgeCallPricing.cost(" ", "gpt-4o-mini", null, null, 1_000_000, 1_000_000), 1e-12);
    }
}
