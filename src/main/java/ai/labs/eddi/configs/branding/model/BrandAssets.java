/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The content hash ({@code sha256:<hex>}) of the image in each slot, or
 * {@code null} for "none uploaded". The bytes live in a separate collection or
 * table, keyed by slot; the hash doubles as the asset's ETag.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BrandAssets(String logo, String logoDark, String icon, String favicon, String ogImage) {

    public static final BrandAssets EMPTY = new BrandAssets(null, null, null, null, null);

    public String get(BrandAssetSlot slot) {
        return switch (slot) {
            case LOGO -> logo;
            case LOGO_DARK -> logoDark;
            case ICON -> icon;
            case FAVICON -> favicon;
            case OG_IMAGE -> ogImage;
        };
    }

    public BrandAssets with(BrandAssetSlot slot, String hash) {
        return new BrandAssets(
                slot == BrandAssetSlot.LOGO ? hash : logo,
                slot == BrandAssetSlot.LOGO_DARK ? hash : logoDark,
                slot == BrandAssetSlot.ICON ? hash : icon,
                slot == BrandAssetSlot.FAVICON ? hash : favicon,
                slot == BrandAssetSlot.OG_IMAGE ? hash : ogImage);
    }

    @JsonIgnore
    public boolean isEmpty() {
        for (BrandAssetSlot slot : BrandAssetSlot.values()) {
            if (get(slot) != null) {
                return false;
            }
        }
        return true;
    }
}
