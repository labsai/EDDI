/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import java.util.Optional;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * The paid calls of one conversation that are in flight: what each is expected
 * to cost, held against the agent's {@code maxCostPerConversationUsd} until the
 * call is over. Without it, calls that start at the same time all read the same
 * spend, all pass the same ceiling check, and the ceiling is overrun by every
 * one of them at once.
 * <h3>The rule</h3> A call is admitted when the conversation's spend so far
 * plus every reservation still held stays <em>below</em> the ceiling. Its own
 * expected cost is not part of the comparison, so the call that crosses the
 * ceiling still completes — the rule that applies to a single call since the
 * ceiling exists — but a second call can no longer start on the strength of a
 * figure the first one is about to make stale.
 * <h3>Lifecycle</h3> {@link #tryReserve} holds the expected cost;
 * {@link Reservation#settle} replaces it with the actual cost once the call is
 * done, and {@link Reservation#close} releases whatever is held. A caller that
 * records the actual cost in memory ({@code audit:cost}) itself closes the
 * reservation right after, so the amount is counted once; a caller that leaves
 * the recording to a later point settles and keeps the reservation open until
 * then. Close in a {@code finally}: a failed call releases its reservation like
 * a finished one.
 * <h3>Scope</h3> One instance per loaded conversation memory, held in memory
 * and never persisted. That is exactly the scope the ceiling needs: the ceiling
 * is computed from the conversation's own steps, and its turns are serialized —
 * by the conversation coordinator on one node and by the conversation lease in
 * a cluster — so every paid call of a conversation runs on the node that holds
 * it. Concurrency only exists <em>within</em> a turn, which this class makes
 * safe. It does not reserve against the tenant's monthly budget, which is
 * shared by every conversation of every node and read, not reserved.
 * <p>
 * Thread-safe; every method is short and holds only this object's monitor.
 */
public final class SpendReservations {

    private double heldUsd;
    private int open;

    /**
     * Admits one call and holds {@code expectedUsd} for it, or refuses.
     *
     * @param spentUsd
     *            the conversation's priced spend that is already settled — every
     *            step's {@code audit:cost} plus what the caller has spent but not
     *            yet recorded
     * @param ceilingUsd
     *            the ceiling; not negative
     * @param expectedUsd
     *            what the call is expected to cost; negative, NaN and infinite
     *            values count as 0
     * @return the reservation, or empty when {@code spentUsd} plus everything
     *         already held reaches the ceiling
     */
    public synchronized Optional<Reservation> tryReserve(double spentUsd, double ceilingUsd, double expectedUsd) {
        if (spentUsd + heldUsd >= ceilingUsd) {
            return Optional.empty();
        }
        double amount = sanitize(expectedUsd);
        heldUsd += amount;
        open++;
        return Optional.of(new Reservation(amount));
    }

    /**
     * Reserves all the headroom left — {@code ceilingUsd} less {@code spentUsd}
     * less what is already held — in one step, so nothing reserved in between can
     * push the total above the ceiling.
     *
     * @return the reservation, holding the headroom it took; empty when there is
     *         none
     */
    public synchronized Optional<Reservation> reserveHeadroom(double spentUsd, double ceilingUsd) {
        return reserveHeadroom(() -> spentUsd, ceilingUsd);
    }

    /**
     * As {@link #reserveHeadroom(double, double)}, with the recorded spend read
     * <em>inside</em> this object's monitor: a charge written under the same
     * monitor (see {@link #exclusively}) then lands either wholly before the read
     * or wholly after the reservation, never between them.
     */
    public synchronized Optional<Reservation> reserveHeadroom(DoubleSupplier spentUsd, double ceilingUsd) {
        double headroom = ceilingUsd - spentUsd.getAsDouble() - heldUsd;
        if (!Double.isFinite(headroom) || !(headroom > 0.0)) {
            return Optional.empty();
        }
        heldUsd += headroom;
        open++;
        return Optional.of(new Reservation(headroom));
    }

    /**
     * Runs {@code action} under this object's monitor, the one every reservation,
     * settlement and release takes, so a write to the recorded spend it makes is
     * atomic with respect to {@link #reserveHeadroom(DoubleSupplier, double)}.
     */
    public synchronized void exclusively(Runnable action) {
        action.run();
    }

    /**
     * As {@link #exclusively(Runnable)}, returning what {@code action} computes:
     * for a decision (the ceiling, the headroom, the hold) that must be taken from
     * one consistent view of the spend.
     */
    public synchronized <T> T computeExclusively(Supplier<T> action) {
        return action.get();
    }

    /** What the calls in flight are holding right now. */
    public synchronized double heldUsd() {
        return heldUsd;
    }

    /** How many reservations are open. */
    public synchronized int openReservations() {
        return open;
    }

    private synchronized void adjust(double delta, boolean closing) {
        heldUsd += delta;
        if (closing) {
            open--;
        }
        if (open == 0 || heldUsd < 0.0) {
            // Floating-point residue must not accumulate into a phantom reservation.
            heldUsd = open == 0 ? 0.0 : Math.max(0.0, heldUsd);
        }
    }

    private static double sanitize(double usd) {
        return Double.isFinite(usd) && usd > 0.0 ? usd : 0.0;
    }

    /**
     * One admitted call. Not shared between threads: the call that reserved it
     * settles and closes it.
     */
    public final class Reservation implements AutoCloseable {
        private double amount;
        private boolean closed;

        private Reservation(double amount) {
            this.amount = amount;
        }

        /** What this reservation holds right now; 0 once closed. */
        public double amountUsd() {
            return closed ? 0.0 : amount;
        }

        /**
         * Replaces the expected cost with the actual one and keeps holding it until
         * {@link #close}. No effect once closed.
         */
        public void settle(double actualUsd) {
            if (closed) {
                return;
            }
            double actual = sanitize(actualUsd);
            adjust(actual - amount, false);
            amount = actual;
        }

        /** Releases what is held. Idempotent. */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            adjust(-amount, true);
            amount = 0.0;
        }
    }
}
