/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer meters for Agent Health collection, exposed at {@code /q/metrics}:
 * <ul>
 * <li>{@code eddi.health.recorded} — turn rows written;</li>
 * <li>{@code eddi.health.dropped} — turn records lost, tagged {@code reason}:
 * {@code queue_full} (the health worker could not keep up) or
 * {@code store_error} (the write failed), {@code shutdown} (offered while the
 * application stops); never expected in a healthy deployment;</li>
 * <li>{@code eddi.health.queue.depth} — records waiting for the health worker
 * (a gauge registered by {@code HealthTurnRecorder});</li>
 * <li>{@code eddi.health.rollup.duration} — a timer per rollup pass;</li>
 * <li>{@code eddi.health.rollup.days} / {@code eddi.health.rollup.rows} — agent
 * days recomputed and daily rows written;</li>
 * <li>{@code eddi.health.rollup.failures} — agent days whose rollup failed
 * (retried next pass);</li>
 * <li>{@code eddi.health.rollup.pending} / {@code eddi.health.rollup.lag} —
 * gauges, as the last pass left them: agent days still waiting, and the age in
 * seconds of the oldest of them.</li>
 * </ul>
 * No tag carries an agent id: one series per agent is what the health store is
 * for, not Prometheus.
 */
@ApplicationScoped
public class HealthMetrics {

    static final String RECORDED = "eddi.health.recorded";
    static final String DROPPED = "eddi.health.dropped";
    static final String QUEUE_DEPTH = "eddi.health.queue.depth";

    static final String REASON_QUEUE_FULL = "queue_full";
    static final String REASON_STORE_ERROR = "store_error";
    /** Records offered while the application stops; not a backlog. */
    static final String REASON_SHUTDOWN = "shutdown";

    static final String ROLLUP_DURATION = "eddi.health.rollup.duration";
    static final String ROLLUP_DAYS = "eddi.health.rollup.days";
    static final String ROLLUP_ROWS = "eddi.health.rollup.rows";
    static final String ROLLUP_FAILURES = "eddi.health.rollup.failures";
    static final String ROLLUP_PENDING = "eddi.health.rollup.pending";
    static final String ROLLUP_LAG = "eddi.health.rollup.lag";

    private final MeterRegistry registry;
    private final Counter recorded;
    private final Timer rollupDuration;
    private final Counter rollupDays;
    private final Counter rollupRows;
    private final Counter rollupFailures;
    private final AtomicLong rollupPending = new AtomicLong();
    private final AtomicLong rollupLagSeconds = new AtomicLong();

    @Inject
    public HealthMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.recorded = Counter.builder(RECORDED).description("Agent Health turn rows written").register(registry);
        // At zero from the start, so the first drop is a rise rate() can see.
        dropped0(REASON_QUEUE_FULL);
        dropped0(REASON_STORE_ERROR);
        dropped0(REASON_SHUTDOWN);
        this.rollupDuration = Timer.builder(ROLLUP_DURATION).description("Agent Health rollup passes").register(registry);
        this.rollupDays = Counter.builder(ROLLUP_DAYS).description("Agent days the health rollup recomputed").register(registry);
        this.rollupRows = Counter.builder(ROLLUP_ROWS).description("Daily health rows the rollup wrote").register(registry);
        this.rollupFailures = Counter.builder(ROLLUP_FAILURES).description("Agent days whose health rollup failed").register(registry);
        Gauge.builder(ROLLUP_PENDING, rollupPending, AtomicLong::get).description("Agent days waiting for the health rollup")
                .register(registry);
        Gauge.builder(ROLLUP_LAG, rollupLagSeconds, AtomicLong::get).baseUnit("seconds")
                .description("Age of the oldest agent day waiting for the health rollup").register(registry);
    }

    void rolledUp(long days, long rows, long failures, Duration took, long pending, long lagSeconds) {
        rollupDays.increment(days);
        rollupRows.increment(rows);
        rollupFailures.increment(failures);
        rollupDuration.record(took);
        rollupPending.set(pending);
        rollupLagSeconds.set(lagSeconds);
    }

    void registerQueueDepth(ThreadPoolExecutor executor) {
        Gauge.builder(QUEUE_DEPTH, executor, e -> e.getQueue().size()).description("Turn records waiting for the health worker")
                .register(registry);
    }

    void recorded() {
        recorded.increment();
    }

    void dropped(String reason) {
        dropped0(reason).increment();
    }

    private Counter dropped0(String reason) {
        return Counter.builder(DROPPED).description("Agent Health turn records lost").tag("reason", reason).register(registry);
    }
}
