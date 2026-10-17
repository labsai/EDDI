/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy.model;

/**
 * Quota configuration for a tenant. Defines resource limits and rate caps. Use
 * -1 for any limit to indicate "unlimited".
 *
 * @param tenantId
 *            tenant identifier ("default" for single-tenant mode)
 * @param maxConversationsPerDay
 *            max new conversations per UTC calendar day (-1 = unlimited)
 * @param maxAgentsPerTenant
 *            max deployed agents (-1 = unlimited)
 * @param maxApiCallsPerMinute
 *            max say/sayStreaming calls per UTC calendar minute (-1 =
 *            unlimited). A calendar-aligned window, not a sliding one: that is
 *            what all three stores implement, so a burst spanning a minute
 *            boundary can consume two windows worth of allowance.
 * @param maxMonthlyCostUsd
 *            max priced spend per UTC calendar month, in USD (-1 = unlimited).
 *            Every settled conversation turn charges its {@code audit:cost} —
 *            LLM tokens at the configured
 *            {@code inputPricePer1M}/{@code outputPricePer1M} (or cascade
 *            prices) plus tracked tool cost — and Dream consolidation charges
 *            its priced calls. Once the month's total reaches the limit, LLM
 *            tasks serve their fallback answer instead of calling the model and
 *            tool calls are refused. Only <em>priced</em> spend counts: an
 *            unpriced model meters $0. See {@code docs/tenant-quotas.md} for
 *            the call classes that are not metered.
 * @param enabled
 *            whether quota enforcement is active for this tenant
 */
public record TenantQuota(String tenantId, int maxConversationsPerDay, int maxAgentsPerTenant, int maxApiCallsPerMinute, double maxMonthlyCostUsd,
        boolean enabled) {
    /**
     * Create a default "unlimited" quota (all limits = -1, disabled).
     */
    public static TenantQuota unlimited(String tenantId) {
        return new TenantQuota(tenantId, -1, -1, -1, -1.0, false);
    }

}
