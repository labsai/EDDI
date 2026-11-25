/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.adoption;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.gdpr.erasure.AsyncErasureWriter;
import ai.labs.eddi.engine.gdpr.erasure.DrainBarrier;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * An async writer shaped like Agent Health's turn recorder, built only from the
 * public API of {@code engine.gdpr.erasure} (this package is outside it): one
 * worker thread behind a bounded queue that rejects when full, rows that hold
 * the plain user id and are <em>deleted</em>, not blanked, and a GDPR
 * participant that deletes by user id. It is how the tests prove that a writer
 * of that shape can give the erasure guarantee with nothing but
 * {@link DrainBarrier}, {@link ErasureGuard#beforeWrite},
 * {@link ErasureGuard#afterWrite} and {@link ErasureGuard#verifyUserErased}.
 * <p>
 * Several instances may share one row map: each is a node, and an instance the
 * erasing node's guard does not list is a node its drain barrier cannot reach.
 */
public class HealthShapedWriter implements AsyncErasureWriter, AutoCloseable {

    /** Recorded in pending removals. */
    public static final String NAME = "agentHealthTurns";

    /** One health row: the turn's id, its user (plain), its conversation. */
    public record Row(String id, String userId, String conversationId) {
    }

    private final Map<String, Row> rows;
    private final ErasureGuard guard;
    private final DrainBarrier barrier = new DrainBarrier();
    private final ThreadPoolExecutor executor;

    /** The row store refuses every write and delete while set. */
    public volatile boolean storeDown;
    /** The row store accepts writes but refuses deletes while set. */
    public volatile boolean failDeletes;
    /**
     * Test seam: when set, the worker waits on it after {@code beforeWrite} passed
     * and before it inserts — a write in flight.
     */
    public volatile CountDownLatch holdBeforeInsert;
    /** Counted down when the worker reached the hold. */
    public volatile CountDownLatch reachedHold;

    public HealthShapedWriter(Map<String, Row> rows, ErasureGuard guard, int queueCapacity) {
        this.rows = rows;
        this.guard = guard;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Queues the write of a turn captured at {@code capturedAt}, as the recorder
     * does after the turn.
     *
     * @return false when the queue was full and the row was dropped
     */
    public boolean record(String rowId, String userId, String conversationId, Instant capturedAt) {
        try {
            barrier.execute(executor, () -> write(new Row(rowId, userId, conversationId), capturedAt));
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private void write(Row row, Instant capturedAt) {
        if (!guard.beforeWrite(row.userId(), row.conversationId(), capturedAt)) {
            return;
        }
        CountDownLatch hold = holdBeforeInsert;
        if (hold != null) {
            if (reachedHold != null) {
                reachedHold.countDown();
            }
            try {
                hold.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (storeDown) {
            return;
        }
        rows.put(row.id(), row);
        guard.afterWrite(NAME, row.id(), row.userId(), row.conversationId(), capturedAt, () -> delete(row.id()));
    }

    private void delete(String rowId) {
        if (storeDown || failDeletes) {
            throw new IllegalStateException("health store down");
        }
        rows.remove(rowId);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean awaitDrained(Duration timeout) throws InterruptedException {
        return barrier.awaitDrained(timeout);
    }

    @Override
    public void removeRow(String rowId) {
        delete(rowId);
    }

    @Override
    public long eraseConversation(String conversationId) {
        if (storeDown || failDeletes) {
            throw new IllegalStateException("health store down");
        }
        int before = rows.size();
        rows.values().removeIf(r -> conversationId.equals(r.conversationId()));
        return before - rows.size();
    }

    @Override
    public long countForConversation(String conversationId) {
        return rows.values().stream().filter(r -> conversationId.equals(r.conversationId())).count();
    }

    /** The writer's GDPR participant: delete the user's rows, then verify. */
    public IGdprParticipant participant() {
        return new IGdprParticipant() {
            @Override
            public String name() {
                return NAME;
            }

            @Override
            public long erase(String userId) {
                if (storeDown || failDeletes) {
                    throw new IllegalStateException("health store down");
                }
                int before = rows.size();
                rows.values().removeIf(r -> userId.equals(r.userId()));
                guard.verifyUserErased(NAME, userId, () -> rows.values().stream().filter(r -> userId.equals(r.userId())).count());
                return before - rows.size();
            }

            @Override
            public Object export(String userId) {
                return null;
            }
        };
    }

    @Override
    public void close() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }
}
