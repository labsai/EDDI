/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.impl.PgVectorConnectionPools.Login;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.AgroalDataSourceMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The pool cache's eviction must never strand a store: stores hold a handle,
 * not a pool, and a pool is not closed while connections are borrowed from it.
 * Pools are fakes — Agroal cannot open a connection without a server.
 */
class PgVectorConnectionPoolsTest {

    /**
     * A fake pool: refuses to lend once closed, and reports a settable borrow
     * count.
     */
    private static final class FakePool {
        final AgroalDataSource pool = mock(AgroalDataSource.class);
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicLong active = new AtomicLong();
        final Login login;
        /** Signalled when a gated borrow is inside {@code getConnection()}. */
        final CountDownLatch entered = new CountDownLatch(1);
        /**
         * When set, {@code getConnection()} waits for it, then counts the connection as
         * borrowed — the way Agroal's {@code activeCount()} only includes a connection
         * once the borrow has completed.
         */
        volatile CountDownLatch gate;

        FakePool(Login login) throws SQLException {
            this.login = login;
            AgroalDataSourceMetrics metrics = mock(AgroalDataSourceMetrics.class);
            when(metrics.activeCount()).thenAnswer(inv -> active.get());
            when(pool.getMetrics()).thenReturn(metrics);
            doAnswer(inv -> {
                closed.set(true);
                return null;
            }).when(pool).close();
            when(pool.getConnection()).thenAnswer(inv -> {
                CountDownLatch g = gate;
                if (g != null) {
                    entered.countDown();
                    if (!g.await(5, TimeUnit.SECONDS)) {
                        throw new SQLException("test gate was never opened");
                    }
                }
                if (closed.get()) {
                    throw new SQLException("This pool is closed and does not handle any more connections!");
                }
                if (g != null) {
                    active.incrementAndGet();
                }
                return mock(Connection.class);
            });
        }
    }

    private final List<FakePool> created = new ArrayList<>();
    private final Map<AgroalDataSource, FakePool> byPool = new IdentityHashMap<>();

    private PgVectorConnectionPools pools() {
        return new PgVectorConnectionPools(login -> {
            try {
                FakePool fake = new FakePool(login);
                created.add(fake);
                byPool.put(fake.pool, fake);
                return fake.pool;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static DataSource handle(PgVectorConnectionPools pools, int i) {
        return pools.get("db" + i + ".example.com", 5432, "eddi", "eddi", "secret", 10);
    }

    private FakePool poolOf(String host) {
        return created.stream().filter(p -> p.login.host().equals(host)).reduce((a, b) -> b).orElseThrow();
    }

    /**
     * Opens far more logins than the cache holds, each used several times, until
     * the first pool is evicted.
     */
    private void flood(PgVectorConnectionPools pools, int from) throws SQLException {
        for (int i = from; i < from + 3 * PgVectorConnectionPools.MAX_POOLS; i++) {
            DataSource other = handle(pools, i);
            for (int use = 0; use < 4; use++) {
                other.getConnection();
            }
        }
        pools.size();
    }

    @Test
    @DisplayName("a store whose pool was evicted still gets a connection: its handle opens a new pool")
    void evictedPoolIsReopened() throws SQLException {
        PgVectorConnectionPools pools = pools();
        DataSource store = handle(pools, 0);
        assertNotNull(store.getConnection());
        FakePool first = poolOf("db0.example.com");

        flood(pools, 1);
        assertTrue(first.closed.get(), "the flood must evict and close the idle first pool");
        assertTrue(pools.size() <= PgVectorConnectionPools.MAX_POOLS);

        assertNotNull(assertDoesNotThrow(() -> store.getConnection()), "a store must survive its pool being evicted");
        FakePool reopened = poolOf("db0.example.com");
        assertFalse(reopened.closed.get());
        assertTrue(reopened != first, "the handle must have opened a new pool for the same login");
    }

    @Test
    @DisplayName("an evicted pool with borrowed connections stays open until they are returned; closeAll closes it regardless")
    void busyPoolIsRetiredNotClosed() throws SQLException {
        PgVectorConnectionPools pools = pools();
        DataSource store = handle(pools, 0);
        store.getConnection();
        FakePool busy = poolOf("db0.example.com");
        busy.active.set(1); // an ingestion run is still writing through it

        flood(pools, 1);
        assertEquals(1, pools.retiredCount(), "the busy pool is evicted from the cache but kept");
        assertFalse(busy.closed.get(), "a pool must not be closed under a borrowed connection");

        handle(pools, 1).getConnection();
        assertFalse(busy.closed.get(), "still borrowed: still open");

        busy.active.set(0); // the run returned its connection
        handle(pools, 1).getConnection();
        assertTrue(busy.closed.get(), "returned: the next lookup closes the retired pool");
        assertEquals(0, pools.retiredCount());

        // closeAll does not wait for borrowers
        DataSource again = handle(pools, 0);
        again.getConnection();
        FakePool second = poolOf("db0.example.com");
        second.active.set(1);
        flood(pools, 1000);
        assertEquals(1, pools.retiredCount());
        pools.closeAll();
        assertTrue(second.closed.get());
        assertEquals(0, pools.retiredCount());
        assertTrue(byPool.values().stream().allMatch(p -> p.closed.get()), "closeAll closes every pool");
    }

    @Test
    @DisplayName("a pool evicted while a borrow is inside getConnection() is retired, not closed under the borrower")
    void evictionDuringBorrowDoesNotCloseThePool() throws Exception {
        PgVectorConnectionPools pools = pools();
        DataSource store = handle(pools, 0);
        store.getConnection();
        FakePool busy = poolOf("db0.example.com");
        CountDownLatch gate = new CountDownLatch(1);
        busy.gate = gate;

        ExecutorService borrower = Executors.newSingleThreadExecutor();
        try {
            // Thread A has looked the pool up and is inside getConnection(); activeCount()
            // is still 0
            Future<Connection> borrowed = borrower.submit(() -> store.getConnection());
            assertTrue(busy.entered.await(5, TimeUnit.SECONDS), "the borrow must reach getConnection()");
            assertEquals(0, busy.active.get());

            // Evict the pool while the borrow is in progress
            flood(pools, 1);
            assertFalse(busy.closed.get(), "a pool must not be closed while a borrow is inside getConnection()");
            assertEquals(1, pools.retiredCount(), "the pool is evicted from the cache and retired");

            gate.countDown();
            assertNotNull(borrowed.get(5, TimeUnit.SECONDS), "the borrower gets its connection");
            assertEquals(1, busy.active.get());
            assertFalse(busy.closed.get(), "still borrowed: still open");
            pools.size();
            assertFalse(busy.closed.get(), "a lookup while the connection is borrowed keeps the pool");
            assertEquals(1, pools.retiredCount());

            busy.active.set(0); // the connection is returned
            handle(pools, 1).getConnection();
            assertTrue(busy.closed.get(), "returned: the next lookup closes the retired pool");
            assertEquals(0, pools.retiredCount());
        } finally {
            gate.countDown();
            borrower.shutdownNow();
            assertTrue(borrower.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
