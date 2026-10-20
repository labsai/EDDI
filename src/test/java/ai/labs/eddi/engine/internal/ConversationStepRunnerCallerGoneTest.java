/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A queued turn whose caller has stopped waiting is dropped when it reaches the
 * front of the queue, before it reads or writes anything; a turn whose caller
 * still waits runs as before.
 */
@DisplayName("ConversationStepRunner — turns whose caller has gone")
class ConversationStepRunnerCallerGoneTest {

    private static final String ID = "6ac11c7a3dc075f0b3dbb8ec";

    private ConversationService service;
    private IConversationMemoryStore store;
    private IRuntime runtime;
    private ConversationStepRunner runner;

    private static void setFinal(Object target, String name, Object value) throws Exception {
        Field field = ConversationService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @BeforeEach
    void setUp() throws Exception {
        service = mock(ConversationService.class);
        setFinal(service, "conversationHitlService", mock(ConversationHitlService.class));
        setFinal(service, "contextLogger", mock(IContextLogger.class));
        store = mock(IConversationMemoryStore.class);
        when(store.getConversationState(ID)).thenReturn(ConversationState.READY);
        @SuppressWarnings("unchecked")
        ICache<String, ConversationState> stateCache = mock(ICache.class);
        runtime = mock(IRuntime.class);
        runner = new ConversationStepRunner(service, store, mock(IConversationDescriptorStore.class), runtime, stateCache,
                new ConcurrentHashMap<>(), 60);
    }

    private static ConversationService.ProcessingTurn turnWhoseCallerGivesUpAt(Long epochMs) {
        var turn = ConversationService.ProcessingTurn.admit(new AtomicInteger(), new AtomicInteger(), 0);
        turn.callerGivesUpAt(epochMs);
        return turn;
    }

    /** Runs the turn; returns whether it was reported as skipped. */
    private boolean run(ConversationService.ProcessingTurn turn) {
        AtomicBoolean skipped = new AtomicBoolean();
        runner.runConversationStep(Environment.production, mock(IConversationMemory.class), ID, Map.of(), () -> null, null,
                memory -> skipped.set(true), null, turn);
        return skipped.get();
    }

    @Test
    @DisplayName("a caller who gave up: the turn is reported skipped, counted, and never reaches the store or the runtime")
    void callerGoneDropsTheTurnUnrun() throws Exception {
        var turn = turnWhoseCallerGivesUpAt(System.currentTimeMillis() - 1);

        assertTrue(run(turn), "the caller's handler must be told the turn was not run");
        assertTrue(turn.wasDroppedForCaller());
        verify(service).countTurnSkippedForCaller();
        verify(store, never()).getConversationState(ID);
        verify(runtime, never()).submitCallable(any(), any(), any());
    }

    @Test
    @DisplayName("a caller still waiting: the turn runs")
    void callerWaitingRunsTheTurn() throws Exception {
        var turn = turnWhoseCallerGivesUpAt(System.currentTimeMillis() + 60_000);

        assertFalse(run(turn));
        assertFalse(turn.wasDroppedForCaller());
        verify(service, never()).countTurnSkippedForCaller();
        verify(store).getConversationState(ID);
        verify(runtime).submitCallable(any(), any(), any());
    }

    @Test
    @DisplayName("no caller deadline (streams, schedules, groups): the turn runs however long it queued")
    void noCallerDeadlineRunsTheTurn() throws Exception {
        var turn = turnWhoseCallerGivesUpAt(null);

        assertFalse(run(turn));
        verify(runtime).submitCallable(any(), any(), any());
    }
}
