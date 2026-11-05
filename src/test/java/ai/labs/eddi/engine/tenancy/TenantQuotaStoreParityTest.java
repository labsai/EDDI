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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.Function;
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
    /** Mid-window on every axis, so nothing passes by sitting on a boundary. */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-15T12:30:30Z"), ZoneOffset.UTC);

    static Stream<Arguments> stores() {
        // Pinned, for the reason MongoTenantQuotaStoreContainerTest pins it: the
        // counter assertions below increment N times and expect N, which only holds
        // if every call landed in the same wall-clock window. That now applies to the
        // in-memory store too — its windows used to run from the first hit, so N
        // rapid increments could never straddle one; they are calendar-aligned like
        // the DB-backed stores', so on the real clock a minute (or a day) ticking
        // over mid-loop resets the counter and fails the assertion intermittently.
        //
        // Residue: a FIXED clock cannot be advanced, so these cases prove parity
        // *within* one window only. Window ROLLOVER parity — that all three reset the
        // counter at the same boundary, the behaviour that diverged in the first place
        // — needs a mutable test clock the stores read per call, which is a change to
        // their constructors rather than to this fixture.
        Supplier<ITenantQuotaStore> inMemory = () -> new InMemoryTenantQuotaStore(
                new TenantQuota("unused", -1, -1, -1, -1.0, true), FIXED_CLOCK);
        Supplier<ITenantQuotaStore> mongo = () -> new MongoTenantQuotaStore(getDatabase(), FIXED_CLOCK);
        Supplier<ITenantQuotaStore> postgres = () -> new PostgresTenantQuotaStore(
                PostgresTestBase.createDataSourceInstance(), FIXED_CLOCK);

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

    private static final long TTL = 600_000L;

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("cost reservation: a hold counts against the budget, a release gives it back")
    void reservationHoldsAndReleases(String storeName, Supplier<ITenantQuotaStore> factory) {
        ITenantQuotaStore store = factory.get();
        String tenant = freshTenant(storeName);
        store.tryAddCost(tenant, 4.0, 10.0);

        CostReservationResult five = store.tryReserveCost(tenant, 5.0, 10.0, TTL);
        assertTrue(five.granted(), storeName + ": 4 spent + 5 held fits a limit of 10");
        assertEquals(5.0, store.getUsage(tenant).reservedUsd(), 1e-9, storeName + ": the usage snapshot reports what is held");
        assertFalse(store.tryReserveCost(tenant, 2.0, 10.0, TTL).granted(), storeName + ": 4 + 5 + 2 does not fit");
        CostReservationResult one = store.tryReserveCost(tenant, 1.0, 10.0, TTL);
        assertTrue(one.granted(), storeName + ": 4 + 5 + 1 fits exactly (the limit itself is allowed)");
        assertFalse(store.tryReserveCost(tenant, 0.01, 10.0, TTL).granted(), storeName + ": nothing is left");

        store.releaseCost(tenant, five.reservation().generation(), 5.0);
        assertTrue(store.tryReserveCost(tenant, 5.0, 10.0, TTL).granted(), storeName + ": the released 5 is available again");
        assertEquals(4.0, store.getMonthlyCost(tenant), 1e-9, storeName + ": a hold never adds to the month's cost");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("cost reservation: settled spend and holds are summed against the limit")
    void reservationSeesSettledCost(String storeName, Supplier<ITenantQuotaStore> factory) {
        ITenantQuotaStore store = factory.get();
        String tenant = freshTenant(storeName);
        assertTrue(store.tryReserveCost(tenant, 6.0, 10.0, TTL).granted());
        store.tryAddCost(tenant, 3.0, 10.0);
        assertFalse(store.tryReserveCost(tenant, 1.5, 10.0, TTL).granted(), storeName + ": 3 spent + 6 held + 1.5 passes 10");
        assertTrue(store.tryReserveCost(tenant, 1.0, 10.0, TTL).granted(), storeName + ": 3 + 6 + 1 fits");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("cost reservation: concurrent callers can never hold more than the budget has left")
    void reservationIsAtomicUnderContention(String storeName, Supplier<ITenantQuotaStore> factory) throws Exception {
        ITenantQuotaStore store = factory.get();
        String tenant = freshTenant(storeName);
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            done.add(pool.submit(() -> {
                start.await();
                if (store.tryReserveCost(tenant, 1.0, 10.0, TTL).granted()) {
                    granted.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(10, granted.get(), storeName + ": exactly the 10 that fit are granted - check and hold are one operation");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("cost reservation: a hold older than the TTL no longer pins the budget")
    void reservationExpires(String storeName, Supplier<ITenantQuotaStore> factory) {
        ITenantQuotaStore store = factory.get();
        String tenant = freshTenant(storeName);
        assertTrue(store.tryReserveCost(tenant, 9.0, 10.0, TTL).granted());
        assertFalse(store.tryReserveCost(tenant, 5.0, 10.0, TTL).granted(), storeName + ": still held");
        // The clock is fixed, so a negative TTL makes every existing hold already older
        // than it.
        assertTrue(store.tryReserveCost(tenant, 5.0, 10.0, -1L).granted(), storeName + ": the unreleased 9 was dropped");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("cost reservation: releasing more than is held never goes below zero")
    void releaseNeverGoesNegative(String storeName, Supplier<ITenantQuotaStore> factory) {
        ITenantQuotaStore store = factory.get();
        String tenant = freshTenant(storeName);
        CostReservationResult two = store.tryReserveCost(tenant, 2.0, 10.0, TTL);
        store.releaseCost(tenant, two.reservation().generation(), 50.0);
        store.releaseCost(tenant, two.reservation().generation(), 50.0);
        assertTrue(store.tryReserveCost(tenant, 10.0, 10.0, TTL).granted(), storeName + ": the whole budget is free");
        assertFalse(store.tryReserveCost(tenant, 0.5, 10.0, TTL).granted(), storeName + ": and a negative hold did not appear");
    }

    /** A clock the test can step forward; the stores read it on every call. */
    private static final class SteppingClock extends Clock {
        private Instant now;

        SteppingClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static Stream<Arguments> steppingStores() {
        return Stream.of(
                Arguments.of("in-memory", (Function<Clock, ITenantQuotaStore>) clock -> new InMemoryTenantQuotaStore(
                        new TenantQuota("unused", -1, -1, -1, -1.0, true), clock)),
                Arguments.of("mongo", (Function<Clock, ITenantQuotaStore>) clock -> new MongoTenantQuotaStore(getDatabase(), clock)),
                Arguments.of("postgres", (Function<Clock, ITenantQuotaStore>) clock -> new PostgresTenantQuotaStore(
                        PostgresTestBase.createDataSourceInstance(), clock)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("steppingStores")
    @DisplayName("cost reservation: young holds survive once the shared epoch is older than the TTL")
    void youngHoldsOutliveAnOldEpoch(String storeName, Function<Clock, ITenantQuotaStore> factory) {
        SteppingClock clock = new SteppingClock(Instant.parse("2026-06-15T12:00:00Z"));
        ITenantQuotaStore store = factory.apply(clock);
        String tenant = freshTenant(storeName);

        CostReservationResult first = store.tryReserveCost(tenant, 3.0, 10.0, TTL);
        assertTrue(first.granted());
        clock.advance(Duration.ofMinutes(8));
        CostReservationResult second = store.tryReserveCost(tenant, 3.0, 10.0, TTL);
        assertTrue(second.granted());
        store.releaseCost(tenant, first.reservation().generation(), 3.0);

        // the epoch is now 12 minutes old (TTL 10), the held 3 only 4 minutes
        clock.advance(Duration.ofMinutes(4));
        assertFalse(store.tryReserveCost(tenant, 7.5, 10.0, TTL).granted(),
                storeName + ": the running call's 3 still counts although the epoch it shares is past the TTL");
        store.releaseCost(tenant, second.reservation().generation(), 3.0);
        assertTrue(store.tryReserveCost(tenant, 7.5, 10.0, TTL).granted(), storeName + ": and its release still lands");

        // nothing reserved for longer than the TTL: every hold left is a dead one
        clock.advance(Duration.ofMinutes(11));
        assertTrue(store.tryReserveCost(tenant, 10.0, 10.0, TTL).granted(),
                storeName + ": an unreleased hold is dropped once its newest hold is stale");
    }
}
