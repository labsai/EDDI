/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

/**
 * Asked before a guardrail makes a paid call of its own (a {@code classifier}'s
 * model call): may it start, given what the conversation and the running task
 * have already spent? Supplied by the LLM task, which owns the spend checks —
 * the agent's {@code maxCostPerConversationUsd} and the tenant's monthly budget
 * — so a guardrail call is checked before it starts exactly like the task's own
 * model call, instead of running past a ceiling the model call would have been
 * stopped at.
 * <p>
 * A refused call does not happen. A guardrail reports it as "could not decide"
 * ({@link GuardrailUndecidedException} with {@link #REASON_SPEND_REFUSED}), so
 * the guardrail's {@code failMode} applies, as for an unreachable model.
 */
@FunctionalInterface
public interface PaidCallGate {

    /**
     * The audit reason of a guardrail whose paid call the spend checks refused.
     */
    String REASON_SPEND_REFUSED = "spend_refused";

    /** Admits every call and records nothing — a context with no spend checks. */
    PaidCallGate UNGATED = expectedUsd -> PaidCall.ADMITTED;

    /**
     * Asks to start one paid call.
     *
     * @param expectedUsd
     *            what the call is expected to cost; 0 when unknown
     * @return the admission; close it once the call is over (in a {@code finally})
     */
    PaidCall begin(double expectedUsd);

    /** One admitted (or refused) paid call. */
    interface PaidCall extends AutoCloseable {

        /** An admitted call that records nothing. */
        PaidCall ADMITTED = new PaidCall() {
            @Override
            public String refusalReason() {
                return null;
            }

            @Override
            public void spent(double actualUsd) {
                // nothing to record
            }

            @Override
            public void close() {
                // nothing held
            }
        };

        /**
         * Why the call may not start ({@code conversation_ceiling},
         * {@code tenant_budget}, ...), or null when it may.
         */
        String refusalReason();

        default boolean admitted() {
            return refusalReason() == null;
        }

        /**
         * Reports what the call actually cost, so a later call of the same task is
         * checked with it. Call it whether the call's answer was usable or not.
         */
        void spent(double actualUsd);

        /** Releases what the call held against the ceiling. Idempotent. */
        @Override
        void close();
    }
}
