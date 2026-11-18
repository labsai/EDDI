/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.TurnError;
import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A client-visible completion that must wait until the turn's outcome has been
 * persisted.
 * <p>
 * <b>Why it exists.</b> A pipeline turn reports completion to the caller from
 * INSIDE the pipeline callable (the {@code IConversationOutputRenderer}
 * callback), while the turn's memory is written afterwards, by the runtime's
 * {@code onComplete}. Delivered from the callback, a turn whose write then
 * failed (a 16 MB MongoDB document, a PostgreSQL data error, a fence or a
 * revision conflict) was answered as done although it did not exist, and a turn
 * that ends {@code AWAITING_HUMAN} was announced as paused while the store
 * still held the previous state. So every say turn's delivery is deferred until
 * its write outcome is known; a failed write is reported through
 * {@link #markNotStored} as a {@link TurnNotStored} error instead of the
 * answer.
 * <p>
 * <b>Contract.</b> The completion callback {@link #defer defers} the delivery
 * of a paused outcome; the persistence step {@link #deliver delivers} it once
 * the commit outcome is known, passing the state the store really holds. The
 * delivery runs at most once ({@code getAndSet}), so every exit path of the
 * turn can call {@link #deliver} as a safety net without risking a second
 * terminal event on the stream.
 */
final class DeferredDelivery {

    private static final Logger LOGGER = Logger.getLogger(DeferredDelivery.class);

    private final AtomicReference<Consumer<ConversationState>> pending = new AtomicReference<>();

    /** Work that must follow the delivery (see {@link #runAfterDelivery}). */
    private final AtomicReference<Runnable> afterDelivery = new AtomicReference<>();

    /**
     * Set by the persistence step when the turn's write failed; read by the
     * delivery (see {@link #notStored()}).
     */
    private volatile TurnError notStored;

    /** See {@link #markError}. */
    private volatile TurnError error;

    /**
     * Stores the delivery to run once the turn's outcome is persisted. Called from
     * the completion callback, on the turn's own thread, at most once per turn.
     *
     * @param delivery
     *            receives the state the store holds after the commit attempt
     */
    void defer(Consumer<ConversationState> delivery) {
        pending.set(delivery);
    }

    /**
     * Runs the deferred delivery, if any, exactly once.
     * <p>
     * Never throws: this runs from {@code finally} blocks of the persistence
     * callbacks, where an escaping exception would be reported through the Future
     * and turn a healthy conversation into ERROR.
     *
     * @param effectiveState
     *            the state the client should be told — the committed state, not the
     *            in-memory state the pipeline ended in
     */
    void deliver(ConversationState effectiveState) {
        Consumer<ConversationState> delivery = pending.getAndSet(null);
        if (delivery == null) {
            return;
        }
        try {
            delivery.accept(effectiveState);
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Deferred completion handler threw while delivering a turn outcome (state=%s)", effectiveState);
        } finally {
            runAfter(afterDelivery.getAndSet(null));
        }
    }

    /** {@code true} while a delivery is deferred and not yet delivered. */
    boolean isPending() {
        return pending.get() != null;
    }

    /**
     * Records that the turn's write failed, so the delivery reports the turn as not
     * stored instead of delivering its answer. Called by the persistence step
     * before {@link #deliver}.
     */
    void markNotStored(TurnError error) {
        this.notStored = error;
    }

    /**
     * The error of a turn whose write failed, or {@code null} when it was stored
     * (or never reached its write).
     */
    TurnError notStored() {
        return notStored;
    }

    /**
     * Records why a turn that WAS stored is still reported as failed (its long-term
     * memory write failed afterwards); the delivery puts it on the snapshot.
     */
    void markError(TurnError error) {
        this.error = error;
    }

    /** See {@link #markError}; {@code null} when nothing was recorded. */
    TurnError error() {
        return error;
    }

    /**
     * Runs {@code work} after the deferred delivery, or now when nothing is
     * deferred. A streamed turn whose pipeline failed reports the failure after its
     * final snapshot; with the snapshot held back until the write, the failure
     * report has to wait for it too, or the stream would end before the snapshot.
     * Never throws.
     */
    void runAfterDelivery(Runnable work) {
        if (work == null) {
            return;
        }
        if (!isPending()) {
            runAfter(work);
            return;
        }
        afterDelivery.set(work);
        // The delivery may have run between the check and the set: take the work
        // back and run it here (only one of the two sides gets it).
        if (!isPending()) {
            runAfter(afterDelivery.getAndSet(null));
        }
    }

    private static void runAfter(Runnable work) {
        if (work == null) {
            return;
        }
        try {
            work.run();
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Work queued behind a turn's delivery threw");
        }
    }
}
