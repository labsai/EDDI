package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;

import java.util.List;

/**
 * Store interface for tenant quota configurations and atomic usage tracking.
 * <p>
 * <strong>Atomicity contract:</strong> The {@code tryIncrement*} and
 * {@code tryAddCost} methods MUST be implemented atomically — the check (is the
 * counter within the limit?) and the record (increment the counter) MUST happen
 * as a single indivisible operation. This eliminates the TOCTOU
 * (Time-Of-Check-Time-Of-Use) race that exists when check and record are
 * separate calls.
 * <p>
 * <strong>Cluster safety:</strong> In-memory implementations achieve atomicity
 * via Java synchronization. DB-backed implementations MUST use storage-level
 * atomicity (e.g., {@code UPDATE ... WHERE count < limit RETURNING count} in
 * PostgreSQL, or {@code findAndModify} with conditional update in MongoDB).
 * Java-level synchronization is NOT sufficient for multi-instance deployments.
 */
public interface ITenantQuotaStore {

    // ─── Quota Configuration ───

    /**
     * Get quota configuration for a tenant.
     *
     * @param tenantId
     *            tenant identifier
     * @return the quota config, or null if not found
     */
    TenantQuota getQuota(String tenantId);

    /**
     * Create or update quota configuration for a tenant.
     *
     * @param quota
     *            the quota configuration to store
     */
    void setQuota(TenantQuota quota);

    /**
     * List all configured tenant quotas.
     *
     * @return list of all quotas
     */
    List<TenantQuota> listQuotas();

    /**
     * Delete quota configuration for a tenant.
     *
     * @param tenantId
     *            tenant identifier
     */
    void deleteQuota(String tenantId);

    // ─── Atomic Usage Operations ───

    /**
     * Atomically check the daily conversation limit and increment the counter if
     * within budget. Resets the daily window if expired before checking.
     *
     * @param tenantId
     *            tenant identifier
     * @param limit
     *            max conversations per day (-1 = unlimited)
     * @return {@link QuotaCheckResult#OK} if the slot was acquired, or a denied
     *         result with reason if the limit was reached
     */
    QuotaCheckResult tryIncrementConversations(String tenantId, int limit);

    /**
     * Atomically check the per-minute API call limit and increment the counter if
     * within budget. Resets the minute window if expired before checking.
     *
     * @param tenantId
     *            tenant identifier
     * @param limit
     *            max API calls per minute (-1 = unlimited)
     * @return {@link QuotaCheckResult#OK} if the slot was acquired, or a denied
     *         result with reason if the limit was reached
     */
    QuotaCheckResult tryIncrementApiCalls(String tenantId, int limit);

    /**
     * Atomically add a cost amount and check the monthly budget. The cost is
     * <strong>always added</strong>, even if the budget is exceeded — this is
     * post-call accounting (the LLM call already happened). This differs from
     * {@code tryIncrement*} methods which never increment past the limit.
     * <p>
     * The returned result indicates whether the budget has been exceeded after
     * adding the cost, which can be used to block <em>subsequent</em> calls.
     *
     * @param tenantId
     *            tenant identifier
     * @param cost
     *            the cost to add (USD)
     * @param limit
     *            max monthly cost (-1 = unlimited)
     * @return {@link QuotaCheckResult#OK} if within budget after adding cost, or a
     *         denied result if the budget was exceeded
     */
    QuotaCheckResult tryAddCost(String tenantId, double cost, double limit);

    // ─── Usage Reporting ───

    /**
     * Get a point-in-time snapshot of usage counters for a tenant.
     * <p>
     * <strong>Side effect:</strong> resets any expired time windows before reading,
     * so the returned snapshot reflects current-window values only.
     *
     * @param tenantId
     *            tenant identifier
     * @return current usage snapshot (never null — returns zeros for unknown
     *         tenants)
     */
    UsageSnapshot getUsage(String tenantId);

    /**
     * Get the current monthly cost for a tenant without allocating a full
     * {@link UsageSnapshot}. Intended for hot-path pre-call budget checks.
     * <p>
     * <strong>Side effect:</strong> resets expired windows (including the monthly
     * cost if a month boundary has passed).
     *
     * @param tenantId
     *            tenant identifier
     * @return accumulated monthly cost in USD (0.0 for unknown tenants)
     */
    double getMonthlyCost(String tenantId);

    /**
     * Reset all usage counters for a tenant (admin operation).
     *
     * @param tenantId
     *            tenant identifier
     */
    void resetUsage(String tenantId);
}
