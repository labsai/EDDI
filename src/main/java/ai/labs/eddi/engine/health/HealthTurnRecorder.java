/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.gdpr.erasure.DrainBarrier;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard.Admission;
import ai.labs.eddi.engine.health.HealthTurnFactory.Capture;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.memory.IConversationMemory;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * <p>
 * <b>Erasure and deletion win over a queued write</b> — the GDPR erasure
 * guarantee ({@link ErasureGuard}, with {@link HealthErasureWriter} as this
 * worker's writer). A row can wait in the queue while its user is erased or its
 * conversation deleted; written after the delete, it would outlive it. So
 * <ul>
 * <li>every write is queued through a {@link DrainBarrier}: an erasure or a
 * permanent conversation delete waits, on every node, until the work queued
 * before its tombstone has finished;</li>
 * <li>before writing, the worker asks {@link ErasureGuard#beforeWrite} with the
 * turn's capture time and writes nothing — neither the row nor its discard
 * markers — when it is refused (the user was erased after the turn was
 * captured, the conversation was deleted, or the tombstones cannot be read),
 * counting {@code eddi_health_dropped_total{reason="erased"}};</li>
 * <li>after writing — also after a failed write, part of which may have landed
 * — it calls {@link ErasureGuard#afterWrite} for the row and for each marker,
 * with a remover of exactly that row: a tombstone that went up meanwhile has
 * the row deleted again, or a durable pending removal recorded that keeps the
 * erasure failing until it is gone.</li>
 * </ul>
 * The tombstone reads run on this worker, never on the turn's thread.
 */
@ApplicationScoped
@Startup
public class HealthTurnRecorder {

    private static final Logger LOGGER = Logger.getLogger(HealthTurnRecorder.class);

    private static final long WARN_INTERVAL_MILLIS = 60_000;

    private final Instance<IHealthTurnStore> store;
    private final ErasureGuard erasureGuard;
    private final HealthSettings settings;
    private final HealthMetrics metrics;
    private final Clock clock;
    private final ThreadPoolExecutor executor;
    private final DrainBarrier barrier = new DrainBarrier();
    private final AtomicLong lastQueueWarning = new AtomicLong();
    private final AtomicLong lastStoreWarning = new AtomicLong();
    private final AtomicLong lastGuardWarning = new AtomicLong();

    @Inject
    public HealthTurnRecorder(Instance<IHealthTurnStore> store, ErasureGuard erasureGuard, HealthSettings settings, HealthMetrics metrics) {
        this(store, erasureGuard, settings, metrics, Clock.systemUTC());
    }

    /** Without the erasure guard — for tests of the queue alone. */
    HealthTurnRecorder(Instance<IHealthTurnStore> store, HealthSettings settings, HealthMetrics metrics, Clock clock) {
        this(store, null, settings, metrics, clock);
    }

    HealthTurnRecorder(Instance<IHealthTurnStore> store, ErasureGuard erasureGuard, HealthSettings settings, HealthMetrics metrics, Clock clock) {
        this.store = store;
        this.erasureGuard = erasureGuard;
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
        record(memory, conversationId, kind, durationMs, abandoned, true);
    }

    /**
     * As above.
     *
     * @param committed
     *            {@code false} when the turn's snapshot was not stored (a pause
     *            whose compare-and-store lost, a conflict, a fenced or failed
     *            write): unsettled, like an abandoned turn — inserted only if
     *            absent, discarding nothing — and never recorded as paused
     */
    public void record(IConversationMemory memory, String conversationId, ErrorKind kind, Long durationMs, boolean abandoned,
                       boolean committed) {
        record(memory, conversationId, kind, durationMs, abandoned, committed, true);
    }

    /**
     * As above.
     *
     * @param discards
     *            {@code false} when the turn's snapshot was not stored although its
     *            row is written (a turn that failed before its store): the
     *            executions it lists as discarded are not marked (see
     *            {@link HealthTurnFactory#capture})
     */
    public void record(IConversationMemory memory, String conversationId, ErrorKind kind, Long durationMs, boolean abandoned,
                       boolean committed, boolean discards) {
        if (!settings.enabled() || memory == null) {
            return;
        }
        Capture capture;
        try {
            capture = HealthTurnFactory.capture(memory, conversationId, kind, durationMs, clock.instant(), abandoned, committed, discards);
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
            barrier.execute(executor, () -> write(capture));
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
        HealthTurn turn = capture.turn();
        // beforeWrite fails closed on its own: a tombstone store that cannot be read
        // refuses.
        Admission admission = admit(turn);
        if (admission != null && !admission.allowed()) {
            // Neither the row nor its discard markers: the user or the conversation was
            // erased.
            metrics.dropped(HealthMetrics.REASON_ERASED);
            return;
        }
        List<String> markers = List.of();
        try {
            IHealthTurnStore turns = store.get();
            if (!capture.settled()) {
                // What the conversation keeps may be the earlier execution, or nothing at
                // all: never replace a row on the strength of this one.
                turns.insertIfAbsent(turn);
            } else {
                turns.upsert(turn);
                // After the new row: the discard is only true once the replacing turn
                // exists.
                if (!capture.discardedExecutionIds().isEmpty()) {
                    markers = List.copyOf(capture.discardedExecutionIds());
                    turns.markDiscarded(turn, markers);
                }
            }
            metrics.recorded();
        } catch (RuntimeException e) {
            metrics.dropped(HealthMetrics.REASON_STORE_ERROR);
            if (due(lastStoreWarning)) {
                LOGGER.warnf("Could not store an Agent Health turn row (conversation %s): %s — rows are being lost; see "
                        + "eddi_health_dropped_total", sanitize(turn.conversationId()), sanitize(e.getMessage()));
            }
        }
        // Also after a failed write: part of it (the row, some markers) may have
        // landed.
        if (admission != null) {
            guardWritten(turn, markers, admission);
        }
    }

    /**
     * The guard's admission of {@code turn}'s write, captured when the turn
     * finished; {@code null} without a guard (everything allowed).
     */
    private Admission admit(HealthTurn turn) {
        if (erasureGuard == null) {
            return null;
        }
        Instant capturedAt = turn.updatedAt() != null ? turn.updatedAt() : turn.timestamp();
        return erasureGuard.beforeWrite(turn.userId(), turn.conversationId(), capturedAt);
    }

    /**
     * The post-write check of every row this write may have stored — the turn's row
     * and each discard marker: an erasure that started while they were being
     * written has each one removed again, or its removal recorded as pending.
     */
    private void guardWritten(HealthTurn turn, List<String> markers, Admission admission) {
        String conversationId = turn.conversationId();
        boolean withheld = false;
        try {
            withheld = afterWrite(conversationId, turn.executionId(), admission);
            for (String executionId : markers) {
                if (!executionId.equals(turn.executionId())) {
                    withheld |= afterWrite(conversationId, executionId, admission);
                }
            }
        } catch (RuntimeException e) {
            // afterWrite handles store failures itself; this is the never-throw promise.
            if (due(lastGuardWarning)) {
                LOGGER.warnf("The erasure check after an Agent Health write failed (conversation %s): %s", sanitize(conversationId),
                        sanitize(e.getMessage()));
            }
        }
        if (withheld) {
            metrics.dropped(HealthMetrics.REASON_ERASED);
        }
    }

    private boolean afterWrite(String conversationId, String executionId, Admission admission) {
        return erasureGuard.afterWrite(HealthErasureWriter.NAME, HealthErasureWriter.rowId(conversationId, executionId), admission,
                () -> store.get().deleteRow(conversationId, executionId));
    }

    /**
     * Waits until every write queued before the call has finished — the drain
     * barrier of {@link HealthErasureWriter}.
     *
     * @return {@code false} on timeout
     */
    public boolean awaitDrained(Duration timeout) throws InterruptedException {
        return barrier.awaitDrained(timeout);
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
