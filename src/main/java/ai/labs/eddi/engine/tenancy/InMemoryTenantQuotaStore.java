package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link ITenantQuotaStore} with atomic
 * check-and-increment operations.
 * <p>
 * Atomicity is achieved via {@code synchronized} on per-tenant counter objects.
 * This is correct for single-instance deployments. For cluster (multi-instance)
 * deployments, replace with a DB-backed store that uses storage-level atomicity
 * (e.g., PostgreSQL {@code UPDATE ... WHERE count < limit RETURNING}).
 * <p>
 * <strong>Lock granularity:</strong> all five mutating operations
 * ({@code tryIncrementConversations}, {@code tryIncrementApiCalls},
 * {@code tryAddCost}, {@code getUsage}, {@code getMonthlyCost}) share the same
 * per-tenant lock. In a default single-tenant deployment, "per-tenant" is
 * effectively a global lock. This is acceptable because the critical section is
 * microseconds (a few field reads, one integer increment, one timestamp
 * comparison). No I/O or allocation occurs under the lock.
 * <p>
 * This is the default bean; a DB-backed store can override it via
 * {@code @LookupIfProperty} in future phases.
 */
@ApplicationScoped
@DefaultBean
public class InMemoryTenantQuotaStore implements ITenantQuotaStore {

    private static final Logger LOGGER = Logger.getLogger(InMemoryTenantQuotaStore.class);

    private final Map<String, TenantQuota> quotas = new ConcurrentHashMap<>();
    private final Map<String, TenantUsageCounters> usageMap = new ConcurrentHashMap<>();

    @Inject
    public InMemoryTenantQuotaStore(@ConfigProperty(name = "eddi.tenant.default-id", defaultValue = "default") String defaultTenantId,
            @ConfigProperty(name = "eddi.tenant.quota.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "eddi.tenant.quota.max-conversations-per-day", defaultValue = "-1") int maxConvPerDay,
            @ConfigProperty(name = "eddi.tenant.quota.max-agents-per-tenant", defaultValue = "-1") int maxAgents,
            @ConfigProperty(name = "eddi.tenant.quota.max-api-calls-per-minute", defaultValue = "-1") int maxApiCalls,
            @ConfigProperty(name = "eddi.tenant.quota.max-monthly-cost-usd", defaultValue = "-1") double maxCost) {

        var defaultQuota = new TenantQuota(defaultTenantId, maxConvPerDay, maxAgents, maxApiCalls, maxCost, enabled);
        quotas.put(defaultTenantId, defaultQuota);

        LOGGER.infof("Tenant quota store initialized: tenantId=%s, enabled=%s, maxConv=%d, maxAgents=%d, maxApi=%d, maxCost=%.2f", defaultTenantId,
                enabled, maxConvPerDay, maxAgents, maxApiCalls, maxCost);
    }

    /**
     * Test-only constructor — no CDI injection.
     */
    public InMemoryTenantQuotaStore(TenantQuota defaultQuota) {
        quotas.put(defaultQuota.tenantId(), defaultQuota);
    }

    // ─── Quota Configuration ───

    @Override
    public TenantQuota getQuota(String tenantId) {
        return quotas.get(tenantId);
    }

    @Override
    public void setQuota(TenantQuota quota) {
        quotas.put(quota.tenantId(), quota);
    }

    @Override
    public List<TenantQuota> listQuotas() {
        return List.copyOf(quotas.values());
    }

    @Override
    public void deleteQuota(String tenantId) {
        quotas.remove(tenantId);
    }

    // ─── Atomic Usage Operations ───

    @Override
    public QuotaCheckResult tryIncrementConversations(String tenantId, int limit) {
        // CHM.computeIfAbsent is itself atomic — guarantees a single counter object
        // per tenant, which the subsequent synchronized block uses as the lock.
        TenantUsageCounters counters = getOrCreateCounters(tenantId);
        synchronized (counters) {
            counters.resetExpiredWindows();
            if (limit >= 0 && counters.getConversationsToday() >= limit) {
                return QuotaCheckResult.denied(
                        String.format("Daily conversation limit (%d) exceeded for tenant '%s'", limit, tenantId));
            }
            counters.incrementConversations();
            return QuotaCheckResult.OK;
        }
    }

    @Override
    public QuotaCheckResult tryIncrementApiCalls(String tenantId, int limit) {
        // CHM.computeIfAbsent is itself atomic — guarantees a single counter object
        // per tenant, which the subsequent synchronized block uses as the lock.
        TenantUsageCounters counters = getOrCreateCounters(tenantId);
        synchronized (counters) {
            counters.resetExpiredWindows();
            if (limit >= 0 && counters.getApiCallsThisMinute() >= limit) {
                return QuotaCheckResult.denied(
                        String.format("API rate limit (%d/min) exceeded for tenant '%s'", limit, tenantId));
            }
            counters.incrementApiCalls();
            return QuotaCheckResult.OK;
        }
    }

    @Override
    public QuotaCheckResult tryAddCost(String tenantId, double cost, double limit) {
        TenantUsageCounters counters = getOrCreateCounters(tenantId);
        synchronized (counters) {
            counters.resetExpiredWindows();
            counters.addCost(cost);
            if (limit >= 0 && counters.getMonthlyCostUsd() >= limit) {
                return QuotaCheckResult.denied(
                        String.format("Monthly cost budget ($%.2f) exceeded for tenant '%s'", limit, tenantId));
            }
            return QuotaCheckResult.OK;
        }
    }

    // ─── Usage Reporting ───

    @Override
    public UsageSnapshot getUsage(String tenantId) {
        TenantUsageCounters counters = usageMap.get(tenantId);
        if (counters == null) {
            return UsageSnapshot.empty(tenantId);
        }
        synchronized (counters) {
            counters.resetExpiredWindows();
            return counters.toSnapshot(tenantId);
        }
    }

    @Override
    public double getMonthlyCost(String tenantId) {
        TenantUsageCounters counters = usageMap.get(tenantId);
        if (counters == null) {
            return 0.0;
        }
        synchronized (counters) {
            counters.resetExpiredWindows();
            return counters.getMonthlyCostUsd();
        }
    }

    @Override
    public void resetUsage(String tenantId) {
        TenantUsageCounters counters = usageMap.get(tenantId);
        if (counters != null) {
            synchronized (counters) {
                counters.resetAll();
            }
            LOGGER.infof("Usage counters reset for tenant '%s'", sanitize(tenantId));
        }
    }

    private TenantUsageCounters getOrCreateCounters(String tenantId) {
        return usageMap.computeIfAbsent(tenantId, k -> new TenantUsageCounters());
    }
}
