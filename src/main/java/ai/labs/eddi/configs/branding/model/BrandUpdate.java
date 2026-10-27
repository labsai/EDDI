/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body of {@code PUT /administration/branding}: the text fields, colours and
 * toggles, which replace the stored ones (images are set per slot). A
 * {@code null} field clears the stored value, so the seed property or the EDDI
 * default applies again.
 *
 * @param revision
 *            the revision this edit was based on, as read from
 *            {@code GET /administration/branding}; a different current revision
 *            answers 409
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BrandUpdate(Long revision, String productName, String shortName, String tagline, BrandColors colors, Boolean poweredBy,
        Boolean showUpdateCheck) {
}
