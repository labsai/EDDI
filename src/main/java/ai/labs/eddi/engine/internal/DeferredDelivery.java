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
 * {@link #deliverNotStored} as a {@link TurnNotStored} error instead of the
 * answer.
 * <p>
 * <b>Contract.</b> The completion callback {@link #defer defers} the delivery
 * of every say turn's outcome; the persistence step {@link #deliver delivers}
 * it once the write's outcome is known (for a pause, with the state the store
 * really holds), or {@link #deliverNotStored reports} it as not stored. The
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
     * Guards the claim of the delivery together with the "callback running" flag,
     * so that no moment exists where the delivery is neither pending nor running
     * yet not finished: a call to {@link #runAfterDelivery} in that gap would run
     * its work before the terminal callback.
     */
    private final Object claimLock = new Object();

    /** {@code true} from the claim of the delivery until its callback returned. */
    private boolean delivering;

    /**
     * Set by the persistence step when the turn's write failed; read by the
     * delivery (see {@link #notStored()}).
     */
    private volatile TurnError notStored;

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
        Consumer<ConversationState> delivery = claim();
        if (delivery != null) {
            run(delivery, effectiveState);
        }
    }

    /** Takes the pending delivery and marks it running, as one step. */
    private Consumer<ConversationState> claim() {
        synchronized (claimLock) {
            Consumer<ConversationState> delivery = pending.getAndSet(null);
            if (delivery != null) {
                delivering = true;
            }
            return delivery;
        }
    }

    /**
     * Claims the delivery for a turn that is not (verifiably) stored and runs it
     * with {@code error} — one compare-and-set, so it can neither report a turn
     * another path already answered (a stored one, or one with a more precise
     * reason) nor have its reason overwritten (F5).
     *
     * @return whether this call made the delivery
     */
    boolean deliverNotStored(TurnError error) {
        Consumer<ConversationState> delivery = claim();
        if (delivery == null) {
            return false;
        }
        // Set by the claimant only, before the delivery reads it.
        this.notStored = error;
        run(delivery, ConversationState.ERROR);
        return true;
    }

    private void run(Consumer<ConversationState> delivery, ConversationState effectiveState) {
        try {
            delivery.accept(effectiveState);
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Deferred completion handler threw while delivering a turn outcome (state=%s)", effectiveState);
        } finally {
            Runnable queued;
            synchronized (claimLock) {
                delivering = false;
                queued = afterDelivery.getAndSet(null);
            }
            runAfter(queued);
        }
    }

    /** {@code true} while a delivery is deferred and not yet delivered. */
    boolean isPending() {
        return pending.get() != null;
    }

    /**
     * The error of a turn whose write failed, or {@code null} when it was stored
     * (or never reached its write).
     */
    TurnError notStored() {
        return notStored;
    }

    /**
     * Runs {@code work} after the deferred delivery's callback has returned, or now
     * when nothing is deferred or running. A streamed turn whose pipeline failed
     * reports the failure after its final snapshot; with the snapshot held back
     * until the write, the failure report has to wait for it too, or the stream
     * would end before the snapshot. Never throws.
     */
    void runAfterDelivery(Runnable work) {
        if (work == null) {
            return;
        }
        synchronized (claimLock) {
            // Pending, or claimed and its callback not yet returned: queue behind it.
            // The callback's finally block takes the work under the same lock.
            if (pending.get() != null || delivering) {
                afterDelivery.set(work);
                return;
            }
        }
        runAfter(work);
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
