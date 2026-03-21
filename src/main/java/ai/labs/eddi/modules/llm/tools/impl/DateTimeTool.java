package ai.labs.eddi.modules.llm.tools.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Date and time tool for timezone conversions, calculations, and formatting.
 */
@ApplicationScoped
public class DateTimeTool {
    private static final Logger LOGGER = Logger.getLogger(DateTimeTool.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;
    private static final DateTimeFormatter READABLE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    @Tool("Gets the current date and time in a specified timezone. Returns formatted date/time string.")
    public String getCurrentDateTime(
            @P("timezone") String timezone) {

        try {
            ZoneId zoneId = ZoneId.of(timezone);
            ZonedDateTime now = ZonedDateTime.now(zoneId);

            String result = now.format(READABLE_FORMATTER);
            LOGGER.debug("Current time in " + timezone + ": " + result);
            return result;

        } catch (DateTimeException e) {
            LOGGER.error("Invalid timezone: " + timezone);
            return "Error: Invalid timezone '" + timezone + "'. Use standard timezone names like 'America/New_York' or 'UTC'.";
        }
    }

    @Tool("Converts a date/time from one timezone to another")
    public String convertTimezone(
            @P("dateTime") String dateTime,
            @P("fromTimezone") String fromTimezone,
            @P("toTimezone") String toTimezone) {

        try {
            ZoneId fromZone = ZoneId.of(fromTimezone);
            ZoneId toZone = ZoneId.of(toTimezone);

            LocalDateTime localDateTime = LocalDateTime.parse(dateTime, ISO_FORMATTER);
            ZonedDateTime fromZdt = ZonedDateTime.of(localDateTime, fromZone);
            ZonedDateTime toZdt = fromZdt.withZoneSameInstant(toZone);

            String result = toZdt.format(READABLE_FORMATTER);
            LOGGER.info("Converted " + dateTime + " from " + fromTimezone + " to " + toTimezone + ": " + result);
            return result;

        } catch (DateTimeException e) {
            LOGGER.error("Timezone conversion error: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    @Tool("Calculates the difference between two dates/times")
    public String calculateDateDifference(
            @P("startDateTime") String startDateTime,
            @P("endDateTime") String endDateTime,
            @P("unit") String unit) {

        try {
            LocalDateTime start = LocalDateTime.parse(startDateTime, ISO_FORMATTER);
            LocalDateTime end = LocalDateTime.parse(endDateTime, ISO_FORMATTER);

            long difference;
            String unitName;

            switch (unit.toLowerCase()) {
                case "days":
                    difference = ChronoUnit.DAYS.between(start, end);
                    unitName = "days";
                    break;
                case "hours":
                    difference = ChronoUnit.HOURS.between(start, end);
                    unitName = "hours";
                    break;
                case "minutes":
                    difference = ChronoUnit.MINUTES.between(start, end);
                    unitName = "minutes";
                    break;
                case "seconds":
                    difference = ChronoUnit.SECONDS.between(start, end);
                    unitName = "seconds";
                    break;
                default:
                    return "Error: Invalid unit. Use 'days', 'hours', 'minutes', or 'seconds'.";
            }

            String result = "Difference: " + difference + " " + unitName;
            LOGGER.debug(result);
            return result;

        } catch (DateTimeParseException e) {
            LOGGER.error("Date parsing error: " + e.getMessage());
            return "Error: Invalid date format. Use ISO format like '2025-11-03T10:30:00'.";
        }
    }

    @Tool("Adds or subtracts time from a date")
    public String addTime(
            @P("dateTime") String dateTime,
            @P("amount") long amount,
            @P("unit") String unit,
            @P("timezone") String timezone) {

        try {
            LocalDateTime localDateTime = LocalDateTime.parse(dateTime, ISO_FORMATTER);
            ZoneId zoneId = ZoneId.of(timezone);

            LocalDateTime result;
            switch (unit.toLowerCase()) {
                case "years":
                    result = localDateTime.plusYears(amount);
                    break;
                case "months":
                    result = localDateTime.plusMonths(amount);
                    break;
                case "weeks":
                    result = localDateTime.plusWeeks(amount);
                    break;
                case "days":
                    result = localDateTime.plusDays(amount);
                    break;
                case "hours":
                    result = localDateTime.plusHours(amount);
                    break;
                case "minutes":
                    result = localDateTime.plusMinutes(amount);
                    break;
                case "seconds":
                    result = localDateTime.plusSeconds(amount);
                    break;
                default:
                    return "Error: Invalid unit. Use 'years', 'months', 'weeks', 'days', 'hours', 'minutes', or 'seconds'.";
            }

            ZonedDateTime zonedResult = ZonedDateTime.of(result, zoneId);
            String formatted = zonedResult.format(READABLE_FORMATTER);
            LOGGER.debug("Added " + amount + " " + unit + " to " + dateTime + ": " + formatted);
            return formatted;

        } catch (DateTimeException e) {
            LOGGER.error("Date calculation error: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    @Tool("Formats a date/time string into a different format")
    public String formatDateTime(
            @P("dateTime") String dateTime,
            @P("pattern") String pattern,
            @P("timezone") String timezone) {

        try {
            LocalDateTime localDateTime = LocalDateTime.parse(dateTime, ISO_FORMATTER);
            ZoneId zoneId = ZoneId.of(timezone);
            ZonedDateTime zonedDateTime = ZonedDateTime.of(localDateTime, zoneId);

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            String result = zonedDateTime.format(formatter);

            LOGGER.debug("Formatted " + dateTime + " as: " + result);
            return result;

        } catch (DateTimeException e) {
            LOGGER.error("Date formatting error: " + e.getMessage());
            return "Error: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            LOGGER.error("Invalid format pattern: " + pattern);
            return "Error: Invalid format pattern '" + pattern + "'.";
        }
    }

    @Tool("Lists all available timezone names")
    public String listTimezones() {
        StringBuilder sb = new StringBuilder("Available timezones:\n");

        // Get major timezones
        String[] majorTimezones = {
            "UTC", "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles",
            "Europe/London", "Europe/Paris", "Europe/Berlin", "Europe/Rome",
            "Asia/Tokyo", "Asia/Shanghai", "Asia/Hong_Kong", "Asia/Singapore",
            "Australia/Sydney", "Pacific/Auckland"
        };

        for (String tz : majorTimezones) {
            ZoneId zoneId = ZoneId.of(tz);
            ZonedDateTime now = ZonedDateTime.now(zoneId);
            sb.append("- ").append(tz).append(" (").append(now.format(DateTimeFormatter.ofPattern("HH:mm"))).append(")\n");
        }

        sb.append("\nFor a complete list, use standard timezone names like 'Continent/City'.");
        return sb.toString();
    }
}

