/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy.model;

import com.fasterxml.jackson.annotation.JsonFormat;

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
 *            accumulated priced spend this month (USD): LLM tokens at the
 *            configured prices plus tracked tool cost
 * @param minuteWindowStart
 *            start of the current per-minute window (calendar-aligned, not
 *            sliding)
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
        // Without an explicit shape, Jackson's YearMonthSerializer takes the
        // useTimestamp branch under quarkus.jackson.write-dates-as-timestamps=true
        // and emits the ARRAY [2026,7] rather than "2026-07". Both quota stores
        // already persist this as an ISO string (YearMonth.toString() /
        // YearMonth.parse), so only the REST representation disagreed.
        @JsonFormat(shape = JsonFormat.Shape.STRING) YearMonth costMonth,
        // Budget set aside for turns still running (opt-in hard ceiling); 0 when the
        // mode is off. The stored total: it can still include the hold of a node that
        // died until the next reservation drops it past the TTL
        // (docs/tenant-quotas.md).
        double reservedUsd) {

    /**
     * A snapshot with nothing reserved - what a store without reservations reports.
     */
    public UsageSnapshot(String tenantId, int conversationsToday, int apiCallsThisMinute, double monthlyCostUsd, Instant minuteWindowStart,
            Instant dayStart, YearMonth costMonth) {
        this(tenantId, conversationsToday, apiCallsThisMinute, monthlyCostUsd, minuteWindowStart, dayStart, costMonth, 0.0);
    }

    /**
     * Creates an empty snapshot (zero counters) for an unknown tenant.
     */
    public static UsageSnapshot empty(String tenantId) {
        Instant now = Instant.now();
        return new UsageSnapshot(tenantId, 0, 0, 0.0, now, now, YearMonth.now(ZoneOffset.UTC));
    }
}
