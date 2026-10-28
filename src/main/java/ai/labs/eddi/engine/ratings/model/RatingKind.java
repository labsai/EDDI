/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings.model;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** What a rating rates: the whole conversation, or one agent reply. */
public enum RatingKind {
    CONVERSATION, MESSAGE;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses {@code conversation} / {@code message}, case-insensitively.
     *
     * @return the kind, or {@code null} for a blank value
     * @throws IllegalArgumentException
     *             for anything else
     */
    public static RatingKind parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return RatingKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
