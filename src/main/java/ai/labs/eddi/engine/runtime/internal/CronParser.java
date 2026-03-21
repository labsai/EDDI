package ai.labs.eddi.engine.runtime.internal;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;

/**
 * Lightweight 5-field cron parser (min hour dom month dow).
 * <p>
 * No external library needed — handles:
 * <ul>
 *   <li>{@code *} — any value</li>
 *   <li>{@code 5} — exact value</li>
 *   <li>{@code 1,3,5} — list</li>
 *   <li>{@code 1-5} — range</li>
 *   <li>{@code * /15} — step (without space)</li>
 *   <li>{@code MON-FRI} — day-of-week names</li>
 * </ul>
 *
 * @author ginccc
 * @since 6.0.0
 */
public final class CronParser {

    static final Map<String, String> DOW_NAMES = Map.of(
            "SUN", "0", "MON", "1", "TUE", "2", "WED", "3",
            "THU", "4", "FRI", "5", "SAT", "6"
    );

    static final Map<String, String> MONTH_NAMES = Map.ofEntries(
            Map.entry("JAN", "1"), Map.entry("FEB", "2"), Map.entry("MAR", "3"),
            Map.entry("APR", "4"), Map.entry("MAY", "5"), Map.entry("JUN", "6"),
            Map.entry("JUL", "7"), Map.entry("AUG", "8"), Map.entry("SEP", "9"),
            Map.entry("OCT", "10"), Map.entry("NOV", "11"), Map.entry("DEC", "12")
    );

    private CronParser() {
    }

    /**
     * Validate a cron expression. Throws IllegalArgumentException if invalid.
     */
    public static void validate(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            throw new IllegalArgumentException("Cron expression must not be empty");
        }
        String[] parts = cronExpression.trim().split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException(
                    "Cron expression must have exactly 5 fields (min hour dom month dow), got " + parts.length);
        }
        parseField(parts[0], 0, 59);   // minute
        parseField(parts[1], 0, 23);   // hour
        parseField(parts[2], 1, 31);   // day of month
        parseField(substituteNames(parts[3], MONTH_NAMES), 1, 12);  // month
        parseField(substituteNames(parts[4], DOW_NAMES), 0, 6);     // day of week
    }

    /**
     * Compute the next fire time after the given instant, in the given time zone.
     *
     * @param cronExpression 5-field cron expression
     * @param after          compute next fire after this instant
     * @param zoneId         time zone for evaluation
     * @return next fire instant (UTC)
     */
    public static Instant computeNextFire(String cronExpression, Instant after, ZoneId zoneId) {
        String[] parts = cronExpression.trim().split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException("Expected 5 cron fields, got " + parts.length);
        }

        Set<Integer> minutes = parseField(parts[0], 0, 59);
        Set<Integer> hours = parseField(parts[1], 0, 23);
        Set<Integer> daysOfMonth = parseField(parts[2], 1, 31);
        Set<Integer> months = parseField(substituteNames(parts[3], MONTH_NAMES), 1, 12);
        Set<Integer> daysOfWeek = parseField(substituteNames(parts[4], DOW_NAMES), 0, 6);

        // Walk forward minute-by-minute from 'after + 1 minute' (aligned to minute boundary)
        ZonedDateTime candidate = after.atZone(zoneId)
                .withSecond(0).withNano(0)
                .plusMinutes(1);

        // Safety: max 2 years of scanning (covers leap years + DST)
        ZonedDateTime limit = candidate.plusYears(2);

        while (candidate.isBefore(limit)) {
            if (months.contains(candidate.getMonthValue())
                    && daysOfMonth.contains(candidate.getDayOfMonth())
                    && daysOfWeek.contains(candidate.getDayOfWeek().getValue() % 7) // Java DayOfWeek: MON=1..SUN=7
                    && hours.contains(candidate.getHour())
                    && minutes.contains(candidate.getMinute())) {
                return candidate.toInstant();
            }

            // Smart skip: if month doesn't match, jump to next valid month
            if (!months.contains(candidate.getMonthValue())) {
                candidate = skipToNextMonth(candidate, months);
                continue;
            }
            // If day doesn't match, jump to next day
            if (!daysOfMonth.contains(candidate.getDayOfMonth())
                    || !daysOfWeek.contains(candidate.getDayOfWeek().getValue() % 7)) {
                candidate = candidate.plusDays(1).withHour(0).withMinute(0);
                continue;
            }
            // If hour doesn't match, jump to next hour
            if (!hours.contains(candidate.getHour())) {
                candidate = candidate.plusHours(1).withMinute(0);
                continue;
            }
            // Minute doesn't match, step forward
            candidate = candidate.plusMinutes(1);
        }

        throw new IllegalStateException("Could not compute next fire within 2 years for: " + cronExpression);
    }

    /**
     * Compute the minimum interval in seconds between two successive fires.
     * Used for validating the minimum interval policy.
     */
    public static long computeMinIntervalSeconds(String cronExpression, ZoneId zoneId) {
        Instant now = Instant.now();
        Instant first = computeNextFire(cronExpression, now, zoneId);
        Instant second = computeNextFire(cronExpression, first, zoneId);
        return second.getEpochSecond() - first.getEpochSecond();
    }

    // --- Internal helpers ---

    static Set<Integer> parseField(String field, int min, int max) {
        Set<Integer> values = new TreeSet<>();
        for (String part : field.split(",")) {
            part = part.trim();
            if (part.contains("/")) {
                // Step: */15  or  1-30/5
                String[] stepParts = part.split("/");
                int step = Integer.parseInt(stepParts[1]);
                if (step <= 0) throw new IllegalArgumentException("Step must be > 0: " + field);
                int start = min;
                int end = max;
                if (!stepParts[0].equals("*")) {
                    if (stepParts[0].contains("-")) {
                        String[] range = stepParts[0].split("-");
                        start = Integer.parseInt(range[0]);
                        end = Integer.parseInt(range[1]);
                    } else {
                        start = Integer.parseInt(stepParts[0]);
                    }
                }
                for (int i = start; i <= end; i += step) {
                    values.add(i);
                }
            } else if (part.contains("-")) {
                // Range: 1-5
                String[] range = part.split("-");
                int start = Integer.parseInt(range[0]);
                int end = Integer.parseInt(range[1]);
                for (int i = start; i <= end; i++) {
                    values.add(i);
                }
            } else if (part.equals("*")) {
                IntStream.rangeClosed(min, max).forEach(values::add);
            } else {
                values.add(Integer.parseInt(part));
            }
        }

        // Validate bounds
        for (int v : values) {
            if (v < min || v > max) {
                throw new IllegalArgumentException(
                        String.format("Value %d out of range [%d, %d] in field: %s", v, min, max, field));
            }
        }
        return values;
    }

    private static String substituteNames(String field, Map<String, String> names) {
        String result = field.toUpperCase();
        for (Map.Entry<String, String> entry : names.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static ZonedDateTime skipToNextMonth(ZonedDateTime dt, Set<Integer> validMonths) {
        ZonedDateTime candidate = dt.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0);
        int safety = 0;
        while (!validMonths.contains(candidate.getMonthValue()) && safety++ < 24) {
            candidate = candidate.plusMonths(1);
        }
        return candidate;
    }
}
