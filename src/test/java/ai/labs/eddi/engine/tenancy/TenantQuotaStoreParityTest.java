/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.datastore.mongo.MongoTestBase;
import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-store parity: every {@link ITenantQuotaStore} implementation must
 * return the SAME allow/deny verdict at the limit boundary.
 * <p>
 * This test exists because the three implementations drifted apart silently.
 * {@code PostgresTenantQuotaStore} accepted requests at exactly the daily and
 * per-minute limits (its stale-window fallback treated an unchanged counter as
 * a successful acquisition), while {@code InMemoryTenantQuotaStore} denied them
 * — so switching the backing store changed whether a quota was enforced at all.
 * Per-store unit tests could not catch that; only running one sequence through
 * all three can.
 * <p>
 * Runs the real MongoDB and PostgreSQL adapters against Testcontainers, reusing
 * the containers already shared by {@link MongoTestBase} and
 * {@link PostgresTestBase}.
 *
 * @since 6.0.0
 */
class TenantQuotaStoreParityTest extends MongoTestBase {

    private static final AtomicInteger TENANT_SEQ = new AtomicInteger();

    /**
     * Store factories, invoked lazily inside each test so that container startup
     * never happens during argument resolution.
     */
    static Stream<Arguments> stores() {
        Supplier<ITenantQuotaStore> inMemory = () -> new InMemoryTenantQuotaStore(
                new TenantQuota("unused", -1, -1, -1, -1.0, true));
        Supplier<ITenantQuotaStore> mongo = () -> new MongoTenantQuotaStore(getDatabase());
        Supplier<ITenantQuotaStore> postgres = () -> new PostgresTenantQuotaStore(
                PostgresTestBase.createDataSourceInstance());

        return Stream.of(
                Arguments.of("in-memory", inMemory),
                Arguments.of("mongo", mongo),
                Arguments.of("postgres", postgres));
    }

    private static String freshTenant(String storeName) {
        return "parity-" + storeName + "-" + TENANT_SEQ.incrementAndGet();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("daily conversations: allowed below the limit, denied at the limit")
    void conversationsDenyAtLimit(String storeName, Supplier<ITenantQuotaStore> factory) {
        ITenantQuotaStore store = factory.get();
        String tenant = freshTenant(storeName);
        int limit = 3;

        for (int i = 1; i <= limit; i++) {
            QuotaCheckResult allowed = store.tryIncrementConversations(tenant, limit);
            assertTrue(allowed.allowed(), storeName + ": conversation " + i + " of " + limit + " must be allowed");
        }

        QuotaCheckResult atLimit = store.tryIncrementConversations(tenant, limit);

        assertFalse(atLimit.allowed(), storeName + ": the request past the limit must be denied");
        assertEquals(limit, store.getUsage(tenant).conversationsToday(),
                storeName + ": a denied request must not consume a slot");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("per-minute api calls: allowed below the limit, denied at the limit")
    void apiCallsDenyAtLimit(String storeName, Supplier<ITenantQuotaStore> factory) {
        ITenantQuotaStore store = factory.get();
        String tenant = freshTenant(storeName);
        int limit = 2;

        for (int i = 1; i <= limit; i++) {
            assertTrue(store.tryIncrementApiCalls(tenant, limit).allowed(),
                    storeName + ": api call " + i + " of " + limit + " must be allowed");
        }

        assertFalse(store.tryIncrementApiCalls(tenant, limit).allowed(),
                storeName + ": the api call past the limit must be denied");
        assertEquals(limit, store.getUsage(tenant).apiCallsThisMinute(),
                storeName + ": a denied api call must not consume a slot");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("a limit of zero denies the very first request")
    void zeroLimitDeniesEverything(String storeName, Supplier<ITenantQuotaStore> factory) {
        ITenantQuotaStore store = factory.get();
        String tenant = freshTenant(storeName);

        assertFalse(store.tryIncrementConversations(tenant, 0).allowed(),
                storeName + ": a zero conversation limit must deny");
        assertFalse(store.tryIncrementApiCalls(tenant, 0).allowed(),
                storeName + ": a zero api-call limit must deny");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("a negative limit means unlimited")
    void negativeLimitIsUnlimited(String storeName, Supplier<ITenantQuotaStore> factory) {
        ITenantQuotaStore store = factory.get();
        String tenant = freshTenant(storeName);

        for (int i = 0; i < 5; i++) {
            assertTrue(store.tryIncrementConversations(tenant, -1).allowed(), storeName + ": unlimited conversations");
            assertTrue(store.tryIncrementApiCalls(tenant, -1).allowed(), storeName + ": unlimited api calls");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("monthly cost: denied once spend reaches the budget")
    void costDeniesAtBudget(String storeName, Supplier<ITenantQuotaStore> factory) {
        ITenantQuotaStore store = factory.get();
        String tenant = freshTenant(storeName);

        assertTrue(store.tryAddCost(tenant, 60.0, 100.0).allowed(), storeName + ": $60 of a $100 budget is fine");
        assertFalse(store.tryAddCost(tenant, 40.0, 100.0).allowed(),
                storeName + ": $100 spent against a $100 budget is spent");
        assertEquals(100.0, store.getMonthlyCost(tenant), 1e-9, storeName + ": spend must be readable back");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("all three counters coexist for one tenant")
    void countersCoexist(String storeName, Supplier<ITenantQuotaStore> factory) {
        ITenantQuotaStore store = factory.get();
        String tenant = freshTenant(storeName);

        assertTrue(store.tryIncrementConversations(tenant, 10).allowed());
        assertTrue(store.tryIncrementApiCalls(tenant, 10).allowed());
        assertTrue(store.tryAddCost(tenant, 7.5, 100.0).allowed());

        var usage = store.getUsage(tenant);
        assertEquals(1, usage.conversationsToday(), storeName + ": conversation counter");
        assertEquals(1, usage.apiCallsThisMinute(), storeName + ": api-call counter");
        assertEquals(7.5, usage.monthlyCostUsd(), 1e-9, storeName + ": cost counter");
    }
}
