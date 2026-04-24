/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy.model;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Immutable snapshot of tenant usage counters at a point in time. Returned by
 * the REST API and used for admin dashboards.
 *
 * @param tenantId
 *            tenant identifier
 * @param conversationsToday
 *            conversations started in the current daily window
 * @param apiCallsThisMinute
 *            API calls in the current minute window
 * @param monthlyCostUsd
 *            accumulated tool cost this month (USD)
 * @param minuteWindowStart
 *            start of the current per-minute sliding window
 * @param dayStart
 *            start of the current daily window
 * @param costMonth
 *            the calendar month (UTC) that {@code monthlyCostUsd} applies to
 */
public record UsageSnapshot(
        String tenantId,
        int conversationsToday,
        int apiCallsThisMinute,
        double monthlyCostUsd,
        Instant minuteWindowStart,
        Instant dayStart,
        YearMonth costMonth) {

    /**
     * Creates an empty snapshot (zero counters) for an unknown tenant.
     */
    public static UsageSnapshot empty(String tenantId) {
        Instant now = Instant.now();
        return new UsageSnapshot(tenantId, 0, 0, 0.0, now, now, YearMonth.now(ZoneOffset.UTC));
    }
}
