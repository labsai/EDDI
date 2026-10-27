/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings.model;

import java.util.Locale;

/** The order of a rating listing. */
public enum RatingSort {
    /** Newest first. */
    NEWEST,
    /** Lowest normalized score first, newest first among equal scores. */
    LOWEST;

    /**
     * @return {@link #NEWEST} for a blank value
     * @throws IllegalArgumentException
     *             for anything but {@code newest} or {@code lowest}
     */
    public static RatingSort parse(String value) {
        if (value == null || value.isBlank()) {
            return NEWEST;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "newest" -> NEWEST;
            case "lowest" -> LOWEST;
            default -> throw new IllegalArgumentException("sort must be newest or lowest");
        };
    }
}
