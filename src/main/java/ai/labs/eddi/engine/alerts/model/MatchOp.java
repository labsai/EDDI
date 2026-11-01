/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * The comparison of an {@link AlertSource#OUTPUT_MATCH} condition. A fixed set,
 * not an expression language.
 */
public enum MatchOp {
    EXISTS, EQ, NEQ, IN, GT, GTE, LT, LTE, CONTAINS;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static MatchOp parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return MatchOp.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    /** Whether the op compares against a constant ({@code exists} takes none). */
    public boolean needsValue() {
        return this != EXISTS;
    }
}
