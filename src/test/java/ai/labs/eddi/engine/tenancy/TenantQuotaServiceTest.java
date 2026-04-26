/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TenantQuotaService — the core quota enforcement engine.
 * Validates atomic slot acquisition semantics and single-instance TOCTOU
 * safety.
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
    @DisplayName("should allow when quota disabled")
    void shouldAllowWhenQuotaDisabled() {
        // Default quota is disabled (enabled=false)
        QuotaCheckResult result = quotaService.acquireConversationSlot();
        assertTrue(result.allowed());
    }

    @Test
    @DisplayName("should allow API calls when quota disabled")
    void shouldAllowApiCallsWhenQuotaDisabled() {
        QuotaCheckResult result = quotaService.acquireApiCallSlot();
        assertTrue(result.allowed());
    }

    // --- Conversation limits ---

    @Test
    @DisplayName("should allow within conversation limit")
    void shouldAllowWithinConversationLimit() {
        enableQuotaWithConversationLimit(5);

        QuotaCheckResult result = quotaService.acquireConversationSlot();
        assertTrue(result.allowed());
    }

    @Test
    @DisplayName("should deny when conversation limit exhausted")
    void shouldDenyWhenConversationLimitExhausted() {
        enableQuotaWithConversationLimit(2);

        // Acquire both slots
        assertTrue(quotaService.acquireConversationSlot().allowed());
        assertTrue(quotaService.acquireConversationSlot().allowed());

        // Third attempt should be denied
        QuotaCheckResult result = quotaService.acquireConversationSlot();
        assertFalse(result.allowed());
        assertNotNull(result.reason());
        assertTrue(result.reason().contains("Daily conversation limit"));
    }

    @Test
    @DisplayName("should allow unlimited conversations when -1")
    void shouldAllowUnlimitedConversationsWhenMinusOne() {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, -1, -1, -1, true));

        // Even when enabled, -1 = unlimited
        for (int i = 0; i < 1000; i++) {
            assertTrue(quotaService.acquireConversationSlot().allowed());
        }
    }

    // --- API call rate limits ---

    @Test
    @DisplayName("should allow within API call limit")
    void shouldAllowWithinApiCallLimit() {
        enableQuotaWithApiCallLimit(10);

        QuotaCheckResult result = quotaService.acquireApiCallSlot();
        assertTrue(result.allowed());
    }

    @Test
    @DisplayName("should deny when API call limit exhausted")
    void shouldDenyWhenApiCallLimitExhausted() {
        enableQuotaWithApiCallLimit(3);

        assertTrue(quotaService.acquireApiCallSlot().allowed());
        assertTrue(quotaService.acquireApiCallSlot().allowed());
        assertTrue(quotaService.acquireApiCallSlot().allowed());

        QuotaCheckResult result = quotaService.acquireApiCallSlot();
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("API rate limit"));
    }

    @Test
    @DisplayName("should allow unlimited API calls when -1")
    void shouldAllowUnlimitedApiCallsWhenMinusOne() {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, -1, -1, -1, true));

        for (int i = 0; i < 1000; i++) {
            assertTrue(quotaService.acquireApiCallSlot().allowed());
        }
    }

    // --- Cost budget ---

    @Test
    @DisplayName("should allow when within cost budget (pre-call check)")
    void shouldAllowWithinCostBudget() {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, -1, -1, 100.0, true));

        quotaService.recordCost(TENANT_ID, 50.0);
        QuotaCheckResult result = quotaService.checkCostBudget(TENANT_ID);
        assertTrue(result.allowed());
    }

    @Test
    @DisplayName("should deny when cost budget exceeded (pre-call check)")
    void shouldDenyWhenCostBudgetExceeded() {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, -1, -1, 100.0, true));

        quotaService.recordCost(TENANT_ID, 100.0);
        QuotaCheckResult result = quotaService.checkCostBudget(TENANT_ID);
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("Monthly cost budget"));
    }

    @Test
    @DisplayName("should return denied from recordCost when budget exceeded")
    void shouldReturnDeniedFromRecordCost() {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, -1, -1, 100.0, true));

        // First cost is within budget
        QuotaCheckResult first = quotaService.recordCost(TENANT_ID, 60.0);
        assertTrue(first.allowed());

        // Second cost pushes over the limit
        QuotaCheckResult second = quotaService.recordCost(TENANT_ID, 50.0);
        assertFalse(second.allowed());
    }

    // --- Usage tracking ---

    @Test
    @DisplayName("should return correct usage summary after slot acquisitions")
    void shouldReturnCorrectUsageSummary() {
        enableQuotaWithBothLimits(100, 100);

        quotaService.acquireConversationSlot();
        quotaService.acquireConversationSlot();
        quotaService.acquireApiCallSlot();
        quotaService.recordCost(TENANT_ID, 12.50);

        UsageSnapshot usage = quotaService.getUsage(TENANT_ID);
        assertEquals(TENANT_ID, usage.tenantId());
        assertEquals(2, usage.conversationsToday());
        assertEquals(1, usage.apiCallsThisMinute());
        assertEquals(12.50, usage.monthlyCostUsd(), 0.01);
    }

    @Test
    @DisplayName("should reset usage counters")
    void shouldResetUsageCounters() {
        enableQuotaWithBothLimits(100, 100);

        quotaService.acquireConversationSlot();
        quotaService.acquireApiCallSlot();
        quotaService.recordCost(TENANT_ID, 10.0);

        quotaService.resetUsage(TENANT_ID);

        UsageSnapshot usage = quotaService.getUsage(TENANT_ID);
        assertEquals(0, usage.conversationsToday());
        assertEquals(0, usage.apiCallsThisMinute());
        assertEquals(0.0, usage.monthlyCostUsd(), 0.01);
    }

    @Test
    @DisplayName("should return empty usage for unknown tenant")
    void shouldReturnEmptyUsageForUnknownTenant() {
        UsageSnapshot usage = quotaService.getUsage("unknown-tenant");
        assertEquals("unknown-tenant", usage.tenantId());
        assertEquals(0, usage.conversationsToday());
    }

    // --- Dynamic quota update ---

    @Test
    @DisplayName("should enforce tightened quota at runtime")
    void shouldUpdateQuotaAtRuntime() {
        enableQuotaWithApiCallLimit(5);

        // Initially within limit
        assertTrue(quotaService.acquireApiCallSlot().allowed());

        // Tighten the limit to 1
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, -1, 1, -1, true));

        // Now exceeded (1 already acquired)
        QuotaCheckResult result = quotaService.acquireApiCallSlot();
        assertFalse(result.allowed());
    }

    // --- Metrics ---

    @Test
    @DisplayName("should track allowed and denied metrics only when quota evaluated")
    void shouldTrackAllowedAndDeniedMetrics() {
        enableQuotaWithConversationLimit(1);

        // First call — allowed
        quotaService.acquireConversationSlot();

        // Second call — denied
        quotaService.acquireConversationSlot();

        double denied = meterRegistry.find("eddi.tenant.quota.denied")
                .counters().stream().mapToDouble(c -> c.count()).sum();
        assertTrue(denied >= 1.0, "Expected at least 1 denied metric, got " + denied);
    }

    @Test
    @DisplayName("should NOT increment metrics when quota is disabled")
    void shouldNotIncrementMetricsWhenQuotaDisabled() {
        // Default quota is disabled
        quotaService.acquireConversationSlot();
        quotaService.acquireApiCallSlot();

        double allowed = meterRegistry.find("eddi.tenant.quota.allowed").counter().count();
        assertEquals(0.0, allowed, "Metrics should not increment when quota is disabled");
    }

    @Test
    @DisplayName("should track usage metrics")
    void shouldTrackUsageMetrics() {
        enableQuotaWithBothLimits(100, 100);

        quotaService.acquireConversationSlot();
        quotaService.acquireApiCallSlot();
        quotaService.recordCost(TENANT_ID, 5.0);

        double convCount = meterRegistry.find("eddi.tenant.usage.conversations").counter().count();
        double apiCount = meterRegistry.find("eddi.tenant.usage.api_calls").counter().count();

        assertEquals(1.0, convCount);
        assertEquals(1.0, apiCount);
    }

    // --- Concurrency (TOCTOU regression test) ---

    @Nested
    @DisplayName("Single-instance TOCTOU safety")
    class ConcurrencyTests {

        @Test
        @DisplayName("100 threads racing for 50 conversation slots — exactly 50 should succeed")
        void atomicConversationSlotAcquisition() throws InterruptedException {
            int limit = 50;
            int threads = 100;
            enableQuotaWithConversationLimit(limit);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            List<QuotaCheckResult> results = new CopyOnWriteArrayList<>();

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await(); // all threads start simultaneously
                        results.add(quotaService.acquireConversationSlot());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            startGate.countDown(); // release all threads
            done.await();
            pool.shutdown();

            long allowed = results.stream().filter(QuotaCheckResult::allowed).count();
            long denied = results.stream().filter(r -> !r.allowed()).count();

            assertEquals(limit, allowed,
                    "Exactly " + limit + " slots should have been acquired, but got " + allowed);
            assertEquals(threads - limit, denied,
                    "Exactly " + (threads - limit) + " should have been denied, but got " + denied);

            // Verify usage counter matches
            UsageSnapshot usage = quotaService.getUsage(TENANT_ID);
            assertEquals(limit, usage.conversationsToday(),
                    "Usage counter should match the number of acquired slots");
        }

        @Test
        @DisplayName("100 threads racing for 50 API call slots — exactly 50 should succeed")
        void atomicApiCallSlotAcquisition() throws InterruptedException {
            int limit = 50;
            int threads = 100;
            enableQuotaWithApiCallLimit(limit);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            List<QuotaCheckResult> results = new CopyOnWriteArrayList<>();

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        results.add(quotaService.acquireApiCallSlot());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            startGate.countDown();
            done.await();
            pool.shutdown();

            long allowed = results.stream().filter(QuotaCheckResult::allowed).count();
            assertEquals(limit, allowed,
                    "Exactly " + limit + " API call slots should have been acquired, but got " + allowed);
        }
    }

    // --- Helpers ---

    private void enableQuotaWithConversationLimit(int limit) {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, limit, -1, -1, -1, true));
    }

    private void enableQuotaWithApiCallLimit(int limit) {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, -1, limit, -1, true));
    }

    private void enableQuotaWithBothLimits(int convLimit, int apiLimit) {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, convLimit, -1, apiLimit, -1, true));
    }
}
