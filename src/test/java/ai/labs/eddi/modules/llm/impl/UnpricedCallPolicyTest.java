/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.pricing.ModelPriceCatalogs;
import ai.labs.eddi.engine.pricing.PriceCatalogSnapshot;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import ai.labs.eddi.modules.llm.pricing.PriceOverride;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UnpricedCallPolicyTest {

    private static PriceCatalogSnapshot catalog(UnpricedPolicy document, UnpricedPolicy floor) {
        return PriceCatalogSnapshot.of(new ModelPriceCatalog(1, document, null, null,
                List.of(ModelPriceEntry.of("openai", "gpt-4o", 2.5, 10.0), ModelPriceEntry.of("ollama", "*", 0.0, 0.0))), floor);
    }

    /**
     * policy × ceiling × {catalogued, explicit price, explicit zero, catalog zero,
     * unpriced}; no memory, so only the caller's own ceiling counts as budgeted.
     */
    @ParameterizedTest(name = "{0} ceiling={1} {2} -> refused={3}")
    @CsvSource({
            "ALLOW,true,unpriced,false",
            "ALLOW,false,unpriced,false",
            "REFUSE,false,unpriced,true",
            "REFUSE,true,unpriced,true",
            "REFUSE,false,catalogued,false",
            "REFUSE,false,explicit,false",
            "REFUSE,false,explicitZero,false",
            "REFUSE,false,catalogZero,false",
            "REFUSE_WHEN_BUDGETED,false,unpriced,false",
            "REFUSE_WHEN_BUDGETED,true,unpriced,true",
            "REFUSE_WHEN_BUDGETED,true,catalogued,false",
            "REFUSE_WHEN_BUDGETED,true,explicitZero,false"
    })
    void matrix(UnpricedPolicy policy, boolean ceiling, String call, boolean refused) {
        PriceOverride own = switch (call) {
            case "explicit" -> PriceOverride.of(1.0, 2.0);
            case "explicitZero" -> PriceOverride.of(0.0, null);
            default -> PriceOverride.NONE;
        };
        String provider = call.equals("catalogZero") ? "ollama" : "openai";
        String model = call.equals("catalogued") ? "gpt-4o" : call.equals("catalogZero") ? "llama3" : "unknown-model";
        var refusal = UnpricedCallPolicy.check(own, provider, model, null, ceiling, "test", catalog(policy, UnpricedPolicy.ALLOW));
        assertEquals(refused, refusal != null);
        if (refused) {
            assertEquals(UnpricedCallPolicy.REASON, refusal.reason());
        }
    }

    @Test
    void theFloorAppliesWhenTheDocumentIsLooser() {
        assertNotNull(UnpricedCallPolicy.check(PriceOverride.NONE, "openai", "x", null, false, "test", catalog(UnpricedPolicy.ALLOW,
                UnpricedPolicy.REFUSE)));
        assertNull(UnpricedCallPolicy.check(PriceOverride.NONE, "openai", "x", null, false, "test", catalog(UnpricedPolicy.ALLOW,
                UnpricedPolicy.ALLOW)));
    }

    @Test
    void anUnknownModelNameIsUnpriced() {
        assertNotNull(UnpricedCallPolicy.check(PriceOverride.NONE, "openai", null, null, false, "test", catalog(UnpricedPolicy.REFUSE,
                UnpricedPolicy.ALLOW)));
    }

    @Test
    void requireThrowsWithTheDetail() {
        var catalog = catalog(UnpricedPolicy.REFUSE, UnpricedPolicy.ALLOW);
        ModelPriceCatalogs.publish(catalog);
        try {
            var e = assertThrows(UnpricedCallPolicy.UnpricedModelRefusedException.class,
                    () -> UnpricedCallPolicy.require(PriceOverride.NONE, "openai", "nope", null, false, "test"));
            assertEquals(true, e.getMessage().contains("openai/nope"));
        } finally {
            ModelPriceCatalogs.publish(null);
        }
    }
}
