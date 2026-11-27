/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.exception.ErrorResponses;
import jakarta.ws.rs.BadRequestException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Parses and checks the query parameters the Agent Health endpoints share —
 * each bad value is a 400 naming the parameter, in EDDI's usual error body.
 */
public final class HealthPeriods {

    /** The longest period: the turn-row retention default. */
    public static final int MAX_DAYS = 90;
    /** Period values accepted by {@code period=}. */
    public static final List<String> PERIODS = List.of("7d", "30d", "90d");

    /**
     * A resolved period and its baseline — the previous period of equal length.
     */
    public record Window(LocalDate from, LocalDate to) {
        public int days() {
            return (int) ChronoUnit.DAYS.between(from, to) + 1;
        }

        public Window baseline() {
            return new Window(from.minusDays(days()), from.minusDays(1));
        }

        public boolean contains(LocalDate day) {
            return !day.isBefore(from) && !day.isAfter(to);
        }
    }

    private HealthPeriods() {
    }

    /** A 400 whose message names the parameter. */
    public static BadRequestException badRequest(String message) {
        return new BadRequestException(message, ErrorResponses.badRequest(message));
    }

    /**
     * The window of {@code period} (default {@code defaultPeriod}) ending
     * {@code today}, or of {@code from}/{@code to}.
     */
    public static Window window(String period, String from, String to, String defaultPeriod, LocalDate today) {
        boolean hasRange = notBlank(from) || notBlank(to);
        if (hasRange) {
            if (notBlank(period)) {
                throw badRequest("period and from/to cannot be combined: pass either period or both from and to");
            }
            if (!notBlank(from) || !notBlank(to)) {
                throw badRequest("from and to must be given together (YYYY-MM-DD)");
            }
            LocalDate start = day("from", from);
            LocalDate end = day("to", to);
            if (end.isBefore(start)) {
                throw badRequest("to must not be before from");
            }
            if (end.isAfter(today)) {
                throw badRequest("to must not be after today (" + today + ")");
            }
            Window window = new Window(start, end);
            if (window.days() > MAX_DAYS) {
                throw badRequest("from/to may span at most " + MAX_DAYS + " days");
            }
            return window;
        }
        String p = notBlank(period) ? period.trim() : defaultPeriod;
        if (!PERIODS.contains(p)) {
            throw badRequest("period must be one of 7d, 30d or 90d — was '" + sanitize(p) + "'");
        }
        int days = Integer.parseInt(p.substring(0, p.length() - 1));
        return new Window(today.minusDays(days - 1L), today);
    }

    /** A {@code YYYY-MM-DD} parameter. */
    public static LocalDate day(String parameter, String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw badRequest(parameter + " must be a date YYYY-MM-DD — was '" + sanitize(value) + "'");
        }
    }

    /** An optional version: a positive integer. */
    public static Integer version(String parameter, Integer value) {
        if (value != null && value < 1) {
            throw badRequest(parameter + " must be a positive version number — was " + value);
        }
        return value;
    }

    /**
     * The origins asked for, each checked; {@code null} when none were (the preset
     * default applies).
     */
    public static List<String> origins(List<String> requested) {
        if (requested == null) {
            return null;
        }
        Set<String> origins = new LinkedHashSet<>();
        for (String origin : requested) {
            if (origin == null || origin.isBlank()) {
                continue;
            }
            String o = origin.trim();
            if (!HealthOrigins.ALL.contains(o)) {
                throw badRequest("origin must be one of " + String.join(", ", HealthOrigins.ALL) + " — was '" + sanitize(o) + "'");
            }
            origins.add(o);
        }
        return origins.isEmpty() ? null : new ArrayList<>(origins);
    }

    /**
     * The default origins of a preset: interactive ones, or everything but agent
     * tests.
     */
    public static List<String> defaultOrigins(String preset) {
        if (HealthDefaults.PRESET_TASK.equals(preset)) {
            return HealthOrigins.ALL.stream().filter(o -> !HealthOrigins.AGENT_TEST.equals(o)).toList();
        }
        return HealthOrigins.INTERACTIVE;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
