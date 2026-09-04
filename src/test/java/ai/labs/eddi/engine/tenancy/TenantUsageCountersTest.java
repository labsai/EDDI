/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TenantUsageCounters — increment, reset, and snapshot behavior.
 */
class TenantUsageCountersTest {

    @Test
    @DisplayName("initial counters are zero")
    void initialState() {
        var counters = new TenantUsageCounters();
        assertEquals(0, counters.getConversationsToday());
        assertEquals(0, counters.getApiCallsThisMinute());
        assertEquals(0.0, counters.getMonthlyCostUsd(), 0.001);
    }

    @Test
    @DisplayName("incrementConversations increases counter")
    void incrementConversations() {
        var counters = new TenantUsageCounters();
        counters.incrementConversations();
        counters.incrementConversations();
        assertEquals(2, counters.getConversationsToday());
    }

    @Test
    @DisplayName("incrementApiCalls increases counter")
    void incrementApiCalls() {
        var counters = new TenantUsageCounters();
        counters.incrementApiCalls();
        counters.incrementApiCalls();
        counters.incrementApiCalls();
        assertEquals(3, counters.getApiCallsThisMinute());
    }

    @Test
    @DisplayName("addCost accumulates")
    void addCost() {
        var counters = new TenantUsageCounters();
        counters.addCost(0.05);
        counters.addCost(0.10);
        assertEquals(0.15, counters.getMonthlyCostUsd(), 0.001);
    }

    @Test
    @DisplayName("resetAll clears all counters")
    void resetAll() {
        var counters = new TenantUsageCounters();
        counters.incrementConversations();
        counters.incrementApiCalls();
        counters.addCost(1.0);

        counters.resetAll();

        assertEquals(0, counters.getConversationsToday());
        assertEquals(0, counters.getApiCallsThisMinute());
        assertEquals(0.0, counters.getMonthlyCostUsd(), 0.001);
    }

    @Test
    @DisplayName("toSnapshot produces correct snapshot")
    void toSnapshot() {
        var counters = new TenantUsageCounters();
        counters.incrementConversations();
        counters.incrementApiCalls();
        counters.addCost(0.25);

        UsageSnapshot snapshot = counters.toSnapshot("tenant-1");

        assertEquals("tenant-1", snapshot.tenantId());
        assertEquals(1, snapshot.conversationsToday());
        assertEquals(1, snapshot.apiCallsThisMinute());
        assertEquals(0.25, snapshot.monthlyCostUsd(), 0.001);
        assertNotNull(snapshot.minuteWindowStart());
        assertNotNull(snapshot.dayStart());
        assertNotNull(snapshot.costMonth());
    }

    @Test
    @DisplayName("resetExpiredWindows does not reset within window")
    void resetExpiredWindows_withinWindow() {
        var counters = new TenantUsageCounters();
        counters.incrementConversations();
        counters.incrementApiCalls();
        counters.addCost(0.5);

        // Call immediately — windows haven't expired
        counters.resetExpiredWindows();

        // Within the same minute/day/month — counters should remain
        assertEquals(1, counters.getConversationsToday());
        assertEquals(1, counters.getApiCallsThisMinute());
        assertEquals(0.5, counters.getMonthlyCostUsd(), 0.001);
    }

    /**
     * Finding 15. These counters used to run their windows from the first hit
     * (first call + 60s, first call + 24h) while both DB-backed stores truncate to
     * the calendar minute and UTC day — so identical configuration enforced a
     * different limit depending on which backend was selected, and neither matched
     * the "sliding window" {@code TenantQuota} documented. Calendar alignment is
     * the one semantics a multi-instance deployment can agree on without shared
     * state.
     */
    @Test
    @DisplayName("windows are aligned to the calendar minute and UTC day, not to the first hit")
    void windowsAreCalendarAligned() {
        var clock = new MutableClock(Instant.parse("2026-03-04T12:00:30Z"));
        var counters = new TenantUsageCounters(clock);

        assertEquals(Instant.parse("2026-03-04T12:00:00Z"), counters.toSnapshot("t").minuteWindowStart());
        assertEquals(Instant.parse("2026-03-04T00:00:00Z"), counters.toSnapshot("t").dayStart());
    }

    @Test
    @DisplayName("the minute window rolls at the calendar boundary, not 60s after the first call")
    void minuteWindowRollsAtTheCalendarBoundary() {
        var clock = new MutableClock(Instant.parse("2026-03-04T12:00:30Z"));
        var counters = new TenantUsageCounters(clock);
        counters.incrementApiCalls();

        // 35 seconds later — same calendar minute, so the count stands. Under the
        // old "first hit + 60s" rule it would also have stood, which is the point:
        // the two rules only diverge at the boundary.
        clock.set(Instant.parse("2026-03-04T12:00:55Z"));
        counters.resetExpiredWindows();
        assertEquals(1, counters.getApiCallsThisMinute());

        // 5 seconds later still, but a new calendar minute: the window rolls here,
        // where the DB-backed stores also roll it. "First hit + 60s" would have
        // waited until 12:01:30.
        clock.set(Instant.parse("2026-03-04T12:01:00Z"));
        counters.resetExpiredWindows();
        assertEquals(0, counters.getApiCallsThisMinute());
        assertEquals(Instant.parse("2026-03-04T12:01:00Z"), counters.toSnapshot("t").minuteWindowStart());
    }

    @Test
    @DisplayName("the daily window rolls at UTC midnight")
    void dayWindowRollsAtUtcMidnight() {
        var clock = new MutableClock(Instant.parse("2026-03-04T23:59:00Z"));
        var counters = new TenantUsageCounters(clock);
        counters.incrementConversations();

        clock.set(Instant.parse("2026-03-05T00:00:00Z"));
        counters.resetExpiredWindows();

        assertEquals(0, counters.getConversationsToday());
        assertEquals(Instant.parse("2026-03-05T00:00:00Z"), counters.toSnapshot("t").dayStart());
    }

    /** A {@link Clock} whose "now" a test can step forward deliberately. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("multiple increments and snapshot")
    void multipleIncrementsAndSnapshot() {
        var counters = new TenantUsageCounters();
        for (int i = 0; i < 100; i++) {
            counters.incrementApiCalls();
        }
        for (int i = 0; i < 10; i++) {
            counters.incrementConversations();
        }
        counters.addCost(99.99);

        UsageSnapshot snapshot = counters.toSnapshot("heavy-user");
        assertEquals(100, snapshot.apiCallsThisMinute());
        assertEquals(10, snapshot.conversationsToday());
        assertEquals(99.99, snapshot.monthlyCostUsd(), 0.001);
    }
}
