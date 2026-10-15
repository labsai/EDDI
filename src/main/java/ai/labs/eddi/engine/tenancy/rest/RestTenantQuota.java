/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy.rest;

import ai.labs.eddi.engine.tenancy.ITenantQuotaStore;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
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
        if (tenantId == null || tenantId.isBlank()) {
            throw new BadRequestException("tenantId must not be blank");
        }
        if (quota == null) {
            // An empty request body used to reach the dereference below and surface
            // as a 500.
            throw new BadRequestException("A quota body is required");
        }
        // Every store treats "< 0" as unlimited, so -5 was accepted and silently
        // behaved as unlimited — the opposite of what an admin typing a negative
        // number means.
        requireLimit("maxConversationsPerDay", quota.maxConversationsPerDay());
        requireLimit("maxAgentsPerTenant", quota.maxAgentsPerTenant());
        requireLimit("maxApiCallsPerMinute", quota.maxApiCallsPerMinute());
        requireCostLimit(quota.maxMonthlyCostUsd());

        // Ensure tenantId consistency
        var storedQuota = new TenantQuota(tenantId, quota.maxConversationsPerDay(), quota.maxAgentsPerTenant(), quota.maxApiCallsPerMinute(),
                quota.maxMonthlyCostUsd(), quota.enabled());

        // Through the service, not straight to the store: the enforcement gates read
        // configuration through a short-TTL cache, and the service write-through is
        // what drops the stale entry so the new limits apply to the very next turn.
        quotaService.setQuota(storedQuota);

        return Response.ok(storedQuota).build();
    }

    /**
     * A limit is either {@code -1} (unlimited) or a non-negative count. Any other
     * negative value is rejected rather than quietly read as unlimited.
     */
    private static void requireLimit(String field, int value) {
        if (value < UNLIMITED) {
            throw new BadRequestException(field + " must be -1 (unlimited) or >= 0, was " + value);
        }
    }

    /**
     * The {@code double} twin of {@link #requireLimit}, and it needs a different
     * predicate. {@code value < -1} leaves the whole open interval between the
     * sentinel and zero open: {@code -0.5} is not less than {@code -1}, so it was
     * accepted although the rejection message says "-1 (unlimited) or >= 0" — and
     * every store reads any negative as unlimited, so it silently disabled the
     * budget. The integer overload has the same shape but no reachable gap, because
     * no integer sits strictly between -1 and 0.
     */
    private static void requireCostLimit(double value) {
        boolean unlimited = value == UNLIMITED;
        if (Double.isNaN(value) || (!unlimited && value < 0)) {
            throw new BadRequestException("maxMonthlyCostUsd must be -1 (unlimited) or >= 0, was " + value);
        }
    }

    /** The sentinel every store reads as "no limit". */
    private static final int UNLIMITED = -1;

    @Override
    public UsageSnapshot getUsage(String tenantId) {
        return quotaService.getUsage(tenantId);
    }

    @Override
    public Response resetUsage(String tenantId) {
        quotaService.resetUsage(tenantId);
        return Response.ok().build();
    }
}
