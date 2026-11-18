/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.modules.llm.tools.NeverCached;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Date and time tool for timezone conversions, calculations, and formatting.
 */
@ApplicationScoped
public class DateTimeTool {
    private static final Logger LOGGER = Logger.getLogger(DateTimeTool.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;
    private static final DateTimeFormatter READABLE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    @NeverCached
    @Tool("Gets the current date and time in a specified timezone. Returns formatted date/time string.")
    public String getCurrentDateTime(@P("IANA timezone name, e.g. 'Europe/Vienna', 'America/New_York' or 'UTC'") String timezone) {

        try {
            ZoneId zoneId = ZoneId.of(timezone);
            ZonedDateTime now = ZonedDateTime.now(zoneId);

            String result = now.format(READABLE_FORMATTER);
            LOGGER.debugf("Current time in %s: %s", sanitize(timezone), result);
            return result;

        } catch (DateTimeException e) {
            LOGGER.debugf("Invalid timezone: %s", sanitize(timezone));
            return "Error: Invalid timezone '" + timezone + "'. Use standard timezone names like 'America/New_York' or 'UTC'.";
        }
    }

    @Tool("Converts a date/time from one timezone to another")
    public String convertTimezone(@P("ISO-8601 date-time, e.g. '2025-11-03T10:30:00' (local, in fromTimezone) or with an offset/zone such as '2025-11-03T10:30:00Z' (fromTimezone is then ignored)") String dateTime,
                                  @P("Timezone the date-time is expressed in (IANA name)") String fromTimezone,
                                  @P("Timezone to convert to (IANA name)") String toTimezone) {

        try {
            ZoneId fromZone = ZoneId.of(fromTimezone);
            ZoneId toZone = ZoneId.of(toTimezone);

            // An offset or zone written in the input wins over fromTimezone.
            ZonedDateTime fromZdt = place(parse(dateTime), fromZone);
            ZonedDateTime toZdt = fromZdt.withZoneSameInstant(toZone);

            String result = toZdt.format(READABLE_FORMATTER);
            LOGGER.debugf("Converted %s from %s to %s: %s", sanitize(dateTime), sanitize(fromTimezone), sanitize(toTimezone), result);
            return result;

        } catch (DateTimeException e) {
            LOGGER.debugf("Timezone conversion rejected: %s", sanitize(e.getMessage()));
            return "Error: " + e.getMessage();
        }
    }

    @Tool("Calculates the difference between two dates/times")
    public String calculateDateDifference(@P("Start, as an ISO-8601 local date-time") String startDateTime,
                                          @P("End, as an ISO-8601 local date-time") String endDateTime,
                                          @P("Unit of the result: days, hours, minutes or seconds") String unit) {

        try {
            Parsed startParsed = parse(startDateTime);
            Parsed endParsed = parse(endDateTime);
            if ((startParsed.zoned() == null) != (endParsed.zoned() == null)) {
                return "Error: Give both date-times with an offset or zone, or both without; a mix cannot be compared.";
            }
            // With offsets/zones the difference is between instants; without, between
            // the local values, as before.
            Temporal start = startParsed.zoned() != null ? startParsed.zoned() : startParsed.local();
            Temporal end = endParsed.zoned() != null ? endParsed.zoned() : endParsed.local();

            long difference;
            String unitName;

            switch (unit.toLowerCase()) {
                case "days" :
                    difference = ChronoUnit.DAYS.between(start, end);
                    unitName = "days";
                    break;
                case "hours" :
                    difference = ChronoUnit.HOURS.between(start, end);
                    unitName = "hours";
                    break;
                case "minutes" :
                    difference = ChronoUnit.MINUTES.between(start, end);
                    unitName = "minutes";
                    break;
                case "seconds" :
                    difference = ChronoUnit.SECONDS.between(start, end);
                    unitName = "seconds";
                    break;
                default :
                    return "Error: Invalid unit. Use 'days', 'hours', 'minutes', or 'seconds'.";
            }

            String result = "Difference: " + difference + " " + unitName;
            LOGGER.debug(result);
            return result;

        } catch (DateTimeParseException e) {
            LOGGER.debugf("Date parsing rejected: %s", sanitize(e.getMessage()));
            return "Error: Invalid date format. Use ISO format like '2025-11-03T10:30:00'.";
        }
    }

    @Tool("Adds or subtracts time from a date")
    public String addTime(@P("Local date-time in ISO-8601 form, e.g. '2025-11-03T10:30:00'") String dateTime,
                          @P("Amount to add; negative to subtract") long amount,
                          @P("Unit of the amount: years, months, weeks, days, hours, minutes or seconds") String unit,
                          @P("Timezone of the date-time (IANA name)") String timezone) {

        try {
            ZoneId zoneId = ZoneId.of(timezone);
            // The start keeps its own offset: an input that names the later offset of
            // a repeated (fall-back) hour stays on it.
            ZonedDateTime start = place(parse(dateTime), zoneId);

            // Date units move the wall clock (a day later is the same local time; one
            // that lands in a skipped hour is reported, as for an input). Time units
            // move the instant: an hour after 01:30 on the spring-forward night is
            // 03:30, and across fall-back the repeated hour is counted.
            ZonedDateTime zonedResult;
            switch (unit.toLowerCase()) {
                case "years" :
                    zonedResult = onWallClock(start, start.toLocalDateTime().plusYears(amount));
                    break;
                case "months" :
                    zonedResult = onWallClock(start, start.toLocalDateTime().plusMonths(amount));
                    break;
                case "weeks" :
                    zonedResult = onWallClock(start, start.toLocalDateTime().plusWeeks(amount));
                    break;
                case "days" :
                    zonedResult = onWallClock(start, start.toLocalDateTime().plusDays(amount));
                    break;
                case "hours" :
                    zonedResult = start.plusHours(amount);
                    break;
                case "minutes" :
                    zonedResult = start.plusMinutes(amount);
                    break;
                case "seconds" :
                    zonedResult = start.plusSeconds(amount);
                    break;
                default :
                    return "Error: Invalid unit. Use 'years', 'months', 'weeks', 'days', 'hours', 'minutes', or 'seconds'.";
            }

            String formatted = zonedResult.format(READABLE_FORMATTER);
            LOGGER.debugf("Added %d %s to %s: %s", amount, sanitize(unit), sanitize(dateTime), formatted);
            return formatted;

        } catch (DateTimeException e) {
            LOGGER.debugf("Date calculation rejected: %s", sanitize(e.getMessage()));
            return "Error: " + e.getMessage();
        }
    }

    /**
     * {@code local} in the start's zone: refused in a skipped hour, and in a
     * repeated hour on the start's own offset when that offset is one of the two.
     */
    private static ZonedDateTime onWallClock(ZonedDateTime start, LocalDateTime local) {
        place(new Parsed(local, null), start.getZone());
        return ZonedDateTime.ofLocal(local, start.getZone(), start.getOffset());
    }

    @Tool("Formats a date/time string into a different format")
    public String formatDateTime(@P("Local date-time in ISO-8601 form, e.g. '2025-11-03T10:30:00'") String dateTime,
                                 @P("java.time format pattern, e.g. 'dd.MM.yyyy HH:mm' or 'EEEE, MMMM d'") String pattern,
                                 @P("Timezone of the date-time (IANA name)") String timezone) {

        try {
            ZoneId zoneId = ZoneId.of(timezone);
            ZonedDateTime zonedDateTime = place(parse(dateTime), zoneId);

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            String result = zonedDateTime.format(formatter);

            LOGGER.debugf("Formatted %s as: %s", sanitize(dateTime), sanitize(result));
            return result;

        } catch (DateTimeException e) {
            LOGGER.debugf("Date formatting rejected: %s", sanitize(e.getMessage()));
            return "Error: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            LOGGER.debugf("Invalid format pattern: %s", sanitize(pattern));
            return "Error: Invalid format pattern '" + pattern + "'.";
        }
    }

    /**
     * A parsed ISO-8601 date-time: {@code zoned} is set when the text carried an
     * offset or zone ({@code ...Z}, {@code +02:00}, {@code [Europe/Vienna]}),
     * otherwise {@code local} is the wall-clock value.
     */
    record Parsed(LocalDateTime local, ZonedDateTime zoned) {
    }

    static Parsed parse(String text) {
        var parsed = ISO_FORMATTER.parseBest(text.trim(), ZonedDateTime::from, LocalDateTime::from);
        return parsed instanceof ZonedDateTime zdt ? new Parsed(zdt.toLocalDateTime(), zdt) : new Parsed((LocalDateTime) parsed, null);
    }

    /**
     * The instant in {@code zone}: a value that carried its own offset or zone is
     * converted; a local value is attached. A local time that does not exist in the
     * zone (the hour skipped when clocks go forward) is refused rather than
     * shifted. An ambiguous one (the repeated hour when clocks go back) takes the
     * earlier offset, as {@link ZonedDateTime#of(LocalDateTime, ZoneId)} does.
     */
    static ZonedDateTime place(Parsed parsed, ZoneId zone) {
        if (parsed.zoned() != null) {
            return parsed.zoned().withZoneSameInstant(zone);
        }
        if (zone.getRules().getValidOffsets(parsed.local()).isEmpty()) {
            throw new DateTimeException("The local time " + parsed.local() + " does not exist in " + zone
                    + " (clocks are set forward over it). Pick a time outside the skipped hour or give an explicit offset.");
        }
        return ZonedDateTime.of(parsed.local(), zone);
    }

    // Reports the current local time, and takes no arguments: a cached answer would
    // be served unchanged for the whole TTL.
    @NeverCached
    @Tool("Lists common IANA timezone names with their current local time")
    public String listTimezones() {
        StringBuilder sb = new StringBuilder("Available timezones:\n");

        // Get major timezones
        String[] majorTimezones = {"UTC", "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles", "Europe/London",
                "Europe/Paris", "Europe/Berlin", "Europe/Rome", "Asia/Tokyo", "Asia/Shanghai", "Asia/Hong_Kong", "Asia/Singapore", "Australia/Sydney",
                "Pacific/Auckland"};

        for (String tz : majorTimezones) {
            ZoneId zoneId = ZoneId.of(tz);
            ZonedDateTime now = ZonedDateTime.now(zoneId);
            sb.append("- ").append(tz).append(" (").append(now.format(DateTimeFormatter.ofPattern("HH:mm"))).append(")\n");
        }

        sb.append("\nFor a complete list, use standard timezone names like 'Continent/City'.");
        return sb.toString();
    }
}
