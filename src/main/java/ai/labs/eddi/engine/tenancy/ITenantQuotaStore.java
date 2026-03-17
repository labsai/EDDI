package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.TenantQuota;

import java.util.List;

/**
 * Store interface for tenant quota configurations.
 */
public interface ITenantQuotaStore {

    /**
     * Get quota configuration for a tenant.
     *
     * @param tenantId tenant identifier
     * @return the quota config, or null if not found
     */
    TenantQuota getQuota(String tenantId);

    /**
     * Create or update quota configuration for a tenant.
     *
     * @param quota the quota configuration to store
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
     * @param tenantId tenant identifier
     */
    void deleteQuota(String tenantId);
}
