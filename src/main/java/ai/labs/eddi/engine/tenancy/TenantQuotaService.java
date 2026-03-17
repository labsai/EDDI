package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.TenantUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tenant quota enforcement engine.
 * <p>
 * Checks quotas before conversation start and API calls,
 * tracks usage counters, and exposes Micrometer metrics.
 * <p>
 * In single-tenant mode, all operations use the default tenant ID.
 */
@ApplicationScoped
public class TenantQuotaService {

    private static final Logger LOGGER = Logger.getLogger(TenantQuotaService.class);
    private static final Duration MINUTE_WINDOW = Duration.ofMinutes(1);
    private static final Duration DAY_WINDOW = Duration.ofDays(1);

    @Inject
    ITenantQuotaStore quotaStore;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "eddi.tenant.default-id", defaultValue = "default")
    String defaultTenantId;

    private final Map<String, TenantUsage> usageMap = new ConcurrentHashMap<>();

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

    /**
     * Check whether a new conversation is allowed for the default tenant.
     */
    public QuotaCheckResult checkConversationQuota() {
        return checkConversationQuota(defaultTenantId);
    }

    /**
     * Check whether a new conversation is allowed for a specific tenant.
     */
    // TODO: Phase 7.1 — make check+record atomic for multi-tenant SaaS (TOCTOU race under concurrency)
    public QuotaCheckResult checkConversationQuota(String tenantId) {
        TenantQuota quota = quotaStore.getQuota(tenantId);
        if (quota == null || !quota.enabled()) {
            quotaAllowedCounter.increment();
            return QuotaCheckResult.OK;
        }

        TenantUsage usage = getOrCreateUsage(tenantId);
        resetWindowsIfExpired(usage);

        int limit = quota.maxConversationsPerDay();
        if (limit >= 0 && usage.getConversationsToday() >= limit) {
            quotaDeniedCounter.increment();
            meterRegistry.counter("eddi.tenant.quota.denied", "tenant", tenantId, "type", "conversation").increment();
            String reason = String.format("Daily conversation limit (%d) exceeded for tenant '%s'", limit, tenantId);
            LOGGER.warnf(reason);
            return QuotaCheckResult.denied(reason);
        }

        quotaAllowedCounter.increment();
        return QuotaCheckResult.OK;
    }

    /**
     * Check whether an API call (say/sayStreaming) is allowed for the default tenant.
     */
    public QuotaCheckResult checkApiCallQuota() {
        return checkApiCallQuota(defaultTenantId);
    }

    /**
     * Check whether an API call is allowed for a specific tenant.
     */
    // TODO: Phase 7.1 — make check+record atomic for multi-tenant SaaS (TOCTOU race under concurrency)
    public QuotaCheckResult checkApiCallQuota(String tenantId) {
        TenantQuota quota = quotaStore.getQuota(tenantId);
        if (quota == null || !quota.enabled()) {
            quotaAllowedCounter.increment();
            return QuotaCheckResult.OK;
        }

        TenantUsage usage = getOrCreateUsage(tenantId);
        resetWindowsIfExpired(usage);

        int limit = quota.maxApiCallsPerMinute();
        if (limit >= 0 && usage.getApiCallsThisMinute() >= limit) {
            quotaDeniedCounter.increment();
            meterRegistry.counter("eddi.tenant.quota.denied", "tenant", tenantId, "type", "api_call").increment();
            String reason = String.format("API rate limit (%d/min) exceeded for tenant '%s'", limit, tenantId);
            LOGGER.warnf(reason);
            return QuotaCheckResult.denied(reason);
        }

        quotaAllowedCounter.increment();
        return QuotaCheckResult.OK;
    }

    /**
     * Check whether the monthly cost budget is within limits.
     */
    public QuotaCheckResult checkCostBudget(String tenantId) {
        TenantQuota quota = quotaStore.getQuota(tenantId);
        if (quota == null || !quota.enabled()) {
            return QuotaCheckResult.OK;
        }

        TenantUsage usage = getOrCreateUsage(tenantId);
        double limit = quota.maxMonthlyCostUsd();
        if (limit >= 0 && usage.getMonthlyCostUsd() >= limit) {
            quotaDeniedCounter.increment();
            meterRegistry.counter("eddi.tenant.quota.denied", "tenant", tenantId, "type", "cost").increment();
            String reason = String.format("Monthly cost budget ($%.2f) exceeded for tenant '%s'", limit, tenantId);
            LOGGER.warnf(reason);
            return QuotaCheckResult.denied(reason);
        }

        return QuotaCheckResult.OK;
    }

    /**
     * Record that a new conversation was started.
     */
    public void recordConversationStart() {
        recordConversationStart(defaultTenantId);
    }

    public void recordConversationStart(String tenantId) {
        TenantUsage usage = getOrCreateUsage(tenantId);
        usage.incrementConversations();
        meterRegistry.counter("eddi.tenant.usage.conversations", "tenant", tenantId).increment();
    }

    /**
     * Record that an API call was made.
     */
    public void recordApiCall() {
        recordApiCall(defaultTenantId);
    }

    public void recordApiCall(String tenantId) {
        TenantUsage usage = getOrCreateUsage(tenantId);
        usage.incrementApiCalls();
        meterRegistry.counter("eddi.tenant.usage.api_calls", "tenant", tenantId).increment();
    }

    /**
     * Record tool cost for a tenant.
     */
    public void recordCost(String tenantId, double cost) {
        TenantUsage usage = getOrCreateUsage(tenantId);
        usage.addCost(cost);
        meterRegistry.counter("eddi.tenant.usage.cost", "tenant", tenantId).increment(cost);
    }

    /**
     * Get usage snapshot for a tenant.
     */
    public TenantUsage.UsageSnapshot getUsage(String tenantId) {
        TenantUsage usage = usageMap.get(tenantId);
        if (usage == null) {
            return new TenantUsage.UsageSnapshot(tenantId, 0, 0, 0.0, Instant.now(), Instant.now());
        }
        resetWindowsIfExpired(usage);
        return usage.toSnapshot();
    }

    /**
     * Reset all usage counters for a tenant.
     */
    public void resetUsage(String tenantId) {
        TenantUsage usage = usageMap.get(tenantId);
        if (usage != null) {
            usage.resetAll();
            LOGGER.infof("Usage counters reset for tenant '%s'", tenantId);
        }
    }

    // --- Internal helpers ---

    private TenantUsage getOrCreateUsage(String tenantId) {
        return usageMap.computeIfAbsent(tenantId, TenantUsage::new);
    }

    private void resetWindowsIfExpired(TenantUsage usage) {
        Instant now = Instant.now();

        // Reset minute window if expired
        if (Duration.between(usage.getMinuteWindowStart(), now).compareTo(MINUTE_WINDOW) > 0) {
            usage.resetMinuteWindow();
        }

        // Reset daily counters if expired
        if (Duration.between(usage.getDayStart(), now).compareTo(DAY_WINDOW) > 0) {
            usage.resetDailyCounters();
        }
    }
}
