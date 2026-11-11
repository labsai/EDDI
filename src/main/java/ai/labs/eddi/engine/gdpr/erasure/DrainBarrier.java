/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.erasure;

import java.time.Duration;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The drain barrier of an {@link AsyncErasureWriter}, for a writer that queues
 * its work on an {@link Executor}: submit through {@link #execute} and answer
 * {@link AsyncErasureWriter#awaitDrained} with {@link #awaitDrained}.
 * <p>
 * Every task gets a sequence number when it is submitted; a barrier waits until
 * no task numbered at or below the last one submitted before it is still
 * outstanding. That holds for any executor — one thread or many, any order —
 * and a task the executor rejects is forgotten at once. A task that never
 * finishes (an executor shut down with {@code shutdownNow}) keeps every later
 * barrier waiting until its timeout, which the erasure reports as a failure
 * rather than a guarantee it could not give.
 */
public final class DrainBarrier {

    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentSkipListSet<Long> outstanding = new ConcurrentSkipListSet<>();
    private final Object monitor = new Object();

    /**
     * Submits {@code task} to {@code executor}, tracked by this barrier.
     *
     * @throws RejectedExecutionException
     *             as the executor does; the task is then not tracked
     */
    public void execute(Executor executor, Runnable task) {
        long seq = sequence.incrementAndGet();
        outstanding.add(seq);
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    done(seq);
                }
            });
        } catch (RuntimeException e) {
            done(seq);
            throw e;
        }
    }

    /**
     * Waits until every task submitted before this call has finished.
     *
     * @return {@code false} on timeout
     */
    public boolean awaitDrained(Duration timeout) throws InterruptedException {
        long target = sequence.get();
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            while (pendingUpTo(target)) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(monitor, left);
            }
            return true;
        }
    }

    /** How many tracked tasks have not finished yet. */
    public int outstanding() {
        return outstanding.size();
    }

    private boolean pendingUpTo(long target) {
        // ceiling, not first(): first() throws when the set empties concurrently.
        Long first = outstanding.ceiling(Long.MIN_VALUE);
        return first != null && first <= target;
    }

    private void done(long seq) {
        outstanding.remove(seq);
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }
}
