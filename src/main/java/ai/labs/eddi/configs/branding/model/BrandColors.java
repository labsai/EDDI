/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The brand's colours. Every field is optional; {@code null} means "not set".
 * Unset dark colours follow the light ones and an unset chat accent follows
 * {@code primary} — see {@link ResolvedBrand}.
 * <p>
 * Values are CSS colours in the strict grammar of {@code BrandValidator},
 * because they are emitted into a stylesheet.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BrandColors(String primary, String primaryForeground, String darkPrimary, String darkPrimaryForeground, String chatAccent) {

    public static final BrandColors EMPTY = new BrandColors(null, null, null, null, null);

    @JsonIgnore
    public boolean isEmpty() {
        return primary == null && primaryForeground == null && darkPrimary == null && darkPrimaryForeground == null && chatAccent == null;
    }

    /** Field-wise: a field of this object wins, else the one of {@code base}. */
    public BrandColors over(BrandColors base) {
        if (base == null) {
            return this;
        }
        return new BrandColors(
                primary != null ? primary : base.primary,
                primaryForeground != null ? primaryForeground : base.primaryForeground,
                darkPrimary != null ? darkPrimary : base.darkPrimary,
                darkPrimaryForeground != null ? darkPrimaryForeground : base.darkPrimaryForeground,
                chatAccent != null ? chatAccent : base.chatAccent);
    }
}
