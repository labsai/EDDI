/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the 5-field cron parser.
 */
class CronParserTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    // --- Validation ---

    @Test
    void validate_validExpressions() {
        assertDoesNotThrow(() -> CronParser.validate("* * * * *"));
        assertDoesNotThrow(() -> CronParser.validate("0 9 * * MON-FRI"));
        assertDoesNotThrow(() -> CronParser.validate("*/15 * * * *"));
        assertDoesNotThrow(() -> CronParser.validate("0 0 1 * *"));
        assertDoesNotThrow(() -> CronParser.validate("30 6 * * 1,3,5"));
        assertDoesNotThrow(() -> CronParser.validate("0 */4 * * *"));
        assertDoesNotThrow(() -> CronParser.validate("0 9 1-15 * *"));
    }

    @Test
    void validate_rejectsTooFewFields() {
        assertThrows(IllegalArgumentException.class, () -> CronParser.validate("* * *"));
    }

    @Test
    void validate_rejectsTooManyFields() {
        assertThrows(IllegalArgumentException.class, () -> CronParser.validate("0 0 * * * *"));
    }

    @Test
    void validate_rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> CronParser.validate("60 * * * *")); // minute > 59
        assertThrows(IllegalArgumentException.class, () -> CronParser.validate("* 25 * * *")); // hour > 23
    }

    @Test
    void validate_rejectsEmptyExpression() {
        assertThrows(IllegalArgumentException.class, () -> CronParser.validate(""));
        assertThrows(IllegalArgumentException.class, () -> CronParser.validate(null));
    }

    // --- Field Parsing ---

    @Test
    void parseField_star() {
        Set<Integer> result = CronParser.parseField("*", 0, 5);
        assertEquals(Set.of(0, 1, 2, 3, 4, 5), result);
    }

    @Test
    void parseField_singleValue() {
        assertEquals(Set.of(5), CronParser.parseField("5", 0, 59));
    }

    @Test
    void parseField_list() {
        assertEquals(Set.of(1, 3, 5), CronParser.parseField("1,3,5", 0, 6));
    }

    @Test
    void parseField_range() {
        assertEquals(Set.of(1, 2, 3, 4, 5), CronParser.parseField("1-5", 0, 6));
    }

    @Test
    void parseField_step() {
        assertEquals(Set.of(0, 15, 30, 45), CronParser.parseField("*/15", 0, 59));
    }

    @Test
    void parseField_rangeWithStep() {
        assertEquals(Set.of(1, 6, 11), CronParser.parseField("1-15/5", 0, 59));
    }

    // --- Next Fire Computation ---

    @Test
    void computeNextFire_everyMinute() {
        Instant now = ZonedDateTime.of(2026, 3, 20, 10, 30, 0, 0, UTC).toInstant();
        Instant next = CronParser.computeNextFire("* * * * *", now, UTC);
        assertEquals(ZonedDateTime.of(2026, 3, 20, 10, 31, 0, 0, UTC).toInstant(), next);
    }

    @Test
    void computeNextFire_dailyAt9() {
        Instant now = ZonedDateTime.of(2026, 3, 20, 10, 0, 0, 0, UTC).toInstant();
        Instant next = CronParser.computeNextFire("0 9 * * *", now, UTC);
        // Should be next day at 9:00
        assertEquals(ZonedDateTime.of(2026, 3, 21, 9, 0, 0, 0, UTC).toInstant(), next);
    }

    @Test
    void computeNextFire_weekdayMorning() {
        // Friday March 20 at 10:00 → next fire should be Monday March 23 at 9:00
        Instant friday = ZonedDateTime.of(2026, 3, 20, 10, 0, 0, 0, UTC).toInstant();
        Instant next = CronParser.computeNextFire("0 9 * * MON-FRI", friday, UTC);
        ZonedDateTime nextZdt = next.atZone(UTC);
        assertEquals(9, nextZdt.getHour());
        assertEquals(0, nextZdt.getMinute());
        // Should be Monday
        assertEquals(java.time.DayOfWeek.MONDAY, nextZdt.getDayOfWeek());
    }

    @Test
    void computeNextFire_every15min() {
        Instant now = ZonedDateTime.of(2026, 3, 20, 10, 7, 0, 0, UTC).toInstant();
        Instant next = CronParser.computeNextFire("*/15 * * * *", now, UTC);
        assertEquals(ZonedDateTime.of(2026, 3, 20, 10, 15, 0, 0, UTC).toInstant(), next);
    }

    @Test
    void computeNextFire_firstOfMonth() {
        Instant now = ZonedDateTime.of(2026, 3, 15, 0, 0, 0, 0, UTC).toInstant();
        Instant next = CronParser.computeNextFire("0 0 1 * *", now, UTC);
        assertEquals(ZonedDateTime.of(2026, 4, 1, 0, 0, 0, 0, UTC).toInstant(), next);
    }

    @Test
    void computeNextFire_withTimeZone() {
        ZoneId vienna = ZoneId.of("Europe/Vienna");
        Instant now = ZonedDateTime.of(2026, 3, 20, 8, 0, 0, 0, vienna).toInstant();
        Instant next = CronParser.computeNextFire("0 9 * * *", now, vienna);
        ZonedDateTime nextVienna = next.atZone(vienna);
        assertEquals(9, nextVienna.getHour());
        assertEquals(20, nextVienna.getDayOfMonth());
    }

    @Test
    void computeNextFire_every4hours() {
        Instant now = ZonedDateTime.of(2026, 3, 20, 5, 0, 0, 0, UTC).toInstant();
        Instant next = CronParser.computeNextFire("0 */4 * * *", now, UTC);
        assertEquals(ZonedDateTime.of(2026, 3, 20, 8, 0, 0, 0, UTC).toInstant(), next);
    }

    // --- Min Interval ---

    @Test
    void computeMinIntervalSeconds_everyMinute() {
        long interval = CronParser.computeMinIntervalSeconds("* * * * *", UTC);
        assertEquals(60, interval);
    }

    @Test
    void computeMinIntervalSeconds_every15min() {
        long interval = CronParser.computeMinIntervalSeconds("*/15 * * * *", UTC);
        assertEquals(900, interval); // 15 * 60
    }

    // --- Day-of-week 7 = Sunday (standard cron compatibility) ---

    @Test
    void validate_acceptsDayOfWeek7AsSunday() {
        assertDoesNotThrow(() -> CronParser.validate("0 0 * * 7"));
    }

    @Test
    void computeNextFire_dayOfWeek7MatchesSunday() {
        // 2024-01-06 is a Saturday; the next Sunday is 2024-01-07.
        Instant saturday = ZonedDateTime.of(2024, 1, 6, 12, 0, 0, 0, UTC).toInstant();
        Instant next = CronParser.computeNextFire("0 0 * * 7", saturday, UTC);
        assertEquals(java.time.DayOfWeek.SUNDAY, next.atZone(UTC).getDayOfWeek());
    }

    @Test
    void computeNextFire_dayOfWeek0AndDayOfWeek7AgreeOnSunday() {
        Instant base = ZonedDateTime.of(2024, 1, 6, 12, 0, 0, 0, UTC).toInstant();
        assertEquals(CronParser.computeNextFire("0 0 * * 0", base, UTC), CronParser.computeNextFire("0 0 * * 7", base, UTC));
    }

    // --- Malformed-field rejection (clean errors, not AIOOBE / silent never-fire)
    // ---

    @Test
    void parseField_rejectsReversedRange() {
        assertThrows(IllegalArgumentException.class, () -> CronParser.parseField("5-1", 0, 59));
    }

    @Test
    void parseField_rejectsMalformedStep() {
        assertThrows(IllegalArgumentException.class, () -> CronParser.parseField("*/", 0, 59));
    }

    // --- Standard cron dom/dow OR semantics (both fields restricted) ---

    @Test
    void computeNextFire_domOrDow_firesOnDayOfMonthEvenIfNotWeekday() {
        // "0 0 13 * 5" = midnight on the 13th OR any Friday. 2024-01-13 is a Saturday.
        // From the 12th (a Friday) at noon, the next fire is the 13th at 00:00 —
        // proving day-of-month matches independently of weekday (OR, not AND).
        Instant base = ZonedDateTime.of(2024, 1, 12, 12, 0, 0, 0, UTC).toInstant();
        Instant next = CronParser.computeNextFire("0 0 13 * 5", base, UTC);
        ZonedDateTime z = next.atZone(UTC);
        assertEquals(13, z.getDayOfMonth());
        assertEquals(java.time.DayOfWeek.SATURDAY, z.getDayOfWeek());
    }

    @Test
    void computeNextFire_domOrDow_firesOnWeekdayEvenIfNotDayOfMonth() {
        // From 2024-01-01 (a Monday), "0 0 13 * 5" next fires on Fri 2024-01-05 —
        // a Friday that is not the 13th — proving weekday matches independently.
        Instant base = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, UTC).toInstant();
        Instant next = CronParser.computeNextFire("0 0 13 * 5", base, UTC);
        ZonedDateTime z = next.atZone(UTC);
        assertEquals(java.time.DayOfWeek.FRIDAY, z.getDayOfWeek());
        assertEquals(5, z.getDayOfMonth());
    }

    @Test
    void computeNextFire_singleDayFieldRestricted_staysAnd() {
        // Only day-of-month restricted (dow is *): must fire strictly on the 1st,
        // not on arbitrary weekdays.
        Instant base = ZonedDateTime.of(2024, 3, 15, 0, 0, 0, 0, UTC).toInstant();
        Instant next = CronParser.computeNextFire("0 0 1 * *", base, UTC);
        assertEquals(ZonedDateTime.of(2024, 4, 1, 0, 0, 0, 0, UTC).toInstant(), next);
    }
}
