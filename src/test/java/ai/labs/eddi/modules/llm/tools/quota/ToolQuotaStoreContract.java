/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link IToolQuotaStore} contract, run against a real database by each
 * implementation's {@code *IT}. Every test uses subjects of its own, so the
 * tests do not need an empty store.
 */
public interface ToolQuotaStoreContract {

    String AGENT = "aaaaaaaaaaaaaaaaaaaaaaaa";

    IToolQuotaStore store();

    static ToolQuotaKey key(String subject, String counter, long window) {
        return new ToolQuotaKey(AGENT, counter, subject, window);
    }

    static String freshSubject() {
        return "subject-" + UUID.randomUUID();
    }

    static Instant inTheFuture() {
        return Instant.now().plusSeconds(3600);
    }

    @Test
    default void countsUpToTheLimitAndThenRefuses() {
        var key = key(freshSubject(), "tool:a", 86_400L);
        for (int i = 1; i <= 3; i++) {
            var acquisition = store().tryAcquire(key, 3, inTheFuture());
            assertTrue(acquisition.granted(), "call " + i);
            assertEquals(i, acquisition.used());
        }
        var refused = store().tryAcquire(key, 3, inTheFuture());
        assertFalse(refused.granted());
        assertEquals(3, refused.used());
        assertEquals(3, store().used(key), "a refusal does not count");
    }

    @Test
    default void aLimitOfOneAllowsExactlyOneEvenOnTheFirstInsert() {
        var key = key(freshSubject(), "tool:a", 86_400L);
        assertTrue(store().tryAcquire(key, 1, inTheFuture()).granted());
        assertFalse(store().tryAcquire(key, 1, inTheFuture()).granted());
    }

    @Test
    default void usedIsAReadAndZeroWithoutARow() {
        var key = key(freshSubject(), "tool:a", 86_400L);
        assertEquals(0, store().used(key));
        assertEquals(0, store().used(key), "reading creates nothing");
        store().tryAcquire(key, 5, inTheFuture());
        assertEquals(1, store().used(key));
    }

    @Test
    default void releaseGivesOneBackAndNeverGoesBelowZero() {
        var key = key(freshSubject(), "tool:a", 86_400L);
        store().release(key);
        assertEquals(0, store().used(key), "releasing nothing leaves nothing");
        store().tryAcquire(key, 1, inTheFuture());
        assertFalse(store().tryAcquire(key, 1, inTheFuture()).granted());
        store().release(key);
        assertEquals(0, store().used(key));
        store().release(key);
        assertEquals(0, store().used(key), "never negative");
        assertTrue(store().tryAcquire(key, 1, inTheFuture()).granted(), "the freed call can be used again");
    }

    @Test
    default void subjectsCountersAndWindowsAreIndependent() {
        String subject = freshSubject();
        assertTrue(store().tryAcquire(key(subject, "tool:a", 86_400L), 1, inTheFuture()).granted());
        assertTrue(store().tryAcquire(key(freshSubject(), "tool:a", 86_400L), 1, inTheFuture()).granted(), "another subject");
        assertTrue(store().tryAcquire(key(subject, "pool:a", 86_400L), 1, inTheFuture()).granted(), "another counter");
        assertTrue(store().tryAcquire(key(subject, "tool:a", 172_800L), 1, inTheFuture()).granted(), "the next window");
        assertFalse(store().tryAcquire(key(subject, "tool:a", 86_400L), 1, inTheFuture()).granted());
    }

    @Test
    default void aLoweredLimitRefusesWithoutDisturbingTheCount() {
        var key = key(freshSubject(), "tool:a", 86_400L);
        for (int i = 0; i < 5; i++) {
            store().tryAcquire(key, 10, inTheFuture());
        }
        assertFalse(store().tryAcquire(key, 3, inTheFuture()).granted(), "5 already used against a limit that is now 3");
        assertEquals(5, store().used(key));
    }

    /** N threads race for L slots: exactly L are granted, never more. */
    @Test
    default void concurrentCallersNeverOvershoot() throws Exception {
        int limit = 5;
        int threads = 24;
        var key = key(freshSubject(), "tool:race", 86_400L);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            outcomes.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return store().tryAcquire(key, limit, inTheFuture()).granted();
            }));
        }
        ready.await();
        go.countDown();
        int granted = 0;
        for (Future<Boolean> outcome : outcomes) {
            if (outcome.get()) {
                granted++;
            }
        }
        pool.shutdown();
        assertEquals(limit, granted, "exactly the limit, whatever the interleaving");
        assertEquals(limit, store().used(key));
    }

    @Test
    default void concurrentCallersOnManyFreshKeysEachGetExactlyTheirLimit() throws Exception {
        // Fresh keys exercise the first-insert race, the part of an upsert that goes
        // wrong.
        int limit = 2;
        int perKey = 8;
        int keys = 10;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        List<ToolQuotaKey> all = new ArrayList<>();
        for (int k = 0; k < keys; k++) {
            var key = key(freshSubject(), "tool:fresh", 86_400L);
            all.add(key);
            for (int i = 0; i < perKey; i++) {
                outcomes.add(pool.submit(() -> store().tryAcquire(key, limit, inTheFuture()).granted()));
            }
        }
        int granted = 0;
        for (Future<Boolean> outcome : outcomes) {
            if (outcome.get()) {
                granted++;
            }
        }
        pool.shutdown();
        assertEquals(limit * keys, granted);
        for (ToolQuotaKey key : all) {
            assertEquals(limit, store().used(key));
        }
    }

    @Test
    default void deleteAndFindBySubject() {
        String subject = freshSubject();
        String other = freshSubject();
        store().tryAcquire(key(subject, "tool:a", 86_400L), 5, inTheFuture());
        store().tryAcquire(key(subject, "pool:p", 172_800L), 5, inTheFuture());
        store().tryAcquire(key(other, "tool:a", 86_400L), 5, inTheFuture());

        List<ToolQuotaUsage> found = store().findBySubject(subject, 10);
        assertEquals(2, found.size());
        assertEquals(172_800L, found.get(0).windowStart(), "newest window first");
        assertEquals(AGENT, found.get(0).agentId());
        assertEquals("pool:p", found.get(0).counter());
        assertEquals(1, found.get(0).used());
        assertEquals(1, store().findBySubject(subject, 1).size(), "the cap applies");

        assertEquals(2, store().deleteBySubject(subject));
        assertTrue(store().findBySubject(subject, 10).isEmpty());
        assertEquals(1, store().used(key(other, "tool:a", 86_400L)), "another subject is untouched");
        assertEquals(0, store().deleteBySubject(subject));
    }

    @Test
    default void deleteExpiredRemovesOnlyRowsPastTheirExpiry() {
        String old = freshSubject();
        String live = freshSubject();
        store().tryAcquire(key(old, "tool:a", 86_400L), 5, Instant.now().minusSeconds(7200));
        store().tryAcquire(key(live, "tool:a", 86_400L), 5, Instant.now().plusSeconds(7200));

        assertTrue(store().deleteExpired(Instant.now()) >= 1);

        assertEquals(0, store().findBySubject(old, 10).size());
        assertEquals(1, store().findBySubject(live, 10).size());
    }
}
