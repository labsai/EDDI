/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * An immutable, compiled view of the price catalog: what every pricing site
 * reads on the call path. Built once per catalog change by
 * {@link ModelPriceCatalogService} and published through
 * {@link ModelPriceCatalogs#current()}; a call that takes a snapshot prices
 * against it throughout, so a catalog edit mid-call cannot tear one call's
 * pricing.
 *
 * @param revision
 *            the catalog revision this was compiled from ({@code 0}: none)
 * @param matcher
 *            the compiled entries
 * @param documentPolicy
 *            the admin's policy from the document
 * @param floorPolicy
 *            the operator's floor
 *            ({@code eddi.llm.pricing.unpriced-policy-floor})
 * @param oldestAsOf
 *            the oldest {@code asOf} among the entries, or null
 */
public record PriceCatalogSnapshot(long revision, ModelPriceMatcher matcher, UnpricedPolicy documentPolicy, UnpricedPolicy floorPolicy,
        LocalDate oldestAsOf) {

    /** No entries, policy {@code ALLOW}: exactly the pricing before the catalog. */
    public static final PriceCatalogSnapshot EMPTY = new PriceCatalogSnapshot(0, ModelPriceMatcher.EMPTY, UnpricedPolicy.ALLOW,
            UnpricedPolicy.ALLOW, null);

    public PriceCatalogSnapshot {
        matcher = matcher == null ? ModelPriceMatcher.EMPTY : matcher;
        documentPolicy = documentPolicy == null ? UnpricedPolicy.ALLOW : documentPolicy;
        floorPolicy = floorPolicy == null ? UnpricedPolicy.ALLOW : floorPolicy;
    }

    /** Compiles a stored catalog under the operator's floor policy. */
    public static PriceCatalogSnapshot of(ModelPriceCatalog catalog, UnpricedPolicy floor) {
        if (catalog == null) {
            return new PriceCatalogSnapshot(0, ModelPriceMatcher.EMPTY, UnpricedPolicy.ALLOW, floor, null);
        }
        LocalDate oldest = null;
        for (ModelPriceEntry entry : catalog.entries()) {
            LocalDate asOf = parseDate(entry.asOf());
            if (asOf != null && (oldest == null || asOf.isBefore(oldest))) {
                oldest = asOf;
            }
        }
        return new PriceCatalogSnapshot(catalog.revision(), ModelPriceMatcher.compile(catalog.entries()), catalog.unpricedPolicy(), floor,
                oldest);
    }

    /** The policy in force: the stricter of the document's and the floor. */
    public UnpricedPolicy effectivePolicy() {
        return UnpricedPolicy.stricter(documentPolicy, floorPolicy);
    }

    /** As {@link ModelPriceMatcher#match(String, String, String)}. */
    public Optional<ModelPriceEntry> match(String provider, String responseModel, String requestModel) {
        return matcher.match(provider, responseModel, requestModel);
    }

    /** As {@link ModelPriceMatcher#match(String, String)}. */
    public Optional<ModelPriceEntry> match(String provider, String model) {
        return matcher.match(provider, model);
    }

    /** The same entries under another floor. */
    public PriceCatalogSnapshot withFloor(UnpricedPolicy floor) {
        return new PriceCatalogSnapshot(revision, matcher, documentPolicy, floor, oldestAsOf);
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
