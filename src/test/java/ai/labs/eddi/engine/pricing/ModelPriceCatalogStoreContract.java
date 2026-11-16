/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import ai.labs.eddi.engine.pricing.model.LongContextTier;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every {@link IModelPriceCatalogStore} must do, run against MongoDB and
 * PostgreSQL by the store ITs — through the real persistence boundary, since an
 * in-memory store cannot show a field the database drops.
 */
public final class ModelPriceCatalogStoreContract {

    private ModelPriceCatalogStoreContract() {
    }

    public static void emptyStoreHasNoCatalog(IModelPriceCatalogStore store) {
        assertTrue(store.read().isEmpty());
    }

    /** Every field, nested tier included, comes back as written. */
    public static void documentRoundTrips(IModelPriceCatalogStore store) {
        ModelPriceCatalog written = new ModelPriceCatalog(1, UnpricedPolicy.REFUSE_WHEN_BUDGETED, "2026-11-02T10:00:00Z", "admin",
                List.of(new ModelPriceEntry("anthropic", "claude-sonnet-4-5*", 3.0, 15.0, 0.3, 3.75,
                        new LongContextTier(200_000L, 6.0, 22.5, 0.6, 7.5), "2026-11-02", "https://www.anthropic.com/pricing", "note"),
                        ModelPriceEntry.of("ollama", "*", 0.0, 0.0)));
        assertTrue(store.compareAndSet(0, written));
        assertEquals(written, store.read().orElseThrow());
    }

    public static void compareAndSetRefusesAStaleRevision(IModelPriceCatalogStore store) {
        ModelPriceCatalog first = new ModelPriceCatalog(1, UnpricedPolicy.ALLOW, null, null, List.of(ModelPriceEntry.of("openai", "a", 1.0, 2.0)));
        assertTrue(store.compareAndSet(0, first));
        assertFalse(store.compareAndSet(0, first), "a second create must lose");

        ModelPriceCatalog second = new ModelPriceCatalog(2, UnpricedPolicy.REFUSE, null, null, List.of());
        assertTrue(store.compareAndSet(1, second));
        assertFalse(store.compareAndSet(1, new ModelPriceCatalog(2, UnpricedPolicy.ALLOW, null, "lost", List.of())));
        ModelPriceCatalog stored = store.read().orElseThrow();
        assertEquals(2, stored.revision());
        assertEquals(UnpricedPolicy.REFUSE, stored.unpricedPolicy());
    }
}
