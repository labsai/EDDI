/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Date and time tool for timezone conversions, calculations, and formatting.
 */
@ApplicationScoped
public class DateTimeTool {
    private static final Logger LOGGER = Logger.getLogger(DateTimeTool.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;
    private static final DateTimeFormatter READABLE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

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
    public String convertTimezone(@P("Local date-time in ISO-8601 form, e.g. '2025-11-03T10:30:00'") String dateTime,
                                  @P("Timezone the date-time is expressed in (IANA name)") String fromTimezone,
                                  @P("Timezone to convert to (IANA name)") String toTimezone) {

        try {
            ZoneId fromZone = ZoneId.of(fromTimezone);
            ZoneId toZone = ZoneId.of(toTimezone);

            LocalDateTime localDateTime = LocalDateTime.parse(dateTime, ISO_FORMATTER);
            ZonedDateTime fromZdt = ZonedDateTime.of(localDateTime, fromZone);
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
            LocalDateTime start = LocalDateTime.parse(startDateTime, ISO_FORMATTER);
            LocalDateTime end = LocalDateTime.parse(endDateTime, ISO_FORMATTER);

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
            LocalDateTime localDateTime = LocalDateTime.parse(dateTime, ISO_FORMATTER);
            ZoneId zoneId = ZoneId.of(timezone);

            LocalDateTime result;
            switch (unit.toLowerCase()) {
                case "years" :
                    result = localDateTime.plusYears(amount);
                    break;
                case "months" :
                    result = localDateTime.plusMonths(amount);
                    break;
                case "weeks" :
                    result = localDateTime.plusWeeks(amount);
                    break;
                case "days" :
                    result = localDateTime.plusDays(amount);
                    break;
                case "hours" :
                    result = localDateTime.plusHours(amount);
                    break;
                case "minutes" :
                    result = localDateTime.plusMinutes(amount);
                    break;
                case "seconds" :
                    result = localDateTime.plusSeconds(amount);
                    break;
                default :
                    return "Error: Invalid unit. Use 'years', 'months', 'weeks', 'days', 'hours', 'minutes', or 'seconds'.";
            }

            ZonedDateTime zonedResult = ZonedDateTime.of(result, zoneId);
            String formatted = zonedResult.format(READABLE_FORMATTER);
            LOGGER.debugf("Added %d %s to %s: %s", amount, sanitize(unit), sanitize(dateTime), formatted);
            return formatted;

        } catch (DateTimeException e) {
            LOGGER.debugf("Date calculation rejected: %s", sanitize(e.getMessage()));
            return "Error: " + e.getMessage();
        }
    }

    @Tool("Formats a date/time string into a different format")
    public String formatDateTime(@P("Local date-time in ISO-8601 form, e.g. '2025-11-03T10:30:00'") String dateTime,
                                 @P("java.time format pattern, e.g. 'dd.MM.yyyy HH:mm' or 'EEEE, MMMM d'") String pattern,
                                 @P("Timezone of the date-time (IANA name)") String timezone) {

        try {
            LocalDateTime localDateTime = LocalDateTime.parse(dateTime, ISO_FORMATTER);
            ZoneId zoneId = ZoneId.of(timezone);
            ZonedDateTime zonedDateTime = ZonedDateTime.of(localDateTime, zoneId);

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

    @Tool("Lists all available timezone names")
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
