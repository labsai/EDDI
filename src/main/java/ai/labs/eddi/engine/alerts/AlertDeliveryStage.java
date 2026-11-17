/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The first delivery of a freshly written alert, on its own threads — outside
 * the GDPR drain barrier of the alert worker ({@link AlertDispatcher}).
 * <p>
 * The alert worker stores an event (the guarded write) and hands the send to
 * this stage. An erasure therefore waits only for the writes queued before it,
 * never for a slow Slack or webhook call: a stuck webhook or an alert storm can
 * no longer fail every erasure and every conversation delete.
 * <p>
 * Sending erased content stays impossible: the stage re-runs the event's guard
 * check right before the send (an erasure that started meanwhile removes the
 * content first), and every send reads the event under a send claim the
 * erasure's redaction waits for ({@link AlertDeliveryService}).
 * <p>
 * A full queue is no loss: the event was stored with {@code nextAttemptAt} a
 * claim lease away, so the maintenance pass delivers it then; the skip is
 * counted as {@code eddi_alerts_dropped_total{reason="delivery_queue_full"}}.
 */
@ApplicationScoped
public class AlertDeliveryStage {

    private static final Logger LOGGER = Logger.getLogger(AlertDeliveryStage.class);

    static final String REASON_DELIVERY_QUEUE_FULL = "delivery_queue_full";
    private static final int THREADS = 2;

    private final AlertMetrics metrics;
    private final ThreadPoolExecutor executor;

    @Inject
    public AlertDeliveryStage(AlertMetrics metrics, AlertSettings settings) {
        this.metrics = metrics;
        AtomicInteger threads = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, settings.queueCapacity())), task -> {
                    Thread thread = new Thread(task, "eddi-alerts-delivery-" + threads.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Queues a first delivery.
     *
     * @return {@code false} when the queue is full — the maintenance pass delivers
     *         the event after its claim lease
     */
    public boolean submit(Runnable delivery) {
        try {
            executor.execute(() -> {
                try {
                    delivery.run();
                } catch (RuntimeException e) {
                    LOGGER.warn("Alert delivery failed; the maintenance pass retries it", e);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            metrics.dropped(REASON_DELIVERY_QUEUE_FULL);
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
