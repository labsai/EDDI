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
import java.util.concurrent.atomic.AtomicInteger;

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
 * {@link #observeRetries}: {@code retry} when a later attempt of the same retry
 * loop succeeded (the model is not known there). The retries of a loop that
 * failed share the fate of the failure that ended it: they are written with the
 * next entry recorded on the task's thread — a cascade step's {@code cascade}
 * or {@link #PENDING}, or {@link #settle}'s recovery — and as {@code none} if
 * the task ends without one;</li>
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

    /** The scope collecting the retries of the LLM task running on this thread. */
    private static final ThreadLocal<RetryScope> CURRENT = new ThreadLocal<>();

    private LlmFailureRecords() {
    }

    /**
     * Appends one failed attempt. The same-model retries of loops that failed
     * before it, collected on this thread, are written first and take the same
     * {@code recovery}: they ended in this failure.
     */
    static void record(IWritableConversationStep step, FailureClass failureClass, String model, String recovery) {
        if (step == null || failureClass == null) {
            return;
        }
        drainRetries(step, recovery);
        append(step, failureClass, model, recovery);
    }

    private static void append(IWritableConversationStep step, FailureClass failureClass, String model, String recovery) {
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
                // A recovered same-model retry is not the phase's failure.
                phaseRecorded |= inPhase && !HealthFailure.RETRY.equals(previous);
                if (PENDING.equals(previous) || inPhase && HealthFailure.CASCADE.equals(previous)) {
                    entry.put(HealthFailure.KEY_RECOVERY, recovery);
                    settled = true;
                }
            }
            if (settled) {
                step.storeData(new Data<>(MemoryKeys.HEALTH_LLM_FAILURES, records));
            }
            // The retries of a loop that ended in this failure share its recovery.
            drainRetries(step, recovery);
            if (!settled && !phaseRecorded && failure != null && !(failure instanceof LlmCircuitOpenException)) {
                append(step, LlmFailureClassifier.classify(failure).cls(), model, recovery);
            }
        } catch (RuntimeException e) {
            // Recording must never affect the turn.
        }
    }

    /**
     * Collects the same-model retries of the LLM task running on this thread (and
     * of the cascade steps it hands to other threads) until closed, and writes them
     * to the memory's current step — on the task's own thread only: when an entry
     * is recorded or settled there, and at the latest when the scope is closed.
     */
    static RetryScope observeRetries(IConversationMemory memory) {
        return new RetryScope(memory);
    }

    /**
     * Writes the retries the current thread's scope collected so far: those of a
     * loop that succeeded as {@code retry}, those of a loop that failed as
     * {@code failedRecovery}.
     */
    private static void drainRetries(IWritableConversationStep step, String failedRecovery) {
        RetryScope scope = CURRENT.get();
        if (scope != null) {
            scope.drain(step, failedRecovery);
        }
    }

    /** The retries of one retry loop, and whether the loop succeeded. */
    private record Retries(List<FailureClass> classes, boolean recovered) {
    }

    /** The binding {@link #observeRetries} returns. */
    static final class RetryScope implements AutoCloseable {

        private final IConversationMemory memory;
        private final Queue<Retries> retried = new ConcurrentLinkedQueue<>();
        private final AtomicInteger collected = new AtomicInteger();
        private final RetryObserver.Binding binding;
        private final RetryScope previous;

        private RetryScope(IConversationMemory memory) {
            this.memory = memory;
            this.binding = RetryObserver.bind((classes, recovered) -> {
                if (collected.getAndAdd(classes.size()) < MAX_RECORDS) {
                    retried.add(new Retries(classes, recovered));
                }
            });
            this.previous = CURRENT.get();
            CURRENT.set(this);
        }

        private void drain(IWritableConversationStep step, String failedRecovery) {
            Retries retries;
            while ((retries = retried.poll()) != null) {
                for (FailureClass failureClass : retries.classes()) {
                    append(step, failureClass, null, retries.recovered() ? HealthFailure.RETRY : failedRecovery);
                }
            }
        }

        /**
         * Writes what is left: the retries of a loop that failed without its failure
         * being recorded recovered nothing.
         */
        @Override
        public void close() {
            binding.close();
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            if (memory == null || retried.isEmpty()) {
                return;
            }
            try {
                drain(memory.getCurrentStep(), HealthFailure.NONE);
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
