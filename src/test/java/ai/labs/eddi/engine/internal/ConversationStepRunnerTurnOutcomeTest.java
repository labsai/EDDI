/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.alerts.AlertDispatcher;
import ai.labs.eddi.engine.alerts.AlertEngine;
import ai.labs.eddi.engine.alerts.AlertMetrics;
import ai.labs.eddi.engine.alerts.AlertSettings;
import ai.labs.eddi.engine.alerts.TurnOutcome;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.internal.ConversationStepRunner.TurnWait;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The alert hook in the turn runner: one place, the outcome classified from the
 * memory, and the turn never slowed or failed by it.
 */
@DisplayName("ConversationStepRunner — turn outcome hook")
class ConversationStepRunnerTurnOutcomeTest {

    private static final String ID = "6ac11c7a3dc075f0b3dbb8ec";
    private static final String AGENT = "6ac11c7a3dc075f0b3dbb8aa";

    private ConversationService service;
    private ConversationStepRunner runner;
    private AlertDispatcher dispatcher;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        service = mock(ConversationService.class);
        Field logger = ConversationService.class.getDeclaredField("contextLogger");
        logger.setAccessible(true);
        logger.set(service, mock(IContextLogger.class));
        @SuppressWarnings("unchecked")
        ICache<String, ConversationState> stateCache = mock(ICache.class);
        runner = new ConversationStepRunner(service, mock(IConversationMemoryStore.class), mock(IConversationDescriptorStore.class),
                mock(IRuntime.class), stateCache, new ConcurrentHashMap<>(), 60);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
    }

    private static IConversationMemory memory(ConversationState state, boolean cancelled, Map<String, Object> refusal) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getConversationId()).thenReturn(ID);
        when(memory.getAgentId()).thenReturn(AGENT);
        when(memory.getAgentVersion()).thenReturn(4);
        when(memory.getConversationState()).thenReturn(state);
        when(memory.isCancelled()).thenReturn(cancelled);
        IWritableConversationStep step = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(step);
        if (refusal != null) {
            @SuppressWarnings("unchecked")
            IData<Object> data = mock(IData.class);
            when(data.getResult()).thenReturn(refusal);
            when(step.getAllData(MemoryKeys.LLM_SPEND_REFUSED_PREFIX)).thenReturn(List.of(data));
        } else {
            when(step.getAllData(MemoryKeys.LLM_SPEND_REFUSED_PREFIX)).thenReturn(List.of());
        }
        return memory;
    }

    @Test
    @DisplayName("classification: deadline, interruption, error, cost ceiling, tenant budget — and nothing for a clean turn or a pause")
    void classification() {
        assertEquals(ErrorKind.TURN_DEADLINE,
                ConversationStepRunner.classifyTurnOutcome(memory(ConversationState.READY, false, null), TurnWait.TIMED_OUT));
        assertEquals(ErrorKind.TURN_INTERRUPTED,
                ConversationStepRunner.classifyTurnOutcome(memory(ConversationState.READY, false, null), TurnWait.INTERRUPTED));
        assertNull(
                ConversationStepRunner.classifyTurnOutcome(memory(ConversationState.AWAITING_HUMAN, false, null), TurnWait.ABANDONED_WHILE_PAUSED));
        assertEquals(ErrorKind.TURN_INTERRUPTED,
                ConversationStepRunner.classifyTurnOutcome(memory(ConversationState.ERROR, true, null), TurnWait.COMPLETED));
        assertEquals(ErrorKind.TURN_ERROR,
                ConversationStepRunner.classifyTurnOutcome(memory(ConversationState.ERROR, false, null), TurnWait.COMPLETED));
        assertEquals(ErrorKind.TURN_ERROR, ConversationStepRunner.classifyTurnOutcome(memory(ConversationState.READY, false, null), TurnWait.FAILED));
        assertEquals(ErrorKind.COST_CEILING, ConversationStepRunner.classifyTurnOutcome(
                memory(ConversationState.READY, false, Map.of("reason", "conversation_ceiling", "detail", "x")), TurnWait.COMPLETED));
        assertEquals(ErrorKind.TENANT_BUDGET, ConversationStepRunner.classifyTurnOutcome(
                memory(ConversationState.READY, false, Map.of("reason", "tenant_budget", "detail", "x")), TurnWait.COMPLETED));
        assertNull(ConversationStepRunner.classifyTurnOutcome(memory(ConversationState.READY, false, null), TurnWait.COMPLETED));
        assertNull(ConversationStepRunner.classifyTurnOutcome(memory(ConversationState.AWAITING_HUMAN, false, null), TurnWait.COMPLETED));
    }

    @Test
    @DisplayName("a failed turn is reported once, with no conversation content; a clean turn reports nothing")
    void reportsBadTurnsOnly() {
        runner.waitForExecutionFinishOrTimeout(Map.of(), ID, memory(ConversationState.ERROR, false, null), CompletableFuture.completedFuture(null));
        ArgumentCaptor<TurnOutcome> reported = ArgumentCaptor.forClass(TurnOutcome.class);
        verify(service).reportTurnOutcome(reported.capture());
        assertEquals(new TurnOutcome(ID, AGENT, 4, ErrorKind.TURN_ERROR), reported.getValue());

        ConversationService quiet = mock(ConversationService.class);
        ConversationStepRunner quietRunner = new ConversationStepRunner(quiet, mock(IConversationMemoryStore.class),
                mock(IConversationDescriptorStore.class), mock(IRuntime.class), null, new ConcurrentHashMap<>(), 60);
        assertFalse(quietRunner.waitForExecutionFinishOrTimeout(Map.of(), ID, memory(ConversationState.READY, false, null),
                CompletableFuture.completedFuture(null)));
        verify(quiet, never()).reportTurnOutcome(any());
    }

    @Test
    @DisplayName("an alert hook that throws does not fail the turn")
    void hookFailureIsContained() {
        doThrow(new IllegalStateException("observer blew up")).when(service).reportTurnOutcome(any());
        assertFalse(runner.waitForExecutionFinishOrTimeout(Map.of(), ID, memory(ConversationState.ERROR, false, null),
                CompletableFuture.completedFuture(null)));
    }

    @Test
    @DisplayName("with the alert worker stuck and its queue full, 2,000 failing turns still finish without waiting on alerting")
    void turnsNeverWaitForAlerting() throws Exception {
        AlertEngine engine = mock(AlertEngine.class);
        CountDownLatch busy = new CountDownLatch(1);
        doAnswer(inv -> {
            busy.countDown();
            release.await(30, TimeUnit.SECONDS);
            return null;
        }).when(engine).evaluateTurn(any());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        dispatcher = new AlertDispatcher(engine, new AlertMetrics(registry), new AlertSettings(true, 5, Duration.ofSeconds(60), 5,
                Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofDays(90), Duration.ofSeconds(10), Optional.empty()));
        // The CDI event, as the container delivers it: synchronously, to the
        // dispatcher.
        doAnswer(inv -> {
            dispatcher.onTurnOutcome(inv.getArgument(0));
            return null;
        }).when(service).reportTurnOutcome(any());
        IConversationMemory failed = memory(ConversationState.ERROR, false, null);

        runner.waitForExecutionFinishOrTimeout(Map.of(), ID, failed, CompletableFuture.completedFuture(null));
        assertTrue(busy.await(5, TimeUnit.SECONDS));

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int i = 0; i < 2_000; i++) {
                runner.waitForExecutionFinishOrTimeout(Map.of(), ID, failed, CompletableFuture.completedFuture(null));
            }
        });
        assertEquals(2_000 - 5, registry.counter("eddi.alerts.dropped", "reason", "queue_full").count());
    }

    @Test
    @DisplayName("a clean turn goes to the OUTPUT_MATCH hook; a failed, abandoned or paused one does not")
    void cleanTurnsReachTheOutputMatchHook() {
        runner.waitForExecutionFinishOrTimeout(Map.of(), ID, memory(ConversationState.READY, false, null), CompletableFuture.completedFuture(null));
        verify(service).reportCleanTurn(any());

        ConversationService other = mock(ConversationService.class);
        ConversationStepRunner otherRunner = new ConversationStepRunner(other, mock(IConversationMemoryStore.class),
                mock(IConversationDescriptorStore.class), mock(IRuntime.class), null, new ConcurrentHashMap<>(), 60);
        otherRunner.reportTurnOutcome(memory(ConversationState.ERROR, false, null), TurnWait.COMPLETED);
        otherRunner.reportTurnOutcome(memory(ConversationState.AWAITING_HUMAN, false, null), TurnWait.COMPLETED);
        otherRunner.reportTurnOutcome(memory(ConversationState.READY, false, null), TurnWait.ABANDONED_WHILE_PAUSED);
        otherRunner.reportTurnOutcome(memory(ConversationState.READY, false, null), TurnWait.TIMED_OUT);
        verify(other, never()).reportCleanTurn(any());
    }
}
