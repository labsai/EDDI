/*
 * Copyright (c) 2016-2026 EDDI contributors
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
}
