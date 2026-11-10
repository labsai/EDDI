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
import ai.labs.eddi.engine.health.HealthMetrics;
import ai.labs.eddi.engine.health.HealthSettings;
import ai.labs.eddi.engine.health.HealthTurnRecorder;
import ai.labs.eddi.engine.health.IHealthTurnStore;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.internal.ConversationStepRunner.TurnWait;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent Health's hook in the turn runner: every turn — clean, failed, paused —
 * reaches the health recorder, on its own worker, with the alert path unchanged
 * and alerting switched off making no difference.
 */
@DisplayName("ConversationStepRunner — Agent Health hook")
class ConversationStepRunnerHealthHookTest {

    private ConversationService service;
    private ConversationStepRunner runner;
    private IHealthTurnStore store;
    private HealthTurnRecorder recorder;
    private AlertDispatcher dispatcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        service = mock(ConversationService.class);
        Field logger = ConversationService.class.getDeclaredField("contextLogger");
        logger.setAccessible(true);
        logger.set(service, mock(IContextLogger.class));
        store = mock(IHealthTurnStore.class);
        Instance<IHealthTurnStore> instance = mock(Instance.class);
        when(instance.get()).thenReturn(store);
        recorder = new HealthTurnRecorder(instance, HealthSettings.defaults(), new HealthMetrics(new SimpleMeterRegistry()));
        service.healthTurnRecorder = recorder;
        doCallRealMethod().when(service).reportHealthTurn(any(), any(TurnWait.class), anyLong());
        doCallRealMethod().when(service).reportHealthTurn(any(), any(), any(TurnWait.class), anyLong());
        ICache<String, ConversationState> stateCache = mock(ICache.class);
        runner = new ConversationStepRunner(service, mock(IConversationMemoryStore.class), mock(IConversationDescriptorStore.class),
                mock(IRuntime.class), stateCache, new ConcurrentHashMap<>(), 60);
    }

    @AfterEach
    void tearDown() {
        recorder.shutdown();
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
    }

    private static ConversationMemory memory(ConversationState state) {
        ConversationMemory memory = new ConversationMemory("conv-1", "agent-1", 3, "user-1");
        memory.setConversationState(state);
        return memory;
    }

    private HealthTurn written() {
        ArgumentCaptor<HealthTurn> row = ArgumentCaptor.forClass(HealthTurn.class);
        verify(store, timeout(5_000)).upsert(row.capture());
        return row.getValue();
    }

    @Test
    @DisplayName("a clean turn is recorded with its duration; the OUTPUT_MATCH hook still sees it")
    void cleanTurn() {
        runner.waitForExecutionFinishOrTimeout(Map.of(), "conv-1", memory(ConversationState.READY), CompletableFuture.completedFuture(null));

        HealthTurn row = written();
        assertNull(row.errorKind());
        assertTrue(row.durationMs() != null && row.durationMs() >= 0);
        verify(service).reportCleanTurn(any());
    }

    @Test
    @DisplayName("a failed turn is recorded with its error kind, and the alert outcome is still fired")
    void failedTurn() {
        runner.waitForExecutionFinishOrTimeout(Map.of(), "conv-1", memory(ConversationState.ERROR), CompletableFuture.completedFuture(null));

        assertEquals(ErrorKind.TURN_ERROR.name(), written().errorKind());
        verify(service).reportTurnOutcome(any(TurnOutcome.class));
    }

    @Test
    @DisplayName("a paused turn is recorded as paused — its resume replaces the row")
    void pausedTurn() {
        runner.waitForExecutionFinishOrTimeout(Map.of(), "conv-1", memory(ConversationState.AWAITING_HUMAN), CompletableFuture.completedFuture(null));

        HealthTurn row = written();
        assertTrue(row.paused());
        assertNull(row.errorKind());
        verify(service, never()).reportCleanTurn(any());
    }

    @Test
    @DisplayName("a watchdog timeout is recorded insert-only under a fresh id per timeout, and repeated timeouts do not overwrite each other")
    void timedOutTurn() {
        ConversationMemory memory = memory(ConversationState.IN_PROGRESS);
        memory.startNextStep(); // the new say step, not stamped yet: the body is still running

        service.reportHealthTurn(memory, TurnWait.TIMED_OUT, 60_000L);
        service.reportHealthTurn(memory, TurnWait.TIMED_OUT, 60_000L);

        ArgumentCaptor<HealthTurn> rows = ArgumentCaptor.forClass(HealthTurn.class);
        verify(store, timeout(5_000).times(2)).insertIfAbsent(rows.capture());
        verify(store, never()).upsert(any());
        assertEquals(ErrorKind.TURN_DEADLINE.name(), rows.getAllValues().get(0).errorKind());
        assertFalse(rows.getAllValues().get(0).executionId().startsWith("legacy-"));
        assertNotEquals(rows.getAllValues().get(0).executionId(), rows.getAllValues().get(1).executionId());
    }

    @Test
    @DisplayName("eddi.alerts.enabled=false does not stop health collection")
    void alertsOffHealthOn() {
        dispatcher = new AlertDispatcher(mock(AlertEngine.class), new AlertMetrics(new SimpleMeterRegistry()), new AlertSettings(false, 5,
                Duration.ofSeconds(60), 5, Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofDays(90), Duration.ofSeconds(10),
                Optional.empty()));
        doAnswer(inv -> {
            dispatcher.onTurnOutcome(inv.getArgument(0));
            return null;
        }).when(service).reportTurnOutcome(any(TurnOutcome.class));

        runner.waitForExecutionFinishOrTimeout(Map.of(), "conv-1", memory(ConversationState.ERROR), CompletableFuture.completedFuture(null));

        assertEquals("TURN_ERROR", written().errorKind());
    }

    @Test
    @DisplayName("the start turn names its conversation; without a recorder nothing happens and nothing throws")
    void startTurnAndNoRecorder() {
        ConversationMemory fresh = new ConversationMemory("agent-1", 3, "user-1");
        service.reportHealthTurn(fresh, "new-conv", TurnWait.COMPLETED, 7L);
        assertEquals("new-conv", written().conversationId());

        ConversationService bare = mock(ConversationService.class);
        doCallRealMethod().when(bare).reportHealthTurn(any(), any(), any(TurnWait.class), anyLong());
        IConversationMemory memory = memory(ConversationState.READY);
        assertDoesNotThrow(() -> bare.reportHealthTurn(memory, null, TurnWait.COMPLETED, 1L));
        verify(bare).reportHealthTurn(eq(memory), isNull(), eq(TurnWait.COMPLETED), eq(1L));
    }
}
