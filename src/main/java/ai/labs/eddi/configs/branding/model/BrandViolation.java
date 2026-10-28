/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.model;

/**
 * One rejected brand value.
 *
 * @param field
 *            the JSON field, e.g. {@code colors.primary}
 * @param message
 *            why it was rejected
 */
public record BrandViolation(String field, String message) {
}
