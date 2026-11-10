/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.health.HealthTurnFactory.Capture;
import ai.labs.eddi.engine.memory.IConversationMemory;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Records every finished turn as an Agent Health turn row — after the fact, off
 * the turn-completion hook, the way alerts are evaluated.
 * <p>
 * <b>It never blocks and never throws into its caller.</b> On the turn's thread
 * it copies the few values the row needs out of the live memory
 * ({@link HealthTurnFactory}, a handful of step reads) and hands the immutable
 * row to its <em>own</em> worker — one thread, a queue of
 * {@code eddi.health.queue-capacity} — which writes it. A full queue drops the
 * row and counts {@code eddi_health_dropped_total{reason="queue_full"}}; a
 * failed write counts {@code reason="store_error"}; either logs a WARN at most
 * once a minute. A turn must not wait for the health store.
 * <p>
 * Separate from the alert worker on purpose: {@code eddi.alerts.enabled=false}
 * leaves health collection running, a slow webhook delivery never delays a
 * health write, and a backlog of one never drops the other's work.
 * {@code eddi.health.enabled=false} switches recording off; nothing else
 * changes.
 */
@ApplicationScoped
@Startup
public class HealthTurnRecorder {

    private static final Logger LOGGER = Logger.getLogger(HealthTurnRecorder.class);

    private static final long WARN_INTERVAL_MILLIS = 60_000;

    private final Instance<IHealthTurnStore> store;
    private final HealthSettings settings;
    private final HealthMetrics metrics;
    private final Clock clock;
    private final ThreadPoolExecutor executor;
    private final AtomicLong lastQueueWarning = new AtomicLong();
    private final AtomicLong lastStoreWarning = new AtomicLong();

    @Inject
    public HealthTurnRecorder(Instance<IHealthTurnStore> store, HealthSettings settings, HealthMetrics metrics) {
        this(store, settings, metrics, Clock.systemUTC());
    }

    HealthTurnRecorder(Instance<IHealthTurnStore> store, HealthSettings settings, HealthMetrics metrics, Clock clock) {
        this.store = store;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
        AtomicInteger threads = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(settings.queueCapacity()), task -> {
            Thread thread = new Thread(task, "eddi-health-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        metrics.registerQueueDepth(executor);
    }

    /**
     * Records the turn that just finished on {@code memory}.
     *
     * @param conversationId
     *            the conversation's id when the memory does not carry it yet (the
     *            start turn), else {@code null}
     * @param kind
     *            how the turn ended — {@code null} for a clean or paused turn
     * @param durationMs
     *            how long the hook saw the turn run, or {@code null}
     * @param abandoned
     *            the watchdog gave up waiting for the turn (it may still run): its
     *            row is inserted only if absent, and it discards nothing
     */
    public void record(IConversationMemory memory, String conversationId, ErrorKind kind, Long durationMs, boolean abandoned) {
        if (!settings.enabled() || memory == null) {
            return;
        }
        Capture capture;
        try {
            capture = HealthTurnFactory.capture(memory, conversationId, kind, durationMs, clock.instant(), abandoned);
        } catch (RuntimeException e) {
            // The live step may still be changing (a watchdog-abandoned turn).
            LOGGER.debugf("Could not capture a turn for Agent Health: %s", e.getMessage());
            return;
        }
        if (capture == null) {
            return;
        }
        submit(capture);
    }

    /** Queues the write of {@code capture}; {@code false} when it was dropped. */
    boolean submit(Capture capture) {
        try {
            executor.execute(() -> write(capture));
            return true;
        } catch (RejectedExecutionException e) {
            if (executor.isShutdown()) {
                // The application is stopping: not a backlog, and not worth a warning.
                metrics.dropped(HealthMetrics.REASON_SHUTDOWN);
                return false;
            }
            metrics.dropped(HealthMetrics.REASON_QUEUE_FULL);
            if (due(lastQueueWarning)) {
                LOGGER.warnf("Agent Health queue full (%d waiting) — turn records are being dropped; see eddi_health_dropped_total",
                        executor.getQueue().size());
            }
            return false;
        }
    }

    void write(Capture capture) {
        try {
            IHealthTurnStore turns = store.get();
            if (!capture.settled()) {
                // What the conversation keeps may be the earlier execution, or nothing at
                // all: never replace a row on the strength of this one.
                turns.insertIfAbsent(capture.turn());
            } else {
                turns.upsert(capture.turn());
                // After the new row: the discard is only true once the replacing turn
                // exists.
                if (!capture.discardedExecutionIds().isEmpty()) {
                    turns.markDiscarded(capture.turn(), capture.discardedExecutionIds());
                }
            }
            metrics.recorded();
        } catch (RuntimeException e) {
            metrics.dropped(HealthMetrics.REASON_STORE_ERROR);
            if (due(lastStoreWarning)) {
                LOGGER.warnf("Could not store an Agent Health turn row (conversation %s): %s — rows are being lost; see "
                        + "eddi_health_dropped_total", sanitize(capture.turn().conversationId()), sanitize(e.getMessage()));
            }
        }
    }

    private static boolean due(AtomicLong last) {
        long now = System.currentTimeMillis();
        long previous = last.get();
        return now - previous >= WARN_INTERVAL_MILLIS && last.compareAndSet(previous, now);
    }

    /** Waiting records — for tests. */
    int queued() {
        return executor.getQueue().size();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
