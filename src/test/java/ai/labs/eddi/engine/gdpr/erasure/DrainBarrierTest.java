/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.erasure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DrainBarrier")
class DrainBarrierTest {

    private final ExecutorService single = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        single.shutdownNow();
    }

    @Test
    @DisplayName("waits for the work queued before it, then passes")
    void waitsForQueuedWork() throws Exception {
        DrainBarrier barrier = new DrainBarrier();
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean written = new AtomicBoolean();
        barrier.execute(single, () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            written.set(true);
        });
        assertFalse(barrier.awaitDrained(Duration.ofMillis(50)), "the queued write has not finished");
        release.countDown();
        assertTrue(barrier.awaitDrained(Duration.ofSeconds(5)));
        assertTrue(written.get(), "the barrier passed only after the write");
    }

    @Test
    @DisplayName("does not wait for work queued after it")
    void ignoresLaterWork() throws Exception {
        DrainBarrier barrier = new DrainBarrier();
        ExecutorService other = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch releaseBefore = new CountDownLatch(1);
            CountDownLatch releaseAfter = new CountDownLatch(1);
            barrier.execute(single, () -> await(releaseBefore));
            Future<Boolean> waiting = other.submit(() -> barrier.awaitDrained(Duration.ofSeconds(10)));
            Thread.sleep(200); // the barrier has taken its snapshot
            // Queued after the barrier started, and blocked: it must not hold the barrier.
            barrier.execute(other, () -> await(releaseAfter));
            releaseBefore.countDown();
            assertTrue(waiting.get(5, TimeUnit.SECONDS));
            assertEquals(1, barrier.outstanding());
            releaseAfter.countDown();
        } finally {
            other.shutdown();
            assertTrue(other.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("a rejected task is forgotten at once and never holds a barrier")
    void rejected() throws Exception {
        DrainBarrier barrier = new DrainBarrier();
        ThreadPoolExecutor full = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        CountDownLatch release = new CountDownLatch(1);
        try {
            Runnable blocked = () -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            barrier.execute(full, blocked);
            barrier.execute(full, blocked);
            assertThrows(RejectedExecutionException.class, () -> barrier.execute(full, blocked));
            assertEquals(2, barrier.outstanding());
            release.countDown();
            assertTrue(barrier.awaitDrained(Duration.ofSeconds(5)));
            assertEquals(0, barrier.outstanding());
        } finally {
            full.shutdownNow();
        }
    }

    @Test
    @DisplayName("a task that throws still counts as finished")
    void throwingTask() throws Exception {
        DrainBarrier barrier = new DrainBarrier();
        barrier.execute(single, () -> {
            throw new IllegalStateException("boom");
        });
        assertTrue(barrier.awaitDrained(Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("a task discarded by shutdownNow keeps the barrier from passing — a timeout, never a false 'drained'")
    void discardedTask() throws Exception {
        DrainBarrier barrier = new DrainBarrier();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        barrier.execute(executor, () -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        barrier.execute(executor, () -> {
        });
        started.await();
        executor.shutdownNow();
        assertFalse(barrier.awaitDrained(Duration.ofMillis(200)));
    }
}
