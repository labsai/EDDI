/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the cron-to-human-readable describer.
 */
class CronDescriberTest {

    @Test
    void describe_everyMinute() {
        assertEquals("Every minute", CronDescriber.describe("* * * * *"));
    }

    @Test
    void describe_every15min() {
        assertEquals("Every 15 minutes", CronDescriber.describe("*/15 * * * *"));
    }

    @Test
    void describe_every4hours() {
        assertEquals("Every 4 hours", CronDescriber.describe("0 */4 * * *"));
    }

    @Test
    void describe_dailyAt9Weekdays() {
        String desc = CronDescriber.describe("0 9 * * MON-FRI");
        assertEquals("At 09:00 on every weekday", desc);
    }

    @Test
    void describe_midnightFirstOfMonth() {
        String desc = CronDescriber.describe("0 0 1 * *");
        assertEquals("At midnight on the 1st of every month", desc);
    }

    @Test
    void describe_specificDays() {
        String desc = CronDescriber.describe("30 6 * * 1,3,5");
        assertTrue(desc.contains("06:30"));
        assertTrue(desc.contains("Monday"));
    }

    @Test
    void describe_invalidExpression() {
        assertEquals("Invalid cron expression (expected 5 fields)", CronDescriber.describe("* *"));
    }

    @Test
    void describe_nullExpression() {
        assertEquals("Invalid cron expression", CronDescriber.describe(null));
    }

    @Test
    void describe_weekends() {
        String desc = CronDescriber.describe("0 10 * * 0,6");
        assertTrue(desc.contains("weekends") || desc.contains("Saturday"));
    }

    /**
     * Standard cron accepts 7 as Sunday, and {@code CronParser.validate} does too,
     * so a schedule with "0 9 * * 7" is validated and stored successfully. The
     * describer parsed day-of-week as 0..6 and threw
     * {@code IllegalArgumentException} on it — and
     * {@code RestScheduleStore.enrichCronDescription} runs on every create AND
     * every read, so the client got a 400 for a schedule that had in fact been
     * created, and every later GET of it returned 400 too.
     */
    @Test
    void describe_dayOfWeekSeven_isSundayNotAnError() {
        String desc = CronDescriber.describe("0 9 * * 7");

        assertFalse(desc.startsWith("Invalid"), "day-of-week 7 is valid cron for Sunday: " + desc);
        assertTrue(desc.contains("Sunday"), desc);
    }

    @Test
    void describe_dayOfWeekSevenAndZero_namesSundayOnce() {
        String desc = CronDescriber.describe("0 9 * * 0,7");

        assertTrue(desc.contains("Sunday"), desc);
        assertEquals(desc.indexOf("Sunday"), desc.lastIndexOf("Sunday"),
                "7 must normalise onto 0 rather than describing Sunday twice: " + desc);
    }

    /**
     * A description is presentation only and is recomputed on every read, so
     * anything this helper cannot parse must degrade to a string rather than turn a
     * GET of a stored schedule into an error.
     */
    @Test
    void describe_unparseableField_degradesToAStringInsteadOfThrowing() {
        String desc = assertDoesNotThrow(() -> CronDescriber.describe("0 9 * * 99"));
        assertTrue(desc.startsWith("Invalid cron expression"), desc);
    }
}
