/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.guardrails.PaidCallGate;

/**
 * The spend of one phase of a running LLM task that is not yet in
 * {@code audit:cost}, and the gate every further paid call of that phase asks
 * before it starts.
 * <p>
 * An LLM task writes its cost into the step only once a phase is over — the
 * model phase's tokens after the output guardrails, a guardrail phase's cost
 * after the phase — so the conversation's recorded spend leaves out what the
 * phase has spent so far. Every attempt after the first (a guardrail reprompt,
 * a classifier call after a reprompt) would be checked against a figure that
 * misses the attempts before it. This object carries that figure: {@link #add}
 * for spend known by the task (the primary answer's cost), and
 * {@link PaidCall#spent} for each admitted call's own cost.
 * <p>
 * One instance per phase of one task execution; never shared between tasks or
 * turns, and discarded once the phase's cost is recorded — that is what keeps
 * an amount from being counted both here and in memory. Thread-safe, so a phase
 * whose calls run concurrently stays correct.
 */
final class TaskSpend implements PaidCallGate {

    private final LlmSpendGate gate;
    private final IConversationMemory memory;
    private double pendingUsd;

    private TaskSpend(LlmSpendGate gate, IConversationMemory memory) {
        this.gate = gate;
        this.memory = memory;
    }

    /**
     * The spend of a new phase; with no gate (or no memory) every call is admitted
     * and only the figure is kept.
     */
    static TaskSpend of(LlmSpendGate gate, IConversationMemory memory) {
        return new TaskSpend(gate, memory);
    }

    /** Adds spend this phase made outside {@link #begin}. */
    synchronized void add(double usd) {
        if (Double.isFinite(usd) && usd > 0.0) {
            pendingUsd += usd;
        }
    }

    /** What this phase has spent and not yet recorded. */
    synchronized double pendingUsd() {
        return pendingUsd;
    }

    /**
     * Admits one paid call through {@link LlmSpendGate#admit} with this phase's
     * pending spend; refusals are counted and logged by the gate.
     */
    @Override
    public PaidCall begin(double expectedUsd) {
        LlmSpendGate.Admission admission = gate != null && memory != null
                ? gate.admit(memory, pendingUsd(), expectedUsd)
                : LlmSpendGate.Admission.ALLOWED;
        return new Call(admission);
    }

    private final class Call implements PaidCall {
        private final LlmSpendGate.Admission admission;

        private Call(LlmSpendGate.Admission admission) {
            this.admission = admission;
        }

        @Override
        public String refusalReason() {
            return admission.allowed() ? null : admission.refusal().reason();
        }

        @Override
        public void spent(double actualUsd) {
            add(actualUsd);
        }

        @Override
        public void close() {
            admission.close();
        }
    }
}
