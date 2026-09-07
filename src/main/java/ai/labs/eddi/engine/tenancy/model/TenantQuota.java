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
 *            max monthly tool cost budget (-1 = unlimited). <strong>Not
 *            enforced yet.</strong> The gate exists
 *            ({@code TenantQuotaService.checkCostBudget}) but the accumulator
 *            it reads is only written by {@code recordCost}, which has no
 *            production caller — so the recorded cost is always 0 and the gate
 *            always allows. A tenant can be configured with a budget and spend
 *            past it; {@code PUT /administration/quotas/&#123;id&#125;} logs a
 *            warning saying so rather than pretending otherwise.
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
