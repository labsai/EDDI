package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.TenantUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TenantQuotaService — the core quota enforcement engine.
 */
class TenantQuotaServiceTest {

    private static final String TENANT_ID = "default";
    private TenantQuotaService quotaService;
    private InMemoryTenantQuotaStore quotaStore;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        var defaultQuota = TenantQuota.unlimited(TENANT_ID);
        quotaStore = new InMemoryTenantQuotaStore(defaultQuota);
        quotaService = new TenantQuotaService(quotaStore, meterRegistry, TENANT_ID);
    }

    // --- Quota disabled ---

    @Test
    void shouldAllowWhenQuotaDisabled() {
        // Default quota is disabled (enabled=false)
        QuotaCheckResult result = quotaService.checkConversationQuota();
        assertTrue(result.allowed());
    }

    @Test
    void shouldAllowApiCallsWhenQuotaDisabled() {
        QuotaCheckResult result = quotaService.checkApiCallQuota();
        assertTrue(result.allowed());
    }

    // --- Conversation limits ---

    @Test
    void shouldAllowWithinConversationLimit() {
        enableQuotaWithConversationLimit(5);

        QuotaCheckResult result = quotaService.checkConversationQuota();
        assertTrue(result.allowed());
    }

    @Test
    void shouldDenyWhenConversationLimitExceeded() {
        enableQuotaWithConversationLimit(2);

        quotaService.recordConversationStart();
        quotaService.recordConversationStart();

        QuotaCheckResult result = quotaService.checkConversationQuota();
        assertFalse(result.allowed());
        assertNotNull(result.reason());
        assertTrue(result.reason().contains("Daily conversation limit"));
    }

    @Test
    void shouldAllowUnlimitedConversationsWhenMinusOne() {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, -1, -1, -1, true));

        // Even when enabled, -1 = unlimited
        for (int i = 0; i < 1000; i++) {
            quotaService.recordConversationStart();
        }
        QuotaCheckResult result = quotaService.checkConversationQuota();
        assertTrue(result.allowed());
    }

    // --- API call rate limits ---

    @Test
    void shouldAllowWithinApiCallLimit() {
        enableQuotaWithApiCallLimit(10);

        quotaService.recordApiCall();
        QuotaCheckResult result = quotaService.checkApiCallQuota();
        assertTrue(result.allowed());
    }

    @Test
    void shouldDenyWhenApiCallLimitExceeded() {
        enableQuotaWithApiCallLimit(3);

        quotaService.recordApiCall();
        quotaService.recordApiCall();
        quotaService.recordApiCall();

        QuotaCheckResult result = quotaService.checkApiCallQuota();
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("API rate limit"));
    }

    @Test
    void shouldAllowUnlimitedApiCallsWhenMinusOne() {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, -1, -1, -1, true));

        for (int i = 0; i < 1000; i++) {
            quotaService.recordApiCall();
        }
        QuotaCheckResult result = quotaService.checkApiCallQuota();
        assertTrue(result.allowed());
    }

    // --- Cost budget ---

    @Test
    void shouldAllowWithinCostBudget() {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, -1, -1, 100.0, true));

        quotaService.recordCost(TENANT_ID, 50.0);
        QuotaCheckResult result = quotaService.checkCostBudget(TENANT_ID);
        assertTrue(result.allowed());
    }

    @Test
    void shouldDenyWhenCostBudgetExceeded() {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, -1, -1, 100.0, true));

        quotaService.recordCost(TENANT_ID, 100.0);
        QuotaCheckResult result = quotaService.checkCostBudget(TENANT_ID);
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("Monthly cost budget"));
    }

    // --- Usage tracking ---

    @Test
    void shouldReturnCorrectUsageSummary() {
        quotaService.recordConversationStart();
        quotaService.recordConversationStart();
        quotaService.recordApiCall();
        quotaService.recordCost(TENANT_ID, 12.50);

        TenantUsage.UsageSnapshot usage = quotaService.getUsage(TENANT_ID);
        assertEquals(TENANT_ID, usage.tenantId());
        assertEquals(2, usage.conversationsToday());
        assertEquals(1, usage.apiCallsThisMinute());
        assertEquals(12.50, usage.monthlyCostUsd(), 0.01);
    }

    @Test
    void shouldResetUsageCounters() {
        quotaService.recordConversationStart();
        quotaService.recordApiCall();
        quotaService.recordCost(TENANT_ID, 10.0);

        quotaService.resetUsage(TENANT_ID);

        TenantUsage.UsageSnapshot usage = quotaService.getUsage(TENANT_ID);
        assertEquals(0, usage.conversationsToday());
        assertEquals(0, usage.apiCallsThisMinute());
        assertEquals(0.0, usage.monthlyCostUsd(), 0.01);
    }

    @Test
    void shouldReturnEmptyUsageForUnknownTenant() {
        TenantUsage.UsageSnapshot usage = quotaService.getUsage("unknown-tenant");
        assertEquals("unknown-tenant", usage.tenantId());
        assertEquals(0, usage.conversationsToday());
    }

    // --- Dynamic quota update ---

    @Test
    void shouldUpdateQuotaAtRuntime() {
        enableQuotaWithApiCallLimit(5);

        // Initially within limit
        quotaService.recordApiCall();
        assertTrue(quotaService.checkApiCallQuota().allowed());

        // Tighten the limit
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, -1, 1, -1, true));

        // Now exceeded
        QuotaCheckResult result = quotaService.checkApiCallQuota();
        assertFalse(result.allowed());
    }

    // --- Metrics ---

    @Test
    void shouldTrackAllowedAndDeniedMetrics() {
        enableQuotaWithConversationLimit(1);

        // First call — allowed
        quotaService.checkConversationQuota();
        quotaService.recordConversationStart();

        // Second call — denied
        quotaService.checkConversationQuota();

        double denied = meterRegistry.find("eddi.tenant.quota.denied").counters().stream().mapToDouble(c -> c.count()).sum();
        assertTrue(denied >= 1.0, "Expected at least 1 denied metric, got " + denied);
    }

    @Test
    void shouldTrackUsageMetrics() {
        quotaService.recordConversationStart();
        quotaService.recordApiCall();
        quotaService.recordCost(TENANT_ID, 5.0);

        double convCount = meterRegistry.find("eddi.tenant.usage.conversations").counter().count();
        double apiCount = meterRegistry.find("eddi.tenant.usage.api_calls").counter().count();

        assertEquals(1.0, convCount);
        assertEquals(1.0, apiCount);
    }

    // --- Helpers ---

    private void enableQuotaWithConversationLimit(int limit) {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, limit, -1, -1, -1, true));
    }

    private void enableQuotaWithApiCallLimit(int limit) {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, -1, limit, -1, true));
    }
}
