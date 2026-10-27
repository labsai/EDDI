/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The stored brand: one document per deployment.
 * <p>
 * Every field is optional and {@code null} means "not set here", in which case
 * the {@code eddi.branding.*} seed property applies, and failing that the EDDI
 * default. {@link #revision()} only ever grows: a reset writes an empty
 * document with the next revision rather than deleting it, so every cache keyed
 * on the revision (rendered shells, the brand stylesheet link) sees the change.
 * Revision {@code 0} means no document has been written yet.
 *
 * @param updatedAt
 *            ISO-8601 instant of the last write
 * @param updatedBy
 *            the user who made the last write
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BrandDocument(long revision, String productName, String shortName, String tagline, BrandColors colors, Boolean poweredBy,
        Boolean showUpdateCheck, BrandAssets assets, String updatedAt, String updatedBy) {

    public static final BrandDocument EMPTY = new BrandDocument(0, null, null, null, null, null, null, null, null, null);

    public BrandDocument {
        colors = colors == null ? BrandColors.EMPTY : colors;
        assets = assets == null ? BrandAssets.EMPTY : assets;
    }

    /** Whether any brand value is set (revision and audit fields aside). */
    @JsonIgnore
    public boolean hasValues() {
        return productName != null || shortName != null || tagline != null || !colors.isEmpty() || poweredBy != null
                || showUpdateCheck != null || !assets.isEmpty();
    }

    public BrandDocument withAssets(BrandAssets newAssets) {
        return new BrandDocument(revision, productName, shortName, tagline, colors, poweredBy, showUpdateCheck, newAssets, updatedAt,
                updatedBy);
    }

    /** The same values under a new revision and audit stamp. */
    public BrandDocument stamped(long newRevision, String at, String by) {
        return new BrandDocument(newRevision, productName, shortName, tagline, colors, poweredBy, showUpdateCheck, assets, at, by);
    }
}
