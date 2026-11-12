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
