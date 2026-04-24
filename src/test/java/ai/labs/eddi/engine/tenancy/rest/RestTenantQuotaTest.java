/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy.rest;

import ai.labs.eddi.engine.tenancy.InMemoryTenantQuotaStore;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the tenant quota REST API.
 */
class RestTenantQuotaTest {

    private static final String TENANT_ID = "default";
    private RestTenantQuota restTenantQuota;
    private InMemoryTenantQuotaStore quotaStore;
    private TenantQuotaService quotaService;

    @BeforeEach
    void setUp() {
        var meterRegistry = new SimpleMeterRegistry();
        var defaultQuota = TenantQuota.unlimited(TENANT_ID);
        quotaStore = new InMemoryTenantQuotaStore(defaultQuota);
        quotaService = new TenantQuotaService(quotaStore, meterRegistry, TENANT_ID);
        restTenantQuota = new RestTenantQuota(quotaStore, quotaService);
    }

    @Test
    void shouldReturnQuotaForTenant() {
        TenantQuota quota = restTenantQuota.getQuota(TENANT_ID);

        assertNotNull(quota);
        assertEquals(TENANT_ID, quota.tenantId());
        assertEquals(-1, quota.maxConversationsPerDay());
        assertFalse(quota.enabled());
    }

    @Test
    void shouldReturn404ForUnknownTenant() {
        assertThrows(NotFoundException.class, () -> restTenantQuota.getQuota("unknown-tenant"));
    }

    @Test
    void shouldUpdateQuota() {
        var update = new TenantQuota(TENANT_ID, 100, 10, 50, 500.0, true);

        try (Response response = restTenantQuota.updateQuota(TENANT_ID, update)) {
            assertEquals(200, response.getStatus());
        }

        TenantQuota stored = quotaStore.getQuota(TENANT_ID);
        assertEquals(100, stored.maxConversationsPerDay());
        assertEquals(10, stored.maxAgentsPerTenant());
        assertEquals(50, stored.maxApiCallsPerMinute());
        assertEquals(500.0, stored.maxMonthlyCostUsd());
        assertTrue(stored.enabled());
    }

    @Test
    void shouldReturnUsage() {
        // Enable quota so counters are tracked
        quotaStore.setQuota(new TenantQuota(TENANT_ID, 100, -1, 100, -1, true));
        quotaService.acquireConversationSlot();
        quotaService.acquireApiCallSlot();

        var usage = restTenantQuota.getUsage(TENANT_ID);

        assertEquals(TENANT_ID, usage.tenantId());
        assertEquals(1, usage.conversationsToday());
        assertEquals(1, usage.apiCallsThisMinute());
    }

    @Test
    void shouldResetUsage() {
        // Enable quota so counters are tracked
        quotaStore.setQuota(new TenantQuota(TENANT_ID, 100, -1, 100, -1, true));
        quotaService.acquireConversationSlot();
        quotaService.acquireApiCallSlot();

        try (Response response = restTenantQuota.resetUsage(TENANT_ID)) {
            assertEquals(200, response.getStatus());
        }

        var usage = quotaService.getUsage(TENANT_ID);
        assertEquals(0, usage.conversationsToday());
        assertEquals(0, usage.apiCallsThisMinute());
    }

    @Test
    void shouldListAllQuotas() {
        var quotas = restTenantQuota.listQuotas();

        assertNotNull(quotas);
        assertEquals(1, quotas.size());
        assertEquals(TENANT_ID, quotas.getFirst().tenantId());
    }

    @Test
    void shouldReturnExceptionMapperResponse() {
        var mapper = new QuotaExceededExceptionMapper();
        var exception = new ai.labs.eddi.engine.tenancy.QuotaExceededException("Test limit exceeded");

        try (Response response = mapper.toResponse(exception)) {
            assertEquals(429, response.getStatus());
            assertEquals("60", response.getHeaderString("Retry-After"));
        }
    }
}
