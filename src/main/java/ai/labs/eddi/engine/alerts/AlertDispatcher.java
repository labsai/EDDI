/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.ratings.ConversationRatingSaved;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The only thing the hooks touch: it takes a rating or a turn outcome and puts
 * the work on the alert worker — one thread, a queue of
 * {@code eddi.alerts.queue-capacity} — and returns.
 * <p>
 * <b>It never blocks and never throws into its caller.</b> A full queue drops
 * the work: {@code eddi.alerts.dropped{reason=queue_full}} counts it and a WARN
 * says so (at most once a minute). That trade is deliberate — a turn or a
 * rating request must not wait for Slack, a webhook or the database, and an
 * alert storm big enough to fill the queue is already visible in the inbox and
 * in that counter.
 */
@ApplicationScoped
public class AlertDispatcher {

    private static final Logger LOGGER = Logger.getLogger(AlertDispatcher.class);

    static final String REASON_QUEUE_FULL = "queue_full";
    private static final long WARN_INTERVAL_MILLIS = 60_000;

    private final AlertEngine engine;
    private final AlertMetrics metrics;
    private final AlertSettings settings;
    private final ThreadPoolExecutor executor;
    private final AtomicLong lastDropWarning = new AtomicLong();

    @Inject
    public AlertDispatcher(AlertEngine engine, AlertMetrics metrics, AlertSettings settings) {
        this.engine = engine;
        this.metrics = metrics;
        this.settings = settings;
        AtomicInteger threads = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(settings.queueCapacity()), task -> {
            Thread thread = new Thread(task, "eddi-alerts-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        metrics.registerQueueDepth(executor);
    }

    public void onRatingSaved(@Observes ConversationRatingSaved saved) {
        if (saved == null || saved.rating() == null || saved.rating().agentId() == null) {
            return;
        }
        submit(() -> engine.evaluateRating(saved.rating()));
    }

    public void onTurnOutcome(@Observes TurnOutcome outcome) {
        if (outcome == null || outcome.kind() == null || outcome.agentId() == null) {
            return;
        }
        submit(() -> engine.evaluateTurn(outcome));
    }

    /**
     * Queues {@code work} for the alert worker.
     *
     * @return {@code false} when alerting is off or the queue is full (counted)
     */
    public boolean submit(Runnable work) {
        if (!settings.enabled()) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    LOGGER.warn("Alert evaluation failed", e);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            metrics.dropped(REASON_QUEUE_FULL);
            long now = System.currentTimeMillis();
            long last = lastDropWarning.get();
            if (now - last >= WARN_INTERVAL_MILLIS && lastDropWarning.compareAndSet(last, now)) {
                LOGGER.warnf("Alert queue full (%d waiting) — alert work is being dropped; see eddi_alerts_dropped_total",
                        executor.getQueue().size());
            }
            return false;
        }
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
