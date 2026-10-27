/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.model;

import java.util.List;

/**
 * What {@code GET} and every write of {@code /administration/branding} answer.
 *
 * @param document
 *            the stored, editable document — {@code null} fields are not set;
 *            when {@code locked}, the brand built from the properties instead,
 *            since that is what is in effect and nothing can be edited
 * @param effective
 *            the brand in effect after the seed and defaults are applied
 * @param locked
 *            {@code eddi.branding.locked=true}: properties are authoritative
 *            and every write answers 409
 * @param source
 *            where the effective brand comes from
 * @param warnings
 *            advisories about an accepted write, such as low contrast; empty on
 *            {@code GET}
 */
public record BrandingView(BrandDocument document, ResolvedBrand effective, boolean locked, BrandSource source, List<String> warnings) {
}
