/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.FailureClass;
import ai.labs.eddi.configs.shared.LlmFailureClassifier;
import ai.labs.eddi.configs.shared.RetryObserver;
import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Writes every failed LLM attempt of a step to
 * {@link MemoryKeys#HEALTH_LLM_FAILURES} — including the ones the engine
 * recovered from, which otherwise left nothing on the step but untagged
 * counters. Agent Health reads the record; nothing here changes how a failure
 * is recovered.
 * <p>
 * Each failure is recorded once, by the point that decides what happens next:
 * <ul>
 * <li>a same-model retry — {@link RetryObserver}, bound for the task by
 * {@link #observeRetries}: {@code retry} (the model is not known there);</li>
 * <li>a cascade step — {@code CascadingModelExecutor}: {@code cascade} when it
 * escalates or an earlier step's answer is served; a last step whose failure
 * ends the cascade is left {@link #PENDING};</li>
 * <li>the end of the model phase — the LLM task's {@code catch}, through
 * {@link #settle}: {@code fallback} when {@code onError: fallback} absorbs it,
 * else {@code none}; it settles the phase's cascade entries — pending and
 * escalated alike, since a cascade that ended in a failure recovered nothing —
 * rather than adding a second one.</li>
 * </ul>
 * Control flow — a HITL pause, a cancel, an interrupt — and the circuit
 * breaker's own refusal are not model failures and are not recorded. At most
 * {@link #MAX_RECORDS} entries per step. Never throws.
 */
final class LlmFailureRecords {

    /** A step keeps at most this many entries. */
    static final int MAX_RECORDS = 20;

    /**
     * A recovery not decided yet — a cascade's last step failed and the task has
     * not chosen between its fallback and the error.
     */
    static final String PENDING = "pending";

    private LlmFailureRecords() {
    }

    /** Appends one failed attempt. */
    static void record(IWritableConversationStep step, FailureClass failureClass, String model, String recovery) {
        if (step == null || failureClass == null) {
            return;
        }
        try {
            List<Map<String, Object>> records = current(step);
            if (records.size() >= MAX_RECORDS) {
                return;
            }
            records.add(entry(failureClass.name(), model, recovery));
            step.storeData(new Data<>(MemoryKeys.HEALTH_LLM_FAILURES, records));
        } catch (RuntimeException e) {
            // Recording must never affect the turn.
        }
    }

    /** How many entries the step holds now — taken before a model phase starts. */
    static int mark(IWritableConversationStep step) {
        if (step == null) {
            return 0;
        }
        try {
            return current(step).size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * The model phase that began at {@code mark} ended in {@code failure} (or, with
     * {@code failure} {@code null}, in a refusal that is no model failure) and the
     * task decided {@code recovery} ({@link HealthFailure#FALLBACK} or
     * {@link HealthFailure#NONE}).
     * <p>
     * Every entry of the phase that a cascade recorded — {@link #PENDING}, and
     * {@code cascade} too: a cascade that ended in this failure recovered nothing,
     * however many steps it escalated past — takes that recovery. When the phase
     * left no entry of its own (no cascade), the failure itself is recorded, unless
     * it is the circuit breaker's own refusal (the model was never called).
     */
    static void settle(IWritableConversationStep step, Throwable failure, String model, String recovery, int mark) {
        if (step == null || failure != null && LlmFallbackHandler.isControlFlow(failure)) {
            return;
        }
        try {
            List<Map<String, Object>> records = current(step);
            boolean settled = false;
            boolean phaseRecorded = false;
            for (int i = 0; i < records.size(); i++) {
                Map<String, Object> entry = records.get(i);
                Object previous = entry.get(HealthFailure.KEY_RECOVERY);
                boolean inPhase = i >= mark;
                phaseRecorded |= inPhase;
                if (PENDING.equals(previous) || inPhase && HealthFailure.CASCADE.equals(previous)) {
                    entry.put(HealthFailure.KEY_RECOVERY, recovery);
                    settled = true;
                }
            }
            if (settled) {
                step.storeData(new Data<>(MemoryKeys.HEALTH_LLM_FAILURES, records));
            } else if (!phaseRecorded && failure != null && !(failure instanceof LlmCircuitOpenException)) {
                record(step, LlmFailureClassifier.classify(failure).cls(), model, recovery);
            }
        } catch (RuntimeException e) {
            // Recording must never affect the turn.
        }
    }

    /**
     * Collects the same-model retries of the LLM task running on this thread (and
     * of the cascade steps it hands to other threads) until closed, then writes
     * them to the memory's current step. Closed on the task's own thread, so the
     * step is only ever written there.
     */
    static RetryScope observeRetries(IConversationMemory memory) {
        return new RetryScope(memory);
    }

    /** The binding {@link #observeRetries} returns. */
    static final class RetryScope implements AutoCloseable {

        private final IConversationMemory memory;
        private final Queue<FailureClass> retried = new ConcurrentLinkedQueue<>();
        private final RetryObserver.Binding binding;

        private RetryScope(IConversationMemory memory) {
            this.memory = memory;
            this.binding = RetryObserver.bind(failureClass -> {
                if (retried.size() < MAX_RECORDS) {
                    retried.add(failureClass);
                }
            });
        }

        @Override
        public void close() {
            binding.close();
            if (memory == null || retried.isEmpty()) {
                return;
            }
            try {
                IWritableConversationStep step = memory.getCurrentStep();
                FailureClass failureClass;
                while ((failureClass = retried.poll()) != null) {
                    record(step, failureClass, null, HealthFailure.RETRY);
                }
            } catch (RuntimeException e) {
                // Recording must never affect the turn.
            }
        }
    }

    /** A mutable copy of the step's record. */
    private static List<Map<String, Object>> current(IWritableConversationStep step) {
        List<Map<String, Object>> records = new ArrayList<>();
        IData<Object> existing = step.getData(MemoryKeys.HEALTH_LLM_FAILURES);
        if (existing != null && existing.getResult() instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    records.add(copy);
                }
            }
        }
        return records;
    }

    private static Map<String, Object> entry(String failureClass, String model, String recovery) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(HealthFailure.KEY_CLASS, failureClass);
        if (model != null && !model.isBlank()) {
            entry.put(HealthFailure.KEY_MODEL, model);
        }
        entry.put(HealthFailure.KEY_RECOVERY, recovery);
        return entry;
    }
}
