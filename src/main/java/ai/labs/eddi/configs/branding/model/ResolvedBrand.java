/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.model;

/**
 * The brand in effect: the stored document over the seed properties over the
 * EDDI defaults, field by field, with every fallback applied. What the shells,
 * {@code __brand__.js} and {@code brand.css} are rendered from.
 *
 * @param revision
 *            the stored document's revision ({@code 0} when none exists or the
 *            brand is locked); every rendered output is cached under it
 * @param branded
 *            whether anything differs from the EDDI brand
 * @param productName
 *            never {@code null}; {@code "EDDI"} by default
 * @param shortName
 *            never {@code null}; the product name when unset
 * @param tagline
 *            {@code null} when unset — each shell has its own default
 * @param colors
 *            resolved: the dark colours follow the light ones and the chat
 *            accent follows {@code primary}; all {@code null} when no colour is
 *            set
 * @param poweredBy
 *            show "Powered by EDDI"; on by default
 * @param showUpdateCheck
 *            show the update check UI even though the deployment is branded;
 *            off by default
 * @param assets
 *            the hash of each slot's own image, without the slot-to-slot
 *            fallbacks; {@code null} for "bundled default"
 */
public record ResolvedBrand(long revision, BrandSource source, boolean locked, boolean branded, String productName, String shortName,
        String tagline, BrandColors colors, boolean poweredBy, boolean showUpdateCheck, BrandAssets assets) {

    public static final String DEFAULT_PRODUCT_NAME = "EDDI";

    /** The EDDI brand, as served when nothing is configured. */
    public static ResolvedBrand defaults(boolean locked) {
        return new ResolvedBrand(0, BrandSource.DEFAULT, locked, false, DEFAULT_PRODUCT_NAME, DEFAULT_PRODUCT_NAME, null, BrandColors.EMPTY,
                true, false, BrandAssets.EMPTY);
    }
}
