/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Mutable per-tenant usage counters. Package-private — used only by
 * {@link InMemoryTenantQuotaStore} under its per-tenant lock.
 * <p>
 * All fields are plain (non-atomic) because callers synchronize externally.
 * This makes the atomic check+increment semantics explicit at the store level
 * rather than hiding them behind AtomicInteger's CAS operations.
 */
class TenantUsageCounters {

    private static final Duration MINUTE_WINDOW = Duration.ofMinutes(1);
    private static final Duration DAY_WINDOW = Duration.ofDays(1);

    private int conversationsToday;
    private int apiCallsThisMinute;
    private double monthlyCostUsd;
    private Instant minuteWindowStart;
    private Instant dayStart;
    private YearMonth costMonth;

    TenantUsageCounters() {
        Instant now = Instant.now();
        this.minuteWindowStart = now;
        this.dayStart = now;
        this.costMonth = YearMonth.now(ZoneOffset.UTC);
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
     * cost accumulator (on calendar month boundary, UTC).
     */
    void resetExpiredWindows() {
        Instant now = Instant.now();
        if (Duration.between(minuteWindowStart, now).compareTo(MINUTE_WINDOW) > 0) {
            apiCallsThisMinute = 0;
            minuteWindowStart = now;
        }
        if (Duration.between(dayStart, now).compareTo(DAY_WINDOW) > 0) {
            conversationsToday = 0;
            dayStart = now;
        }
        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        if (!currentMonth.equals(costMonth)) {
            monthlyCostUsd = 0.0;
            costMonth = currentMonth;
        }
    }

    void resetAll() {
        conversationsToday = 0;
        apiCallsThisMinute = 0;
        monthlyCostUsd = 0.0;
        Instant now = Instant.now();
        minuteWindowStart = now;
        dayStart = now;
        costMonth = YearMonth.now(ZoneOffset.UTC);
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
