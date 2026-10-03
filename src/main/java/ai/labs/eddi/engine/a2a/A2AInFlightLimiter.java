/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounds how many A2A turns run at once —
 * {@code eddi.a2a.max-concurrent-requests}.
 * <p>
 * The {@code /v1} adapter has had a semaphore since it shipped; A2A had none,
 * so a peer could open as many concurrent tasks as it liked, each holding a
 * worker thread for up to the task timeout and each spending the deployment's
 * LLM budget. A send that finds every slot taken is refused before any
 * conversation is started.
 * <p>
 * A permit covers the <em>turn</em>, not the HTTP request: a
 * {@code returnImmediately} send, a stream and a blocking send that outlived
 * its wait all keep their slot until the turn settles. So that a turn which
 * never reports back cannot leak a slot for good, every permit also releases
 * itself after a fixed lease. Release is idempotent; whichever comes first
 * wins.
 */
@ApplicationScoped
public class A2AInFlightLimiter {

    static final int DEFAULT_MAX_CONCURRENT = 64;

    private final Semaphore slots;
    private final int capacity;

    /**
     * Runs the lease timers. A normal release cancels its timer, and a cancelled
     * timer is removed from the queue at once, so the queue holds only the leases
     * of turns still in flight.
     */
    private final ScheduledThreadPoolExecutor leaseTimer;

    @Inject
    public A2AInFlightLimiter(@ConfigProperty(name = "eddi.a2a.max-concurrent-requests", defaultValue = "64") int maxConcurrent,
            MeterRegistry meterRegistry) {
        this.capacity = maxConcurrent > 0 ? maxConcurrent : DEFAULT_MAX_CONCURRENT;
        this.slots = new Semaphore(capacity);
        this.leaseTimer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "a2a-slot-lease");
            thread.setDaemon(true);
            return thread;
        });
        this.leaseTimer.setRemoveOnCancelPolicy(true);
        if (meterRegistry != null) {
            Gauge.builder("eddi.a2a.in_flight", this, A2AInFlightLimiter::inFlight)
                    .description("A2A turns currently holding an in-flight slot")
                    .register(meterRegistry);
        }
    }

    /**
     * Takes a slot.
     *
     * @param leaseSeconds
     *            how long the slot may be held before it is released regardless
     * @return the permit, or null when every slot is taken
     */
    public Permit tryAcquire(long leaseSeconds) {
        if (!slots.tryAcquire()) {
            return null;
        }
        Permit permit = new Permit();
        permit.lease = leaseTimer.schedule(permit::release, Math.max(1, leaseSeconds), TimeUnit.SECONDS);
        return permit;
    }

    /** Lease timers still pending — for tests. */
    int pendingLeases() {
        return leaseTimer.getQueue().size();
    }

    @PreDestroy
    void shutdown() {
        leaseTimer.shutdownNow();
    }

    /** Slots currently taken. */
    public int inFlight() {
        return capacity - slots.availablePermits();
    }

    public int capacity() {
        return capacity;
    }

    /** One taken slot; {@link #release()} may be called any number of times. */
    public final class Permit {

        private final AtomicBoolean released = new AtomicBoolean();
        private volatile ScheduledFuture<?> lease;

        private Permit() {
        }

        public void release() {
            if (released.compareAndSet(false, true)) {
                slots.release();
                ScheduledFuture<?> timer = lease;
                if (timer != null) {
                    timer.cancel(false);
                }
            }
        }

        public boolean isReleased() {
            return released.get();
        }
    }
}
