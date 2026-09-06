/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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

    /**
     * Denial reason every implementation uses when the store itself could not
     * answer — as opposed to the tenant genuinely being over a limit.
     * <p>
     * Paired with {@link QuotaCheckResult#unavailable}, which is what makes the
     * difference visible to the caller: an over-limit denial is 429 with
     * {@code Retry-After: 60} and belongs on {@code eddi.tenant.quota.denied}, an
     * outage is 503 {@code quota_accounting_unavailable} and belongs on
     * {@code eddi.tenant.quota.unavailable}. Shared here rather than owned by one
     * store because the distinction has to hold on <em>every</em> backend — it
     * shipped PostgreSQL-only, while {@code eddi.datastore.type} defaults to
     * mongodb, so the documented behaviour did not apply to the default deployment.
     */
    String ACCOUNTING_UNAVAILABLE = "Quota accounting unavailable — denying request for safety";

    /**
     * Refuse the request, flagged as an accounting outage rather than a limit
     * breach.
     * <p>
     * {@link QuotaCheckResult#denied} would still route it through the ordinary
     * over-quota path: {@code TenantQuotaService} increments
     * {@code eddi.tenant.quota.denied} and the REST layer answers 429 with
     * {@code Retry-After: 60}. Only the reason string differed, so a store outage
     * looked exactly like a tenant burning through its allowance — to the dashboard
     * and to the client.
     *
     * @return a denied result flagged {@code accountingUnavailable}
     */
    static QuotaCheckResult accountingUnavailable() {
        return QuotaCheckResult.unavailable(ACCOUNTING_UNAVAILABLE);
    }

    // ─── Quota Configuration ───

    /**
     * Get quota configuration for a tenant.
     * <p>
     * This is the first store call every gate in {@link TenantQuotaService} makes,
     * so on a real outage it is the one that fails — the {@code tryIncrement*}
     * methods below are never reached. It must therefore fail closed exactly as
     * they do, and it cannot say so in its return value: {@code null} already means
     * "this tenant has no quota row", which the service treats as unlimited. A
     * store that could not be reached throws
     * {@link QuotaAccountingUnavailableException} instead. Returning null for a
     * driver failure silently disabled enforcement for every tenant, while the
     * write half of the very same outage refused the request with a 503.
     *
     * @param tenantId
     *            tenant identifier
     * @return the quota config, or null if the tenant has none configured
     * @throws QuotaAccountingUnavailableException
     *             if the store could not be reached
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
     * <p>
     * <strong>No REST surface and no production caller today</strong> —
     * {@code IRestTenantQuota} exposes list/get/update but no DELETE, so this is
     * reached only from tests and from embedded use. Whether to publish a DELETE
     * endpoint is an open question (a tenant with no quota row is treated as
     * <em>unlimited</em>, so deleting the bootstrapped default tenant's row
     * silently turns enforcement off), which is why the method is documented rather
     * than removed or wired up on the reviewer's behalf.
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
