/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Tenant quota enforcement engine.
 * <p>
 * Provides <strong>atomic</strong> quota acquisition methods that merge the
 * check ("am I within budget?") and record ("increment the counter") into a
 * single indivisible operation, eliminating TOCTOU races.
 * <p>
 * Single-instance atomicity is guaranteed by the in-memory store's per-tenant
 * locks. Cluster-safe atomicity requires a DB-backed {@link ITenantQuotaStore}
 * that uses storage-level atomic operations.
 * <p>
 * In single-tenant mode, all operations use the default tenant ID.
 */
@ApplicationScoped
public class TenantQuotaService {

    private static final Logger LOGGER = Logger.getLogger(TenantQuotaService.class);

    @Inject
    ITenantQuotaStore quotaStore;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "eddi.tenant.default-id", defaultValue = "default")
    String defaultTenantId;

    // Metrics
    private Counter quotaAllowedCounter;
    private Counter quotaDeniedCounter;

    @PostConstruct
    void init() {
        quotaAllowedCounter = meterRegistry.counter("eddi.tenant.quota.allowed");
        quotaDeniedCounter = meterRegistry.counter("eddi.tenant.quota.denied");
        LOGGER.info("Tenant quota service initialized");
    }

    /**
     * CDI constructor.
     */
    public TenantQuotaService() {
    }

    /**
     * Test-only constructor — skips CDI.
     */
    public TenantQuotaService(ITenantQuotaStore quotaStore, MeterRegistry meterRegistry, String defaultTenantId) {
        this.quotaStore = quotaStore;
        this.meterRegistry = meterRegistry;
        this.defaultTenantId = defaultTenantId;
        this.quotaAllowedCounter = meterRegistry.counter("eddi.tenant.quota.allowed");
        this.quotaDeniedCounter = meterRegistry.counter("eddi.tenant.quota.denied");
    }

    /**
     * Get the current default tenant ID.
     */
    public String getDefaultTenantId() {
        return defaultTenantId;
    }

    // ─── Atomic Slot Acquisition ───

    /**
     * Atomically acquire a conversation slot for the default tenant. If the daily
     * limit has been reached, returns a denied result. Otherwise, increments the
     * counter and returns OK.
     */
    public QuotaCheckResult acquireConversationSlot() {
        return acquireConversationSlot(defaultTenantId);
    }

    /**
     * Atomically acquire a conversation slot for a specific tenant. If the daily
     * limit has been reached, returns a denied result. Otherwise, increments the
     * counter and returns OK.
     */
    public QuotaCheckResult acquireConversationSlot(String tenantId) {
        TenantQuota quota = quotaStore.getQuota(tenantId);
        if (quota == null || !quota.enabled()) {
            return QuotaCheckResult.OK;
        }

        int limit = quota.maxConversationsPerDay();
        QuotaCheckResult result = quotaStore.tryIncrementConversations(tenantId, limit);

        if (result.allowed()) {
            quotaAllowedCounter.increment();
            meterRegistry.counter("eddi.tenant.usage.conversations", "tenant", tenantId).increment();
        } else {
            quotaDeniedCounter.increment();
            meterRegistry.counter("eddi.tenant.quota.denied", "tenant", tenantId, "type", "conversation").increment();
            LOGGER.warn(result.reason());
        }

        return result;
    }

    /**
     * Atomically acquire an API call slot for the default tenant. If the per-minute
     * rate limit has been reached, returns a denied result. Otherwise, increments
     * the counter and returns OK.
     */
    public QuotaCheckResult acquireApiCallSlot() {
        return acquireApiCallSlot(defaultTenantId);
    }

    /**
     * Atomically acquire an API call slot for a specific tenant. If the per-minute
     * rate limit has been reached, returns a denied result. Otherwise, increments
     * the counter and returns OK.
     */
    public QuotaCheckResult acquireApiCallSlot(String tenantId) {
        TenantQuota quota = quotaStore.getQuota(tenantId);
        if (quota == null || !quota.enabled()) {
            return QuotaCheckResult.OK;
        }

        int limit = quota.maxApiCallsPerMinute();
        QuotaCheckResult result = quotaStore.tryIncrementApiCalls(tenantId, limit);

        if (result.allowed()) {
            quotaAllowedCounter.increment();
            meterRegistry.counter("eddi.tenant.usage.api_calls", "tenant", tenantId).increment();
        } else {
            quotaDeniedCounter.increment();
            meterRegistry.counter("eddi.tenant.quota.denied", "tenant", tenantId, "type", "api_call").increment();
            LOGGER.warn(result.reason());
        }

        return result;
    }

    // ─── Cost Budget ───

    /**
     * Read-only pre-call gate: checks whether the monthly cost budget has been
     * exceeded. Does NOT add any cost — this is a "can I proceed?" check used
     * before an LLM call whose cost is not yet known.
     * <p>
     * Note: unlike {@code acquire*Slot()}, this method does NOT increment
     * {@code quotaAllowedCounter} on the happy path — it is a read-only gate, not
     * an acquisition. The {@code quota.allowed} metric only counts slot
     * acquisitions.
     */
    public QuotaCheckResult checkCostBudget(String tenantId) {
        TenantQuota quota = quotaStore.getQuota(tenantId);
        if (quota == null || !quota.enabled()) {
            return QuotaCheckResult.OK;
        }

        double currentCost = quotaStore.getMonthlyCost(tenantId);
        double limit = quota.maxMonthlyCostUsd();
        if (limit >= 0 && currentCost >= limit) {
            quotaDeniedCounter.increment();
            meterRegistry.counter("eddi.tenant.quota.denied", "tenant", tenantId, "type", "cost").increment();
            String reason = String.format("Monthly cost budget ($%.2f) exceeded for tenant '%s'", limit, tenantId);
            LOGGER.warn(reason);
            return QuotaCheckResult.denied(reason);
        }

        return QuotaCheckResult.OK;
    }

    /**
     * Post-call accounting: atomically adds the actual cost incurred and checks
     * whether the budget has been exceeded. Use the returned result to decide
     * whether to block subsequent calls or emit metrics.
     * <p>
     * Note: internally calls {@link ITenantQuotaStore#tryAddCost} which resets all
     * expired time windows (minute, day, month) under the per-tenant lock. This is
     * harmless (lazy reset is idempotent), but means this method has broader side
     * effects than its name suggests.
     */
    public QuotaCheckResult recordCost(String tenantId, double cost) {
        TenantQuota quota = quotaStore.getQuota(tenantId);
        if (quota == null || !quota.enabled()) {
            return QuotaCheckResult.OK;
        }

        double limit = quota.maxMonthlyCostUsd();
        QuotaCheckResult result = quotaStore.tryAddCost(tenantId, cost, limit);
        meterRegistry.counter("eddi.tenant.usage.cost", "tenant", tenantId).increment(cost);

        if (!result.allowed()) {
            quotaDeniedCounter.increment();
            meterRegistry.counter("eddi.tenant.quota.denied", "tenant", tenantId, "type", "cost").increment();
            LOGGER.warn(result.reason());
        }

        return result;
    }

    // ─── Usage Reporting ───

    /**
     * Get usage snapshot for a tenant.
     */
    public UsageSnapshot getUsage(String tenantId) {
        return quotaStore.getUsage(tenantId);
    }

    /**
     * Reset all usage counters for a tenant.
     */
    public void resetUsage(String tenantId) {
        quotaStore.resetUsage(tenantId);
    }
}
