/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import com.mongodb.event.ConnectionCheckOutFailedEvent;
import com.mongodb.event.ConnectionCheckedOutEvent;
import com.mongodb.event.ConnectionPoolListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * How long operations wait for a MongoDB connection from the pool, as
 * {@code eddi.datastore.pool.wait}, and how many gave up waiting, as
 * {@code eddi.datastore.pool.wait.timeouts}.
 * <p>
 * Micrometer's own pool listener reports how many operations are waiting
 * ({@code mongodb_driver_pool_waitqueuesize}), not for how long. Under load the
 * pool is where a turn's time goes: a JProfiler run at 1,000 concurrent
 * conversations found indexed single-document reads averaging 2.2–3.3 s inside
 * the driver while MongoDB's own profiler logged them at 15–20 ms. This makes
 * that wait visible without a profiler.
 */
public final class PoolWaitListener implements ConnectionPoolListener {

    private final Timer waitTimer;
    private final Counter timeouts;

    public PoolWaitListener(MeterRegistry meterRegistry) {
        this.waitTimer = Timer.builder("eddi.datastore.pool.wait")
                .description("Time spent waiting for a MongoDB connection from the pool")
                .register(meterRegistry);
        this.timeouts = Counter.builder("eddi.datastore.pool.wait.timeouts")
                .description("MongoDB connection checkouts that gave up waiting (eddi.mongodb.max-wait-time-ms)")
                .register(meterRegistry);
    }

    @Override
    public void connectionCheckedOut(ConnectionCheckedOutEvent event) {
        waitTimer.record(event.getElapsedTime(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS);
    }

    @Override
    public void connectionCheckOutFailed(ConnectionCheckOutFailedEvent event) {
        if (event.getReason() == ConnectionCheckOutFailedEvent.Reason.TIMEOUT) {
            timeouts.increment();
        }
    }
}
