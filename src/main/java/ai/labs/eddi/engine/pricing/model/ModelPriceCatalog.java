/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The deployment's model price catalog: one document per deployment, like the
 * brand. Operational settings, not a versioned resource; writes are
 * compare-and-set on {@link #revision()}, so two admins saving at once cannot
 * silently overwrite each other. Revision {@code 0} means nothing has been
 * written yet — and an empty catalog prices exactly as EDDI did before the
 * catalog existed.
 * <p>
 * Not part of an agent ZIP: prices belong to the deployment, not to an agent.
 *
 * @param unpricedPolicy
 *            the admin's choice of {@link UnpricedPolicy}; the effective policy
 *            is the stricter of this and the
 *            {@code eddi.llm.pricing.unpriced-policy-floor} property
 * @param updatedAt
 *            ISO-8601 instant of the last write
 * @param updatedBy
 *            the user who made the last write
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelPriceCatalog(long revision, UnpricedPolicy unpricedPolicy, String updatedAt, String updatedBy, List<ModelPriceEntry> entries) {

    public static final ModelPriceCatalog EMPTY = new ModelPriceCatalog(0, UnpricedPolicy.ALLOW, null, null, List.of());

    public ModelPriceCatalog {
        unpricedPolicy = unpricedPolicy == null ? UnpricedPolicy.ALLOW : unpricedPolicy;
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** The same values under a new revision and audit stamp. */
    public ModelPriceCatalog stamped(long newRevision, String at, String by) {
        return new ModelPriceCatalog(newRevision, unpricedPolicy, at, by, entries);
    }
}
