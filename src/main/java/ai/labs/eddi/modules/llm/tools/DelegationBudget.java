/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.security.ClientContextGuard;

import java.util.Map;
import java.util.OptionalDouble;

/**
 * The delegating conversation's cost ceiling, as the delegation tools see it
 * ({@link ConverseWithAgentTool}, {@link CreateSubAgentTool}).
 * <p>
 * A delegated turn runs in a conversation of its own, so without this its spend
 * reached none of the caller's ceilings — {@code maxCostPerConversationUsd}, a
 * schedule's {@code maxCostPerFire} — and only the tenant's monthly budget
 * stopped a model that kept delegating. Two halves:
 * <ul>
 * <li><b>Before</b> the turn, {@link #remainingUsd} is handed to the callee as
 * the reserved context {@value ClientContextGuard#KEY_DELEGATION_MAX_COST},
 * which its own spend gate takes as a ceiling for that turn (the tighter of it
 * and the callee's own wins). A caller with nothing left does not
 * delegate.</li>
 * <li><b>After</b> the turn, {@link #charge} adds what the callee's
 * conversation spent onto the caller's current step, where the caller's
 * ceilings count it.</li>
 * </ul>
 */
public interface DelegationBudget {

    /** No ceiling to pass down and nowhere to charge — direct construction. */
    DelegationBudget UNLIMITED = new DelegationBudget() {
        @Override
        public OptionalDouble remainingUsd() {
            return OptionalDouble.empty();
        }

        @Override
        public void charge(double usd) {
            // nothing to charge
        }
    };

    /**
     * What the caller may still spend this turn under its effective ceiling, never
     * negative; empty when no ceiling applies.
     */
    OptionalDouble remainingUsd();

    /**
     * Records {@code usd} the callee spent against the caller. Non-finite and
     * non-positive amounts are ignored.
     */
    void charge(double usd);

    /**
     * Holds {@code usd} against the caller's ceiling for the rest of this turn
     * without recording it as spent — for a delegated turn the caller stopped
     * waiting for, which may still spend up to the allowance it was handed. The
     * same money then cannot be handed out again in this turn. Default: nothing.
     */
    default void reserve(double usd) {
    }

    /**
     * One admitted delegation: the allowance handed down, held against the caller's
     * ceiling from admission until it is settled, so parallel delegations of one
     * turn cannot each be handed the same remaining money.
     */
    interface Hold {
        /** What the delegation may spend; empty when no ceiling applies. */
        OptionalDouble allowance();

        /**
         * The delegated turn is over (finished or failed): stop holding. Idempotent.
         */
        void release();

        /**
         * The caller stopped waiting while the delegated turn may still run: hold
         * {@code usd} for the rest of this turn instead of the whole allowance.
         */
        void keep(double usd);
    }

    /**
     * Admits a delegation: reads the remaining allowance and holds it in one step.
     * Default: no hold — the allowance is {@link #remainingUsd()}, and
     * {@link Hold#keep} falls back to {@link #reserve}.
     */
    default Hold admit() {
        OptionalDouble remaining = remainingUsd();
        DelegationBudget self = this;
        return new Hold() {
            @Override
            public OptionalDouble allowance() {
                return remaining;
            }

            @Override
            public void release() {
                // nothing held
            }

            @Override
            public void keep(double usd) {
                self.reserve(usd);
            }
        };
    }

    /**
     * Settles a delegated turn whose outcome the caller did not get (a timeout, a
     * failure after the start): charges what the callee's conversation has recorded
     * since {@code costBefore} — read now — and, when {@code stillRunning},
     * reserves the rest of {@code allowance}.
     */
    static void settleUnfinished(DelegationBudget budget, IConversationService conversationService, String conversationId,
                                 double costBefore, OptionalDouble allowance, boolean stillRunning) {
        settleUnfinished(budget, null, conversationService, conversationId, costBefore, allowance, stillRunning);
    }

    /**
     * As above, settling {@code hold} too: released, or — when {@code stillRunning}
     * — kept at the rest of {@code allowance}, the ORIGINAL allowance handed down
     * (what was observed since {@code costBefore} already includes a start turn's
     * spend; subtracting it from a reduced allowance would count it twice).
     */
    static void settleUnfinished(DelegationBudget budget, Hold hold, IConversationService conversationService, String conversationId,
                                 double costBefore, OptionalDouble allowance, boolean stillRunning) {
        if (budget == null) {
            return;
        }
        double observed = 0.0;
        if (conversationId != null) {
            double now = costOf(conversationService, conversationId, costBefore);
            observed = Double.isFinite(now - costBefore) && now - costBefore > 0.0 ? now - costBefore : 0.0;
            budget.charge(observed);
        }
        double rest = allowance != null && allowance.isPresent() ? Math.max(0.0, allowance.getAsDouble() - observed) : 0.0;
        if (hold != null) {
            if (stillRunning && conversationId != null && rest > 0.0) {
                hold.keep(rest);
            } else {
                hold.release();
            }
        } else if (stillRunning && conversationId != null && rest > 0.0) {
            budget.reserve(rest);
        }
    }

    /**
     * Adds the remaining allowance to a delegation's context, when a ceiling
     * applies.
     */
    static void addCeiling(Map<String, Context> context, OptionalDouble remaining) {
        if (remaining != null && remaining.isPresent()) {
            context.put(ClientContextGuard.KEY_DELEGATION_MAX_COST,
                    new Context(Context.ContextType.string, String.valueOf(remaining.getAsDouble())));
        }
    }

    /**
     * What is left of {@code allowance} once a conversation's start turn spent
     * {@code startCost} — never negative; empty stays empty.
     */
    static OptionalDouble remainderAfter(OptionalDouble allowance, double startCost) {
        if (allowance == null || allowance.isEmpty()) {
            return OptionalDouble.empty();
        }
        double spent = Double.isFinite(startCost) && startCost > 0.0 ? startCost : 0.0;
        return OptionalDouble.of(Math.max(0.0, allowance.getAsDouble() - spent));
    }

    /**
     * The spend a conversation has recorded so far. When it cannot be read,
     * {@code ifUnknown} — the caller passes the whole allowance, so an unreadable
     * start turn leaves nothing for the next turn (fail closed).
     */
    static double costOf(IConversationService conversationService, String conversationId, double ifUnknown) {
        try {
            return ConversationMemoryUtilities.totalCost(conversationService.getConversationMemorySnapshot(conversationId));
        } catch (Exception e) {
            return ifUnknown;
        }
    }

    /**
     * What the callee's conversation spent between {@code costBefore} and the
     * snapshot its turn returned — {@code 0} when either is unknown or the total
     * did not grow.
     */
    static double spentSince(double costBefore, SimpleConversationMemorySnapshot after) {
        if (after == null || after.getConversationCost() == null) {
            return 0.0;
        }
        double delta = after.getConversationCost() - costBefore;
        return Double.isFinite(delta) && delta > 0.0 ? delta : 0.0;
    }
}
