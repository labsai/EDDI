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
}
