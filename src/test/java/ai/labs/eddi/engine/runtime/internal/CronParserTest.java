/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;
import java.time.DayOfWeek;

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
        assertEquals(DayOfWeek.MONDAY, nextZdt.getDayOfWeek());
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

    /**
     * The result must be a property of the EXPRESSION, not of the moment the
     * request happened to arrive. Measuring only the gap after
     * {@code Instant.now()} made "0 9,10 * * MON" report ~6 days 23 h when
     * submitted on a Monday between its two fires (accepted by the min-interval
     * policy) and 3600 s at any other time (rejectable) — the same configuration
     * passed or failed validation depending on when the operator clicked save.
     * Scanning a horizon and taking the minimum returns the tightest burst the
     * expression can actually produce, which is what the policy is about.
     */
    @Test
    void computeMinIntervalSeconds_findsTheTightestGapWhicheverMomentWeAskFrom() {
        assertEquals(3600, CronParser.computeMinIntervalSeconds("0 9,10 * * MON", UTC));
    }

    @Test
    void computeMinIntervalSeconds_burstWithinAnHour() {
        // 09:00, 09:01, 09:02 then a day's gap — the policy cares about the 60s pair
        assertEquals(60, CronParser.computeMinIntervalSeconds("0,1,2 9 * * *", UTC));
    }

    /**
     * A sparse expression must stop at the scan horizon instead of walking into
     * {@code computeNextFire}'s own two-year limit, which would throw.
     */
    @Test
    void computeMinIntervalSeconds_yearlyExpressionDoesNotThrow() {
        long interval = assertDoesNotThrow(() -> CronParser.computeMinIntervalSeconds("0 0 1 1 *", UTC));
        assertTrue(interval >= 365L * 24 * 3600, "a yearly cron's tightest gap is a year: " + interval);
    }

    /**
     * An expression with a first fire but no SECOND one inside the parser's own
     * two-year horizon has no measurable gap at all, and the scan returns
     * {@link Long#MAX_VALUE} to say so. That sentinel matters: it is what makes
     * such an expression pass the minimum-interval policy, where letting the
     * {@code IllegalStateException} out (the old behaviour) rejected it, and
     * returning 0 would have rejected it even harder.
     * <p>
     * 29 February is the case: it fires once every four years, so from the first
     * fire the next one is provably beyond the horizon. Which branch applies
     * depends on the calendar — for roughly half of any four-year cycle the FIRST
     * fire is out of reach too, and then the expression is unsatisfiable and the
     * exception is correct. The test therefore asks the parser which situation it
     * is in and asserts the matching outcome exactly, rather than skipping half the
     * time or hard-coding an assertion that expires on the next leap day.
     */
    @Test
    void computeMinIntervalSeconds_noSecondFireInTheHorizon_returnsTheSentinelNotZero() {
        String leapDay = "0 0 29 2 *";
        boolean firstFireIsReachable;
        try {
            CronParser.computeNextFire(leapDay, Instant.now(), UTC);
            firstFireIsReachable = true;
        } catch (IllegalStateException e) {
            firstFireIsReachable = false;
        }

        if (firstFireIsReachable) {
            assertEquals(Long.MAX_VALUE, CronParser.computeMinIntervalSeconds(leapDay, UTC),
                    "no second fire within the horizon means no measurable gap — the scan must say so with the "
                            + "sentinel, not throw and not report 0");
        } else {
            assertThrows(IllegalStateException.class, () -> CronParser.computeMinIntervalSeconds(leapDay, UTC),
                    "the next 29 February is beyond the parser's horizon, so there is no fire to measure from "
                            + "at all — that is the unsatisfiable-expression branch, not the sentinel");
        }
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
        assertEquals(DayOfWeek.SUNDAY, next.atZone(UTC).getDayOfWeek());
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
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> CronParser.parseField("*/", 0, 59));
        // Must be a field-aware cron error, not a leaked low-level parse message.
        assertTrue(ex.getMessage().toLowerCase().contains("step") || ex.getMessage().toLowerCase().contains("field"),
                "Expected a field-aware cron error, got: " + ex.getMessage());
    }

    @Test
    void parseField_rejectsNonNumericStep() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> CronParser.parseField("*/abc", 0, 59));
        assertTrue(ex.getMessage().contains("field"), "Expected field context in message, got: " + ex.getMessage());
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
        assertEquals(DayOfWeek.SATURDAY, z.getDayOfWeek());
    }

    @Test
    void computeNextFire_domOrDow_firesOnWeekdayEvenIfNotDayOfMonth() {
        // From 2024-01-01 (a Monday), "0 0 13 * 5" next fires on Fri 2024-01-05 —
        // a Friday that is not the 13th — proving weekday matches independently.
        Instant base = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, UTC).toInstant();
        Instant next = CronParser.computeNextFire("0 0 13 * 5", base, UTC);
        ZonedDateTime z = next.atZone(UTC);
        assertEquals(DayOfWeek.FRIDAY, z.getDayOfWeek());
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

    @Test
    void computeNextFire_starSlashStepInDayField_usesAndNotOr() {
        // "0 0 */2 * 1": */2 day-of-month is "starred" (Vixie DOM_STAR), so this is
        // AND with Mondays, not OR. */2 over 1..31 yields odd days; the next
        // odd-numbered Monday after 2024-01-01 is 2024-01-15. (An OR reading would
        // instead fire on the next odd day, 2024-01-03.)
        Instant base = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, UTC).toInstant();
        Instant next = CronParser.computeNextFire("0 0 */2 * 1", base, UTC);
        assertEquals(ZonedDateTime.of(2024, 1, 15, 0, 0, 0, 0, UTC).toInstant(), next);
    }
}
