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

    /**
     * The one that separates the two measurements at (almost) every instant, and
     * therefore the test that fails if the horizon scan is reverted to
     * {@code second - first}.
     * <p>
     * "0 9,10 * * MON" above states the contract but cannot enforce it on its own:
     * its two fires are an hour apart and the weekly gap only shows up when the
     * request arrives inside that hour, so the old single-gap measurement also
     * answers 3600 for ~99 % of the week. These expressions have THREE fires with
     * UNEQUAL gaps, and the tight pair is the second one: "0,10,11 0 1 1 *" fires
     * on 1 January at 00:00, 00:10 and 00:11, so the gap that follows now is 600 s
     * at every instant except the ten minutes a year between the first two fires,
     * while the tightest gap the expression can produce is 60 s always. Same shape
     * an hour wide for "0 0,6,7 1 1 *": 21600 s measured from now, 3600 s measured
     * over the scan.
     * <p>
     * Both assertions are deterministic on correct code without an injected clock,
     * because the scan is now-independent by construction: it starts at the next
     * fire, and whichever of the three fires that happens to be, the wrap-around
     * brings the tight pair into the scan before the horizon or the 64-fire ceiling
     * stops it. That independence is the whole point of the change, so the test is
     * exactly as stable as the property it pins.
     */
    @Test
    void computeMinIntervalSeconds_isTheTightestGapEvenWhenItIsNotTheGapAfterNow() {
        assertEquals(60, CronParser.computeMinIntervalSeconds("0,10,11 0 1 1 *", UTC),
                "00:00 -> 00:10 -> 00:11: the policy is about the 60 s pair, not the 600 s one that "
                        + "happens to come first");
        assertEquals(3600, CronParser.computeMinIntervalSeconds("0 0,6,7 1 1 *", UTC),
                "00:00 -> 06:00 -> 07:00: the tightest gap is the last pair, 3600 s, not the 21600 s "
                        + "one the old single-gap measurement would report");
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
     * The measurement must not depend on the year in which it is taken.
     * <p>
     * This is the case that survived the first fix. With Vixie DOM/DOW OR semantics
     * {@code 0 0 29 2 MON} fires on every February Monday AND on 29 February, so
     * its tightest pair only exists in a leap year whose 29 February follows a
     * Monday — 2028 is one (29 February 2028 is a Tuesday), giving a gap of exactly
     * one day. A scan bounded to the next 366 days or 64 fires simply never reaches
     * such a year from most starting points and reports the weekly gap instead, so
     * the same expression sat either side of the minimum-interval threshold
     * depending on the current date. Deriving the minimum over a fixed 28-year
     * calendar cycle makes the answer a property of the expression.
     */
    @Test
    void computeMinIntervalSeconds_seesAnAlignmentThatIsYearsAway() {
        assertEquals(86400, CronParser.computeMinIntervalSeconds("0 0 29 2 MON", UTC),
                "DOM/DOW OR semantics put 29 February next to a Monday in 2028 — one day apart, "
                        + "whatever year the validation happens to run in");
    }

    /**
     * The same property from the other side: an expression whose only pair is four
     * years apart reports that, deterministically, instead of the
     * {@link Long#MAX_VALUE} sentinel it used to give whenever the next leap day
     * happened to fall outside the horizon (and an {@code IllegalStateException}
     * for the roughly half of the cycle where even the FIRST fire did).
     */
    @Test
    void computeMinIntervalSeconds_leapDayIsFourYearsNotASentinel() {
        assertEquals(1461L * 86400, CronParser.computeMinIntervalSeconds("0 0 29 2 *", UTC),
                "29 February to 29 February is 1461 days, in every year this is asked in");
    }

    /**
     * A syntactically valid expression that can never match a calendar day is an
     * unsatisfiable one, and must say so — {@code RestScheduleStore} turns this
     * into a 400 rather than saving a schedule that would never fire.
     */
    @Test
    void computeMinIntervalSeconds_unsatisfiableExpressionThrows() {
        assertThrows(IllegalStateException.class, () -> CronParser.computeMinIntervalSeconds("0 0 30 2 *", UTC),
                "30 February matches no day in any calendar cycle");
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
