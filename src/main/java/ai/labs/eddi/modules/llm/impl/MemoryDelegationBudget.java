/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.SpendReservations;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.modules.llm.tools.DelegationBudget;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.DoubleSupplier;

/**
 * The {@link DelegationBudget} of one conversation, read from and written to
 * its memory.
 * <ul>
 * <li>{@link #remainingUsd} — the conversation's effective ceiling
 * ({@link LlmSpendGate#effectiveCeiling}: the agent's
 * {@code maxCostPerConversationUsd}, tightened by a schedule's
 * {@code maxCostPerFire} or by a delegation that reached this conversation)
 * less what it has spent and holds. What the calling task has spent and not yet
 * recorded is not known here, so the figure can be high by that much; the
 * caller's tool loop, which does know it, stops at its next round.</li>
 * <li>{@link #charge} — adds the amount to the current step's
 * {@link MemoryKeys#AUDIT_DELEGATED_COST}, which every conversation total and
 * ceiling counts and the tenant meter does not (the callee's own turn already
 * charged the tenant).</li>
 * </ul>
 */
final class MemoryDelegationBudget implements DelegationBudget {

    private final IConversationMemory memory;

    MemoryDelegationBudget(IConversationMemory memory) {
        this.memory = memory;
    }

    @Override
    public OptionalDouble remainingUsd() {
        if (memory == null) {
            return OptionalDouble.empty();
        }
        SpendReservations reservations = memory.getSpendReservations();
        if (reservations == null) {
            return remaining(null);
        }
        // Ceiling, spend and holds from one view: under the monitor every charge-back
        // and reservation takes.
        return reservations.computeExclusively(() -> remaining(reservations));
    }

    private OptionalDouble remaining(SpendReservations reservations) {
        OptionalDouble ceiling = LlmSpendGate.effectiveCeiling(memory);
        if (!LlmSpendGate.ceilingApplies(ceiling)) {
            return OptionalDouble.empty();
        }
        double spent = LlmSpendGate.conversationCost(memory) + (reservations != null ? reservations.heldUsd() : 0.0);
        return OptionalDouble.of(Math.max(0.0, ceiling.getAsDouble() - spent));
    }

    /**
     * Reads what is left and holds all of it in one step, under the conversation's
     * {@link SpendReservations} monitor: the effective ceiling (whose baseline is
     * itself a spend read) and the recorded spend are both read there
     * ({@link SpendReservations#reserveHeadroom(DoubleSupplier, double)}), and
     * {@link #charge} and every release take the same monitor, so neither a
     * parallel delegation nor a charge-back can land between the read and the hold.
     * A delegation that finds no headroom (or no reservations to hold it in) is
     * refused with an allowance of {@code 0}; only a conversation with no ceiling
     * at all gets no allowance (unbounded) and no hold.
     */
    @Override
    public Hold admit() {
        if (memory == null) {
            return Hold.NONE;
        }
        SpendReservations reservations = memory.getSpendReservations();
        if (reservations == null) {
            // Nothing to hold a reservation in: refused under a ceiling, unbounded
            // without one.
            return LlmSpendGate.ceilingApplies(LlmSpendGate.effectiveCeiling(memory)) ? REFUSED : Hold.NONE;
        }
        return reservations.computeExclusively(() -> admitLocked(reservations));
    }

    private Hold admitLocked(SpendReservations reservations) {
        OptionalDouble ceiling = LlmSpendGate.effectiveCeiling(memory);
        if (!LlmSpendGate.ceilingApplies(ceiling)) {
            return Hold.NONE;
        }
        Optional<SpendReservations.Reservation> held = reservations.reserveHeadroom(() -> LlmSpendGate.conversationCost(memory),
                ceiling.getAsDouble());
        if (held.isEmpty()) {
            return REFUSED;
        }
        SpendReservations.Reservation reservation = held.get();
        OptionalDouble allowance = OptionalDouble.of(reservation.amountUsd());
        return new Hold() {
            @Override
            public OptionalDouble allowance() {
                return allowance;
            }

            @Override
            public void release() {
                reservation.close();
            }

            @Override
            public void keep(double usd) {
                reservation.settle(usd);
            }
        };
    }

    /** No headroom: an allowance of {@code 0}, nothing held. */
    private static final Hold REFUSED = new Hold() {
        private final OptionalDouble none = OptionalDouble.of(0.0);

        @Override
        public OptionalDouble allowance() {
            return none;
        }

        @Override
        public void release() {
            // nothing held
        }

        @Override
        public void keep(double usd) {
            // nothing held
        }
    };

    @Override
    public void reserve(double usd) {
        if (memory == null || !Double.isFinite(usd) || !(usd > 0.0)) {
            return;
        }
        SpendReservations reservations = memory.getSpendReservations();
        if (reservations != null) {
            reservations.tryReserve(0.0, Double.MAX_VALUE, usd);
        }
    }

    @Override
    public void charge(double usd) {
        // !(usd > 0.0): NaN fails every comparison, and one NaN would disable every
        // ceiling compared against the total.
        if (memory == null || !Double.isFinite(usd) || !(usd > 0.0)) {
            return;
        }
        var step = memory.getCurrentStep();
        if (step == null) {
            return;
        }
        // Under the reservations monitor (the one admit() reads the spend in), so an
        // admission sees the charge wholly or not at all; and under the step's, as
        // parallel tool calls of one task may charge at once and the read-add-store
        // must not lose one of them.
        SpendReservations reservations = memory.getSpendReservations();
        if (reservations != null) {
            reservations.exclusively(() -> addToStep(step, usd));
        } else {
            addToStep(step, usd);
        }
    }

    private static void addToStep(IWritableConversationStep step, double usd) {
        synchronized (step) {
            IData<Object> existing = step.getData(MemoryKeys.AUDIT_DELEGATED_COST);
            double total = usd;
            if (existing != null && existing.getResult() instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() > 0.0) {
                total += n.doubleValue();
            }
            step.storeData(new Data<>(MemoryKeys.AUDIT_DELEGATED_COST, total));
        }
    }
}
