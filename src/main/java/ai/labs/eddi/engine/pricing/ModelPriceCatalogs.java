/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

/**
 * The static holder of the current {@link PriceCatalogSnapshot}.
 * <p>
 * <b>Why static.</b> Pricing happens deep inside the tool loop, the cascade
 * executor, the resumer and the decorator around every chat model — classes
 * built with {@code new} and shared by several features. Injecting the catalog
 * into each would ripple constructor parameters through those hot files and
 * their tests. A snapshot holder is the deliberate trade-off: the catalog
 * service publishes, pricing sites read, and every pricing method also has an
 * overload taking a snapshot explicitly, which is what the tests use.
 * <p>
 * Before the service publishes anything (unit tests, a store outage at boot)
 * the snapshot is {@link PriceCatalogSnapshot#EMPTY}, which prices exactly as
 * EDDI did before the catalog existed.
 */
public final class ModelPriceCatalogs {

    private static volatile PriceCatalogSnapshot current = PriceCatalogSnapshot.EMPTY;

    private ModelPriceCatalogs() {
    }

    /** The snapshot in force. Never null. */
    public static PriceCatalogSnapshot current() {
        return current;
    }

    /**
     * Publishes a new snapshot; null resets to {@link PriceCatalogSnapshot#EMPTY}.
     * Called by {@link ModelPriceCatalogService}, and by tests (which reset
     * afterwards).
     */
    public static void publish(PriceCatalogSnapshot snapshot) {
        current = snapshot == null ? PriceCatalogSnapshot.EMPTY : snapshot;
    }
}
