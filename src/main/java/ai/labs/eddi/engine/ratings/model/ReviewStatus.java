/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings.model;

import java.util.Locale;

/** Where a rating stands in the maintainers' triage. */
public enum ReviewStatus {
    /** Not looked at yet, or re-rated since it was. */
    NEW,
    /** Somebody who runs the agent has looked at it. */
    REVIEWED;

    /**
     * Parses {@code NEW} / {@code REVIEWED}, case-insensitively.
     *
     * @return the status, or {@code null} for a blank value
     * @throws IllegalArgumentException
     *             for anything else
     */
    public static ReviewStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "NEW" -> NEW;
            case "REVIEWED" -> REVIEWED;
            default -> throw new IllegalArgumentException("reviewStatus must be NEW or REVIEWED");
        };
    }
}
