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

import ai.labs.eddi.engine.health.model.HealthDirtyDay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

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
 * (a gauge registered by {@code HealthTurnRecorder});</li>
 * <li>{@code eddi.health.rollup.duration} — a timer per rollup pass;</li>
 * <li>{@code eddi.health.rollup.days} / {@code eddi.health.rollup.rows} — agent
 * days recomputed and daily rows written;</li>
 * <li>{@code eddi.health.rollup.failures} — agent days whose rollup failed
 * (retried next pass);</li>
 * <li>{@code eddi.health.rollup.pending} /
 * {@code eddi.health.rollup.lag.seconds} — gauges read from the shared
 * dirty-day queue when scraped (cached for {@link #BACKLOG_CACHE}), so every
 * node reports the same cluster-wide backlog: agent days still waiting, and the
 * age in seconds of the oldest of them;</li>
 * <li>{@code eddi.health.outcomes.sessions} — session outcome rows the outcome
 * evaluation wrote (each re-evaluation counts again);</li>
 * <li>{@code eddi.health.outcomes.failures} — conversation evaluations that
 * failed (the feed retries them for up to an hour, then with their next change
 * or due time);</li>
 * <li>{@code eddi.health.outcomes.lag.seconds} — how far the outcome feed's
 * watermark is behind the clock (a gauge over the shared state, like the
 * backlog gauges).</li>
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

    static final String ROLLUP_DURATION = "eddi.health.rollup.duration";
    static final String ROLLUP_DAYS = "eddi.health.rollup.days";
    static final String ROLLUP_ROWS = "eddi.health.rollup.rows";
    static final String ROLLUP_FAILURES = "eddi.health.rollup.failures";
    static final String ROLLUP_PENDING = "eddi.health.rollup.pending";
    static final String ROLLUP_LAG = "eddi.health.rollup.lag.seconds";
    static final String OUTCOME_SESSIONS = "eddi.health.outcomes.sessions";
    static final String OUTCOME_FAILURES = "eddi.health.outcomes.failures";
    static final String OUTCOME_LAG = "eddi.health.outcomes.lag.seconds";

    private final MeterRegistry registry;
    private final Counter recorded;
    private final Timer rollupDuration;
    private final Counter rollupDays;
    private final Counter rollupRows;
    private final Counter rollupFailures;
    private final Counter outcomeSessions;
    private final Counter outcomeFailures;
    /** How long one read of the backlog serves the gauges. */
    static final Duration BACKLOG_CACHE = Duration.ofSeconds(10);

    private final AtomicReference<Backlog> backlog = new AtomicReference<>();
    private final AtomicReference<Backlog> outcomeLag = new AtomicReference<>();

    /** One read of the dirty-day queue. */
    private record Backlog(Instant readAt, double pending, double lagSeconds) {
    }

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
        this.rollupDuration = Timer.builder(ROLLUP_DURATION).description("Agent Health rollup passes").register(registry);
        this.rollupDays = Counter.builder(ROLLUP_DAYS).description("Agent days the health rollup recomputed").register(registry);
        this.rollupRows = Counter.builder(ROLLUP_ROWS).description("Daily health rows the rollup wrote").register(registry);
        this.rollupFailures = Counter.builder(ROLLUP_FAILURES).description("Agent days whose health rollup failed").register(registry);
        this.outcomeSessions = Counter.builder(OUTCOME_SESSIONS).description("Session outcome rows the health outcome evaluation wrote")
                .register(registry);
        this.outcomeFailures = Counter.builder(OUTCOME_FAILURES).description("Conversations whose health outcome evaluation failed")
                .register(registry);
    }

    /**
     * Registers the backlog gauges over the shared queue. A store that cannot be
     * read reports NaN (no value), never a stale number.
     */
    void registerBacklog(IHealthDirtyDayStore store, Clock clock) {
        Gauge.builder(ROLLUP_PENDING, () -> backlog(store, clock).pending()).description("Agent days waiting for the health rollup")
                .register(registry);
        Gauge.builder(ROLLUP_LAG, () -> backlog(store, clock).lagSeconds())
                .description("Age of the oldest agent day waiting for the health rollup").register(registry);
    }

    private Backlog backlog(IHealthDirtyDayStore store, Clock clock) {
        Instant now = clock.instant();
        Backlog last = backlog.get();
        if (last != null && last.readAt().plus(BACKLOG_CACHE).isAfter(now)) {
            return last;
        }
        Backlog read;
        try {
            long pending = store.count();
            double lag = 0;
            if (pending > 0) {
                List<HealthDirtyDay> oldest = store.oldest(1);
                if (!oldest.isEmpty() && oldest.get(0).markedAt() != null) {
                    lag = Math.max(0, Duration.between(oldest.get(0).markedAt(), now).toSeconds());
                }
            }
            read = new Backlog(now, pending, lag);
        } catch (RuntimeException e) {
            read = new Backlog(now, Double.NaN, Double.NaN);
        }
        backlog.set(read);
        return read;
    }

    void rolledUp(long days, long rows, long failures, Duration took) {
        rollupDays.increment(days);
        rollupRows.increment(rows);
        rollupFailures.increment(failures);
        rollupDuration.record(took);
    }

    /**
     * Registers {@code eddi.health.outcomes.lag.seconds}: how far the outcome
     * feed's watermark is behind the clock, read from the shared state when scraped
     * (cached for {@link #BACKLOG_CACHE}), so every node reports the same value.
     * NaN while the evaluation has never run or the store cannot be read.
     */
    void registerOutcomeLag(IHealthOutcomeStore store, Clock clock) {
        Gauge.builder(OUTCOME_LAG, () -> outcomeLag(store, clock)).description("How far the Agent Health outcome evaluation is behind")
                .register(registry);
    }

    private double outcomeLag(IHealthOutcomeStore store, Clock clock) {
        Instant now = clock.instant();
        Backlog last = outcomeLag.get();
        if (last != null && last.readAt().plus(BACKLOG_CACHE).isAfter(now)) {
            return last.lagSeconds();
        }
        double lag;
        try {
            lag = store.watermark().map(w -> (double) Math.max(0, Duration.between(w, now).toSeconds())).orElse(Double.NaN);
        } catch (RuntimeException e) {
            lag = Double.NaN;
        }
        outcomeLag.set(new Backlog(now, 0, lag));
        return lag;
    }

    void outcomesEvaluated(long sessions, long failures) {
        outcomeSessions.increment(sessions);
        outcomeFailures.increment(failures);
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
