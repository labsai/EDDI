/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
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

    // --- Agent capacity ---

    @Test
    @DisplayName("checkAgentQuota — allows when below the agent limit")
    void shouldAllowBelowAgentLimit() {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, 3, -1, -1.0, true));

        assertTrue(quotaService.checkAgentQuota(TENANT_ID, 2).allowed());
    }

    @Test
    @DisplayName("checkAgentQuota — denies at exactly the agent limit")
    void shouldDenyAtAgentLimit() {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, 3, -1, -1.0, true));

        QuotaCheckResult result = quotaService.checkAgentQuota(TENANT_ID, 3);

        assertFalse(result.allowed());
        assertTrue(result.reason().contains("Agent limit (3)"));
        assertEquals(1.0, meterRegistry.counter("eddi.tenant.quota.denied", "tenant", TENANT_ID, "type", "agent").count());
    }

    @Test
    @DisplayName("checkAgentQuota — -1 means unlimited")
    void shouldAllowUnlimitedAgents() {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, -1, -1, -1.0, true));

        assertTrue(quotaService.checkAgentQuota(TENANT_ID, 9999).allowed());
    }

    @Test
    @DisplayName("checkAgentQuota — disabled quota allows anything")
    void shouldAllowWhenAgentQuotaDisabled() {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, 1, -1, -1.0, false));

        assertTrue(quotaService.checkAgentQuota(TENANT_ID, 100).allowed());
    }

    @Test
    @DisplayName("checkAgentQuota — does not touch the allowed counter (parity with checkCostBudget)")
    void shouldNotCountAllowedOnAgentCheck() {
        quotaStore.setQuota(new TenantQuota(TENANT_ID, -1, 3, -1, -1.0, true));

        quotaService.checkAgentQuota(TENANT_ID, 1);

        // eddi.tenant.quota.allowed counts slot ACQUISITIONS; a read-only gate must
        // not inflate it, or the allowed/denied ratio becomes meaningless.
        assertEquals(0.0, meterRegistry.counter("eddi.tenant.quota.allowed").count());
    }

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

    /**
     * The gates read quota configuration through a short-TTL cache (it used to be a
     * store round trip per turn, twice per request on the hottest path in the
     * system), so a tightened limit reaches enforcement through the service's
     * write-through — the path {@code RestTenantQuota} uses.
     */
    @Test
    @DisplayName("should enforce tightened quota at runtime")
    void shouldUpdateQuotaAtRuntime() {
        enableQuotaWithApiCallLimit(5);

        // Initially within limit
        assertTrue(quotaService.acquireApiCallSlot().allowed());

        // Tighten the limit to 1
        quotaService.setQuota(new TenantQuota(TENANT_ID, -1, -1, 1, -1, true));

        // Now exceeded (1 already acquired)
        QuotaCheckResult result = quotaService.acquireApiCallSlot();
        assertFalse(result.allowed());
        assertEquals(1, quotaStore.getQuota(TENANT_ID).maxApiCallsPerMinute(),
                "the write must reach the store, not just drop a cache entry");
    }

    /**
     * Finding 16. {@code ConversationService} calls a gate at conversation start
     * and again on every say/sayStreaming, and each one opened with
     * {@code quotaStore.getQuota(tenantId)} — a pooled-connection checkout per turn
     * on PostgreSQL, usually only to learn that quotas are disabled.
     */
    @Test
    @DisplayName("quota configuration is read once per tenant, not once per turn")
    void quotaConfigurationIsCachedAcrossTurns() {
        var countingStore = new CountingQuotaStore(new TenantQuota(TENANT_ID, -1, -1, -1, -1, false));
        var service = new TenantQuotaService(countingStore, meterRegistry, TENANT_ID);

        for (int turn = 0; turn < 10; turn++) {
            assertTrue(service.acquireApiCallSlot().allowed());
        }

        assertEquals(1, countingStore.reads,
                "the disabled-quota short-circuit must not cost a store round trip per turn");
    }

    /**
     * The CDI wiring itself. {@code init()} is what attaches the cache in a real
     * deployment — the test constructor attaches its own, so a regression that
     * dropped the line from {@code init()} would be invisible to every other test
     * here and would put a store round trip back on every turn in production.
     */
    @Test
    @DisplayName("@PostConstruct wires the cache, so a CDI-built service reads the store once per tenant")
    void postConstructAttachesTheQuotaCache() {
        var countingStore = new CountingQuotaStore(new TenantQuota(TENANT_ID, -1, -1, -1, -1, false));
        var service = new TenantQuotaService();
        service.quotaStore = countingStore;
        service.meterRegistry = meterRegistry;
        service.cacheFactory = new CacheFactory();
        service.defaultTenantId = TENANT_ID;

        service.init();

        for (int turn = 0; turn < 5; turn++) {
            assertTrue(service.acquireApiCallSlot().allowed());
        }
        assertEquals(1, countingStore.reads,
                "without the cache attached in init() every turn pays a pooled-connection checkout");
    }

    /**
     * Caffeine rejects a null key outright, so an unidentified tenant must bypass
     * the cache and ask the store directly rather than throwing on the hottest path
     * in the system. The store has no row for it, which is the "unlimited" case, so
     * the turn is allowed — and, because nothing was cached, the next unidentified
     * turn asks again rather than being served a pinned answer.
     */
    @Test
    @DisplayName("a null tenant bypasses the cache instead of throwing, and is never cached")
    void nullTenantBypassesTheCache() {
        // Both DB-backed stores answer "no row" for a null tenant (a find/SELECT on
        // tenant_id = NULL matches nothing); the in-memory one is a ConcurrentHashMap
        // and would throw, which is not the contract under test here.
        var countingStore = new NullTolerantQuotaStore(new TenantQuota(TENANT_ID, -1, -1, -1, -1, false));
        var service = new TenantQuotaService(countingStore, meterRegistry, TENANT_ID);

        assertTrue(service.acquireConversationSlot(null).allowed());
        assertTrue(service.acquireConversationSlot(null).allowed());

        assertEquals(2, countingStore.reads, "a null key cannot be cached, so both turns read the store");
    }

    /** Answers "no row" for a null tenant, as both DB-backed stores do. */
    private static final class NullTolerantQuotaStore extends InMemoryTenantQuotaStore {
        private int reads;

        private NullTolerantQuotaStore(TenantQuota defaultQuota) {
            super(defaultQuota);
        }

        @Override
        public TenantQuota getQuota(String tenantId) {
            reads++;
            return tenantId == null ? null : super.getQuota(tenantId);
        }
    }

    /**
     * A tenant with no quota row is the "unlimited" case and is deliberately NOT
     * cached — a {@code ConcurrentMap} cannot hold a null, and pinning "no quota"
     * would delay a newly bootstrapped row by the TTL.
     */
    @Test
    @DisplayName("a tenant with no quota row is allowed and its absence is not cached")
    void anUnknownTenantIsAllowedAndNotCached() {
        var countingStore = new CountingQuotaStore(new TenantQuota(TENANT_ID, -1, -1, -1, -1, false));
        var service = new TenantQuotaService(countingStore, meterRegistry, TENANT_ID);

        assertTrue(service.acquireConversationSlot("tenant-with-no-row").allowed());
        assertTrue(service.acquireConversationSlot("tenant-with-no-row").allowed());

        assertEquals(2, countingStore.reads,
                "caching a null would mean a quota created a second later did not apply until the TTL expired");
    }

    /**
     * Counts {@code getQuota} calls; everything else is the in-memory store.
     */
    private static final class CountingQuotaStore extends InMemoryTenantQuotaStore {
        private int reads;

        private CountingQuotaStore(TenantQuota defaultQuota) {
            super(defaultQuota);
        }

        @Override
        public TenantQuota getQuota(String tenantId) {
            reads++;
            return super.getQuota(tenantId);
        }
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

    // --- Prometheus exposition (tag-key collision regression test) ---

    @Nested
    @DisplayName("Prometheus exposition")
    class PrometheusExpositionTests {

        /**
         * A {@link PrometheusMeterRegistry} keeps only the first tag-key shape
         * registered under a given metric name and silently drops every later one — no
         * exception, no warning. This service used to register
         * {@code eddi.tenant.quota.denied} untagged in {@code init()} and then again
         * with {@code tenant}+{@code type} at each denial, so the tagged series never
         * reached {@code /q/metrics} and the per-tenant breakdown documented in
         * {@code docs/metrics.md} did not exist.
         * <p>
         * {@link SimpleMeterRegistry} tolerates the collision and happily reports both
         * shapes, which is exactly why every other test in this class missed it. This
         * one asserts against a real Prometheus scrape.
         */
        @Test
        @DisplayName("denials reach the scrape carrying tenant and type labels")
        void deniedCounterIsExposedWithItsLabels() {
            var prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            var store = new InMemoryTenantQuotaStore(TenantQuota.unlimited(TENANT_ID));
            var service = new TenantQuotaService(store, prometheus, TENANT_ID);
            store.setQuota(new TenantQuota(TENANT_ID, 1, -1, -1, -1, true));

            assertTrue(service.acquireConversationSlot().allowed());
            assertFalse(service.acquireConversationSlot().allowed(), "second slot must be denied");

            String scrape = prometheus.scrape();
            String denied = scrape.lines()
                    .filter(l -> l.startsWith("eddi_tenant_quota_denied_total"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "eddi_tenant_quota_denied_total absent from scrape:\n" + scrape));

            assertTrue(denied.contains("tenant=\"" + TENANT_ID + "\""),
                    "denial series lost its tenant label — a colliding untagged registration is "
                            + "shadowing it: " + denied);
            assertTrue(denied.contains("type=\"conversation\""),
                    "denial series lost its type label: " + denied);
        }
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

    /**
     * An accounting outage refuses the request for safety, which is right — but it
     * is not the tenant being over a limit. Counting it on
     * {@code eddi.tenant.quota.denied} made a database failure look exactly like an
     * exhausted allowance on the very graph an operator uses to decide whether to
     * raise a limit, and only the reason string distinguished them.
     */
    @Test
    @DisplayName("an accounting outage is counted apart from an over-quota denial")
    void accountingOutageIsNotCountedAsAQuotaDenial() {
        var failingStore = new ITenantQuotaStore() {
            @Override
            public TenantQuota getQuota(String tenantId) {
                return new TenantQuota(TENANT_ID, 1000, -1, -1, -1, true);
            }

            @Override
            public void setQuota(TenantQuota quota) {
            }

            @Override
            public void deleteQuota(String tenantId) {
            }

            @Override
            public List<TenantQuota> listQuotas() {
                return List.of();
            }

            @Override
            public QuotaCheckResult tryIncrementConversations(String tenantId, int limit) {
                return QuotaCheckResult.unavailable("Quota accounting unavailable — denying request for safety");
            }

            @Override
            public QuotaCheckResult tryIncrementApiCalls(String tenantId, int limit) {
                return QuotaCheckResult.OK;
            }

            @Override
            public QuotaCheckResult tryAddCost(String tenantId, double cost, double limit) {
                return QuotaCheckResult.OK;
            }

            @Override
            public double getMonthlyCost(String tenantId) {
                return 0;
            }

            @Override
            public UsageSnapshot getUsage(String tenantId) {
                return null;
            }

            @Override
            public void resetUsage(String tenantId) {
            }
        };
        var service = new TenantQuotaService(failingStore, meterRegistry, TENANT_ID);

        QuotaCheckResult result = service.acquireConversationSlot();

        assertFalse(result.allowed(), "still fail closed");
        assertTrue(result.accountingUnavailable(), "and say which kind of refusal it is");
        assertNull(meterRegistry.find("eddi.tenant.quota.denied").counter(),
                "a store outage must not spike the tenant's over-quota graph");
        assertEquals(1.0, meterRegistry.counter("eddi.tenant.quota.unavailable",
                "tenant", TENANT_ID, "type", "conversation").count());
    }

    /**
     * Finding f2-01. The test above fails the store's <em>write</em> half, which is
     * only reachable in a partial outage. In a real one the very first call fails —
     * every gate opens by reading the tenant's configuration — and that call was
     * unwrapped: {@code MongoTenantQuotaStore.getQuota} let the driver exception
     * escape into {@code ConversationService}'s generic handler as an opaque 500
     * with no tick on {@code eddi.tenant.quota.unavailable}, while
     * {@code PostgresTenantQuotaStore.getQuota} returned null and thereby switched
     * enforcement off for every tenant. One outage, two answers, neither of them
     * the documented one.
     */
    @Test
    @DisplayName("a configuration read that fails is an accounting outage at every gate — not a 500, not a bypass")
    void unreadableQuotaConfigurationRefusesHonestlyAtEveryGate() {
        var service = new TenantQuotaService(new UnreachableQuotaStore(), meterRegistry, TENANT_ID);

        List<QuotaCheckResult> results = List.of(
                service.acquireConversationSlot(),
                service.acquireApiCallSlot(),
                service.checkAgentQuota(TENANT_ID, 0),
                service.checkCostBudget(TENANT_ID),
                service.recordCost(TENANT_ID, 1.0));

        for (QuotaCheckResult result : results) {
            assertFalse(result.allowed(), "fail closed — a configuration nobody can read is not permission to proceed");
            assertTrue(result.accountingUnavailable(),
                    "503 quota_accounting_unavailable, not 429 quota_exceeded and not an escaping 500");
            assertEquals(ITenantQuotaStore.ACCOUNTING_UNAVAILABLE, result.reason(),
                    "the same reason both backends give for the write half, so parity holds on the wire too");
        }

        assertNull(meterRegistry.find("eddi.tenant.quota.denied").counter(),
                "a store outage must not spike the tenant's over-quota graph");
        assertEquals(1.0, meterRegistry.counter("eddi.tenant.quota.unavailable",
                "tenant", TENANT_ID, "type", "conversation").count(), "conversation-start gate");
        assertEquals(1.0, meterRegistry.counter("eddi.tenant.quota.unavailable",
                "tenant", TENANT_ID, "type", "api_call").count(), "say / sayStreaming gate");
        assertEquals(1.0, meterRegistry.counter("eddi.tenant.quota.unavailable",
                "tenant", TENANT_ID, "type", "agent").count(), "deployment gate");
        assertEquals(2.0, meterRegistry.counter("eddi.tenant.quota.unavailable",
                "tenant", TENANT_ID, "type", "cost").count(), "the pre-call gate and the post-call accounting");
    }

    /**
     * A refusal is not cached, so the next turn asks the store again instead of
     * being pinned to the outage for {@code QUOTA_CACHE_TTL}.
     */
    @Test
    @DisplayName("an unreadable configuration is not cached as a verdict")
    void anOutageIsNotCachedAsAVerdict() {
        var store = new UnreachableQuotaStore();
        var service = new TenantQuotaService(store, meterRegistry, TENANT_ID);

        assertTrue(service.acquireConversationSlot().accountingUnavailable());
        store.reachable = true;

        assertTrue(service.acquireConversationSlot().allowed(),
                "the store came back; the gate must ask it again rather than serve a cached refusal");
    }

    /**
     * A store that cannot be reached. {@code getQuota} cannot say so in its return
     * value — {@code null} there already means "no quota configured", which the
     * service reads as unlimited — so it raises the refusal instead.
     */
    private static final class UnreachableQuotaStore extends InMemoryTenantQuotaStore {

        private boolean reachable;

        private UnreachableQuotaStore() {
            super(TenantQuota.unlimited(TENANT_ID));
        }

        @Override
        public TenantQuota getQuota(String tenantId) {
            if (!reachable) {
                throw new QuotaAccountingUnavailableException(ITenantQuotaStore.ACCOUNTING_UNAVAILABLE);
            }
            return super.getQuota(tenantId);
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
