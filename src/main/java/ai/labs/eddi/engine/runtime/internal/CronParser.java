/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;

/**
 * Lightweight 5-field cron parser (min hour dom month dow).
 * <p>
 * No external library needed — handles:
 * <ul>
 * <li>{@code *} — any value</li>
 * <li>{@code 5} — exact value</li>
 * <li>{@code 1,3,5} — list</li>
 * <li>{@code 1-5} — range</li>
 * <li>{@code * /15} — step (without space)</li>
 * <li>{@code MON-FRI} — day-of-week names</li>
 * </ul>
 *
 * @author ginccc
 * @since 6.0.0
 */
public final class CronParser {

    static final Map<String, String> DOW_NAMES = Map.of("SUN", "0", "MON", "1", "TUE", "2", "WED", "3", "THU", "4", "FRI", "5", "SAT", "6");

    static final Map<String, String> MONTH_NAMES = Map.ofEntries(Map.entry("JAN", "1"), Map.entry("FEB", "2"), Map.entry("MAR", "3"),
            Map.entry("APR", "4"), Map.entry("MAY", "5"), Map.entry("JUN", "6"), Map.entry("JUL", "7"), Map.entry("AUG", "8"), Map.entry("SEP", "9"),
            Map.entry("OCT", "10"), Map.entry("NOV", "11"), Map.entry("DEC", "12"));

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
            throw new IllegalArgumentException("Cron expression must have exactly 5 fields (min hour dom month dow), got " + parts.length);
        }
        parseField(parts[0], 0, 59); // minute
        parseField(parts[1], 0, 23); // hour
        parseField(parts[2], 1, 31); // day of month
        parseField(substituteNames(parts[3], MONTH_NAMES), 1, 12); // month
        parseField(substituteNames(parts[4], DOW_NAMES), 0, 7); // day of week (0 and 7 both = Sunday)
    }

    /**
     * Compute the next fire time after the given instant, in the given time zone.
     *
     * @param cronExpression
     *            5-field cron expression
     * @param after
     *            compute next fire after this instant
     * @param zoneId
     *            time zone for evaluation
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
        Set<Integer> daysOfWeek = normalizeDaysOfWeek(parseField(substituteNames(parts[4], DOW_NAMES), 0, 7));

        // Standard (Vixie) cron: when BOTH day-of-month and day-of-week are
        // restricted, a day matches if EITHER field matches. A field is "starred"
        // (not restricted) when it begins with "*" — this includes "*/2", matching
        // Vixie's DOM_STAR/DOW_STAR flag. If only one is restricted, the starred
        // field is always true, so the AND below reduces to the restricted field.
        boolean bothDayFieldsRestricted = !parts[2].trim().startsWith("*") && !parts[4].trim().startsWith("*");

        // Walk forward minute-by-minute from 'after + 1 minute' (aligned to minute
        // boundary)
        ZonedDateTime candidate = after.atZone(zoneId).withSecond(0).withNano(0).plusMinutes(1);

        // Safety: max 2 years of scanning (covers leap years + DST)
        ZonedDateTime limit = candidate.plusYears(2);

        while (candidate.isBefore(limit)) {
            boolean dayMatches = dayMatches(candidate.toLocalDate(), daysOfMonth, daysOfWeek, bothDayFieldsRestricted);
            if (months.contains(candidate.getMonthValue()) && dayMatches && hours.contains(candidate.getHour())
                    && minutes.contains(candidate.getMinute())) {
                return candidate.toInstant();
            }

            // Smart skip: if month doesn't match, jump to next valid month
            if (!months.contains(candidate.getMonthValue())) {
                candidate = skipToNextMonth(candidate, months);
                continue;
            }
            // If day doesn't match (per OR/AND semantics above), jump to next day
            if (!dayMatches) {
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
     * The calendar window the day scan below covers: a full 28-year Gregorian
     * cycle, and one that contains no century exception (2100 is not a leap year,
     * so a window spanning it would not repeat).
     * <p>
     * 28 years is the period after which weekday/day-of-month alignments repeat:
     * every one of the 14 year shapes (7 starting weekdays × leap or not) occurs in
     * it, and 29 February — which advances 5 weekdays per leap year — lands on
     * every day of the week across its 7 leap years. So every alignment a cron
     * expression can ever meet is inside this window exactly once per its own
     * cycle, which is what makes the answer a property of the EXPRESSION and not of
     * the year in which someone asks.
     */
    private static final int SCAN_FIRST_YEAR = 2001;

    /** @see #SCAN_FIRST_YEAR */
    private static final int SCAN_LAST_YEAR = 2028;

    /**
     * The <em>smallest</em> gap between two successive fires of this expression, in
     * seconds. Used to enforce the minimum-interval policy.
     * <p>
     * Derived from the parsed fields over a fixed calendar cycle rather than from
     * the fires that follow {@code Instant.now()}, because that measurement is not
     * a property of the expression — it is a property of the moment the request
     * arrived, and the policy it feeds decides whether a configuration may be saved
     * at all.
     * <ul>
     * <li>Measuring the single gap after now made {@code 0 9,10 * * MON} report ~6
     * days 23 h when submitted on a Monday between its two fires (accepted) and
     * 3600 s at any other time (rejectable).</li>
     * <li>Walking a bounded run of fires from now fixed that for dense expressions
     * but not for sparse ones: with Vixie's DOM/DOW OR semantics
     * {@code 0 0 29 2 MON} is weekly for years at a time and one DAY apart in a
     * leap year whose 29 February follows a Monday (2028). A horizon of 366 days
     * simply never saw that year, so the same expression crossed the threshold or
     * not depending on the current one.</li>
     * </ul>
     * So the two halves of the gap are now computed separately, each over its own
     * complete cycle: the tightest pair of times WITHIN a firing day (a property of
     * the minute and hour fields alone), and the tightest gap ACROSS days, from the
     * last time of one firing day to the first time of the next — scanned over
     * {@link #SCAN_FIRST_YEAR}..{@link #SCAN_LAST_YEAR}, which contains every
     * calendar alignment. The cross-day gap is measured as real elapsed time in
     * {@code zoneId}, so a DST transition shortens or lengthens it as it actually
     * would; the within-day gap is local-clock arithmetic, which is what "every 15
     * minutes" means.
     *
     * @return the tightest gap in seconds. {@link Long#MAX_VALUE} is the defensive
     *         answer for an expression with no pair of fires to measure at all; no
     *         5-field expression can actually produce that over a 28-year window
     *         (even 29 February recurs seven times), so in practice every
     *         satisfiable expression returns a real gap — where the old scan
     *         returned the sentinel merely because the horizon was too short to see
     *         the second fire
     * @throws IllegalStateException
     *             if no calendar day in the cycle matches at all (e.g.
     *             {@code 0 0 30 2 *} — 30 February): the expression is
     *             syntactically valid but can never fire
     */
    public static long computeMinIntervalSeconds(String cronExpression, ZoneId zoneId) {
        String[] parts = cronExpression.trim().split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException("Expected 5 cron fields, got " + parts.length);
        }
        Set<Integer> minutes = parseField(parts[0], 0, 59);
        Set<Integer> hours = parseField(parts[1], 0, 23);
        Set<Integer> daysOfMonth = parseField(parts[2], 1, 31);
        Set<Integer> months = parseField(substituteNames(parts[3], MONTH_NAMES), 1, 12);
        Set<Integer> daysOfWeek = normalizeDaysOfWeek(parseField(substituteNames(parts[4], DOW_NAMES), 0, 7));
        boolean bothDayFieldsRestricted = !parts[2].trim().startsWith("*") && !parts[4].trim().startsWith("*");

        // Every firing day fires at the same set of times, so the tightest within-day
        // pair is the same on all of them. parseField returns a TreeSet, so hours and
        // minutes both iterate ascending and this list is already sorted.
        List<Integer> secondsOfDay = new ArrayList<>(hours.size() * minutes.size());
        for (int hour : hours) {
            for (int minute : minutes) {
                secondsOfDay.add(hour * 3600 + minute * 60);
            }
        }
        long min = Long.MAX_VALUE;
        for (int i = 1; i < secondsOfDay.size(); i++) {
            min = Math.min(min, secondsOfDay.get(i) - secondsOfDay.get(i - 1));
        }
        int firstOfDay = secondsOfDay.get(0);
        int lastOfDay = secondsOfDay.get(secondsOfDay.size() - 1);

        LocalDate previousMatch = null;
        for (LocalDate date = LocalDate.of(SCAN_FIRST_YEAR, 1, 1); date.getYear() <= SCAN_LAST_YEAR; date = date.plusDays(1)) {
            if (!months.contains(date.getMonthValue()) || !dayMatches(date, daysOfMonth, daysOfWeek, bothDayFieldsRestricted)) {
                continue;
            }
            if (previousMatch != null) {
                min = Math.min(min, epochSecondAt(date, firstOfDay, zoneId) - epochSecondAt(previousMatch, lastOfDay, zoneId));
            }
            previousMatch = date;
        }
        if (previousMatch == null) {
            throw new IllegalStateException("Cron expression matches no calendar day: " + cronExpression);
        }
        return min;
    }

    /**
     * The instant of {@code secondOfDay} local time on {@code date}, in seconds.
     */
    private static long epochSecondAt(LocalDate date, int secondOfDay, ZoneId zoneId) {
        return ZonedDateTime.of(date, LocalTime.ofSecondOfDay(secondOfDay), zoneId).toEpochSecond();
    }

    // --- Internal helpers ---

    static Set<Integer> parseField(String field, int min, int max) {
        Set<Integer> values = new TreeSet<>();
        for (String part : field.split(",")) {
            part = part.trim();
            if (part.contains("/")) {
                // Step: */15 or 1-30/5
                String[] stepParts = part.split("/");
                if (stepParts.length != 2) {
                    throw new IllegalArgumentException("Invalid step expression '" + part + "' in field: " + field);
                }
                int step = parseIntField(stepParts[1], field);
                if (step <= 0)
                    throw new IllegalArgumentException("Step must be > 0: " + field);
                int start = min;
                int end = max;
                if (!stepParts[0].equals("*")) {
                    if (stepParts[0].contains("-")) {
                        String[] range = stepParts[0].split("-");
                        if (range.length != 2) {
                            throw new IllegalArgumentException("Invalid range expression '" + stepParts[0] + "' in field: " + field);
                        }
                        start = parseIntField(range[0], field);
                        end = parseIntField(range[1], field);
                    } else {
                        start = parseIntField(stepParts[0], field);
                    }
                }
                if (start > end) {
                    throw new IllegalArgumentException("Range start must be <= end ('" + part + "') in field: " + field);
                }
                for (int i = start; i <= end; i += step) {
                    values.add(i);
                }
            } else if (part.contains("-")) {
                // Range: 1-5
                String[] range = part.split("-");
                if (range.length != 2) {
                    throw new IllegalArgumentException("Invalid range expression '" + part + "' in field: " + field);
                }
                int start = parseIntField(range[0], field);
                int end = parseIntField(range[1], field);
                if (start > end) {
                    throw new IllegalArgumentException("Range start must be <= end ('" + part + "') in field: " + field);
                }
                for (int i = start; i <= end; i++) {
                    values.add(i);
                }
            } else if (part.equals("*")) {
                IntStream.rangeClosed(min, max).forEach(values::add);
            } else {
                values.add(parseIntField(part, field));
            }
        }

        // Validate bounds
        for (int v : values) {
            if (v < min || v > max) {
                throw new IllegalArgumentException(String.format("Value %d out of range [%d, %d] in field: %s", v, min, max, field));
            }
        }
        return values;
    }

    /**
     * Parse an integer cron token, wrapping low-level {@link NumberFormatException}
     * into a field-aware {@link IllegalArgumentException} for actionable errors.
     */
    private static int parseIntField(String token, String field) {
        try {
            return Integer.parseInt(token.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number '" + token + "' in field: " + field, e);
        }
    }

    /**
     * Determine whether the candidate's date matches the day-of-month and
     * day-of-week sets, applying standard cron semantics: OR when both fields are
     * restricted, AND otherwise.
     */
    private static boolean dayMatches(LocalDate date, Set<Integer> daysOfMonth, Set<Integer> daysOfWeek, boolean bothRestricted) {
        boolean domMatch = daysOfMonth.contains(date.getDayOfMonth());
        boolean dowMatch = daysOfWeek.contains(date.getDayOfWeek().getValue() % 7); // Java DayOfWeek: MON=1..SUN=7
        return bothRestricted ? (domMatch || dowMatch) : (domMatch && dowMatch);
    }

    /**
     * Normalize day-of-week 7 to 0 (both denote Sunday in standard cron). Java's
     * {@code DayOfWeek.getValue() % 7} yields 0 for Sunday, so a parsed value of 7
     * would otherwise never match.
     */
    private static Set<Integer> normalizeDaysOfWeek(Set<Integer> daysOfWeek) {
        if (!daysOfWeek.contains(7)) {
            return daysOfWeek;
        }
        Set<Integer> normalized = new TreeSet<>(daysOfWeek);
        normalized.remove(7);
        normalized.add(0);
        return normalized;
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
