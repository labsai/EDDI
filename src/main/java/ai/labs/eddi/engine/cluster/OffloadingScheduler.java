/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link ScheduledExecutorService} whose timer threads only keep time: every
 * task runs on a virtual thread of its own.
 * <p>
 * The cluster layer's periodic jobs — presence writes, drain-key sync, the
 * dead-letter forwarder, the HITL recovery sweep, the event outbox check, the
 * console's polls — used to share the two platform threads of one
 * {@code ScheduledThreadPoolExecutor}. Most of them block on NATS or the
 * database, so two slow ones (a request timing out against a stream leader that
 * is being elected) stalled every other job, presence included: a presence
 * record that is not rewritten within its 30 s TTL makes the other nodes judge
 * this one dead and take its leases.
 * <p>
 * A periodic task never overlaps itself: a tick that finds the previous run
 * still going is skipped, which is what {@code scheduleWithFixedDelay} on a
 * thread pool amounted to. Unlike there, an exception does not cancel later
 * runs; it is logged.
 * <p>
 * {@link #schedule(Callable, long, TimeUnit)} is the one exception: its future
 * carries the callable's result, so it runs on the timer. No cluster component
 * uses it.
 */
final class OffloadingScheduler implements ScheduledExecutorService {

    private static final Logger LOGGER = Logger.getLogger(OffloadingScheduler.class);

    private final ScheduledExecutorService timer;
    private final ExecutorService workers;

    OffloadingScheduler(ScheduledExecutorService timer, String name) {
        this.timer = timer;
        this.workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(name + "-job-", 0).factory());
    }

    private Runnable offloaded(Runnable command) {
        return () -> workers.execute(() -> runLogged(command));
    }

    private Runnable offloadedOnce(Runnable command) {
        AtomicBoolean running = new AtomicBoolean();
        return () -> {
            if (!running.compareAndSet(false, true)) {
                LOGGER.debugf("Periodic cluster job still running, tick skipped: %s", command);
                return;
            }
            try {
                workers.execute(() -> {
                    try {
                        runLogged(command);
                    } finally {
                        running.set(false);
                    }
                });
            } catch (RuntimeException e) {
                running.set(false);
                throw e;
            }
        };
    }

    private static void runLogged(Runnable command) {
        try {
            command.run();
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Cluster job failed: %s", e.getMessage());
        }
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return timer.schedule(offloaded(command), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return timer.schedule(callable, delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return timer.scheduleAtFixedRate(offloadedOnce(command), initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return timer.scheduleWithFixedDelay(offloadedOnce(command), initialDelay, delay, unit);
    }

    @Override
    public void execute(Runnable command) {
        workers.execute(() -> runLogged(command));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return workers.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return workers.submit(task, result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return workers.submit(task);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return workers.invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return workers.invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return workers.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return workers.invokeAny(tasks, timeout, unit);
    }

    @Override
    public void shutdown() {
        timer.shutdown();
        workers.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        List<Runnable> pending = timer.shutdownNow();
        workers.shutdownNow();
        return pending;
    }

    @Override
    public boolean isShutdown() {
        return timer.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return timer.isTerminated() && workers.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        return timer.awaitTermination(timeout, unit) && workers.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }
}
