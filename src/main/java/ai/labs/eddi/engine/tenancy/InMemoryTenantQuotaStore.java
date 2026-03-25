package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link ITenantQuotaStore}. Loads the default
 * tenant quota from application.properties on startup.
 * <p>
 * This is a stub implementation; a DB-backed store can override this via
 * {@code @LookupIfProperty} in future phases.
 */
@ApplicationScoped
@DefaultBean
public class InMemoryTenantQuotaStore implements ITenantQuotaStore {

    private static final Logger LOGGER = Logger.getLogger(InMemoryTenantQuotaStore.class);

    private final Map<String, TenantQuota> quotas = new ConcurrentHashMap<>();

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
}
