/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.erasure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
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
        CountDownLatch releaseBefore = new CountDownLatch(1);
        CountDownLatch releaseAfter = new CountDownLatch(1);
        try {
            barrier.execute(single, () -> await(releaseBefore));
            CountDownLatch snapshotTaken = new CountDownLatch(1);
            barrier.afterSnapshot = snapshotTaken::countDown;
            Future<Boolean> waiting = other.submit(() -> barrier.awaitDrained(Duration.ofSeconds(10)));
            // Not a sleep: the barrier says when it has read its target.
            assertTrue(snapshotTaken.await(5, TimeUnit.SECONDS), "the barrier never took its snapshot");
            // Queued after the barrier started, and blocked: it must not hold the barrier.
            barrier.execute(other, () -> await(releaseAfter));
            releaseBefore.countDown();
            assertTrue(waiting.get(5, TimeUnit.SECONDS));
            assertEquals(1, barrier.outstanding());
        } finally {
            // Released here, not in the body: an assertion that failed above must not
            // leave the worker blocked, or mask its own failure with a cleanup one.
            releaseBefore.countDown();
            releaseAfter.countDown();
            other.shutdownNow();
            other.awaitTermination(5, TimeUnit.SECONDS);
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
    @DisplayName("stress: under concurrent submitters and a pool, every task submitted before a barrier has run when it passes")
    void stress() throws Exception {
        DrainBarrier barrier = new DrainBarrier();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        ExecutorService submitters = Executors.newFixedThreadPool(4);
        int perSubmitter = 400;
        AtomicBoolean[] done = new AtomicBoolean[4 * perSubmitter];
        ConcurrentLinkedQueue<Integer> submitted = new ConcurrentLinkedQueue<>();
        try {
            for (int s = 0; s < 4; s++) {
                int base = s * perSubmitter;
                submitters.submit(() -> {
                    for (int i = 0; i < perSubmitter; i++) {
                        int id = base + i;
                        done[id] = new AtomicBoolean();
                        barrier.execute(pool, () -> {
                            if (id % 7 == 0) {
                                Thread.onSpinWait();
                            }
                            done[id].set(true);
                        });
                        submitted.add(id); // execute has returned: the task is tracked
                    }
                });
            }
            for (int round = 0; round < 30; round++) {
                List<Integer> before = List.copyOf(submitted);
                assertTrue(barrier.awaitDrained(Duration.ofSeconds(10)));
                for (int id : before) {
                    assertTrue(done[id].get(), "task " + id + " was submitted before the barrier but had not run");
                }
            }
            submitters.shutdown();
            assertTrue(submitters.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(barrier.awaitDrained(Duration.ofSeconds(10)));
            assertEquals(4 * perSubmitter, submitted.size());
            assertEquals(0, barrier.outstanding());
        } finally {
            pool.shutdownNow();
            submitters.shutdownNow();
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
        // Never counted down: the worker stays blocked until shutdownNow interrupts it,
        // however long the test thread is paused, so the queued task cannot run.
        CountDownLatch never = new CountDownLatch(1);
        try {
            barrier.execute(executor, () -> {
                started.countDown();
                await(never);
            });
            barrier.execute(executor, () -> {
            });
            assertTrue(started.await(5, TimeUnit.SECONDS), "the worker never started");
            executor.shutdownNow();
            assertFalse(barrier.awaitDrained(Duration.ofMillis(200)));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("an executor that discards silently (DiscardPolicy) is unsupported: later barriers time out, never a false 'drained'")
    void silentlyDiscardingExecutor() throws Exception {
        DrainBarrier barrier = new DrainBarrier();
        ThreadPoolExecutor discarding = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.DiscardPolicy());
        CountDownLatch release = new CountDownLatch(1);
        try {
            barrier.execute(discarding, () -> await(release));
            barrier.execute(discarding, () -> await(release));
            barrier.execute(discarding, () -> await(release)); // dropped without a word
            release.countDown();
            // Both accepted tasks have run once the pool terminated: the dropped one is
            // then the only entry left, however slow the worker was.
            discarding.shutdown();
            assertTrue(discarding.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(barrier.awaitDrained(Duration.ofMillis(100)), "the dropped task stays outstanding");
            assertEquals(1, barrier.outstanding());
        } finally {
            release.countDown();
            discarding.shutdownNow();
        }
    }
}
