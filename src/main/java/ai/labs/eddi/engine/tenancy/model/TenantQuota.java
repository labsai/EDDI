package ai.labs.eddi.engine.tenancy.model;

/**
 * Quota configuration for a tenant.
 * Defines resource limits and rate caps.
 * Use -1 for any limit to indicate "unlimited".
 *
 * @param tenantId               tenant identifier ("default" for single-tenant mode)
 * @param maxConversationsPerDay max new conversations per day (-1 = unlimited)
 * @param maxBotsPerTenant       max deployed bots (-1 = unlimited)
 * @param maxApiCallsPerMinute   sliding window rate limit for say/sayStreaming (-1 = unlimited)
 * @param maxMonthlyCostUsd      max monthly tool cost budget (-1 = unlimited)
 * @param enabled                whether quota enforcement is active for this tenant
 */
public record TenantQuota(
        String tenantId,
        int maxConversationsPerDay,
        int maxBotsPerTenant,
        int maxApiCallsPerMinute,
        double maxMonthlyCostUsd,
        boolean enabled
) {
    /**
     * Create a default "unlimited" quota (all limits = -1, disabled).
     */
    public static TenantQuota unlimited(String tenantId) {
        return new TenantQuota(tenantId, -1, -1, -1, -1.0, false);
    }

}
