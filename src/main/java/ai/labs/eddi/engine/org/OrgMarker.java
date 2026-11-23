/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import java.time.Instant;
import java.util.Objects;

/**
 * The deployment's org layout record (plan §5.3, §5.4): the enablement marker
 * and {@code layout.required}, the lowest data-layout level a binary must
 * support to run against this data. Platform plane: it carries no org.
 *
 * @param state
 *            the enablement marker
 * @param requiredLayout
 *            {@code layout.required}; 0 is today's layout, and it only ever
 *            rises
 * @param updatedAt
 *            when the record last changed, or {@code null} when none exists yet
 */
public record OrgMarker(OrgMarkerState state, int requiredLayout, Instant updatedAt) {

    /** What a deployment that has never been enabled reads: no record at all. */
    public static final OrgMarker NONE = new OrgMarker(OrgMarkerState.ABSENT, 0, null);

    /**
     * Interprets a stored record strictly.
     *
     * @param state
     *            the stored marker state, or {@code null} for a record without one
     * @param layout
     *            the stored level, or {@code null} for a record without one
     * @throws UnsupportedLayoutRecordException
     *             for an unknown state, or a level that is not a whole number
     *             between 0 and {@link Integer#MAX_VALUE}
     */
    public static OrgMarker parse(Object state, Object layout, Instant updatedAt) {
        OrgMarkerState parsedState;
        if (state == null) {
            parsedState = OrgMarkerState.ABSENT;
        } else if (state instanceof String name) {
            try {
                parsedState = OrgMarkerState.valueOf(name);
            } catch (IllegalArgumentException e) {
                throw new UnsupportedLayoutRecordException("unknown org marker state '" + name + "'");
            }
        } else {
            throw new UnsupportedLayoutRecordException("the org marker state is not a string");
        }
        return new OrgMarker(parsedState, parseLevel(layout), updatedAt);
    }

    private static int parseLevel(Object layout) {
        if (layout == null) {
            return 0;
        }
        long level;
        if (layout instanceof Integer || layout instanceof Long || layout instanceof Short || layout instanceof Byte) {
            level = ((Number) layout).longValue();
        } else if (layout instanceof Double || layout instanceof Float) {
            double value = ((Number) layout).doubleValue();
            if (value != Math.rint(value) || Double.isInfinite(value)) {
                throw new UnsupportedLayoutRecordException("the required layout is not a whole number: " + value);
            }
            level = (long) value;
        } else {
            throw new UnsupportedLayoutRecordException("the required layout is not a number");
        }
        if (level < 0 || level > Integer.MAX_VALUE) {
            throw new UnsupportedLayoutRecordException("the required layout is out of range: " + level);
        }
        return (int) level;
    }

    public OrgMarker {
        Objects.requireNonNull(state, "state");
        if (requiredLayout < 0) {
            throw new IllegalArgumentException("requiredLayout must not be negative");
        }
    }
}
