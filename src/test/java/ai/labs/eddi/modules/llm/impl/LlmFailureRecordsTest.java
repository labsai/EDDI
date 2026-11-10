/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.FailureClass;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.shared.RetryObserver;
import ai.labs.eddi.engine.health.HealthTurnFactory;
import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LlmFailureRecords — recovered LLM failures on the step")
class LlmFailureRecordsTest {

    private static ConversationMemory memory() {
        return new ConversationMemory("conv", "agent", 1, "user");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(IWritableConversationStep step) {
        IData<Object> data = step.getData(MemoryKeys.HEALTH_LLM_FAILURES);
        return data == null ? List.of() : (List<Map<String, Object>>) data.getResult();
    }

    /** What Agent Health reads back. */
    private static List<HealthFailure> asHealth(ConversationMemory memory) {
        return HealthTurnFactory.capture(memory, null, null, null, Instant.now(), false).turn().failures();
    }

    @Test
    @DisplayName("an escalated cascade step is recovered by the cascade")
    void cascade() {
        ConversationMemory memory = memory();
        LlmFailureRecords.record(memory.getCurrentStep(), FailureClass.RATE_LIMITED, "cheap-model", HealthFailure.CASCADE);
        assertEquals(List.of(new HealthFailure("RATE_LIMITED", "cheap-model", "cascade")), asHealth(memory));
    }

    @Test
    @DisplayName("a cascade's exhausted last step is settled by the task: fallback or none — never recorded twice")
    void pendingIsSettled() {
        ConversationMemory memory = memory();
        IWritableConversationStep step = memory.getCurrentStep();
        LlmFailureRecords.record(step, FailureClass.TIMEOUT, "big-model", LlmFailureRecords.PENDING);

        LlmFailureRecords.settle(step, new SocketTimeoutException("read timed out"), null, HealthFailure.FALLBACK, 0);

        assertEquals(List.of(new HealthFailure("TIMEOUT", "big-model", "fallback")), asHealth(memory));
    }

    @Test
    @DisplayName("a cascade that ended in a failure recovered nothing: its escalated steps settle with the last one")
    void exhaustedCascadeSettlesAll() {
        ConversationMemory memory = memory();
        IWritableConversationStep step = memory.getCurrentStep();
        // An earlier task's cascade escalated and then succeeded: its entry stays
        // recovered.
        LlmFailureRecords.record(step, FailureClass.TRANSIENT, "earlier", HealthFailure.CASCADE);
        int mark = LlmFailureRecords.mark(step);
        LlmFailureRecords.record(step, FailureClass.RATE_LIMITED, "cheap", HealthFailure.CASCADE);
        LlmFailureRecords.record(step, FailureClass.TIMEOUT, "big", LlmFailureRecords.PENDING);

        LlmFailureRecords.settle(step, new SocketTimeoutException("t"), null, HealthFailure.NONE, mark);

        assertEquals(List.of(new HealthFailure("TRANSIENT", "earlier", "cascade"), new HealthFailure("RATE_LIMITED", "cheap", "none"),
                new HealthFailure("TIMEOUT", "big", "none")), asHealth(memory));
    }

    @Test
    @DisplayName("a spend refusal after escalating settles the cascade's entries to fallback and records nothing new")
    void refusalAfterEscalation() {
        ConversationMemory memory = memory();
        IWritableConversationStep step = memory.getCurrentStep();
        LlmFailureRecords.record(step, FailureClass.TRANSIENT, "cheap", HealthFailure.CASCADE);

        LlmFailureRecords.settle(step, null, null, HealthFailure.FALLBACK, 0);

        assertEquals(List.of(new HealthFailure("TRANSIENT", "cheap", "fallback")), asHealth(memory));
        LlmFailureRecords.settle(memory().getCurrentStep(), null, null, HealthFailure.FALLBACK, 0);
    }

    @Test
    @DisplayName("a single-model failure is classified and recorded by settle itself")
    void singleModel() {
        ConversationMemory memory = memory();
        LlmFailureRecords.settle(memory.getCurrentStep(), new SocketTimeoutException("read timed out"), "gpt-x", HealthFailure.NONE, 0);
        HealthFailure failure = asHealth(memory).get(0);
        assertEquals("TIMEOUT", failure.failureClass());
        assertEquals("gpt-x", failure.model());
        assertEquals("none", failure.recovery());
    }

    @Test
    @DisplayName("an unsettled pending entry is not counted as recovered")
    void pendingIsNotRecovered() {
        assertFalse(new HealthFailure("TIMEOUT", null, LlmFailureRecords.PENDING).recovered());
    }

    @Test
    @DisplayName("control flow and the circuit's own refusal are not model failures")
    void controlFlowIgnored() {
        ConversationMemory memory = memory();
        IWritableConversationStep step = memory.getCurrentStep();
        LlmFailureRecords.settle(step, new ConversationPauseException("wf", 0, "paused"), "m", HealthFailure.NONE, 0);
        LlmFailureRecords.settle(step, new LlmCircuitOpenException("open", LlmCircuitBreakers.Failure.AUTH), "m", HealthFailure.NONE, 0);
        LlmFailureRecords.settle(step, null, "m", HealthFailure.NONE, 0);
        assertNull(step.getData(MemoryKeys.HEALTH_LLM_FAILURES));
    }

    @Test
    @DisplayName("at most MAX_RECORDS entries per step; a null step is a no-op")
    void capped() {
        ConversationMemory memory = memory();
        for (int i = 0; i < 50; i++) {
            LlmFailureRecords.record(memory.getCurrentStep(), FailureClass.TRANSIENT, null, HealthFailure.RETRY);
        }
        assertEquals(LlmFailureRecords.MAX_RECORDS, records(memory.getCurrentStep()).size());
        assertDoesNotThrow(() -> LlmFailureRecords.record(null, FailureClass.TRANSIENT, null, HealthFailure.RETRY));
    }

    @Test
    @DisplayName("same-model retries inside a model call are recorded as retry, also from a cascade step's thread")
    void retriesObserved() throws Exception {
        ConversationMemory memory = memory();
        var config = new RetryConfiguration();
        config.setMaxAttempts(3);
        config.setBackoffDelayMs(0L);
        AtomicInteger calls = new AtomicInteger();
        Callable<String> flaky = () -> {
            if (calls.incrementAndGet() < 3) {
                throw new SocketTimeoutException("read timed out");
            }
            return "ok";
        };
        ExecutorService other = Executors.newSingleThreadExecutor();
        try (var ignored = LlmFailureRecords.observeRetries(memory)) {
            try (var modelCall = RetryObserver.modelCall()) {
                assertEquals("ok", RetryConfiguration.executeWithRetry(flaky, config, "Chat model execution"));
            }
            calls.set(1);
            // A cascade step runs on another thread; the binding is carried over.
            assertEquals("ok", other.submit(RetryObserver.propagate(() -> {
                try (var modelCall = RetryObserver.modelCall()) {
                    return RetryConfiguration.executeWithRetry(flaky, config, "step");
                }
            })).get());
            // A side call — a judge, a summary, an MCP call — retries outside a model call.
            calls.set(1);
            assertEquals("ok", RetryConfiguration.executeWithRetry(flaky, config, "judge"));
        } finally {
            other.shutdownNow();
        }
        List<HealthFailure> failures = asHealth(memory);
        assertEquals(3, failures.size(), "two retries on the task thread, one on the step thread, none for the side call");
        assertTrue(failures.stream().allMatch(f -> "TIMEOUT".equals(f.failureClass()) && "retry".equals(f.recovery()) && f.model() == null));
    }

    @Test
    @DisplayName("outside an observed task a retry is not recorded, and the binding is gone after close")
    void unobserved() throws Exception {
        ConversationMemory memory = memory();
        try (var ignored = LlmFailureRecords.observeRetries(memory)) {
            // nothing retried
        }
        var config = new RetryConfiguration();
        config.setMaxAttempts(2);
        config.setBackoffDelayMs(0L);
        AtomicInteger calls = new AtomicInteger();
        RetryConfiguration.executeWithRetry(() -> {
            if (calls.incrementAndGet() < 2) {
                throw new SocketTimeoutException("t");
            }
            return "ok";
        }, config, "x");
        assertNull(memory.getCurrentStep().getData(MemoryKeys.HEALTH_LLM_FAILURES));
    }
}
