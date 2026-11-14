/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.SpendReservations;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.modules.llm.tools.DelegationBudget;

import java.util.OptionalDouble;

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
        OptionalDouble ceiling = LlmSpendGate.effectiveCeiling(memory);
        if (!LlmSpendGate.ceilingApplies(ceiling)) {
            return OptionalDouble.empty();
        }
        SpendReservations reservations = memory.getSpendReservations();
        double spent = LlmSpendGate.conversationCost(memory) + (reservations != null ? reservations.heldUsd() : 0.0);
        return OptionalDouble.of(Math.max(0.0, ceiling.getAsDouble() - spent));
    }

    /**
     * Held in the conversation's {@link SpendReservations}, which every ceiling
     * check of this turn counts, and never released: the turn's memory carries it
     * until the turn ends. Not persisted — a later turn sees only what was charged.
     */
    /**
     * Reads what is left and holds all of it in one step
     * ({@link SpendReservations#tryReserve} refuses once the spend plus what is
     * held reaches the ceiling): a parallel delegation of the same turn then finds
     * the allowance taken and is refused rather than handed it a second time.
     */
    @Override
    public Hold admit() {
        OptionalDouble remaining = remainingUsd();
        SpendReservations reservations = memory != null ? memory.getSpendReservations() : null;
        if (remaining.isEmpty() || reservations == null || !(remaining.getAsDouble() > 0.0)) {
            return DelegationBudget.super.admit();
        }
        OptionalDouble ceiling = LlmSpendGate.effectiveCeiling(memory);
        // Read and reserve the headroom in one step: a value read earlier could be
        // stale once another reservation landed in between.
        var held = reservations.reserveHeadroom(LlmSpendGate.conversationCost(memory), ceiling.getAsDouble());
        if (held.isEmpty()) {
            // Another delegation of this turn took it first.
            OptionalDouble none = OptionalDouble.of(0.0);
            return new Hold() {
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
        // Parallel tool calls of one task may charge at once: the read-add-store must
        // not lose one of them.
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
