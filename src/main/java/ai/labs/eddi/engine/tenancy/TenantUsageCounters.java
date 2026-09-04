/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Mutable per-tenant usage counters. Package-private — used only by
 * {@link InMemoryTenantQuotaStore} under its per-tenant lock.
 * <p>
 * All fields are plain (non-atomic) because callers synchronize externally.
 * This makes the atomic check+increment semantics explicit at the store level
 * rather than hiding them behind AtomicInteger's CAS operations.
 * <p>
 * <strong>Windows are wall-clock aligned</strong> —
 * {@code truncatedTo(MINUTES)}, {@code truncatedTo(DAYS)}, {@code YearMonth} —
 * which is what the two DB-backed stores have always used. These counters used
 * to run their windows from the first hit instead (first call + 60s, first call
 * + 24h), so identical configuration enforced a different limit depending on
 * which backend was selected, and neither of the two matched the "sliding
 * window" the model documented. Calendar alignment is the one semantics a
 * multi-instance deployment can agree on without shared state.
 */
class TenantUsageCounters {

    private int conversationsToday;
    private int apiCallsThisMinute;
    private double monthlyCostUsd;
    private Instant minuteWindowStart;
    private Instant dayStart;
    private YearMonth costMonth;

    /**
     * Source of "now". Production always gets {@link Clock#systemUTC()}; a test
     * pins it so a window boundary is something it can step across deliberately
     * rather than occasionally trip over. Same reasoning as the DB-backed stores.
     */
    private final Clock clock;

    TenantUsageCounters() {
        this(Clock.systemUTC());
    }

    TenantUsageCounters(Clock clock) {
        this.clock = clock;
        Instant now = clock.instant();
        this.minuteWindowStart = now.truncatedTo(ChronoUnit.MINUTES);
        this.dayStart = now.truncatedTo(ChronoUnit.DAYS);
        this.costMonth = YearMonth.now(clock.withZone(ZoneOffset.UTC));
    }

    int getConversationsToday() {
        return conversationsToday;
    }

    int getApiCallsThisMinute() {
        return apiCallsThisMinute;
    }

    double getMonthlyCostUsd() {
        return monthlyCostUsd;
    }

    void incrementConversations() {
        conversationsToday++;
    }

    void incrementApiCalls() {
        apiCallsThisMinute++;
    }

    void addCost(double cost) {
        monthlyCostUsd += cost;
    }

    /**
     * Reset expired time windows. Must be called inside the same synchronized block
     * as the check+increment to ensure atomicity.
     * <p>
     * Resets: per-minute API call counter, daily conversation counter, and monthly
     * cost accumulator — all on calendar boundaries in UTC, matching the DB-backed
     * stores.
     */
    void resetExpiredWindows() {
        Instant now = clock.instant();
        Instant currentMinuteStart = now.truncatedTo(ChronoUnit.MINUTES);
        if (minuteWindowStart.isBefore(currentMinuteStart)) {
            apiCallsThisMinute = 0;
            minuteWindowStart = currentMinuteStart;
        }
        Instant currentDayStart = now.truncatedTo(ChronoUnit.DAYS);
        if (dayStart.isBefore(currentDayStart)) {
            conversationsToday = 0;
            dayStart = currentDayStart;
        }
        YearMonth currentMonth = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        if (!currentMonth.equals(costMonth)) {
            monthlyCostUsd = 0.0;
            costMonth = currentMonth;
        }
    }

    void resetAll() {
        conversationsToday = 0;
        apiCallsThisMinute = 0;
        monthlyCostUsd = 0.0;
        Instant now = clock.instant();
        minuteWindowStart = now.truncatedTo(ChronoUnit.MINUTES);
        dayStart = now.truncatedTo(ChronoUnit.DAYS);
        costMonth = YearMonth.now(clock.withZone(ZoneOffset.UTC));
    }

    /**
     * Create an immutable snapshot of current counters.
     *
     * @param tenantId
     *            the tenant identifier (callers know this from the map lookup)
     */
    UsageSnapshot toSnapshot(String tenantId) {
        return new UsageSnapshot(tenantId, conversationsToday, apiCallsThisMinute,
                monthlyCostUsd, minuteWindowStart, dayStart, costMonth);
    }
}
