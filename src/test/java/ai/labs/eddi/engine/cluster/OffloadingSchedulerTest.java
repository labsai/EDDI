/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cluster scheduler's jobs block on NATS and the database; on two shared
 * platform threads, two blocked jobs stalled presence past its TTL (D6).
 */
@DisplayName("OffloadingScheduler")
class OffloadingSchedulerTest {

    private final CountDownLatch release = new CountDownLatch(1);
    private final OffloadingScheduler scheduler = new OffloadingScheduler(Executors.newScheduledThreadPool(2), "test");

    @AfterEach
    void tearDown() {
        release.countDown();
        scheduler.shutdownNow();
    }

    private void block() {
        try {
            release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("blocked jobs do not delay a periodic job such as presence")
    void blockedJobsDoNotStarvePresence() throws Exception {
        // More blocked jobs than the timer has threads: on the old pool, presence
        // never ran again.
        for (int i = 0; i < 4; i++) {
            scheduler.scheduleWithFixedDelay(this::block, 0, 10, TimeUnit.MILLISECONDS);
        }
        scheduler.execute(this::block);
        Thread.sleep(100);
        CountDownLatch presence = new CountDownLatch(3);
        scheduler.scheduleAtFixedRate(presence::countDown, 0, 20, TimeUnit.MILLISECONDS);
        assertTrue(presence.await(5, TimeUnit.SECONDS), "presence must keep running while other jobs block");
    }

    @Test
    @DisplayName("a periodic job never overlaps itself")
    void noSelfOverlap() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        scheduler.scheduleAtFixedRate(() -> {
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrent.decrementAndGet();
            runs.incrementAndGet();
        }, 0, 5, TimeUnit.MILLISECONDS);
        Thread.sleep(400);
        assertEquals(1, maxConcurrent.get());
        assertTrue(runs.get() >= 2, "the job kept running: " + runs.get());
    }

    @Test
    @DisplayName("an exception does not cancel later runs")
    void exceptionDoesNotCancel() throws Exception {
        CountDownLatch runs = new CountDownLatch(3);
        scheduler.scheduleWithFixedDelay(() -> {
            runs.countDown();
            throw new IllegalStateException("boom");
        }, 0, 10, TimeUnit.MILLISECONDS);
        assertTrue(runs.await(5, TimeUnit.SECONDS));
    }
}
