/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Micrometer meters for Agent Health collection, exposed at {@code /q/metrics}:
 * <ul>
 * <li>{@code eddi.health.recorded} — turn rows written;</li>
 * <li>{@code eddi.health.dropped} — turn records lost, tagged {@code reason}:
 * {@code queue_full} (the health worker could not keep up) or
 * {@code store_error} (the write failed), {@code shutdown} (offered while the
 * application stops) — never expected in a healthy deployment — or
 * {@code erased} (withheld, or removed right after the write, because its user
 * was being erased or its conversation had been deleted; see
 * {@code HealthTurnRecorder}) or {@code orphaned} (rows the daily orphan sweep
 * removed because their conversation no longer exists — a write that landed
 * after the delete and whose own clean-up failed; see
 * {@code HealthTurnRetention}). {@code orphaned} counts rows, the others
 * records;</li>
 * <li>{@code eddi.health.queue.depth} — records waiting for the health worker
 * (a gauge registered by {@code HealthTurnRecorder}).</li>
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
    /**
     * Records of a user being erased, or of a deleted conversation; not a fault.
     */
    static final String REASON_ERASED = "erased";
    /**
     * Rows of a deleted conversation that the orphan sweep removed; not a fault,
     * but each one is a clean-up that failed earlier.
     */
    static final String REASON_ORPHANED = "orphaned";

    private final MeterRegistry registry;
    private final Counter recorded;

    @Inject
    public HealthMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.recorded = Counter.builder(RECORDED).description("Agent Health turn rows written").register(registry);
        // At zero from the start, so the first drop is a rise rate() can see.
        dropped0(REASON_QUEUE_FULL);
        dropped0(REASON_STORE_ERROR);
        dropped0(REASON_SHUTDOWN);
        dropped0(REASON_ERASED);
        dropped0(REASON_ORPHANED);
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

    void dropped(String reason, long count) {
        if (count > 0) {
            dropped0(reason).increment(count);
        }
    }

    private Counter dropped0(String reason) {
        return Counter.builder(DROPPED).description("Agent Health turn records lost").tag("reason", reason).register(registry);
    }
}
