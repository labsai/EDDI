/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.memory.model.ConversationState;
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
 * {@code onComplete}. For an ordinary turn that gap is harmless. For a turn
 * that ends {@code AWAITING_HUMAN} it is not: the client is told "paused" (sync
 * response, streaming {@code done}) while the store still holds the previous
 * state, so a client that reacts by reading {@code approval-status},
 * {@code pending-approvals} or {@code /resume} sees no pause — or a pause with
 * no details — and renders or resolves the wrong thing.
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
        }
    }

    /** {@code true} while a delivery is deferred and not yet delivered. */
    boolean isPending() {
        return pending.get() != null;
    }
}
