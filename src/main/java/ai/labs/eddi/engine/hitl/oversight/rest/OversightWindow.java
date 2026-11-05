/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.rest;

import ai.labs.eddi.engine.hitl.oversight.OversightErrors;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * The time window of an oversight query: {@code from} inclusive, {@code to}
 * exclusive, ISO-8601 instants or dates (UTC). A summary window is bounded, so
 * no request can make the server read years of rows.
 *
 * @since 6.7.0
 */
record OversightWindow(Instant from, Instant to) {

    static final Duration DEFAULT_SPAN = Duration.ofDays(30);
    static final Duration MAX_SPAN = Duration.ofDays(365);

    /** The window of a query; defaults to the last 30 days, at most 365 days. */
    static OversightWindow of(String from, String to, Instant now) {
        Instant end = parse("to", to, true);
        Instant start = parse("from", from, false);
        if (end == null) {
            end = now;
        }
        if (start == null) {
            start = end.minus(DEFAULT_SPAN);
        }
        if (!start.isBefore(end)) {
            throw OversightErrors.badRequest(OversightErrors.INVALID_PARAMETER, "from must be before to");
        }
        if (Duration.between(start, end).compareTo(MAX_SPAN) > 0) {
            throw OversightErrors.badRequest(OversightErrors.WINDOW_TOO_LONG, "The window may span at most 365 days");
        }
        return new OversightWindow(start, end);
    }

    /** An optional bound (no default, no span check). */
    static Instant parse(String name, String value, boolean exclusiveEnd) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException notAnInstant) {
            try {
                LocalDate date = LocalDate.parse(text);
                return (exclusiveEnd ? date.plusDays(1) : date).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException notADate) {
                throw OversightErrors.badRequest(OversightErrors.INVALID_PARAMETER,
                        name + " must be an ISO-8601 instant (2026-10-01T00:00:00Z) or date (2026-10-01)");
            }
        }
    }
}
