package ai.labs.eddi.engine.tenancy.rest;

import ai.labs.eddi.engine.tenancy.ITenantQuotaStore;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.TenantUsage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * REST implementation for tenant quota management.
 */
@ApplicationScoped
public class RestTenantQuota implements IRestTenantQuota {

    private final ITenantQuotaStore quotaStore;
    private final TenantQuotaService quotaService;

    @Inject
    public RestTenantQuota(ITenantQuotaStore quotaStore, TenantQuotaService quotaService) {
        this.quotaStore = quotaStore;
        this.quotaService = quotaService;
    }

    @Override
    public List<TenantQuota> listQuotas() {
        return quotaStore.listQuotas();
    }

    @Override
    public TenantQuota getQuota(String tenantId) {
        TenantQuota quota = quotaStore.getQuota(tenantId);
        if (quota == null) {
            throw new NotFoundException("No quota configured for tenant: " + tenantId);
        }
        return quota;
    }

    @Override
    public Response updateQuota(String tenantId, TenantQuota quota) {
        // Ensure tenantId consistency
        var storedQuota = new TenantQuota(tenantId, quota.maxConversationsPerDay(), quota.maxAgentsPerTenant(), quota.maxApiCallsPerMinute(),
                quota.maxMonthlyCostUsd(), quota.enabled());
        quotaStore.setQuota(storedQuota);
        return Response.ok(storedQuota).build();
    }

    @Override
    public TenantUsage.UsageSnapshot getUsage(String tenantId) {
        return quotaService.getUsage(tenantId);
    }

    @Override
    public Response resetUsage(String tenantId) {
        quotaService.resetUsage(tenantId);
        return Response.ok().build();
    }
}
